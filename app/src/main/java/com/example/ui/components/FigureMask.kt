package com.example.ui.components

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Whether money on the figure cards is visible. Hidden until the owner taps the eye, and
 * remembered on this phone, so figures stay private when someone looks at the screen.
 */
object FigurePrivacy {
    private const val PREFS = "ui_prefs"
    private const val KEY_SHOWN = "figures_shown"
    private var loaded = false

    var shown by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (loaded) return
        shown = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)
        loaded = true
    }

    fun toggle(context: Context) {
        shown = !shown
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, shown).apply()
    }

    /** For tests: set visibility directly, without reading the saved choice. */
    internal fun resetForTests(shownValue: Boolean = false) {
        loaded = true
        shown = shownValue
    }
}

/** Current visibility of card figures (reads the saved choice the first time). */
@Composable
fun figuresShown(): Boolean {
    FigurePrivacy.init(LocalContext.current)
    return FigurePrivacy.shown
}

/** Text shown in place of an amount while figures are hidden, e.g. "GH₵ •••••". */
fun maskedAmount(currency: String): String = "${currency.trim()} •••••".trim()

/** Read "Amount hidden" instead of the dots when a screen reader reaches a masked figure. */
fun Modifier.maskedSemantics(masked: Boolean): Modifier =
    if (masked) semantics { contentDescription = "Amount hidden" } else this

/** Eye button that shows or hides every card figure at once. */
@Composable
fun FiguresToggleButton(tint: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shown = figuresShown()
    IconButton(onClick = { FigurePrivacy.toggle(context) }, modifier = modifier) {
        Icon(
            imageVector = if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (shown) "Hide amounts" else "Show amounts",
            tint = tint
        )
    }
}

/**
 * Soft contour waves in the top-right corner of a card, fading out towards the text.
 * Same shape as the web dashboard's card pattern. [color] should already be faint.
 */
fun Modifier.wavePattern(color: Color): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val lines = 8
    val gap = h / 6.5f
    val paths = List(lines) { i ->
        val y = -h * 0.08f + i * gap
        val amp = h * (0.07f + i * 0.008f)
        Path().apply {
            moveTo(w * 0.30f, y + amp)
            cubicTo(w * 0.46f, y - amp, w * 0.60f, y + amp * 1.6f, w * 0.75f, y)
            cubicTo(w * 0.86f, y - amp * 0.7f, w * 0.95f, y + amp * 0.3f, w * 1.04f, y - amp * 0.2f)
        }
    }
    // Strongest at the top-right corner, gone before the left-hand text and the bottom.
    val fade = Brush.radialGradient(
        0f to color, 0.55f to color.copy(alpha = color.alpha * 0.45f), 1f to Color.Transparent,
        center = Offset(w, 0f), radius = maxOf(w * 0.78f, h * 1.1f)
    )
    val stroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
    onDrawBehind { paths.forEach { drawPath(it, fade, style = stroke) } }
}
