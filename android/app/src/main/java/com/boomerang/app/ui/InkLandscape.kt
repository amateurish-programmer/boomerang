package com.boomerang.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boomerang.app.R
import com.boomerang.app.domain.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

val InkEffectiveModeKey = SemanticsPropertyKey<String>("InkEffectiveMode")
var SemanticsPropertyReceiver.inkEffectiveMode by InkEffectiveModeKey
val InkMotionRunningKey = SemanticsPropertyKey<Boolean>("InkMotionRunning")
var SemanticsPropertyReceiver.inkMotionRunning by InkMotionRunningKey
val InkArtworkKey = SemanticsPropertyKey<String>("InkArtwork")
var SemanticsPropertyReceiver.inkArtwork by InkArtworkKey
internal val InkFeedbackActiveKey = SemanticsPropertyKey<Boolean>("InkFeedbackActive")

/** Optional frame evidence for isolated fixtures; production has no observer or frame semantics. */
internal val LocalInkFrameObserver = staticCompositionLocalOf<((Long) -> Unit)?> { null }
internal val LocalInkFeedbackObserver = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

/** The framework limit also covers direct fixtures which do not have a ShellViewModel. */
@Composable
internal fun frameworkAnimationScale(): Float =
    rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f

/** Standalone screens still obey Android settings, without UI touching storage or business data. */
@Composable
internal fun defaultSystemAnimationScale(): Float {
    val model: InkAppearanceViewModel = viewModel()
    val scale by model.systemAnimationScale.collectAsStateWithLifecycle()
    return scale
}

internal class InkEntranceState {
    var sceneAlpha by mutableFloatStateOf(1f)
    var brandAlpha by mutableFloatStateOf(1f)
    var recordsAlpha by mutableFloatStateOf(1f)
    fun finish() { sceneAlpha = 1f; brandAlpha = 1f; recordsAlpha = 1f }
}

/** Cancellable UI-frame clock. Only accepted frames update drawing/layer state, at most 30fps. */
private suspend fun inkFrames(frame: (Long) -> Boolean) {
    val clock = InkActiveClock()
    var lastDraw: Long? = null
    while (currentCoroutineContext().isActive) {
        val nanos = withFrameNanos { it }
        if (lastDraw == null || nanos - lastDraw >= 33_333_334L) {
            lastDraw = nanos
            if (!frame(clock.frame(nanos))) return
        }
    }
}

