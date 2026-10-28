#!/usr/bin/env bash
# UI occlusion audit poses (taste D8 / D9 / D10, learning-2026-10-26 checklist 1, 2, 6):
# turn 90 deg -> shot -> "Reset around me" -> shot -> quiet card; turn to 180 -> reset -> menu; look down 40;
# body panel + menu in view (turned left 30). Camera turns are emulator right-drags (0.0537 deg / fb px).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
H="bash $ROOT/tools/harness/emu.sh"
S="bash $ROOT/tools/shot.sh"
turn() { # degrees, + = right
  local px; px=$(python -c "print(int(round($1/0.0537)))")
  while [ "$px" -ne 0 ]; do
    local step=$px; [ "$step" -gt 800 ] && step=800; [ "$step" -lt -800 ] && step=-800
    $H drag 1080 1080 $((1080 + step)) 1080 -Button right -Steps 20 >/dev/null 2>&1
    px=$((px - step))
  done
}
pitch() { # degrees, + = down
  local px; px=$(python -c "print(int(round($1/0.0537)))")
  $H drag 1080 900 1080 $((900 + px)) -Button right -Steps 20 >/dev/null 2>&1
}
$S audit-0-S06 "goto s=S06" "level n=1 instant=true"
turn 90
$S audit-90-before-reset
$S audit-90-reset "reset_me"
$S audit-90-quiet "quiet"
$S audit-90-continue "continue"
turn 90
$S audit-180-reset "reset_me"
$S audit-180-unload-card "goto s=S10" "card id=phone"
turn -30
$S audit-180-body-menu "menu"
$S audit-180-room-page "room_page on=true"
$S audit-180-menu-closed "menu"
turn 30
pitch 40
$S audit-180-down40 "goto s=S06"
pitch -40
$S audit-uvtest "uvtest on=true"
$S audit-uvtest-off "uvtest on=false"
