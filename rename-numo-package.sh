#!/usr/bin/env bash
# =============================================================
# Refueler · Numo fork package rename — CC-54 Phase 0
# com.electricdreams.numo -> io.refueler.merchant
#
# WHY THIS SCRIPT EXISTS RATHER THAN CLAUDE JUST DOING IT:
# 271 files reference the old package (224 in app/src/main alone).
# Claude's sandbox has no Android SDK/Gradle toolchain, so a rename
# done there could never be compile-verified — it would be exactly
# the "looks complete, silently isn't" failure mode this project's
# own standing rule (§4j) exists to catch (see CC-41 stations bug).
# Run this locally where you can actually ./gradlew build afterward.
#
# USAGE:
#   cd <numo fork checkout root>
#   chmod +x rename-numo-package.sh
#   ./rename-numo-package.sh
#
# Safe to re-run — operations are idempotent (dir moves only happen
# if source exists; text replacement is a no-op on already-renamed
# content).
# =============================================================

set -euo pipefail

OLD_PKG="com.electricdreams.numo"
NEW_PKG="io.refueler.merchant"
OLD_PATH="com/electricdreams/numo"
NEW_PATH="io/refueler/merchant"
SCRIPT_NAME="$(basename "$0")"

if [ ! -f "settings.gradle.kts" ]; then
  echo "ERROR: run this from the Numo fork checkout root (settings.gradle.kts not found here)."
  exit 1
fi

# Portable in-place sed — macOS/BSD needs '' after -i, GNU doesn't.
_sed_inplace() {
  local file="$1"
  # Use | as delimiter to avoid conflicting with dots/slashes in package names.
  if sed --version >/dev/null 2>&1; then
    # GNU sed
    sed -i "s|${OLD_PKG}|${NEW_PKG}|g" "$file"
  else
    # BSD/macOS sed
    sed -i '' "s|${OLD_PKG}|${NEW_PKG}|g" "$file"
  fi
}

echo "=== 1. Text replacement across source, resources, config ==="
# Covers every file type found during the CC-54 scoping pass.
# Uses -print0 / read -d '' to handle paths with spaces safely.
# grep -lF: literal-string match (not regex) — avoids dots matching as wildcards.
COUNT=0
while IFS= read -r -d '' f; do
  if grep -qF "$OLD_PKG" "$f" 2>/dev/null; then
    _sed_inplace "$f"
    COUNT=$((COUNT + 1))
  fi
done < <(find . \
  -path ./.git -prune -o \
  -path "*/build/*" -prune -o \
  -name "$SCRIPT_NAME" -prune -o \
  -type f \( \
    -name "*.kt" -o \
    -name "*.java" -o \
    -name "*.xml" -o \
    -name "*.gradle.kts" -o \
    -name "*.pro" -o \
    -name "*.yml" -o \
    -name "*.yaml" -o \
    -name "*.md" \
  \) -print0)

echo "Text-replaced package identifier in $COUNT files."

echo ""
echo "=== 2. Move source directories to match new package path ==="
for SRC_ROOT in "app/src/main/java" "app/src/test/java" "app/src/androidTest/java"; do
  OLD_DIR="$SRC_ROOT/$OLD_PATH"
  NEW_DIR="$SRC_ROOT/$NEW_PATH"
  if [ -d "$OLD_DIR" ]; then
    mkdir -p "$(dirname "$NEW_DIR")"
    git mv "$OLD_DIR" "$NEW_DIR" 2>/dev/null || mv "$OLD_DIR" "$NEW_DIR"
    echo "Moved: $OLD_DIR -> $NEW_DIR"
    # clean up now-empty old package parent dirs (com/electricdreams)
    rmdir -p "$(dirname "$OLD_DIR")" 2>/dev/null || true
  else
    echo "Skipped (not found, already moved?): $OLD_DIR"
  fi
done

echo ""
echo "=== 3. Verify no old-package references remain ==="
# -F: literal string, not regex. Excludes this script (contains OLD_PKG by necessity).
REMAINING=$(grep -rlF "$OLD_PKG" --exclude-dir=.git --exclude-dir=build --exclude="$SCRIPT_NAME" . 2>/dev/null || true)
if [ -n "$REMAINING" ]; then
  echo "WARNING: old package string still found in:"
  echo "$REMAINING"
  echo "Review these manually before building."
else
  echo "Clean — no remaining references to $OLD_PKG found."
fi

echo ""
echo "=== 4. Manual checks still required (not automated by this script) ==="
cat <<'EOF'
- app/google-services.json / Firebase config: none found in the CC-54 scoping pass,
  but if you've added one locally since, its package_name field needs updating too.
- Keystore / signing config: unaffected by this rename, no action needed.
- Android Studio tip: "Refactor > Rename Package" is safe to run afterward as a
  supplementary step — it catches any IDE-cached references this text pass misses.
  Do NOT use it as a replacement; Studio can silently skip .yml/.md files.
- rootProject.name in settings.gradle.kts is still "Numo" (cosmetic — decide
  separately whether you want the Gradle module renamed to something Refueler-branded).
- Run `./gradlew clean assembleDebug` after this completes. That is the real
  verification step — nothing above confirms the app actually compiles.
EOF

echo ""
echo "Done. Review 'git status' / 'git diff --stat' before committing."
