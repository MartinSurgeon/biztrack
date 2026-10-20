package com.example.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppTheme
import android.graphics.Paint as NativePaint
import android.graphics.Path as NativePath

/**
 * Claymorphism tuning per theme.
 *
 * Tuned against hardware-rendered output (what phones actually draw): highlights are
 * kept tight so they read as a soft rim rather than a foggy band, and dark mode leans
 * on a light top rim + inner shade because drop shadows are invisible on near-black.
 */
data class ClayStyle(
    val drop: Color,
    val dropBlur: Dp,
    val dropOffset: Dp,
    val contact: Color,
    val highlight: Color,
    val highlightBlur: Dp,
    val highlightOffset: Dp,
    val shade: Color,
    val shadeBlur: Dp,
    val shadeOffset: Dp,
    /** How much lighter the top / darker the bottom of the fill is (0..1). */
    val topLift: Float,
    val bottomSink: Float,
    /** Light rim along the top edge; transparent to disable. */
    val rim: Color
)

private val LightClay = ClayStyle(
    drop = Color(0x2E2A3270), dropBlur = 20.dp, dropOffset = 10.dp,
    contact = Color(0x12000000),
    highlight = Color(0x8CFFFFFF), highlightBlur = 5.dp, highlightOffset = 2.5.dp,
    shade = Color(0x1A1E2050), shadeBlur = 12.dp, shadeOffset = 6.dp,
    topLift = 0.10f, bottomSink = 0.04f,
    rim = Color.Transparent
)

private val DarkClay = ClayStyle(
    drop = Color(0xCC000000), dropBlur = 18.dp, dropOffset = 10.dp,
    contact = Color(0x80000000),
    highlight = Color(0x2EFFFFFF), highlightBlur = 5.dp, highlightOffset = 2.5.dp,
    shade = Color(0x99000000), shadeBlur = 12.dp, shadeOffset = 6.dp,
    topLift = 0.06f, bottomSink = 0.18f,
    rim = Color(0x2BFFFFFF)
)

val clayStyle: ClayStyle
    @Composable @ReadOnlyComposable get() = if (AppTheme.status.isDark) DarkClay else LightClay

/**
 * Paints a "clay" surface: a softly shaded [color] fill (lighter at the top, deeper at
 * the bottom), a drop shadow underneath, a tight light rim on the top-left inner edge and
 * a darker inner shade on the bottom-right, so the card looks slightly inflated.
 *
 * Place it before any `clip` in the modifier chain so the drop shadow isn't clipped.
 */
@Composable
fun Modifier.clay(shape: Shape, color: Color, style: ClayStyle = clayStyle): Modifier {
    val top = lerp(color, Color.White, style.topLift)
    val bottom = lerp(color, Color.Black, style.bottomSink)
    val supportsShadowLayers = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    return this
        // Shadow layers on shapes need API 28; older phones get a regular elevation shadow.
        .then(if (supportsShadowLayers) Modifier else Modifier.shadow(6.dp, shape, ambientColor = style.drop, spotColor = style.drop))
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val nativePath = path.asAndroidPath()
            val fillBrush = Brush.verticalGradient(listOf(top, color, bottom), startY = 0f, endY = size.height)
            val rimBrush = Brush.verticalGradient(listOf(style.rim, Color.Transparent), startY = 0f, endY = size.height * 0.45f)

            // Everything outside the card. Its shadow, offset inward and clipped to the
            // card, becomes an inner shadow.
            val spread = (style.shadeBlur + style.shadeOffset).toPx() * 3f
            val outside = NativePath().apply {
                fillType = NativePath.FillType.EVEN_ODD
                addRect(-spread, -spread, size.width + spread, size.height + spread, NativePath.Direction.CW)
                addPath(nativePath)
            }
            val solid = color.toArgb()
            val drop = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                this.color = solid
                setShadowLayer(style.dropBlur.toPx(), 0f, style.dropOffset.toPx(), style.drop.toArgb())
            }
            val contact = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                this.color = solid
                setShadowLayer(2.dp.toPx(), 0f, 1.dp.toPx(), style.contact.toArgb())
            }
            val highlight = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.BLACK // clipped away; only its shadow shows
                val o = style.highlightOffset.toPx()
                setShadowLayer(style.highlightBlur.toPx(), o, o, style.highlight.toArgb())
            }
            val shade = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.BLACK
                val o = style.shadeOffset.toPx()
                setShadowLayer(style.shadeBlur.toPx(), -o, -o, style.shade.toArgb())
            }

            onDrawBehind {
                if (supportsShadowLayers) {
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawPath(nativePath, drop)
                        c.nativeCanvas.drawPath(nativePath, contact)
                    }
                }
                drawPath(path, fillBrush)
                if (supportsShadowLayers) {
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.save()
                        nc.clipPath(nativePath)
                        nc.drawPath(outside, highlight)
                        nc.drawPath(outside, shade)
                        nc.restore()
                    }
                }
                if (style.rim.alpha > 0f) {
                    drawPath(path, rimBrush, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
}

