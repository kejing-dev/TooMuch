# Too Much (感官过载)

Empathy experience about sensory overload for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed
Stage (Full Space, passthrough), package `io.embodit.toomuch`. You pin six everyday sounds (fridge,
clock, plastic bag, dripping tap, lamp buzz, phone pings) to real spots in your room, do a small task
on a tray in front of you while the sounds slowly grow and soft colours drift at the edge of view,
then unload the sounds one by one and do the task again in the quiet. No flashing; "Quiet now" heads
every page of the main window (and the Stage chip while the window is closed).

Design package (binding): `../spatial-app-factory/apps/too-much/design-package.json` (revision 3,
archetype D, STAGE_MIXED, single_panel_with_popup, states S01 - S16, interactions T1 - T24, safety
V1 - V6 / A1 - A6 / C1 - C5). Gate B: passed by the user's chat ("可以这四个研发QA"), mock-up not
reviewed item by item, so the build follows the package strictly.

Skeleton: copied from `../access-check` (Spatial Ruler lineage: PanelRig pool, stage root, follower,
controller input, theme + fonts, debug receiver); the anchor gateway from `../robot-cell`. Those
folders were only read.

## Structure

```
app/src/main/java/io/embodit/toomuch/
  Main.kt                       DefaultStage { TooMuchScreen() } + one Planar WindowContainer { TmWindow() } (v0.1.1)
  domain/model/                 pure Kotlin, unit-tested
    Vec3.kt                     Vec3 / Quat4
    Sources.kt                  the six sources: gains L0-L4, caps, pulse intervals, unload options; Intensity
    Room.kt                     virtual room (RoomSize, RoomFrame), default spots, ray-room hit, pin clearance /
                                snap / nearer-farther, stick yaw rate
    Placement.kt                level head frame, body-panel cone, front card, tray pose (tilt 10 deg), unload-card rules
  domain/engine/                pure Kotlin, unit-tested
    Levels.kt                   level formula (max(time, actions) gated by 15 s) + LevelClock (lock, resume + climb)
    Engine.kt                   modes, three clocks, per-source gain slew (A3), group gain (intensity / mute / quiet),
                                pulses + interruptions (T24), overlay targets, ripple phase
    Overlay.kt                  haze + six blobs with every rate limit of V1 - V3
    TrayTask.kt                 hold-to-reveal, keys under the 4th candidate (from L3), hook, return drags + snap
  data/                         SettingsStore (prefs + "my home" source -> anchor map), AnchorGateway
  platform/                     TmLog, TmGraph, HeadTracker, ControllerInput (ray, trigger, stick x/y),
                                anchor/WorldAnchorGateway, audio/SoundOut (ObjectSoundOut, PoolSoundOut, UiSfx)
  domain/usecase/LevelGate.kt   the window opens only with a level head (D9), unit-tested (LevelGateTest)
  ui/tm/                        TmUiState (+ design state id), TmEvent, TmViewModel (state machine, owns the Engine),
                                TmShared (process-level store: the ViewModel, window lifecycle, level gate),
                                TooMuchScreen (Stage: opens / recalls / closes the window, exit)
  ui/tm/window/                 MainWindow (factory template: native TabBar, exitApp), TmWindow (3 pages)
  ui/tm/scene/                  TmScene (the one ECS system: engine step, audio 10 Hz, ripples, tray, overlay, aim,
                                stick, chip placement + level-gate feed, taps / drags), Views (Meshes, SourceView, RoomView,
                                TrayView, OverlayView, AimView), Textures, SceneKit, SceneMaterials, PanelRig, PanelSpec
  ui/tm/components/             BodyPanel (safety strip, step title, capsules, step buttons, sound grid), Cards (front
                                content, unload card, settings, room page, labels), TmStage (labels + chip), Common
                                (+ fits() overflow log), Strings (run-time language)
app/src/debug/                  DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/                   TooMuchModelTest (design verification JVM list), LevelGateTest, TrayLayoutTest
app/src/main/assets/audio/      CC0 recordings of the six sounds (tools/import_cc0.py) + two synthesised UI cues (tools/gen_sounds.py)
tools/                          import_cc0.py (freesound CC0 previews -> assets), gen_sounds.py (UI cues), loudness.py (offline
                                loudness render), gen_strings.py (strings from the design copy), make_icons.py
```

