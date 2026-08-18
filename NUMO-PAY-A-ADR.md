# NumoPay-A — Architecture Decisions Record
> **Session:** NumoPay-A · Opus uncounted · 2026-08-18
> **Status:** Decisions locked. Build brief for future Sonnet execution sessions.
> **Supersedes:** nothing — first NumoPay architecture record.
> **Lives in:** `refueler-io/docs/` and `numo-fork/` root (alongside BRIDGE).
> **References reviewed:** `OnboardingActivity.kt` · `ModernPOSActivity.kt` · `WebhookSettingsActivity.kt` · BTCPay PoS plugin (invoice creation + status model) · LNbits TPoS (offline PIN grant pattern, repo extracted from monorepo — confirmed not buildable as external dependency).

---

## The governing decision (read this first)

**NumoPay is a Supabase-backed order-entry terminal. It holds no funds and processes no payments of its own.**

Floor staff use it to take in-venue orders, which land in `merchant_orders` via Supabase. Lightning walk-in payments are settled via LNURL-pay direct to `venue_partners.lightning_address` — the same rail the consumer app uses. The device is never in the custody chain.

Consequences:
- The entire Cashu wallet ceremony (seed / mints / Nostr backup / melt) is **deleted**, not adapted.
- `CashuWalletManager`, `AutoWithdrawManager`, NFC tap-to-pay, BTCMap banner all go.
- The CDK dependency (`cdk-android:0.17.2-rc.1`) is **removed** from `build.gradle`.
- `OnboardingActivity` is **replaced wholesale** with a Supabase auth flow.
- `ModernPOSActivity` is **gutted and repurposed** — shell, lifecycle, vibrator, and theme wiring survive; payment and wallet logic does not.
- `WebhookSettingsActivity` is **repurposed** as a provisioning/QR-onboard mechanism.

CDK returns **only** when stamps land on the floor device (Block 8 / Pass with floor-device redemption). At that point it must pin to stable `cdk-android:0.17.2` matching `refueler-mint` per lock 4s. The `-rc.1` suffix must never ship to a real merchant device.

---

## 1. Auth model

### The model
```
First-time only (AM-assisted, one-off):
  Supabase magic link → email → AM hands phone back → session established

Session stored in EncryptedSharedPreferences (Hardening Phase 2 — already present in fork)
  Key: "refueler_session_jwt"   Value: JWT string
  Key: "refueler_session_expiry" Value: epoch millis

12h JWT lifetime (43200s — matches terminal, set in Supabase dashboard)

Shift-start gate:
  → phone wakes / app foregrounds
  → read stored JWT from EncryptedSharedPreferences
  → if within 12h: call verify-pin EF (server-side bcrypt)
  → on success: set local grant flag + timestamp in EncryptedSharedPreferences
  → local grant valid for: 30 minutes (see offline resilience below)
  → gate: PinEntryActivity (native UI retained — see below)

Mid-shift:
  → FLAG_KEEP_SCREEN_ON on ModernPOSActivity — screen never sleeps while app is foregrounded
  → no re-auth while screen is on
  → if app is backgrounded and returns within local grant window: no re-auth
  → if local grant expired (>30 min background): PIN prompt on return

Session expiry (12h from magic link):
  → next verify-pin call returns 401
  → app shows "Session expired — contact your manager" screen
  → AM re-provisions via magic link or calls Rajesh
  → no self-service re-auth on the device (no magic link input on the floor device)
```

### Offline PIN resilience
Confirmed model: **server-verify at shift-start, then local grant for 30 minutes**.

Motivation: venue wifi is unreliable mid-shift. A waiter must not be locked out of a basket because the router hiccupped. The local grant is not a security regression — the worst-case window is 30 minutes of continued access after the server has been unavailable, on a device that is physically present in the venue.

Implementation:
```kotlin
// EncryptedSharedPreferences keys
const val KEY_LOCAL_GRANT_UNTIL = "refueler_local_grant_until"  // epoch millis

// On successful verify-pin response:
prefs.putLong(KEY_LOCAL_GRANT_UNTIL, System.currentTimeMillis() + 30 * 60 * 1000L)

// On foreground / unlock attempt:
val grantUntil = prefs.getLong(KEY_LOCAL_GRANT_UNTIL, 0L)
if (System.currentTimeMillis() < grantUntil) {
    // local grant valid — proceed without server call
} else {
    // call verify-pin EF; on success refresh grant; on network failure: short-circuit
    // allow with a UI indicator "offline — limited mode" rather than hard-block
}
```

