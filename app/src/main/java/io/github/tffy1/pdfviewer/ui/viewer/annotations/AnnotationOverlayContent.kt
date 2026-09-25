package io.github.tffy1.pdfviewer.ui.viewer.annotations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.annotations.AnnotationContent
import io.github.tffy1.pdfviewer.annotations.AnnotationGeometry
import io.github.tffy1.pdfviewer.annotations.AnnotationType
import io.github.tffy1.pdfviewer.annotations.PageAnnotation
import io.github.tffy1.pdfviewer.core.model.PagePoint
import io.github.tffy1.pdfviewer.ui.viewer.document.PageLayoutInfo
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/** On-screen size of a note icon (it does not scale with zoom). */
private val NoteIconSize = 24.dp

/** Touch target around a note icon. */
private val NoteTouchTarget = 40.dp

/** How far from the finger the eraser reaches. */
private val EraserRadius = 12.dp

/** Stroke simplification tolerance, in screen pixels (converted to points with the zoom). */
private const val SIMPLIFY_TOLERANCE_PX = 1.25f

/** Highlight fill alpha. Multiply keeps text readable; alpha keeps it safe if blending is unavailable. */
private const val HIGHLIGHT_ALPHA = 0.5f

/** Longest time a just-finished stroke is kept on screen while its annotation is being added. */
private const val COMMIT_PREVIEW_TIMEOUT_MS = 1_500L

@Composable
internal fun AnnotationOverlayContent(controller: AnnotationController, page: PageLayoutInfo) {
    val byPage by controller.annotations.collectAsStateWithLifecycle()
    val annotations = byPage[page.pageIndex].orEmpty()
    val tool by controller.activeTool.collectAsStateWithLifecycle()
    val noteEditor by controller.noteEditor.collectAsStateWithLifecycle()

    // Absolute alignment: page coordinates don't mirror in RTL layouts.
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        AnnotationCanvas(
            annotations = annotations,
            pageScale = page.scale,
            modifier = Modifier.matchParentSize().clipToBounds(),
        )
        when (tool) {
            AnnotationTool.INK -> InkInputLayer(controller, page, annotations, Modifier.matchParentSize())
            AnnotationTool.ERASER -> EraserInputLayer(controller, page, Modifier.matchParentSize())
            AnnotationTool.NOTE -> NotePlacementLayer(controller, page, Modifier.matchParentSize())
            null -> Unit
        }
        // Note icons sit above the input layer so taps on them open the note in any mode,
        // except with the eraser, where tapping a note erases it.
        for (annotation in annotations) {
            val content = annotation.content as? AnnotationContent.Note ?: continue
            key(annotation.id) {
                NoteIcon(
                    annotation = annotation,
                    anchor = content.anchor,
                    pageScale = page.scale,
                    onClick = if (tool == AnnotationTool.ERASER) null else ({ controller.openNote(annotation.id) }),
                )
            }
        }
    }

    val editor = noteEditor
    if (editor != null && editor.pageIndex == page.pageIndex) {
        NoteEditorDialog(
            state = editor,
            onSave = controller::saveNote,
            onDelete = controller::deleteOpenNote,
            onDismiss = controller::dismissNoteEditor,
        )
    }
}

// ---- Drawing ---------------------------------------------------------------------------

@Composable
private fun AnnotationCanvas(annotations: List<PageAnnotation>, pageScale: Float, modifier: Modifier) {
    // Paths are built in page space once per list change; the canvas scales them.
    val inkPaths = remember(annotations) {
        annotations.mapNotNull { annotation ->
            val ink = annotation.content as? AnnotationContent.Ink ?: return@mapNotNull null
            annotation.id to ink.strokes.map { smoothPath(it.points) }
        }.toMap()
    }
    Canvas(modifier) {
        withTransform({ scale(pageScale, pageScale, pivot = Offset.Zero) }) {
            for (annotation in annotations) drawAnnotation(annotation, inkPaths[annotation.id])
        }
    }
}