Data flows one way: panels, scene taps and debug commands -> `TmViewModel.onEvent` -> `TmUiState` ->
`TmScene.render`. Per frame the scene calls `TmViewModel.onFrame(dt)`, which steps the `Engine` and turns
its discrete events (level change, keys on the hook, all returned, idle timeout, help ready, silenced)
into state; the scene reads the engine's continuous values (gains, layer alphas, ripple phase, tray item
poses) directly. Nothing per frame goes through Compose.

## What happens (design `states`)

- S01 starting -> anchors load (3 tries on -5 / -6, empty store = none, 5 s cap) -> S02 room (4 x 4 x 2.6 m
  around the first head pose, 0.8 s) -> S03 intro card. "Continue" stays disabled until "Quiet now" was
  pressed once (S03b, C2).
- S04 pinning: capsule = current source; controller: ghost + ring follow the ray to the room faces,
  stick up / down pushes / pulls 0.5 - 6 m, trigger pins; hands: no cursor at all, a pinch on a room face
  pins there and goes to S04c. Pins are pushed out to 0.6 m from the head (D14), snap to their own outline
  within 0.3 m, get an anchor `toomuch-<source>` (failure keeps the card, log `ANCHOR fallback`), and
  preview 2 s at L1. S04c: Nearer / Farther 0.5 m, Default spot, Delete, stick x turns the card (D1).
  Auto-place / Start (>= 3 pinned) put the rest on their default spots.
- S05 baseline: tray 0.8 m ahead, 0.55 m below the head (v0.1.1), all sources at L0.
- S06 / S07 task: level formula; hold a candidate 1.5 s to look under it; keys under the 4th different
  candidate once the level is >= 3; tap the keys -> hook; drag mug / glasses / book into their outlines
  (5 -> 2 cm by level). Bag and phone shots drop what is held (each >= 5 s, both >= 3 s apart). S08 at L4:
  plateau line 8 s, layer sways only downwards; 150 s without action -> S10.
- S09 quiet: group to 0 in 0.5 s, layer out in 1 s, clocks stop. Continue: levelClock = 15 x (L - 1),
  gains climb from L0 one level per 3 s. Back from the background always lands here (A5).