The "offline — limited mode" UI indicator is a small banner on the POS screen. It does not prevent order entry; it signals that the next connectivity window will re-verify. No order is lost.

### What survives from the fork
- `PinEntryActivity` — UI shell kept. Backend call changed from local Cashu keystore to `verify-pin` v2 EF.
- `PinSetupActivity` — replaced entirely. PIN setup is provisioning-time, done via the terminal or dashboard (`[R]`). The floor device never sets its own PIN.
- `PinResetActivity` — **removed**. PIN reset is an Owner tab operation on the tablet terminal, routed through a dedicated Edge Function (TDP-B agenda item, not on this device).

### What is deleted
- Entire `OnboardingActivity` — all 1,805 lines. No seed, no mints, no Nostr.
- `ONBOARDING_DEFAULT_MINTS` list (minibits, chorus, cubabitcoin, coinos).
- `KEY_ONBOARDING_COMPLETE` SharedPreferences flag — replaced by JWT presence check.
- `NostrMintBackup`, `CashuWalletManager`, `MintManager`, `MintProfileService`, `MintIconCache`.

### New activity: `RefuelerAuthActivity`
Single-screen. Shown only when no valid JWT exists in EncryptedSharedPreferences.

```
┌─────────────────────────────────────┐
│                                     │
│         [Refueler wordmark]         │
│                                     │
│   This device hasn't been set up.   │
│   Ask your manager to link it.      │
│                                     │
│   [Scan setup QR]                   │  ← WebhookSettings QR provisioning
│                                     │   repurposed (see §6)
└─────────────────────────────────────┘
```

AM scans a QR from the Command Centre → device receives JWT bootstrap → auth is established → `ModernPOSActivity` launches. One-time only.

---

## 2. Payment routing

### Floor order lifecycle
```
Staff selects items from catalogue (ItemListActivity / basket — retained)
  ↓
Staff taps "Charge"
  ↓
Payment method choice:
  [Lightning]          [Cash / Card]
       ↓                    ↓
  LNURL-pay flow       Record-only flow

─── LNURL-pay flow ────────────────────────────────────────────────
  NumoPay calls create-order EF (same as consumer app)
    → EF fetches venue_partners.lightning_address
    → resolves BOLT11 via LNURL-pay
    → returns {invoice: BOLT11, order_code: "RF-XXXX"}
  NumoPay displays QR on screen
  Customer scans with their own wallet
  Blink webhook → blink-webhook EF → merchant_orders.status = 'confirmed'
  NumoPay polls merchant_orders WHERE order_code = "RF-XXXX"
    → on confirmed: show success screen (vibrate, green)
    → poll interval: 2s, max 90s, then timeout with "check with customer"

─── Record-only flow ───────────────────────────────────────────────
  NumoPay writes merchant_orders row directly via Supabase PostgREST
    payment_method: 'cash' | 'card_external'
    status: 'confirmed' (immediate — external payment already taken)
    settled_sats: null
    routing_fee_sats: null
  No QR, no poll. Receipt shown immediately.
```

### create-order EF call from NumoPay
NumoPay calls `create-order` v10 with the JWT in the Authorization header. The EF already derives `venue_id` from the auth chain — no venue ID in the request body. The only addition needed in v10 is an `origin` field on the `merchant_orders` row (see §4).

### Polling pattern (informed by BTCPay PoS)
BTCPay's PoS uses SSE (server-sent events) for invoice status. We use Supabase Realtime, which is equivalent and already wired in the consumer app. NumoPay subscribes to:

```kotlin
supabase.from("merchant_orders")
    .select()
    .eq("order_code", orderCode)
    .on(RealtimeEvent.UPDATE) { record ->
        if (record["status"] == "confirmed") showSuccess()
    }
    .subscribe()
```

Fallback: if Realtime drops (network), poll `merchant_orders` via PostgREST every 2s for 90s. On timeout: show "Payment pending — tap to recheck" rather than marking failed. Same rule as the terminal.

