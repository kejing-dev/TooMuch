"""Writes app/src/main/res/values{,-zh}/strings.xml from the design package's `copy` (and audio.sources).

    python -I -X utf8 tools/gen_strings.py <design-package.json>
"""
import json, os, sys
from xml.sax.saxutils import escape

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
d = json.load(open(sys.argv[1], encoding="utf-8"))
c = d["copy"]
S = {}

def put(name, zh, en):
    S[name] = (zh, en)

put("app_name", "感官过载", "Too Much")
for k, v in c["panelTitle"].items():
    put("phase_" + k, v["zh"].replace("{n}", "%1$d").replace("{name}", "%1$s"), v["en"].replace("{n}", "%1$d").replace("{name}", "%1$s"))
put("phase_quiet", "安静", "Quiet")
put("phase_intro", "感官过载", "Too Much")
for k, v in c["shortNames"].items():
    put("src_" + k, v["zh"], v["en"])
for s in d["audio"]["sources"]:
    put("src_long_" + s["id"], s["zh"], s["en"])
    put("fp_" + s["id"], s["firstPersonLine"]["zh"], s["firstPersonLine"]["en"])
    for i, o in enumerate(s["unloadOptions"]):
        put("opt_%s_%d" % (s["id"], i), o["zh"], o["en"])
for k, v in c["guide"].items():
    put("guide_" + k, v["zh"].replace("{name}", "%1$s"), v["en"].replace("{name}", "%1$s"))
for k, v in c["buttons"].items():
    put("btn_" + k, v["zh"], v["en"])
put("btn_menu", "菜单", "Menu")
put("btn_cancel", "取消", "Cancel")
put("btn_forget", "忘掉", "Forget")
put("btn_close", "关闭", "Close")
i = c["intro"]
for k in ["title", "p1", "p2", "p3", "p3armed", "inputController", "inputHands", "continueDisabled"]:
    put("intro_" + k, i[k]["zh"], i[k]["en"])
put("intensity_gentle", i["intensity"]["zh"][0], i["intensity"]["en"][0])
put("intensity_standard", i["intensity"]["zh"][1], i["intensity"]["en"][1])
put("quiet_title", c["quietCard"]["title"]["zh"], c["quietCard"]["title"]["en"])
put("quiet_body", c["quietCard"]["body"]["zh"], c["quietCard"]["body"]["en"])
for k in ["plateauLine", "endingTitle", "helpTitle", "unloadDone", "about", "unavailable", "clearConfirm"]:
    put(k, c[k]["zh"], c[k]["en"])
# v0.1.1: the six everyday sounds became CC0 recordings (user 2026-11-13 「换录音」); the design's about line said all
# sounds were synthesised, so that sentence goes and the about card's own sources line (about_synth) says what is what
put("about", c["about"]["zh"].replace("全部声音由程序合成。", ""), c["about"]["en"].replace("All sounds are synthesised. ", ""))
put("endingTimes", c["endingTimes"]["zh"].replace("{m}", "%1$d").replace("{s}", "%2$d").replace("{q}", "%3$d"), c["endingTimes"]["en"].replace("{m}", "%1$d").replace("{s}", "%2$d").replace("{q}", "%3$d"))
put("endingTimesSkipped", c["endingTimesSkipped"]["zh"].replace("{m}", "%1$d").replace("{s}", "%2$d"), c["endingTimesSkipped"]["en"].replace("{m}", "%1$d").replace("{s}", "%2$d"))
for n, (z, e) in enumerate(zip(c["helpLines"]["zh"], c["helpLines"]["en"])):
    put("help_%d" % (n + 1), z, e)
put("starting", "准备中", "Getting ready")
put("label_volume", "声量", "Volume")
put("label_mute", "静音", "Mute")
put("label_input", "输入", "Input")
put("menu_language", "语言", "Language")
put("menu_intensity", "强度", "Intensity")
put("lang_zh", "中文", "中文")
put("lang_en", "EN", "EN")
put("room_title", "虚拟房间", "Virtual room")
put("room_length", "长", "Length")
put("room_width", "宽", "Width")
put("room_height", "高", "Height")
put("room_floor", "地面高度", "Floor height")
put("room_tag", "长 %1$s · 宽 %2$s · 高 %3$s m", "L %1$s · W %2$s · H %3$s m")
put("task_keys", "找到钥匙", "Find the keys")
put("task_return", "三样东西归位", "Put 3 things back")
put("about_sources", "NAS · Too Much Information\nThe Drum · TMI VR\nThe Mighty · Sensory issues\nPsychology Today · Shopping while autistic\nNDTi · Autistic flourishing\nJ. P. Knapp · Every Clock", "NAS · Too Much Information\nThe Drum · TMI VR\nThe Mighty · Sensory issues\nPsychology Today · Shopping while autistic\nNDTi · Autistic flourishing\nJ. P. Knapp · Every Clock")
put("about_synth", "六种实物声是 CC0 真实录音（freesound.org），界面提示音为合成", "The six everyday sounds are CC0 recordings (freesound.org); the UI cues are synthesised")
put("about_version", "版本 %1$s", "Version %1$s")
put("cd_icon", "图标", "icon")
# v0.1.1: one system window with a native TabBar (the menu became the Room / Settings tabs)
put("tab_now", "体验", "Experience")
put("tab_room", "房间", "Room")
put("tab_settings", "设置", "Settings")
put("btn_openPanel", "打开面板", "Open panel")
put("look_level", "平视前方\n面板就出来", "Look straight ahead\nand the panel opens")
put("btn_recall", "叫回面前", "Bring panel here")
put("btn_quit", "退出应用", "Quit app")
put("quit_confirm", "退出感官过载？这一轮不会保存。", "Quit Too Much? This run is not saved.")
put("skip_confirm", "跳过剩下的任务，直接去减负？", "Skip the rest of the task and go unload?")
put("btn_keepGoing", "继续任务", "Keep going")
put("pin_hint", "至少钉好 3 个才能开始，没钉的会放到默认位置。", "Pin at least 3 to start; the rest go to their default spots.")
put("room_hint", "调长、宽、高，让虚线框贴着你家的墙。", "Change the size until the dashed outlines sit on your walls.")
put("guide_room", "房间大小不对，可以在「房间」页调", "Room size off? Adjust it on the Room tab")
put("guide_reusedHome", "用上次钉好的位置；想改在「设置」里重新钉", "Using last time's spots; re-pin in Settings")

