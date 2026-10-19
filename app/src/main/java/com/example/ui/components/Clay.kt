package com.example.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppTheme
import android.graphics.Paint as NativePaint
import android.graphics.Path as NativePath

/**
 * Claymorphism tuning per theme. Light mode uses a soft cool-tinted lift; dark mode
 * relies mostly on the inner highlight, since drop shadows barely show on black.
 */
data class ClayStyle(
    val drop: Color,
    val dropBlur: Dp,
    val dropOffset: Dp,
    val contact: Color,
    val highlight: Color,
    val shade: Color,
    val innerBlur: Dp,
    val innerOffset: Dp
)

private val LightClay = ClayStyle(
    drop = Color(0x2E2A3270), dropBlur = 22.dp, dropOffset = 10.dp,
    contact = Color(0x0F000000),
    highlight = Color(0xCCFFFFFF), shade = Color(0x14000000),
    innerBlur = 10.dp, innerOffset = 5.dp
)

private val DarkClay = ClayStyle(
    drop = Color(0x99000000), dropBlur = 22.dp, dropOffset = 10.dp,
    contact = Color(0x66000000),
    highlight = Color(0x17FFFFFF), shade = Color(0x73000000),
    innerBlur = 10.dp, innerOffset = 5.dp
)

val clayStyle: ClayStyle
    @Composable @ReadOnlyComposable get() = if (AppTheme.status.isDark) DarkClay else LightClay

/**
 * Paints a "clay" surface: [color] fill, a soft drop shadow underneath, a light inner
 * highlight on the top-left edges and a darker inner shade on the bottom-right, so the
 * card looks slightly inflated. Content is drawn on top as usual.
 *
 * Place it before any `clip` in the modifier chain so the drop shadow isn't clipped.
 */
@Composable
fun Modifier.clay(shape: Shape, color: Color, style: ClayStyle = clayStyle): Modifier {
    // Hardware-accelerated shadow layers on paths need API 28; older devices get a
    // regular elevation shadow and a flat fill instead.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return this
            .shadow(6.dp, shape, ambientColor = style.drop, spotColor = style.drop)
            .drawWithCache {
                val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                onDrawBehind { drawPath(path, color) }
            }
    }
    return this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }.asAndroidPath()

        // Everything outside the card. Its shadow, offset inward and clipped to the
        // card, becomes an inner shadow.
        val spread = (style.innerBlur + style.innerOffset).toPx() * 3f
        val outside = NativePath().apply {
            fillType = NativePath.FillType.EVEN_ODD
            addRect(-spread, -spread, size.width + spread, size.height + spread, NativePath.Direction.CW)
            addPath(path)
        }

        val fill = color.toArgb()
        val drop = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            this.color = fill
            setShadowLayer(style.dropBlur.toPx(), 0f, style.dropOffset.toPx(), style.drop.toArgb())
        }
        val contact = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            this.color = fill
            setShadowLayer(3.dp.toPx(), 0f, 1.5.dp.toPx(), style.contact.toArgb())
        }
        val off = style.innerOffset.toPx()
        val blur = style.innerBlur.toPx()
        val highlight = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            setShadowLayer(blur, off, off, style.highlight.toArgb())
        }
        val shade = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            setShadowLayer(blur * 1.2f, -off, -off, style.shade.toArgb())
        }

        onDrawBehind {
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.drawPath(path, drop)
                nc.drawPath(path, contact)
                nc.save()
                nc.clipPath(path)
                nc.drawPath(outside, highlight)
                nc.drawPath(outside, shade)
                nc.restore()
            }
        }
    }
}
