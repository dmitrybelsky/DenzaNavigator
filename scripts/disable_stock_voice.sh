#!/usr/bin/env bash
# Disable the stock BYD voice ASSISTANT (com.byd.voiceAssis) — non-root, reversible.
# Leaves com.byd.autovoice (voice ENGINE: system warning prompts, TTS) intact by default, since
# disabling it can mute seatbelt/safety voice prompts. Yandex nav voice is unaffected (it uses
# USAGE_ASSISTANCE_NAVIGATION_GUIDANCE on its own annotation player). Re-enable with `pm enable <pkg>`.
set -u
SERIAL="${1:-192.168.1.67:5555}"
ASSISTANT=com.byd.voiceAssis
ENGINE=com.byd.autovoice            # NOT disabled by default (pass "full" as $2 to also disable)
adb connect "$SERIAL" >/dev/null 2>&1

disable() {
    echo "[*] disable-user $1"
    adb -s "$SERIAL" shell pm disable-user --user 0 "$1" 2>&1 | tail -1
    echo "    state: $(adb -s "$SERIAL" shell pm list packages -d 2>/dev/null | grep -c "$1") (1=disabled)"
}

disable "$ASSISTANT"
[ "${2:-}" = "full" ] && disable "$ENGINE"

echo "[i] re-enable later: adb -s $SERIAL shell pm enable $ASSISTANT"
echo "[i] Yandex nav voice unchanged (nav-guidance audio channel)."
