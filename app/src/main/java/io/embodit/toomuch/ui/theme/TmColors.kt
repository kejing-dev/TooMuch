package io.embodit.toomuch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Panels use PicoTheme roles (container + on- colours); the tokens below are for the 3D content,
 * the head-locked layer and the level ring.
 */
object TmColors {
    // ---- PicoTheme roles fixed by the app (TooMuchTheme)
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFC93A3F) // design-style: fixed-figma-color error container of "Quiet now" (white text 5.0:1)
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert
    val Passable = Color(0xFF66E08A) // design-style: fixed-figma-color passable: done ticks
    val Interaction = Color(0xFFFFC72C) // design-style: fixed-figma-color interaction: selected capsule, selected card

    // ---- design `palette`
    val Level = listOf(Color(0xFF8FA39B), Color(0xFFB7A86A), Color(0xFFD29B4A), Color(0xFFDB7A3C), Color(0xFFDE6142)) // design-style: fixed-figma-color levelColors (L4 lifted from #C9553A to #DE6142 for 3:1 on glass)
    val Blob = listOf(Color(0xFFFF8A3D), Color(0xFF3DD6C8), Color(0xFFE05CB8)) // design-style: fixed-figma-color blobColors
    val Haze = Color(0xFF7E522C) // design-style: fixed-figma-color hazeColor, darkened from #E8B07A: the engine blends in linear light, the light tone lifted dark areas (V3 "never brighter")
    val Ripple = Color(0xFFF2E7D5) // design-style: fixed-figma-color rippleColor
    val CardFace = Color(0xFFF4EFE6) // design-style: fixed-figma-color cardFace
    val RoomLine = Color(0xFF9FB3C8) // design-style: fixed-figma-color roomLine
    val TrayWood = Color(0xFFC8A37A) // design-style: fixed-figma-color trayWood
    val MugBlue = Color(0xFF4A7BA6) // design-style: fixed-figma-color mugBlue
    val IconBg = Color(0xFFEFE7D8) // design-style: fixed-figma-color appIconBg

    // ---- 3D props (design `entities3d.trayItems`, plain colours)
    val Ink = Color(0xFF2B2B2B) // design-style: fixed-figma-color icon ink, card glyphs
    val Frame = Color(0xFFD5D2CC) // design-style: fixed-figma-color labelSecondary-like outline grey (drawn at 50 %)
    val CardEdge = Color(0xFF8E8A82) // design-style: fixed-figma-color card edge
    val Envelope = Color(0xFFEDE6D6) // design-style: fixed-figma-color envelope off-white
    val Notebook = Color(0xFF2F5D46) // design-style: fixed-figma-color notebook dark green
    val Remote = Color(0xFF3A3D42) // design-style: fixed-figma-color remote dark grey
    val Scarf = Color(0xFF8E2F35) // design-style: fixed-figma-color scarf dark red
    val Glasses = Color(0xFF1C1C1E) // design-style: fixed-figma-color glasses black
    val Book = Color(0xFF23345C) // design-style: fixed-figma-color book navy
    val Brass = Color(0xFFC9A043) // design-style: fixed-figma-color keys brass
    val Hook = Color(0xFF5A4632) // design-style: fixed-figma-color hook dark wood
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color white
}
