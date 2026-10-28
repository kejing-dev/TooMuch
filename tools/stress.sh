#!/usr/bin/env bash
# "In and out 30 times" (design verification, A4 pitfall): S04 <-> S05, the menu, the unload card and
# the quiet card, 30 rounds each, then one screenshot per round type (tools/shot.sh) and a crash / ANR count.
#   tools/stress.sh [ROUNDS]
set -u
DEV=${DEV:-emulator-5560}; PKG=io.embodit.toomuch; N=${1:-30}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
b() { MSYS_NO_PATHCONV=1 adb -s "$DEV" shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd $*" >/dev/null 2>&1; }
adb -s "$DEV" logcat -c
for i in $(seq 1 "$N"); do b goto --es s S04; sleep 0.8; b goto --es s S05; sleep 0.8; done
SETTLE=8 bash "$ROOT/tools/shot.sh" stress-S04-S05
for i in $(seq 1 "$N"); do b menu; sleep 0.7; b menu; sleep 0.7; done
SETTLE=8 bash "$ROOT/tools/shot.sh" stress-menu "menu"
b menu; sleep 0.5
b goto --es s S10; sleep 1
for i in $(seq 1 "$N"); do b card --es id phone; sleep 0.7; b card --es id lamp; sleep 0.7; done
SETTLE=8 bash "$ROOT/tools/shot.sh" stress-unload-card
for i in $(seq 1 "$N"); do b quiet; sleep 0.7; b continue; sleep 0.7; done
SETTLE=8 bash "$ROOT/tools/shot.sh" stress-quiet "quiet"
echo "crash=$(adb -s "$DEV" logcat -d | grep -c -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG")" \
  "anr=$(adb -s "$DEV" logcat -d | grep -c -E "ANR in $PKG|am_anr.*$PKG")" \
  "anrWin=$(adb -s "$DEV" shell dumpsys window windows | grep -c 'Application Not Responding')"