/** Draws one annotation; the current transform maps page points to pixels. */
private fun DrawScope.drawAnnotation(annotation: PageAnnotation, inkPaths: List<Path>?) {
    val color = Color(annotation.color)
    when (val content = annotation.content) {
        is AnnotationContent.Markup -> for (rect in content.rects) {
            when (annotation.type) {
                AnnotationType.HIGHLIGHT -> drawRect(
                    color = color.copy(alpha = HIGHLIGHT_ALPHA),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    blendMode = BlendMode.Multiply,
                )
                AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT -> {
                    val y = if (annotation.type == AnnotationType.UNDERLINE) {
                        AnnotationGeometry.underlineY(rect)
                    } else {
                        AnnotationGeometry.strikeoutY(rect)
                    }
                    drawLine(
                        color = color,
                        start = Offset(rect.left, y),
                        end = Offset(rect.right, y),
                        strokeWidth = AnnotationGeometry.markupLineWidth(rect),
                    )
                }
                AnnotationType.INK, AnnotationType.NOTE -> Unit
            }
        }
        is AnnotationContent.Ink -> content.strokes.forEachIndexed { i, stroke ->
            val path = inkPaths?.getOrNull(i) ?: smoothPath(stroke.points)
            drawPath(path, color, style = inkStroke(stroke.width))
        }
        is AnnotationContent.Note -> Unit // drawn as composables (clickable icons)
    }
}

private fun inkStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/**
 * Smoothed stroke path: quadratic curves through segment midpoints using each inner point as the
 * control point (same curve as [AnnotationGeometry.smoothPolyline], which the export uses).
 */
internal fun smoothPath(points: List<PagePoint>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    if (points.size == 1) {
        lineTo(points[0].x, points[0].y)
        return@apply
    }
    for (i in 1 until points.lastIndex) {
        val mid = AnnotationGeometry.midpoint(points[i], points[i + 1])
        quadraticTo(points[i].x, points[i].y, mid.x, mid.y)
    }
    lineTo(points.last().x, points.last().y)
}

// ---- Input layers ----------------------------------------------------------------------

/** A finished stroke kept on screen until its annotation shows up (avoids a flicker). */
private class CommittedStroke(val points: List<PagePoint>, val color: Int, val width: Float, val knownIds: Set<Long>)

@Composable
private fun InkInputLayer(
    controller: AnnotationController,
    page: PageLayoutInfo,
    annotations: List<PageAnnotation>,
    modifier: Modifier,
) {
    val currentPage by rememberUpdatedState(page)
    val currentAnnotations by rememberUpdatedState(annotations)
    val colors by controller.colors.collectAsStateWithLifecycle()
    val strokeWidth by controller.strokeWidth.collectAsStateWithLifecycle()
    val inkColor = colors[AnnotationType.INK] ?: controller.colorFor(AnnotationType.INK)
    val live = remember { mutableStateListOf<PagePoint>() }
    var committed by remember { mutableStateOf<CommittedStroke?>(null) }

    val preview = committed
    LaunchedEffect(annotations, preview) {
        if (preview == null) return@LaunchedEffect
        if (annotations.any { it.type == AnnotationType.INK && it.id !in preview.knownIds }) {
            committed = null
        } else {
            delay(COMMIT_PREVIEW_TIMEOUT_MS)
            committed = null
        }
    }

    Canvas(
        modifier.pointerInput(controller) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val pageAtStart = currentPage
                live.clear()
                live.add(pageAtStart.toPagePoint(down.position.x, down.position.y))
                var completed = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        // A second finger cancels the stroke (and whatever follows is ignored).
                        if (event.changes.count { it.pressed } > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            completed = true
                            break
                        }
                        if (change.positionChanged()) {
                            live.add(pageAtStart.toPagePoint(change.position.x, change.position.y))
                            change.consume()
                        }
                    }
                    if (completed && live.size >= 2) {
                        val points = live.toList()
                        val tempId = controller.addInkStroke(
                            pageIndex = pageAtStart.pageIndex,
                            points = points,
                            pageSize = pageAtStart.pageSize,
                            tolerance = SIMPLIFY_TOLERANCE_PX / pageAtStart.scale.coerceAtLeast(0.01f),
                        )
                        if (tempId != null) {
                            committed = CommittedStroke(
                                points = points,
                                color = controller.colorFor(AnnotationType.INK),
                                width = controller.strokeWidth.value.points,
                                knownIds = currentAnnotations.mapTo(HashSet()) { it.id },
                            )
                        }
                    }
                } finally {
                    live.clear()
                }
            }
        },
    ) {
        val pageScale = page.scale
        withTransform({ scale(pageScale, pageScale, pivot = Offset.Zero) }) {
            committed?.let { drawPath(smoothPath(it.points), Color(it.color), style = inkStroke(it.width)) }
            if (live.isNotEmpty()) {
                drawPath(smoothPath(live), Color(inkColor), style = inkStroke(strokeWidth.points))
            }
        }
    }
}

