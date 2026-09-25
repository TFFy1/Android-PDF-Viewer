package io.github.tffy1.pdfviewer.ui.viewer.document

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shared between the tap and the pan/zoom detectors for the gesture in progress. */
internal class GestureFlags {
    /** More than one pointer went down (a pinch): a long press must not start text selection. */
    var multiTouch = false

    /** A long press fired: the rest of the gesture belongs to the long-press consumer, don't pan. */
    var longPressed = false
}

/** Speed (per second) above which releasing a page drag turns the page. */
private val PagerFlingThreshold = 400.dp

/** Distance scrolled per mouse-wheel notch. */
private val WheelStep = 64.dp

/**
 * Gesture handling for the whole document, installed on the parent of all pages (and so of all
 * page overlays). How pointer events flow:
 *
 * - Page overlays are descendants, so they see every event in the Main pass BEFORE these
 *   detectors. Whatever an overlay consumes is ignored here: a consumed position change cancels
 *   the pan/zoom gesture and a consumed down or up cancels taps. Events an overlay leaves
 *   unconsumed (e.g. a selection overlay that only handles drags that start on its handles)
 *   scroll, zoom and tap the document as usual.
 * - The pan/zoom detector comes after the tap detector in the modifier chain, so it handles each
 *   event first and consumes position changes once past touch slop; the tap detector then sees
 *   the consumption and cancels the tap/long press. Double tap is detected with
 *   detectTapGestures semantics, so a double tap never also produces a single tap.
 * - One detector owns pan and zoom together, so adding or lifting a finger never makes the
 *   content jump (pan and zoom only use pointers that were down in both events).
 */
internal fun Modifier.documentGestures(
    state: DocumentViewState,
    scope: CoroutineScope,
    decay: DecayAnimationSpec<Float>,
    flags: GestureFlags,
    onTap: State<(PageTap?) -> Unit>,
    onLongPress: State<(PageTap) -> Unit>,
): Modifier = this
    .pointerInput(state, flags) {
        detectTapGestures(
            onDoubleTap = { position -> scope.launch { state.toggleZoom(position) } },
            onLongPress = { position ->
                if (!flags.multiTouch) {
                    state.hitTest(position)?.let { tap ->
                        flags.longPressed = true
                        onLongPress.value(tap)
                    }
                }
            },
            onTap = { position -> onTap.value(state.hitTest(position)) },
        )
    }
    .pointerInput(state, flags) { detectPanZoom(state, scope, decay, flags) }
    .pointerInput(state) { detectMouseWheel(state, scope) }

private suspend fun PointerInputScope.detectPanZoom(
    state: DocumentViewState,
    scope: CoroutineScope,
    decay: DecayAnimationSpec<Float>,
    flags: GestureFlags,
) {
    val flingThreshold = PagerFlingThreshold.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        flags.multiTouch = false
        flags.longPressed = false
        // Touching the document stops flings and programmatic scrolls, like any scroll container.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { state.stopAnimation() }

        val touchSlop = viewConfiguration.touchSlop
        val maxVelocity = viewConfiguration.maximumFlingVelocity
        val velocityTracker = VelocityTracker()
        var trackedPosition = down.position
        velocityTracker.addPosition(down.uptimeMillis, trackedPosition)
        var pressedCount = 1
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        var pastSlop = false
        var interrupted = false
        var focus = down.position

        while (true) {
            val event = awaitPointerEvent()
            if (flags.longPressed || event.changes.any { it.isConsumed }) {
                interrupted = true
                break
            }
            val pressed = event.changes.count { it.pressed }
            if (pressed > 1) flags.multiTouch = true
            if (pressed != pressedCount && pressed > 0) {
                // Only the last phase with a stable set of fingers decides the fling velocity,
                // so lifting one finger of a pinch doesn't fling.
                pressedCount = pressed
                velocityTracker.resetTracking()
            }

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                accumulatedZoom *= zoomChange
                accumulatedPan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                pastSlop = abs(1f - accumulatedZoom) * centroidSize > touchSlop ||
                    accumulatedPan.getDistance() > touchSlop
            }
            if (pastSlop) {
                val centroid = event.calculateCentroid(useCurrent = false)
                if (centroid.isSpecified) focus = centroid
                if (zoomChange != 1f || panChange != Offset.Zero) state.transform(focus, panChange, zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }

            trackedPosition += panChange
            velocityTracker.addPosition(event.changes.first().uptimeMillis, trackedPosition)
            if (event.changes.none { it.pressed }) break
        }

        val velocity = if (interrupted || !pastSlop) {
            Velocity.Zero
        } else {
            val raw = velocityTracker.calculateVelocity()
            Velocity(raw.x.coerceIn(-maxVelocity, maxVelocity), raw.y.coerceIn(-maxVelocity, maxVelocity))
        }
        // Always settle: snaps a displaced pager, springs back from < 1x, or flings.
        state.settle(velocity, focus, flingThreshold, decay, scope)
    }
}

/** Mouse wheel / touchpad scroll (Chromebooks, DeX, tablets with keyboards). */
private suspend fun PointerInputScope.detectMouseWheel(state: DocumentViewState, scope: CoroutineScope) {
    val step = WheelStep.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll || !state.gesturesEnabled) continue
            var delta = Offset.Zero
            event.changes.forEach { if (!it.isConsumed) delta += it.scrollDelta }
            if (delta != Offset.Zero && state.onMouseWheel(delta * step, scope)) {
                event.changes.forEach { it.consume() }
            }
        }
    }
}