@Composable
internal fun InkLandscape(
    mode: InkMotionMode, eligible: Boolean, entranceShown: Boolean, onEntranceShown: () -> Unit,
    entrance: InkEntranceState, parallax: () -> Float, dateLabel: String, modifier: Modifier = Modifier,
) {
    val dark = LocalInkDarkTheme.current
    val frameObserver by rememberUpdatedState(LocalInkFrameObserver.current)
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val artwork = if (dark) R.drawable.ink_landscape_dark else R.drawable.ink_landscape_light
    // Exactly one theme bitmap is retained by this composed hero; never decode from a frame callback.
    val bitmap = remember(artwork, resources) { ImageBitmap.imageResource(resources, artwork) }
    val paper = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onSurface
    val mistColor = if (dark) Color(0xFFA9C9BC) else Color(0xFFF9F5EB)
    val dustPaint = remember(ink) { Paint().apply { color = ink.copy(alpha = .075f) } }
    val mistPaints = remember(mistColor) { List(2) { layer -> Paint().apply { color = mistColor.copy(alpha = .035f + layer * .012f) } } }
    val transform = remember { Matrix() }
    val bottomEdge = remember(paper) { Brush.verticalGradient(0f to paper, .06f to Color.Transparent, .74f to Color.Transparent, 1f to paper) }
    val sideEdges = remember(paper) { Brush.horizontalGradient(0f to paper, .05f to Color.Transparent, .95f to Color.Transparent, 1f to paper) }
    var started by remember { mutableStateOf(entranceShown) }
    var timeMs by remember { mutableLongStateOf(0L) }
    val latestEntranceShown by rememberUpdatedState(onEntranceShown)
    // Shapes are reusable, normalized and fixed in number; drawing does not allocate pools each frame.
    val dustShape = remember { Path().apply {
        moveTo(-.7f, -.3f); lineTo(.1f, -1f); lineTo(.8f, .1f); lineTo(.2f, .8f); lineTo(-.8f, .4f); close()
    } }
    val dust = remember { List(32) { i ->
        InkDust(.14f + ((i * 47) % 83) / 100f, .25f + ((i * 29) % 63) / 100f, .55f + (i % 4) * .24f, i * .71f)
    } }
    val mistShapes = remember { List(2) { layer -> Path().apply {
        moveTo(-.2f, .58f + layer * .13f)
        cubicTo(.17f, .43f + layer * .13f, .37f, .74f, .66f, .59f + layer * .1f)
        cubicTo(.88f, .47f + layer * .13f, 1.15f, .59f, 1.25f, .68f + layer * .13f)
        lineTo(1.25f, .78f + layer * .11f)
        cubicTo(.75f, .67f + layer * .11f, .31f, .92f, -.2f, .71f + layer * .11f); close()
    } } }
    LaunchedEffect(eligible, mode) {
        if (!eligible) { timeMs = 0L; entrance.finish(); return@LaunchedEffect }
        val entering = !started
        if (entering) { started = true; latestEntranceShown() }
        if (mode == InkMotionMode.OFF) { entrance.finish(); return@LaunchedEffect }
        var elapsed = 0L
        if (entering) { entrance.sceneAlpha = 0f; entrance.brandAlpha = 0f; entrance.recordsAlpha = 0f }
        try {
            inkFrames { dt ->
                frameObserver?.invoke(dt)
                elapsed += dt
                if (mode == InkMotionMode.FULL) timeMs += dt
                if (entering) {
                    entrance.sceneAlpha = (elapsed / if (mode == InkMotionMode.FULL) 800f else 160f).coerceAtMost(1f)
                    entrance.brandAlpha = (elapsed / if (mode == InkMotionMode.FULL) 500f else 160f).coerceAtMost(1f)
                    entrance.recordsAlpha = (elapsed / if (mode == InkMotionMode.FULL) 350f else 160f).coerceAtMost(1f)
                }
                mode == InkMotionMode.FULL || (entering && elapsed < 160L)
            }
        } finally { entrance.finish(); timeMs = 0L }
    }
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds().testTag("ink_landscape").semantics {
        inkEffectiveMode = mode.name
        inkMotionRunning = eligible && mode == InkMotionMode.FULL
        inkArtwork = if (dark) "dark" else "light"
    }) {
        val fontScale = LocalDensity.current.fontScale
        Box(Modifier.fillMaxWidth().heightIn(min = maxOf(maxWidth / 1.5f, (116f * fontScale).dp))) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, alignment = Alignment.BottomCenter,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = if (eligible && mode != InkMotionMode.OFF) entrance.sceneAlpha else 1f })
            Canvas(Modifier.matchParentSize()) {
                if (eligible && mode == InkMotionMode.FULL) {
                    val t = timeMs / 1000f
                    val shift = parallax().coerceIn(-6f, 6f).dp.toPx()
                    translate(top = shift) {
                        mistShapes.forEachIndexed { layer, path ->
                            val x = sin(t / (19f + layer * 7f)) * 4.dp.toPx()
                            transform.reset(); transform.scale(size.width, size.height)
                            // Reuse the two source paths; normalized geometry is scaled in the draw transform.
                            with(drawContext.canvas) {
                                save(); translate(x, 0f); concat(transform)
                                drawPath(path, mistPaints[layer])
                                restore()
                            }
                        }
                        dust.forEach { particle ->
                            val x = (particle.x + sin(t / 23f + particle.phase) * .012f) * size.width
                            val y = (particle.y + sin(t / 29f + particle.phase) * .018f) * size.height
                            val radius = particle.radius.dp.toPx()
                            transform.reset(); transform.translate(x, y); transform.scale(radius, radius)
                            with(drawContext.canvas) {
                                save(); concat(transform)
                                drawPath(dustShape, dustPaint)
                                restore()
                            }
                        }
                    }
                }
                // A natural paper edge, without cropping or reconstructing any of the original mountain scene.
                drawRect(bottomEdge)
                drawRect(sideEdges)
            }
            Column(Modifier.padding(start = 24.dp, top = 20.dp, bottom = 64.dp).fillMaxWidth(.56f)
                .graphicsLayer { alpha = if (eligible && mode != InkMotionMode.OFF) entrance.brandAlpha else 1f },
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("回旋镖", style = MaterialTheme.typography.displaySmall)
                if (dateLabel.isNotBlank()) Text(dateLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("记下原话，留待时间验证。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class InkDust(val x: Float, val y: Float, val radius: Float, val phase: Float)

/** Consumes a completed write once, independently of the subsequent animation's lifetime. */
@Composable
internal fun InkSaveFeedback(signal: Long?, consume: (Long) -> Unit, mode: InkMotionMode, eligible: Boolean, modifier: Modifier = Modifier) {
    var receipt by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableFloatStateOf(1f) }
    var feedbackActive by remember { mutableStateOf(false) }
    val feedbackObserver by rememberUpdatedState(LocalInkFeedbackObserver.current)
    val latestConsume by rememberUpdatedState(consume)
    val color = MaterialTheme.colorScheme.primary
    val paint = remember(color) { Paint().apply { this.color = color } }
    val transform = remember { Matrix() }
    val particleShape = remember { Path().apply {
        moveTo(-1f, -.25f); lineTo(.1f, -1f); lineTo(1f, .2f); lineTo(-.1f, 1f); close()
    } }
    LaunchedEffect(signal, eligible) {
        if (eligible && signal != null) {
            receipt = signal
            latestConsume(signal)
        }
    }
    // Capture the key: another effect may publish a receipt before this coroutine starts.
    val activeReceipt = receipt
    val owner = remember(activeReceipt, eligible, mode) { Any() }
    val latestOwner by rememberUpdatedState(owner)
    LaunchedEffect(owner) {
        if (latestOwner !== owner || receipt != activeReceipt) return@LaunchedEffect
        if (activeReceipt == null || !eligible || mode == InkMotionMode.OFF) {
            progress = 1f
            feedbackActive = false
            feedbackObserver?.invoke(false)
            if (receipt == activeReceipt) receipt = null
            return@LaunchedEffect
        }
        var elapsed = 0L
        val duration = if (mode == InkMotionMode.FULL) 450f else 180f
        progress = 0f
        feedbackActive = true
        feedbackObserver?.invoke(true)
        try { inkFrames { dt ->
            elapsed += dt
            progress = (elapsed / duration).coerceAtMost(1f)
            elapsed < duration
        } } finally {
            // A replacement/mode restart may already own the drawing state when cancellation ends.
            if (latestOwner === owner) {
                progress = 1f; feedbackActive = false
                feedbackObserver?.invoke(false)
            }
        }
        // Cancellation leaves a replacement receipt (or a mode restart) for the next effect.
        if (latestOwner === owner && receipt == activeReceipt) receipt = null
    }
    Canvas(modifier.testTag("ink_save_feedback").semantics {
        this[InkFeedbackActiveKey] = eligible && feedbackActive && mode != InkMotionMode.OFF
    }) {
        if (eligible && progress < 1f && mode != InkMotionMode.OFF) {
            val p = progress
            if (mode == InkMotionMode.REDUCED) {
                drawRoundRect(color.copy(alpha = .18f * (1f - p)), topLeft = Offset(24.dp.toPx(), 12.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size((size.width - 48.dp.toPx()).coerceAtLeast(0f), (size.height - 24.dp.toPx()).coerceAtLeast(0f)))
            } else repeat(8) { i ->
                val angle = (PI * (1.08 + i * .12)).toFloat()
                val distance = (8f + p * 32f).dp.toPx()
                val x = size.width / 2f + cos(angle) * distance
                val y = 12.dp.toPx() + sin(angle) * distance
                val radius = (1.4f * (1f - p)).dp.toPx()
                transform.reset(); transform.translate(x, y); transform.scale(radius, radius)
                paint.color = color.copy(alpha = .6f * (1f - p))
                with(drawContext.canvas) {
                    save(); concat(transform)
                    drawPath(particleShape, paint); restore()
                }
            }
        }
    }
}
