package io.embodit.toomuch.ui.tm.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.SortAsUIElementComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import io.embodit.toomuch.ui.theme.TmColors

/** Scene-lifetime materials, one per appearance. All unlit, tone mapping off so the design colours are exact. */
internal class SceneMaterials(private val shared: SharedResources) {
    val cardEdge: Material = unlit(TmColors.CardEdge)
    val halo: Material = sheer(TmColors.Interaction, 1f)
    val ripple: Material = sheer(TmColors.Ripple, 1f)
    val roomLine: Material = sheer(TmColors.RoomLine, 0.25f, depthTest = false)
    val frameGrey: Material = sheer(TmColors.Frame, 0.5f)
    val frameActive: Material = sheer(TmColors.Interaction, 1f)
    val ghost: Material = sheer(TmColors.CardFace, 0.4f)
    val aimRing: Material = sheer(TmColors.Interaction, 0.9f, depthTest = false)
    val wood: Material = unlit(TmColors.TrayWood)
    val hook: Material = unlit(TmColors.Hook)
    val brass: Material = unlit(TmColors.Brass)
    val envelope: Material = unlit(TmColors.Envelope)
    val notebook: Material = unlit(TmColors.Notebook)
    val remote: Material = unlit(TmColors.Remote)
    val scarf: Material = unlit(TmColors.Scarf)
    val glasses: Material = unlit(TmColors.Glasses)
    val book: Material = unlit(TmColors.Book)
    val mug: Material = unlit(TmColors.MugBlue)
    val traceGrey: Material = sheer(TmColors.Frame, 0.5f)

    /** Head-locked layer: never depth-tested, see-through; opacity per entity (OpacityControllerComponent). */
    val haze: Material = sheer(TmColors.Haze, 1f, depthTest = false)

    /** The card face of a source with its icon texture. */
    fun textured(texture: TextureResource): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(TmColors.White.color4())
                setBaseColorTexture(texture)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** A soft blob: white texture alpha x the blob colour, never depth-tested. */
    fun blob(texture: TextureResource, color: Color): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setBaseColorTexture(texture)
                setOpacity(1f)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
                setDepthTest(false)
            }
        )

    private fun unlit(color: Color, twoSided: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            }
        )

    /** A see-through unlit material: the colour is opaque, the material's opacity carries the share. */
    private fun sheer(color: Color, opacity: Float, depthTest: Boolean = true): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
                if (!depthTest) setDepthTest(false)
            }
        )

    companion object {
        /** Head-locked layer sorts as a UI element with this bias so the panels draw over it (design N9). */
        fun overlaySort(): SortAsUIElementComponent = SortAsUIElementComponent(-1.0f)
    }
}