# v0.1.1 · how-to page (profile B19): every main action, hands and controller
put("tab_howto", "怎么操作", "How to")
put("how_to_title", "怎么操作", "How to")
put("how_to_hands_line", "手势：%1$s", "Hands: %1$s")
put("how_to_ctrl_line", "手柄：%1$s", "Controller: %1$s")
put("how_to_pin", "把声音钉到房间里", "Pin a sound in your room")
put("how_to_pin_hands", "看向墙面或家具，捏一下就钉在那里", "look at a wall or furniture and pinch")
put("how_to_pin_ctrl", "射线指墙面，扳机钉下；摇杆上下推远拉近", "aim at a wall, trigger; stick up / down = farther / nearer")
put("how_to_look", "看看东西底下有没有钥匙", "Look under a thing for the keys")
put("how_to_look_hands", "捏住托盘上的东西 1.5 秒", "pinch and hold a thing on the tray for 1.5 s")
put("how_to_look_ctrl", "射线指着它，按住扳机 1.5 秒", "aim at it and hold the trigger for 1.5 s")
put("how_to_return", "三样东西归位", "Put 3 things back")
put("how_to_return_hands", "捏住杯子、眼镜、书，拖进虚线框", "pinch the mug, glasses or book and drag it into its outline")
put("how_to_return_ctrl", "按住扳机拖进虚线框，松开放下", "hold the trigger, drag into the outline, release")
put("how_to_unload", "给声音减负", "Unload a sound")
put("how_to_unload_hands", "捏一下声音卡片或面板上它的胶囊，选一种做法", "pinch its card or its capsule, pick a way")
put("how_to_unload_ctrl", "射线点卡片或胶囊，扳机选做法", "aim at the card or capsule, trigger to pick")
put("how_to_quiet", "立刻安静", "Quiet now")
put("how_to_quiet_hands", "捏面板顶上的红色按钮，0.5 秒内全静", "pinch the red button at the top: silent within 0.5 s")
put("how_to_quiet_ctrl", "射线点红色按钮，按扳机", "aim at the red button, trigger")
put("how_to_panel", "移动或叫回面板", "Move or bring back the panel")
put("how_to_panel_hands", "捏住面板下方的横条拖动；关掉后点「打开面板」", "pinch the bar under the panel and drag; after × use Open panel")
put("how_to_panel_ctrl", "射线指横条按住扳机拖；设置里有「叫回面前」", "hold the trigger on the bar and drag; Settings has Bring panel here")

# v0.1.2 · short pages carry what is happening (QA #2 q2-3: whitespace <= 25 %)
put("starting_hint", "正在准备六个声音和一间虚拟房间，几秒就好。", "Setting up six sounds and a virtual room; a few seconds.")
put("unavailable_hint", "请在完整空间（Full Space）里重新打开；声音、托盘和这个面板都需要它。", "Open the app again in Full Space; the sounds, the tray and this panel all need it.")
put("quit_keep", "钉好的声音位置会保留，下次直接用。", "Your pinned spots stay saved for next time.")

def esc(t):
    bs = chr(92)
    t = escape(t).replace("'", bs + "'").replace('"', bs + '"').replace(chr(10), bs + "n")
    return t

for lang, idx, folder in [("en", 1, "values"), ("zh", 0, "values-zh")]:
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- generated by tools/gen_strings.py from the design package `copy`; do not edit by hand -->", "<resources>"]
    for k in sorted(S):
        v = esc(S[k][idx])
        fmt = "%1$" in v
        out.append('    <string name="%s"%s>%s</string>' % (k, "" if fmt or "%" not in v else ' formatted="false"', v))
    out.append("</resources>")
    p = os.path.join(root, "app", "src", "main", "res", folder, "strings.xml")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print(p, len(S))