@Composable
private fun EraserInputLayer(controller: AnnotationController, page: PageLayoutInfo, modifier: Modifier) {
    val currentPage by rememberUpdatedState(page)
    var eraserPosition by remember { mutableStateOf<Offset?>(null) }
    val indicatorColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier.pointerInput(controller) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val layout = currentPage
                val radiusPx = EraserRadius.toPx()
                eraseAt(controller, layout, down.position, radiusPx)
                eraserPosition = down.position
                var last = down.position
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            // Sample the segment so fast drags don't skip over thin strokes.
                            val target = change.position
                            val distance = (target - last).getDistance()
                            val steps = ceil(distance / (radiusPx / 2f)).toInt().coerceIn(1, 64)
                            for (s in 1..steps) {
                                eraseAt(controller, layout, last + (target - last) * (s.toFloat() / steps), radiusPx)
                            }
                            last = target
                            eraserPosition = target
                            change.consume()
                        }
                    }
                } finally {
                    eraserPosition = null
                }
            }
        },
    ) {
        eraserPosition?.let { position ->
            val radius = EraserRadius.toPx()
            drawCircle(indicatorColor.copy(alpha = 0.18f), radius, position)
            drawCircle(indicatorColor, radius, position, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

private fun PointerInputScope.eraseAt(
    controller: AnnotationController,
    page: PageLayoutInfo,
    position: Offset,
    radiusPx: Float,
) {
    val scale = page.scale.coerceAtLeast(0.01f)
    controller.eraseAt(
        pageIndex = page.pageIndex,
        point = page.toPagePoint(position.x, position.y),
        tolerance = radiusPx / scale,
        noteRadius = NoteIconSize.toPx() / 2f / scale,
    )
}

@Composable
private fun NotePlacementLayer(controller: AnnotationController, page: PageLayoutInfo, modifier: Modifier) {
    val currentPage by rememberUpdatedState(page)
    Box(
        modifier.pointerInput(controller) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                up.consume()
                if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                val layout = currentPage
                val anchor = AnnotationGeometry.clampToPage(
                    layout.toPagePoint(up.position.x, up.position.y),
                    layout.pageSize.width,
                    layout.pageSize.height,
                )
                controller.startNewNote(layout.pageIndex, anchor)
            }
        },
    )
}

// ---- Note icons ------------------------------------------------------------------------

@Composable
private fun NoteIcon(
    annotation: PageAnnotation,
    anchor: PagePoint,
    pageScale: Float,
    onClick: (() -> Unit)?,
) {
    val targetPx = with(LocalDensity.current) { NoteTouchTarget.roundToPx() }
    val text = annotation.note?.trim().orEmpty()
    val description = if (text.isEmpty()) {
        stringResource(R.string.annotations_note_icon_empty)
    } else {
        stringResource(R.string.annotations_note_icon_description, text.take(80))
    }
    val openLabel = stringResource(R.string.annotations_note_open)
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .absoluteOffset {
                IntOffset(
                    (anchor.x * pageScale).roundToInt() - targetPx / 2,
                    (anchor.y * pageScale).roundToInt() - targetPx / 2,
                )
            }
            .size(NoteTouchTarget)
            .then(clickModifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(NoteIconSize),
            shape = RoundedCornerShape(6.dp),
            color = Color(annotation.color),
            shadowElevation = 2.dp,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Comment,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}