### What is deleted
- `PaymentRequestActivity` — Cashu melt flow. Entirely removed.
- `PaymentReceivedActivity` — replaced by inline success state on POS screen.
- `PaymentMethodHandler` — Cashu payment dispatch. Removed.
- `AutoWithdrawManager` — periodic Cashu→Lightning sweep. Removed.
- `AutoWithdrawSettingsActivity` — removed.
- `BitcoinPriceWorker` — **retained**. GBP→sats conversion for item price display (see §3).
- NFC HCE service (`NdefHostCardEmulationService`) — removed.
- BTCMap banner — removed.

---

## 3. Item catalogue

### Data source
`merchant_menu_items` (Supabase) is the single source of truth. NumoPay reads via PostgREST, filtered by `venue_id` from the auth chain. The write side (add / edit / CSV import) stays with Menu Management v1 on the tablet terminal — not this device, not this session.

### Schema (agreed CC-97, restated for NumoPay context)
```sql
merchant_menu_items:
  id            uuid primary key
  venue_id      uuid references venue_partners(id)
  name          text not null
  description   text
  price_gbp     numeric(10,2) not null
  available     boolean not null default true
  category      text
  display_order integer
  created_at    timestamptz default now()
```

### GBP → sats display
Items are priced in `price_gbp`. `BitcoinPriceWorker` (retained from `ModernPOSActivity`) fetches BTC/GBP rate and provides a `gbpToSats(amount: Double): Long` helper. The basket accumulates in GBP; sats equivalent is shown as secondary text below each item and on the total. The `create-order` EF does its own rate conversion at invoice time — NumoPay's displayed sats are indicative only, not the invoice amount.

### What survives from the fork
- `ItemListActivity` — UI shell retained. Data source repointed to Supabase PostgREST.
- `ItemEntryActivity` — retained for display. Write paths (add / edit item) stubbed as "manage items on the counter terminal."
- Basket system — retained entirely. Basket is in-memory; basket total in GBP is the `create-order` input.
- `InsightsActivity` / `PaymentsHistoryActivity` — **retained**. History reads from `merchant_orders` filtered by `venue_id`. Provides the floor-staff order history the terminal doesn't show (useful: "what did table 4 order?").

### Category display
`merchant_menu_items.category` maps to a tab strip above the item grid. Categories are derived dynamically from the items returned — no separate categories table in v1. Null category items appear in an "All" tab.

### Availability
`merchant_menu_items.available = false` items are hidden in NumoPay (not shown, not orderable). No 86 flow on the floor device in v1 — availability is toggled via the tablet terminal.

---

## 4. Noun/verb/handle taxonomy

**Order code** is the universal join key across consumer app → tablet terminal → NumoPay.

### Nouns
| Noun | Table | Description |
|---|---|---|
| `order` | `merchant_orders` | A single transaction, pre-order or floor |
| `history` | `merchant_orders` (filtered, read-only) | Completed orders for this shift / day |

### Verbs
| Verb | HTTP / Supabase | Description |
|---|---|---|
| `list` | `GET merchant_orders?venue_id=eq.{id}&status=eq.pending` | Live queue view |
| `show` | `GET merchant_orders?order_code=eq.{code}` | Single order detail |
| `watch` | Supabase Realtime subscription on `merchant_orders` | Live status changes |
| `create` | `POST` via `create-order` EF (Lightning) or direct insert (cash) | New floor order |

### The `origin` field
Add `origin text not null default 'preorder'` to `merchant_orders`. Values:
- `'preorder'` — consumer app order (existing, no change to consumer flow)
- `'floor'` — NumoPay floor order

This is a **single-column DDL change** (`apply_migration`). The terminal queue view already filters by `venue_id`; adding `origin` lets it optionally show/hide floor orders in a future UI pass (CC-99 or later). No behaviour change in v1 — the terminal shows all orders regardless of origin.

### Order code format
Existing format inherited from `create-order` EF: `RF-XXXX` (4 uppercase alphanumeric chars). Floor orders use the same generator via the EF, or a client-side equivalent for cash orders (to be confirmed at build time — prefer EF generation for consistency).

---

## 5. Android theming

### Strategy
Replace `Theme.Numo` with `Theme.Refueler` across `res/values/themes.xml`, `res/values/colors.xml`, `res/values/dimens.xml`. Carbon is the always-on default — no toggle on Android, no `AppCompatDelegate.MODE_NIGHT_*` toggle (the POS device is not a personal phone). `MODE_NIGHT_NO` forced in `OnboardingActivity.onCreate` is deleted with that class. `ModernPOSActivity.setupThemeSettings()` block removed.

