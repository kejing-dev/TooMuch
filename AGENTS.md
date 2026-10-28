# Too Much (感官过载)

Empathy experience about sensory overload for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed
Stage (Full Space, passthrough), package `io.embodit.toomuch`. You pin six everyday sounds (fridge,
clock, plastic bag, dripping tap, lamp buzz, phone pings) to real spots in your room, do a small task
on a tray in front of you while the sounds slowly grow and soft colours drift at the edge of view,
then unload the sounds one by one and do the task again in the quiet. No flashing; "Quiet now" is
always on the body panel.

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
  Main.kt                       DefaultStage { TooMuchTheme { TooMuchScreen() } }
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
  ui/tm/                        TmUiState (+ design state id), TmEvent, TmViewModel (state machine, owns the Engine),
                                TooMuchScreen
  ui/tm/scene/                  TmScene (the one ECS system: engine step, audio 10 Hz, ripples, tray, overlay, aim,
                                stick, panel placement, taps / drags), Views (Meshes, SourceView, RoomView, TrayView,
                                OverlayView, AimView), Textures, SceneKit, SceneMaterials, PanelRig, PanelSpec, Follower
  ui/tm/components/             BodyPanel, Cards (front card, unload card, menu, labels, room tag), TmStage, Common,
                                Strings (run-time language)
app/src/debug/                  DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/                   TooMuchModelTest (design verification JVM list)
app/src/main/assets/audio/      synthesised WAVs (tools/gen_sounds.py)
tools/                          gen_sounds.py, gen_strings.py (strings from the design copy), make_icons.py
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
- S05 baseline: tray 0.9 m ahead, 0.30 m below the head, all sources at L0.
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

## Not verified yet

On a headset only: spatial audio (direction, distance, HRTF; whether ObjectAudioComponent sounds at all on
the emulator was not checked by ear), the synthesised sounds' realism, controller ray / trigger / stick,
eye-tracking pinch precision, how strong the haze and blobs feel in real passthrough, tray height comfort.
