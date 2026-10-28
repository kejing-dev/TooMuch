#!/usr/bin/env bash
# Debug commands, then one full-frame console screenshot:
#   tools/shot.sh NAME "goto s=S06" "level n=2 instant=true" ...
# Each argument is one command: the first word is `cmd`, then key=value extras
# (value true/false -> --ez, a number -> --ef, anything else -> --es). The emulator is un-throttled
# (harness `fast on`) while the panels settle, then captured with the emulator console.
#   OUT=dir (default ../spatial-app-factory/apps/too-much/build/shots), PRE / SETTLE seconds, DEV.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
PKG=io.embodit.toomuch
OUT="${OUT:-$ROOT/../spatial-app-factory/apps/too-much/build/shots}"
H="bash $ROOT/tools/harness/emu.sh"
mkdir -p "$OUT/.raw"
name="$1"; shift

send() {
  local cmd="$1"; shift
  local args="--es cmd $cmd"
  for kv in "$@"; do
    local k="${kv%%=*}" v="${kv#*=}"
    if [ "$v" = "true" ] || [ "$v" = "false" ]; then args="$args --ez $k $v"
    elif [[ "$v" =~ ^-?[0-9]+(\.[0-9]+)?$ ]]; then args="$args --ef $k $v"
    else args="$args --es $k $v"; fi
  done
  MSYS_NO_PATHCONV=1 adb -s "$DEV" shell "am broadcast -a $PKG.DEBUG -p $PKG $args" >/dev/null 2>&1
}

for c in "$@"; do
  # shellcheck disable=SC2086
  send $c
  sleep "${GAP:-0.6}"
done
sleep "${PRE:-1.5}"
$H fast on >/dev/null 2>&1
sleep "${SETTLE:-8}"
before=$(ls -1 "$OUT/.raw" 2>/dev/null | wc -l)
adb -s "$DEV" emu screenrecord screenshot "$(cygpath -w "$OUT/.raw")" >/dev/null 2>&1
for i in $(seq 1 20); do sleep 0.5; [ "$(ls -1 "$OUT/.raw" | wc -l)" -gt "$before" ] && break; done
sleep 0.5
newest=$(ls -1t "$OUT/.raw"/*.png 2>/dev/null | head -1)
if [ -n "$newest" ]; then mv -f "$newest" "$OUT/$name.png"; echo "OK $name"; else echo "capture FAILED $name"; fi
$H fast off >/dev/null 2>&1
adb -s "$DEV" logcat -d -s TooMuch:V 2>/dev/null | grep -E "STATE S" | tail -1 | cut -c33-200