### Colour mapping
| Old Numo token | Hex | New Refueler token | Hex |
|---|---|---|---|
| `numo_navy` | `#1A2C3D` | `refueler_bg` | `#1A1A1A` |
| `numo_fluorescent_green` | `#39FF14` | `refueler_success` | `#27AE60` |
| `color_warning_red` | `#FF4444` | `refueler_danger` | `#E05252` |
| `color_success_green` | (variant) | `refueler_success` | `#27AE60` |
| `color_onboarding_text_subtle` | (variant) | `refueler_fg_subtle` | `#6A6560` |
| `color_onboarding_text_muted` | (variant) | `refueler_fg_muted` | `#B0AAA2` |
| `color_onboarding_text_disabled`| (variant) | `refueler_fg_subtle` | `#6A6560` |
| `color_divider` | (variant) | `refueler_border` | `rgba(245,240,232,0.10)` |

### Canonical Refueler Carbon tokens for `colors.xml`
```xml
<color name="refueler_bg">#1A1A1A</color>
<color name="refueler_fg">#E8E2D8</color>
<color name="refueler_fg_muted">#B0AAA2</color>
<color name="refueler_fg_subtle">#6A6560</color>
<color name="refueler_surface">#242424</color>
<color name="refueler_surface_raised">#2E2E2E</color>
<color name="refueler_gold">#C8A96E</color>
<color name="refueler_success">#27AE60</color>
<color name="refueler_danger">#E05252</color>

<!-- Order status — protected, never reassigned -->
<color name="refueler_status_pending">#C8A96E</color>  <!-- gold -->
<color name="refueler_status_in_prep">#7899D4</color>   <!-- periwinkle -->
<color name="refueler_status_ready">#3DCA7A</color>     <!-- green -->
```

### Typography
No Google Fonts dependency on Android. Map to:
- Headings / labels: `sans-serif-medium` (system Roboto Medium — closest available match to Satoshi 600 without a custom font embed; revisit with custom font file at a later design pass)
- Body / UI: `sans-serif` (Roboto Regular, weight 300/400)
- Data / codes: `monospace` (maps to IBM Plex Mono if embedded; `Courier New` fallback if not)

Custom font embed (Satoshi + DM Sans + IBM Plex Mono as TTF assets in `res/font/`) is a **nice-to-have, not a v1 blocker**. The system fonts are legible and Carbon-appropriate.

### `ThemeManager`
The existing `ThemeManager.resolveBackgroundColor()` reference in `ModernPOSActivity` can be simplified to return `Color.parseColor("#1A1A1A")` directly until a full `ThemeManager` rewrite. Remove the multi-theme (obsidian / green / bitcoin orange / white) logic — Refueler Carbon is the one theme.

---

## 6. WebhookSettings repurposed as device provisioning

The `WebhookSettingsActivity` QR payload is `{url, token}`. The provisioning model adopts this shape directly:

### Provisioning QR payload (produced by Command Centre, scanned by floor device)
```json
{
  "url": "https://tihgvdokeofnjxjkenmm.supabase.co",
  "token": "<service-role-scoped bootstrap JWT>",
  "venue_id": "<uuid>"
}
```

The Command Centre generates this QR at provisioning time (Rajesh / AM action). The floor device:
1. Scans QR via `QRScannerActivity` (retained).
2. Writes `url` + `token` to EncryptedSharedPreferences.
3. Exchanges bootstrap token for a magic-link-initiated session (or the bootstrap JWT is itself a 12h session JWT — to be confirmed at build time).
4. Proceeds to `PinEntryActivity` (staff PIN shift-start gate).
5. On success: `ModernPOSActivity` launches and the device is provisioned.

The "add endpoint" / "auth key" UI in `WebhookSettingsActivity` is stripped. The activity becomes `RefuelerProvisioningActivity`, single-purpose: scan QR → provision → done.

The outbound webhook dispatch (`PaymentWebhookDispatcher`, `syncAllTransactions`) is **removed**. NumoPay is not a data source for external dashboards — `merchant_orders` in Supabase is. Anyone who wants sales data queries Supabase, not a phone.

