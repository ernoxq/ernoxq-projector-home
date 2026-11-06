package io.github.jqssun.airplay.ui

import android.app.Activity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/*
 * Projector Home's AirPlay look, in the spirit of Apple TV.
 *
 * The window only opens for screen mirroring or video (music plays in the background, without any window).
 * It starts on black with the AirPlay sign in a glass circle and soft rings spreading out ("Подключение…").
 * When the picture arrives, the sign grows and fades and the picture comes up from behind it.
 * When the iPhone or Mac disconnects, the screen fades to black and the window closes back to the home screen.
 * Opened by hand with nothing connected, it simply says how to connect.
 */

private enum class Stage { IDLE, CONNECTING, LIVE, LEAVING }

@Composable
fun GrinShell(viewModel: MainViewModel, content: @Composable () -> Unit) {
    val connections by viewModel.connectionCount.collectAsState()
    val mirroring by viewModel.mirroringActive.collectAsState()
    val video by viewModel.videoPlaybackActive.collectAsState()
    val pending by viewModel.videoSessionPending.collectAsState()
    val activity = LocalContext.current as? Activity
    val live = mirroring || video || pending

    var wasLive by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(live) {
        if (live) {
            wasLive = true
            leaving = false
            // Let the first frames arrive behind the curtain, so the picture appears whole, not in pieces.
            delay(700)
            revealed = true
        } else {
            revealed = false
        }
    }
    LaunchedEffect(live, connections) {
        if (wasLive && !live && connections == 0) {
            leaving = true
            delay(650)
            activity?.finish()
        }
    }

    val stage = when {
        leaving -> Stage.LEAVING
        revealed -> Stage.LIVE
        connections > 0 || live -> Stage.CONNECTING
        else -> Stage.IDLE
    }
    val isLive = stage == Stage.LIVE
    val curtain by animateFloatAsState(
        if (isLive) 0f else 1f,
        tween(if (isLive) 700 else 450, easing = FastOutSlowInEasing), label = "curtain",
    )
    val signScale by animateFloatAsState(if (isLive) 1.4f else 1f, tween(700, easing = FastOutSlowInEasing), label = "sign")
    val contentScale by animateFloatAsState(if (isLive) 1f else 0.94f, tween(750, easing = FastOutSlowInEasing), label = "content")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().scale(contentScale)) { content() }
        if (curtain > 0.01f) Curtain(stage, signScale, Modifier.fillMaxSize().alpha(curtain))
    }
}

@Composable
private fun Curtain(stage: Stage, signScale: Float, modifier: Modifier) {
    val (title, sub) = when (stage) {
        Stage.IDLE -> "AirPlay" to "На iPhone или Mac откройте «Повтор экрана» и выберите «AirPlay»"
        Stage.CONNECTING -> "Подключение…" to "Сейчас появится экран вашего устройства"
        Stage.LIVE -> "AirPlay" to ""
        Stage.LEAVING -> "AirPlay" to "Отключено"
    }
    Box(
        modifier.background(
            Brush.radialGradient(listOf(Color(0xFF1E1E22), Color.Black), radius = 1400f),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(260.dp).scale(signScale), contentAlignment = Alignment.Center) {
                Rings(active = stage != Stage.LEAVING)
                Box(
                    Modifier
                        .size(124.dp)
                        .background(Color.White.copy(alpha = 0.10f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AirPlaySign(Modifier.size(58.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    sub,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 460.dp),
                )
            }
        }
    }
}

/** Two thin rings spreading out from the glass circle, like the AirPlay pulse on Apple TV. */
@Composable
private fun Rings(active: Boolean) {
    val t = rememberInfiniteTransition(label = "rings")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(Modifier.size(260.dp)) {
        if (!active) return@Canvas
        val base = 62.dp.toPx()
        val max = size.minDimension / 2
        for (k in 0..1) {
            val q = (p + k * 0.5f) % 1f
            val r = base + (max - base) * q
            drawCircle(
                color = Color.White.copy(alpha = 0.32f * (1f - q)),
                radius = r,
                center = Offset(size.width / 2, size.height / 2),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/** The AirPlay sign: a screen outline open at the bottom, with a solid triangle rising into it. */
@Composable
private fun AirPlaySign(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val screen = Path().apply {
            moveTo(7f * s, 17f * s)
            lineTo(4f * s, 17f * s)
            quadraticTo(2.5f * s, 17f * s, 2.5f * s, 15.5f * s)
            lineTo(2.5f * s, 5.5f * s)
            quadraticTo(2.5f * s, 4f * s, 4f * s, 4f * s)
            lineTo(20f * s, 4f * s)
            quadraticTo(21.5f * s, 4f * s, 21.5f * s, 5.5f * s)
            lineTo(21.5f * s, 15.5f * s)
            quadraticTo(21.5f * s, 17f * s, 20f * s, 17f * s)
            lineTo(17f * s, 17f * s)
        }
        drawPath(screen, Color.White, style = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val tri = Path().apply {
            moveTo(12f * s, 13.5f * s)
            lineTo(17.2f * s, 20.5f * s)
            lineTo(6.8f * s, 20.5f * s)
            close()
        }
        drawPath(tri, Color.White)
    }
}
