package com.nxuslab.dreymanager.feature.melhor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nxuslab.dreymanager.core.theme.NxusColors
import kotlin.math.min
import kotlin.math.roundToInt

private val Glass = Color(0xFFEAF2FA)
private val GlassEdge = Color(0xFFBCD6EC)
private val WaterTop = Color(0xFF8CB9DF)
private val WaterBottom = Color(0xFF5C93C2)
private const val BODY_TOP = 0.13f
private const val STEP_ML = 50

/**
 * Garrafa que esvazia com o dedo: arraste para baixo (ou toque) até o nível que sobrou.
 * Só desce; para voltar, use Desfazer. [onSetLeft] recebe o novo "quanto ainda está dentro".
 */
@Composable
fun Bottle(leftMl: Int, bottleMl: Int, onSetLeft: (Int) -> Unit, modifier: Modifier = Modifier) {
    var dragLeft by remember { mutableStateOf<Int?>(null) }
    val shown = dragLeft ?: leftMl
    val fill by animateFloatAsState(shown.toFloat() / bottleMl, spring(dampingRatio = 0.8f, stiffness = 300f), label = "agua")

    fun levelAt(y: Float, height: Float): Int {
        val top = height * BODY_TOP
        val frac = 1f - ((y - top) / (height - top)).coerceIn(0f, 1f)
        val ml = frac * bottleMl
        // Perto do fundo conta como garrafa terminada: o dedo raramente chega no último pixel.
        if (ml < STEP_ML * 1.5f) return 0
        return ((ml / STEP_ML).roundToInt() * STEP_ML).coerceIn(0, bottleMl)
    }

    Canvas(
        modifier
            .size(132.dp, 300.dp)
            .semantics {
                contentDescription = "Garrafa: ainda tem $leftMl ml de $bottleMl ml"
                customActions = listOf(
                    CustomAccessibilityAction("Bebi 100 ml") { onSetLeft((leftMl - 100).coerceAtLeast(0)); true },
                    CustomAccessibilityAction("Terminei a garrafa") { onSetLeft(0); true },
                )
            }
            .pointerInput(bottleMl, leftMl) {
                detectTapGestures { o ->
                    val v = levelAt(o.y, size.height.toFloat())
                    if (v < leftMl) onSetLeft(v)
                }
            }
            .pointerInput(bottleMl, leftMl) {
                detectVerticalDragGestures(
                    onDragStart = { o -> dragLeft = min(levelAt(o.y, size.height.toFloat()), leftMl) },
                    onDragEnd = {
                        dragLeft?.let { if (it < leftMl) onSetLeft(it) }
                        dragLeft = null
                    },
                    onDragCancel = { dragLeft = null },
                ) { change, _ ->
                    change.consume()
                    dragLeft = min(levelAt(change.position.y, size.height.toFloat()), leftMl)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val edge = 3.dp.toPx()
        val bodyTop = h * BODY_TOP
        // Tampa e gargalo.
        drawRoundRect(NxusColors.SkyDark, Offset(w / 2 - 22.dp.toPx(), 0f), Size(44.dp.toPx(), h * 0.06f), CornerRadius(8.dp.toPx()))
        drawRect(Glass, Offset(w / 2 - 16.dp.toPx(), h * 0.055f), Size(32.dp.toPx(), bodyTop - h * 0.05f))
        drawRect(GlassEdge, Offset(w / 2 - 16.dp.toPx(), h * 0.055f), Size(32.dp.toPx(), bodyTop - h * 0.05f), style = Stroke(edge))
        // Corpo.
        val body = RoundRect(edge / 2, bodyTop, w - edge / 2, h - edge / 2, CornerRadius(36.dp.toPx()))
        val bodyPath = Path().apply { addRoundRect(body) }
        drawPath(bodyPath, Glass)
        clipPath(bodyPath) {
            val waterH = (h - bodyTop) * fill
            val top = h - waterH
            drawRect(Brush.verticalGradient(listOf(WaterTop, WaterBottom), startY = top, endY = h), Offset(0f, top), Size(w, waterH))
            if (fill > 0.01f) drawOval(Color(0xFFA9CBE8), Offset(-w * 0.1f, top - 5.dp.toPx()), Size(w * 1.2f, 10.dp.toPx()))
            // Marcas de nível.
            for (i in 1..5) {
                val y = bodyTop + (h - bodyTop) * i / 6f
                val len = if (i % 2 == 1) 14.dp.toPx() else 9.dp.toPx()
                drawLine(NxusColors.SkyDark.copy(alpha = 0.3f), Offset(w - 10.dp.toPx() - len, y), Offset(w - 10.dp.toPx(), y), 2.dp.toPx())
            }
        }
        drawPath(bodyPath, GlassEdge, style = Stroke(edge))
    }
}