### Health-check endpoint convention (from WebhookSettings code)
The existing health-check pattern (`GET /api/health → {"ok":true}`) is reused for the Supabase connectivity indicator in the "offline — limited mode" banner. A single OkHttp call to `${supabaseUrl}/functions/v1/verify-pin` with a deliberate auth header probe is enough to confirm connectivity without a dedicated health endpoint.

---

## 7. BTCPay PoS — what we borrow

Two patterns specifically:

**Invoice metadata structure.** BTCPay attaches `orderId`, `itemCode`, `itemDesc`, `posData` to every invoice. NumoPay should pass equivalent metadata to `create-order` — specifically `order_code` and `items` (JSON array of `{name, price_gbp, quantity}`) — so the `merchant_orders` row carries a human-readable receipt without a second query. The `merchant_orders` table may need a `line_items jsonb` column (DDL for Menu Management v1 session, not today).

**Two-status settlement model.** BTCPay distinguishes `Processing` (payment seen, not confirmed) from `Settled` (confirmed, irreversible). Our equivalent is `payment_status = 'pending'` → `'paid'` via `blink-webhook`. NumoPay polls for `'paid'`, not `'pending'`. This is already the consumer app's model and carries over unchanged.

---

## 8. What is not in scope for NumoPay-A

These items are **explicitly excluded** from this architecture record and must not be designed or built until their named sessions open:

| Item | Named session |
|---|---|
| Commission rate / double-ask model | Dedicated planning session before first real merchant |
| Menu item add / edit on floor device | Menu Management v1 |
| 86 (mark item unavailable) from floor device | Menu Management v1 |
| Stamp issuance on floor device | Block 8 / post-mint — CDK returns here |
| Pass credential redemption on floor device | Pass-A / Pass-B |
| Per-staff accounts / individual shift tokens | Staff Management v1 |
| Android accessibility / RTL | Post-v1 |
| Offline order queue (full offline mode) | Unscoped — watch-line only |

---

## 9. Build sequence for execution sessions

NumoPay-A produces this document. Execution is one or more Sonnet counted sessions, in this order:

| Step | Deliverable | Session |
|---|---|---|
| 1 | `merchant_orders` — add `origin` column migration | First Sonnet (NumoPay-B) |
| 2 | `RefuelerProvisioningActivity` (QR scan → EncryptedSharedPreferences) | NumoPay-B |
| 3 | `RefuelerAuthActivity` (no-JWT landing screen) | NumoPay-B |
| 4 | `PinEntryActivity` — repoint backend to `verify-pin` EF + local grant | NumoPay-B |
| 5 | `ModernPOSActivity` gutted — CashuWalletManager / AutoWithdraw / NFC / BTCMap removed | NumoPay-B |
| 6 | `colors.xml` / `themes.xml` / `dimens.xml` — Refueler Carbon tokens | NumoPay-B |
| 7 | `ItemListActivity` — Supabase PostgREST data source, GBP pricing + sats secondary | NumoPay-C |
| 8 | `create-order` call from floor device + Realtime poll + LNURL QR display | NumoPay-C |
| 9 | Cash/card record-only flow | NumoPay-C |
| 10 | `InsightsActivity` / `PaymentsHistoryActivity` — repoint to `merchant_orders` | NumoPay-C |
| 11 | `build.gradle` — remove CDK dependency | NumoPay-B (alongside step 5) |

NumoPay-B and NumoPay-C are both Sonnet counted sessions. B is the auth/theme/scaffold pass. C is the payments and catalogue pass. Do not combine — they are large.

---

## 10. Open questions (non-blocking, to be resolved at NumoPay-B open)

| # | Question | Notes |
|---|---|---|
| Q1 | Bootstrap JWT: service-role-scoped or magic-link-initiated? | Service-role is simpler but wider; magic-link keeps the auth chain clean. Preference: magic-link, AM-triggered from Command Centre. |
| Q2 | `create-order` floor call: same EF as consumer app, or a `create-floor-order` variant? | Same EF preferred — the `origin` field on the row is sufficient distinction. Confirm at NumoPay-B. |
| Q3 | Custom font embed (Satoshi, DM Sans, IBM Plex Mono as TTF) in v1? | Aesthetic nice-to-have. Decide based on APK size impact. Not a blocker. |
| Q4 | Offline order queue — full offline create? | Not scoped. Flag for unscoped watch-line only. Do not design at NumoPay-B. |

---

*"Nothing stops this train."*