- S10 unload: unprocessed at L2; tap a card or its capsule -> unload card (four placement rules; the
  far-round case moves it to the front and the card breathes). >= 3 done -> "Done unloading"; after 60 s
  "Do the rest for me". S11 redo: other seed, no interruptions, unprocessed at L1. S12 ending with both
  times and five tips. S13 menu (clocks paused), S14 unavailable, S15 about, S16 = language EN.

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.toomuch.DEBUG -p io.embodit.toomuch --es cmd goto --es s S06"
```

Log tag `TooMuch`. Every state change prints `STATE <Sxx> ...`. Every command goes through the same event
the UI sends.

| cmd | extras | does |
| --- | --- | --- |
| goto | `--es s S01..S16` (S03b, S04b, S04c, S10b too) | jumps; pins only the sources not pinned yet (default spots); S06 / S07 / S08 start a task; S09 = quiet from S08 |
| pin | `--es id fridge --ef x --ef y --ef z [--ef yaw]` | S04 only: x / z relative to the first head pose (z < 0 ahead), y above the room floor; same path as a trigger (clearance, snap, anchor) |
| autoplace / start | | the panel buttons |
| level | `--ei n 0..4` | levelClock := 15 n and locked until the next level / goto |
| overlay | `--ei n 0..4` (`-1` clears) | layer level only |
| quiet / continue | | the buttons |
| lang | `--es id zh/en/system` | |
| intensity | `--es id gentle/standard` | |
| mute | `--ez on true/false` | |
| input | `--es id controller/hands` (anything else = auto) | |
| room | `--ef w --ef d --ef h --ef floor` | size and floor offset (emulator floor: `--ef floor 0.4`) |
| unload | `--es id fridge --ei opt 0` | the option on the unload card (S10 only) |
| card | `--es id lamp` | the capsule (S04: adjust / select, S10: open the card) |
| audio | `--es id object/soundpool` | sound back-end (soundpool = QA fallback, stereo pan) |
| reset_home | | forget all anchors -> S04 |
| seed | `--ei n 1..4` | candidate layout |
| timescale | `--ef k` | every app timer (levelClock, pulses, 150 s, 60 s, redo clock) |
| elapsed | `--ei task 160 --ei quiet 28` | ending times |
| interrupt | `--es id bag/phone` | one forced interruption |
| menu / room_page / reset_me | | menu toggle, room page (`--ez on false` back), "Reset around me" |
| state | `--ez on true` / `false`, or none | every second (or once): STATE, GAINS (dB per source, next pulse, position), OVERLAY (hazeAlpha, hazeAlphaMax, blobAlphaMax, every blob az / el / alpha / off-axis angle) |
| panels / pose / park | | panel poses, head pose, re-park the body panel |
| uvtest | `--ez on true/false` | card faces show the asymmetric "F↑" test image (D10) |
| ui | `--es id <control>` | presses a control by name (names: `body_quiet`, `body_menu`, `body_mute`, `cap_<src>`, `adj_nearer`, `adj_farther`, `adj_default`, `adj_delete`, `adj_done`, `pin_auto`, `pin_start`, `base_start`, `task_skip`, `unl_<src>`, `unl_done`, `unl_rest`, `redo_skip`, `end_exit`, `intro_continue`, `intro_gentle`, `intro_standard`, `quiet_continue`, `quiet_unload`, `quiet_exit`, `end_again`, `end_exit_card`, `end_about`, `about_ok`, `card_opt0/1`, `card_ok`, `card_close`, `menu_*`, `room_*`, `clear_yes/no`) |
| controls | `--es id body` | bounds of every control of a panel |

Generic switches on any command: `--ez instant true` (skips eases, screenshots only, never for the V1
recording), `--ez hold true` (freezes S02's auto step, the plateau line, the S04b flash and S08's timeout).

Extra log lines: `GAIN quiet pressed t_ms=...` and `GAIN silenced t_ms=...` (A4: the difference must be
<= 500 ms), `LEVEL n`, `PIN`, `ANCHOR ok / fallback`, `TASK ...`, `INTERRUPT by=...`, `UNLOAD`, `CARD`,
`AIM pin`, `TAP`, `DRAG`, `AUDIO loaded / backend`.

### Screenshot list (design `qaHooks.sceneTable`)

S01 · S02 (`goto S02` holds) · S03 / S03b · S04 with `input controller` (ghost follows the ray) and with
`input hands` (no cursor) · S04b `goto S04` + `pin fridge 1.8 1.5 -1.9` (snaps) · S04c + "Nearer" before /
after · S05 · S06 `seed 1` + `level 1 --ez instant true`, then `level 2` (two blobs 35 deg out), a held
item, `interrupt bag` (record 5 s) · S07 + `level 3` · S08 `level 4 --ez instant true --ez hold true` ·
S09 from S08 (with "Go unload") and from S05 (without) · S10 · S10b: phone (near), lamp (pitch clamp),
far card (`goto S04`, `pin lamp 0 2.5 -4`, `goto S10`, `card lamp`), bag via `card bag` (front fallback,
card breathes) · S11 after `unload fridge 0` · S12 `elapsed 160 28` · S13 from S06 (locked rows) + room page ·
S14 · S15 · S16 `lang en` then S03 / S05 / S10b / S12. Layer states (S06 L2, S07 L3, S08) also as a 10 s
recording without `instant`. Emulator: set the floor first (`room --ef floor 0.4`).

## Deviations (recorded in the factory's state.json `deviations[]`)

1. Head-locked layer: `ShaderGraphMaterial` needs an asset authored in the PICO Spatial Editor (not on
   this PC). The haze is an unlit transparent inner sphere (r 2 m) and the blobs are six textured quads
   (Gaussian alpha bitmap), all depth-test off, `SortAsUIElementComponent(-1)`, opacity per entity; the
   shell rides on a camera anchor (`AnchorTarget.createCameraTarget()`) instead of a per-frame pose write.
   Same look and every V1 - V3 limit; panels drawing over it is still to be checked (N9).
2. Task pacing: with the interruption limits of T24 (>= 5 s per source, >= 3 s overall) no interval
   table gets the scripted player past 60 s more than 1 / 100 times. The keys therefore appear under the
   4th different candidate **and** only once the level is >= 3 (45 s at the earliest); the quiet redo has
   no such gate. Scripted player (unit test, with the tuned table of 7): >= 95 / 100 not done before 60 s,
   quiet redo <= 30 s in all 100.
3. Room size tag: one tag "L · W · H" on the front wall instead of three labels (fewer panels).
4. Sound files total 5.7 MB (design <= 3 MB): the cue layers (fridge hiss, hard clock, tap bass) are
   separate 8 s loops cross-faded by level.
5. Selected capsule / card: interaction-colour fill (capsule) and a yellow halo plate behind the card
   instead of an outline stroke.
6. A tap on a tray item without a drag lifts it for 1.7 s by itself (the emulator mouse cannot hold a
   pinch on an entity); a real hold works through the system drag or the trigger.
7. Pulse interval table tuned (the design says: tune the table, not A3): bag L0 8-12 s -> 4-6 s, phone
   L0 20-30 s -> 10-15 s (L4 unchanged 1.5-3 / 5-8 s). With deviation 2 the scripted player is >= 95 / 100.
8. Colours for contrast: "Quiet now" error container #C93A3F (white 5.0:1); level ring L4 #C9553A ->
   #DE6142 (3.6:1 on glass). Table in `../spatial-app-factory/apps/too-much/build/ui-audit.md`.
9. Haze colour #E8B07A -> #7E522C (alpha caps unchanged): the engine blends in linear light and the light tone
   lifted dark areas (dark wall 27 -> 98), against V3 "never brighter, only warmer and darker".
10. With the tray shown, the front (quiet) card rises to 2 cm under eye level (it cut into the tray's things).
11. Disabled worded buttons are inert, not platform-disabled (the platform fade hid "Press Quiet now once
    first"); card titles 26 sp; long English guide lines drop to three 12 sp lines.
12. "Reset around me" also moves the phone card when it sat on its tray-side spot or would end up inside the
    new tray (its default spot belongs to the tray).

Resolved design `needsCheck`: N1 ObjectAudioComponent(volume, Directivity, DistanceAttenuationMode,
reverbVolume) + `entity.prepareAudio(AudioResource)` -> AudioPlayerController (setLoop / setVolume / play /
pause / stop / seekTo / fade), AudioMixerGroupResource(name, volume, speed) + AudioMixerGroupsComponent,
AudioResourceConfig(mixerGroupId) (javap of core-6.1.9 + agent-vault docs; it compiles; sound on the
emulator unknown). N11 `HoverEffectComponent` exists and is on every card and tray item. SpatialUI
`Switch`, `SegmentControl`, `CircularProgressIndicator` exist; no stepper (two keys + text).

## Emulator pass (2026-10-28, emulator-5560, Pico_Emulator_6_1_fresh, High Definition 2560 x 2558)

Scripts: `tools/shot.sh NAME "cmd k=v ..."` (one state -> one console screenshot), `tools/shots_all.sh`
(every design state), `tools/audit.sh` (turn 90 / 180 + reset, look down 40, uvtest), `tools/stress.sh 30`
(in / out 30x), `tools/launch_check.sh 60` (cold start + ANR watch), `tools/sheet.py` (contact sheets).
Screenshots land in `../spatial-app-factory/apps/too-much/build/shots/`. Set the floor first:
`room floor=0.4` (the emulator room's floor is at Stage y 0.40).

- Scene builds in 1.6 - 1.9 s (9 meshes, 8 textures, 17 sounds, one per frame); cold start x 3 + 60 s idle:
  no ANR, no crash. 30x S04 <-> S05, menu, unload card, quiet card: no black patch, 0 crash / ANR.
- Anchors: created in 0.3 - 1 s, survive relaunch and reinstall ("my home" comes back, S03b -> S05).
- Panels draw over the head-locked layer (N9): the red "Quiet now" pixel is identical at L0 and L4.
- The emulator has no controller (`TRACK controller ... DEVICE_NOT_SUPPORTED`): the aim ghost, trigger pins
  and the stick turn cannot be seen here; use `pin` and the S04c buttons. Hands mode: a click on a room face
  gives the Stage point (N6 ok) and pins there.
- The Regular glass renders light on the emulator (dark labelPrimary text): measured contrast 5.8:1.
- Fixed on the emulator: card textures were upside down until the quad's v was flipped (the SDK samples v from
  the bottom); disabled buttons are inert instead of platform-disabled (the platform fade made "Press Quiet now
  once first" invisible); card titles 26 sp; long English guide lines go to three 12 sp lines; the front card
  rises above the tray when the tray is shown (it cut into the things on it); haze colour darkened (the engine
  blends in linear light, the light design tone lifted dark areas, against V3 "never brighter"); restored
  cards add the anchor's rotation to their stored yaw (the Stage re-centres on the head at each launch).
- Background / foreground (A5) cannot be triggered on the emulator (a system activity does not stop the Full
  Space app; HOME + Confirm exits): check on a headset with the Home button.
- A fresh-context QA sub-agent checked all shots against `states[].qaCapture`: fixed the task capsule ② tick
  (it looked done), the phone card inside the tray after a reset, the grey disabled Quiet now, the clock outline.
- Hand-over state: saved home cleared (no anchors), app on S03 (Chinese), emulator-5560 kept.
- The body panel (40 deg left) sits at the edge of the emulator's narrow view; turn the camera 35 deg left to
  photograph it (`emu.sh drag 1400 1080 750 1080 -Button right`).

## v0.1.1 · one system window (rework 2026-11-12)

User (2026-11-02): panels overlapped front / back; merge them into one template, use SpatialUI's native draggable
bar and the native TabBar. Factory snippet `prompts/snippets/native-window-move.md` + `MainWindowTemplate.kt`.

- All 2D content is in one Planar `WindowContainer` (600 x 640 dp), pages switch with the native SpatialUI `TabBar`
  only: **Experience** (step title + level ring, guide, then the intro / quiet / ending / about / unavailable / forget
  -home card, the unload card, or the capsules + step buttons), **Room** (virtual room size), **Settings** (language,
  intensity, reset around me, bring panel here, re-pin, forget home, about, Quit app with an inline confirmation).
  Every page starts with "Quiet now" + Mute. Moving / closing / minimising are the system window controls.
- The room / settings tabs are the old menu (S13: clocks pause while open); `TmEvent.TabSelected`, debug `tab id=...`.
- The ViewModel is process-level (`TmShared`); a second Stage in the same process starts from S01 (`stageStarted`).
- The window opens only with the head within 10 deg of level for 300 ms (`LevelGate`, D9); while it waits, the Stage
  shows "Look straight ahead / and the panel opens" on the line of sight; fallbacks 5 s without a head pose, 15 s
  without Stage frames. After the window's × (or minimise) the Stage shows a chip 25 deg left / 12 deg down / 0.8 m
  with **Quiet now** and **Open panel** (C1 keeps "Quiet now" reachable while the window is closed).
- Bring panel here = close, wait for ON_DESTROY, reopen through the level gate (debug `window id=recall`).
- Exit (template order): release scene / sound / window, `closeWindowContainer`, `closeStage`, `finishAndRemoveTask`,
  `exitProcess` after 400 ms. "Skip to unloading" and leaving from the quiet card mid-run ask first, in place.
- Removed: body / front / unload / menu attachment panels, the lazy-follow `Follower` (D21), the room size tag.
  Stage panels left: six source labels (world text) and the chip.
- 3D off the line to the window (it opens 1.75 m straight ahead): tray 0.8 m ahead, 0.55 m under the eyes (was 0.9 /
  0.30; on the emulator the window's move dot sat on the book); clock default spot 0.9 m left of the front wall's
  middle; the phone's tray-side spot follows the tray height.
- D24: the room's six face colliders are enabled only while pinning (`RoomView.setPinnable`), see
  `../spatial-app-factory/apps/too-much/build/reality-audit.md`.
- Short pages show a live **sound grid** (six sources, five dots of their current level, a tick once unloaded) in
  baseline / task / redo / quiet / forget-home, from `TmViewModel.liveLevels()` (2.5 Hz).
- Debug additions: `window id=open|close|recall`, `tab id=now|room|howto|settings`; `park` and the BODY / FRONT / UNLOAD /
  MENU control names are gone (`ui id=body_quiet`, `body_mute`, `menu_*`, `chip_quiet`, `open_window`,
  `skip_no` / `skip_yes`, `quiet_exit_no` / `quiet_exit_yes`, `exit_no` / `exit_yes` exist).
- Emulator 6.1 (emulator-5556, Pico_Emulator_6_1_c, 2160 SD): `pico-cli interact` has no `uiautomator spatial`
  backend on this ROM; real clicks go through `tools/harness/emu.sh click` (window buttons, the TabBar, the system
  Close menu under the window, the chip all respond). The TabBar shows icons, labels on hover, in the app language.

- Sounds (2026-11-16, user: 「换录音」, factory B20): the six everyday sounds are CC0 recordings from freesound.org
  (sources, licences and processing per file in `../spatial-app-factory/apps/too-much/build/sound-sources.md`).
  `tools/import_cc0.py` cuts loops / ticks / pieces and gives each file the RMS its synthesised file had, so the
  level tables and the gentle -8 dB default keep their meaning (render: old -25.5, new -26.1 dBFS voiced RMS). The cue
  layers are filtered copies of the same recordings. Only `ui_snap` / `ui_ding` stay synthesised (UI cues).
- A fourth tab **How to** (profile B19): pin, look under, put back, unload, quiet now, move / bring back the panel,
  each with the hands way and the controller way (`how_to_*` strings). Like Room / Settings it pauses the clocks.
- Buttons are 64 dp high (worded buttons, capsules, Quiet now, unload options, stepper keys) and side-by-side buttons
  16 dp apart (profile B31 gates). Tray drags sum SDK 6.1.9's per-event `dragAmount` (gate drag-delta).

## v0.1.2 · QA #2 rework (2026-11-16)

- Tray things are modelled in code (`ui/tm/scene/ItemModels.kt`, q2-1 / B12): mug with handle and coffee, keys with
  bits on a ring, folded glasses with lenses, book with page block and label, spiral notebook with elastic, envelopes
  with flap / stamp / address lines, folded striped scarf with fringes, remote with pad / number keys / red power key.
  The scene stays unlit; each part is an up-facing mesh in its colour plus a darker side mesh (`ItemTone`), built one
  part per frame (`Meshes` steps, ANR rule).
- Tray layout (q2-2 / D17): board 0.90 x 0.56 m, candidates in a 2 x 2 grid on the left, mug right of the back row,
  book / remote / glasses along the front, outlines on the right strip, hook between mug and its outline; every pair
  of footprints >= 5 cm apart in all four seeds (`TrayLayoutTest`). Revealed keys lie in their candidate's own
  footprint and that candidate stays lifted until they are tapped (`TrayTask.keysWaiting`).
- Short pages (q2-3): starting, room ready and unavailable spread their content (sound grid, room size, what to do)
  over the page; the settings quit confirmation shows an icon and "your pins stay saved" above the question.
- Standard intensity is -3.5 dB (L4 mix peak <= -1.5 dBFS over 12 render seeds); gentle stays -8 dB (user waiver).

Deviations 3 (room tag) and 10 (front card over the tray) no longer apply; 13 - 18 are the items above.

## Not verified yet

On a headset only: whether a controller trigger aimed at the system window also reaches the room faces while
pinning (the emulator has no controller), the window size / text size at real distance, spatial audio (direction, distance, HRTF; whether ObjectAudioComponent sounds at all on
the emulator was not checked by ear), how the CC0 recordings sound in spatial audio on a headset, controller ray / trigger / stick,
eye-tracking pinch precision, how strong the haze and blobs feel in real passthrough, tray height comfort.
