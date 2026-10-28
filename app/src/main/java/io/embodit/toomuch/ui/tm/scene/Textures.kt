package io.embodit.toomuch.ui.tm.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import io.embodit.toomuch.R
import io.embodit.toomuch.domain.model.SourceId
import io.embodit.toomuch.ui.theme.TmColors
import kotlin.math.exp

/**
 * Bitmaps for the textured faces, drawn at run time (128 px: small enough for `TextureResource.create`
 * on the main thread). The quad's uv (0, 0) is its top left, so a bitmap drawn upright reads upright;
 * the debug command `uvtest` swaps every card face for an asymmetric "F↑" to check that (D10).
 */
internal object Textures {
    const val SIZE = 128

    fun iconRes(id: SourceId): Int =
        when (id) {
            SourceId.FRIDGE -> R.drawable.ic_fridge
            SourceId.CLOCK -> R.drawable.ic_clock
            SourceId.BAG -> R.drawable.ic_bag
            SourceId.TAP -> R.drawable.ic_drop
            SourceId.LAMP -> R.drawable.ic_lamp
            SourceId.PHONE -> R.drawable.ic_phone
        }

    /** Card face: the card colour with the source's glyph in ink, centred at 70 %. */
    fun cardFace(context: Context, id: SourceId): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(TmColors.CardFace.toArgb())
        val d = ContextCompat.getDrawable(context, iconRes(id))?.mutate()
        if (d != null) {
            val m = (SIZE * 0.15f).toInt()
            d.setBounds(m, m, SIZE - m, SIZE - m)
            d.colorFilter = PorterDuffColorFilter(TmColors.Ink.toArgb(), PorterDuff.Mode.SRC_IN)
            d.draw(c)
        }
        return bmp
    }

    /** The orientation test: a big "F" and an up arrow, both asymmetric left / right and up / down. */
    fun orientationTest(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(AColor.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.BLACK
            textSize = SIZE * 0.62f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText("F", SIZE * 0.10f, SIZE * 0.72f, p)
        p.textSize = SIZE * 0.5f
        c.drawText("↑", SIZE * 0.58f, SIZE * 0.62f, p)
        p.color = AColor.RED
        c.drawRect(0f, 0f, SIZE * 0.25f, SIZE * 0.08f, p)
        return bmp
    }

    /** A soft round blob: white, alpha a Gaussian falling to ~0 at the edge. */
    fun blob(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val px = IntArray(SIZE * SIZE)
        val r = SIZE / 2f
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val dx = (x + 0.5f - r) / r
            val dy = (y + 0.5f - r) / r
            val d2 = dx * dx + dy * dy
            val a = if (d2 >= 1f) 0f else exp(-d2 * 3.2f) * (1f - d2)
            px[y * SIZE + x] = ((a * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        }
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        return bmp
    }
}
