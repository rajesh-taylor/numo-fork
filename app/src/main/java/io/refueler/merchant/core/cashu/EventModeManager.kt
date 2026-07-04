package io.refueler.merchant.core.cashu

import android.content.Context
import io.refueler.merchant.core.prefs.PreferenceStore

/**
 * Gates Nostr mint backup behind an event/pop-up mode flag.
 *
 * When event mode is active, backup to Nostr is suppressed —
 * suitable for temporary terminal deployments (gig venues, pop-ups)
 * where persistent relay publishing is undesirable.
 *
 * The flag is stored in plaintext app prefs (non-sensitive toggle).
 */
class EventModeManager(private val context: Context) {

    fun isEventMode(): Boolean =
        PreferenceStore.app(context).getBoolean(KEY_EVENT_MODE, false)

    fun setEventMode(enabled: Boolean) {
        PreferenceStore.app(context).putBoolean(KEY_EVENT_MODE, enabled)
    }

    companion object {
        const val KEY_EVENT_MODE = "event_mode_enabled"
    }
}
