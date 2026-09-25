package io.github.tffy1.pdfviewer.ui.viewer.annotations

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.annotations.AnnotationColors
import io.github.tffy1.pdfviewer.annotations.AnnotationType
import io.github.tffy1.pdfviewer.annotations.StrokeWidth

@Composable
internal fun AnnotationToolbarContent(
    controller: AnnotationController,
    onDone: () -> Unit,
    modifier: Modifier,
    windowInsets: WindowInsets,
) {
    val tool by controller.activeTool.collectAsStateWithLifecycle()
    val canUndo by controller.canUndo.collectAsStateWithLifecycle()
    val colors by controller.colors.collectAsStateWithLifecycle()
    val strokeWidth by controller.strokeWidth.collectAsStateWithLifecycle()
    val toolbarTitle = stringResource(R.string.annotations_toolbar)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { paneTitle = toolbarTitle },
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        BoxWithConstraints(Modifier.windowInsetsPadding(windowInsets)) {
            // Scrolls horizontally only on very narrow screens; otherwise spreads to full width.
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .widthIn(min = maxWidth)
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolToggle(
                        selected = tool == AnnotationTool.INK,
                        icon = Icons.Filled.Draw,
                        label = stringResource(R.string.annotations_tool_ink),
                        onToggle = { controller.setTool(if (it) AnnotationTool.INK else null) },
                    )
                    ToolToggle(
                        selected = tool == AnnotationTool.NOTE,
                        icon = Icons.AutoMirrored.Filled.StickyNote2,
                        label = stringResource(R.string.annotations_tool_note),
                        onToggle = { controller.setTool(if (it) AnnotationTool.NOTE else null) },
                    )
                    ToolToggle(
                        selected = tool == AnnotationTool.ERASER,
                        icon = EraserIcon,
                        label = stringResource(R.string.annotations_tool_eraser),
                        onToggle = { controller.setTool(if (it) AnnotationTool.ERASER else null) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val swatchType = when (tool) {
                        AnnotationTool.INK -> AnnotationType.INK
                        AnnotationTool.NOTE -> AnnotationType.NOTE
                        AnnotationTool.ERASER, null -> AnnotationType.HIGHLIGHT
                    }
                    ColorButton(
                        currentColor = colors[swatchType] ?: AnnotationColors.defaultFor(swatchType),
                        colors = colors,
                        onSelect = controller::setColor,
                    )
                    if (tool == AnnotationTool.INK) {
                        StrokeWidthButton(selected = strokeWidth, onSelect = controller::setStrokeWidth)
                    }
                    IconButton(onClick = controller::undo, enabled = canUndo) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.annotations_undo),
                        )
                    }
                    TextButton(
                        onClick = {
                            controller.setTool(null)
                            onDone()
                        },
                    ) {
                        Text(stringResource(R.string.annotations_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolToggle(selected: Boolean, icon: ImageVector, label: String, onToggle: (Boolean) -> Unit) {
    IconToggleButton(
        checked = selected,
        onCheckedChange = onToggle,
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

// ---- Colors ----------------------------------------------------------------------------

private data class ColorSection(val type: AnnotationType, @param:StringRes val label: Int)

private val colorSections = listOf(
    ColorSection(AnnotationType.INK, R.string.annotations_color_section_ink),
    ColorSection(AnnotationType.NOTE, R.string.annotations_color_section_note),
    ColorSection(AnnotationType.HIGHLIGHT, R.string.annotations_color_section_highlight),
    ColorSection(AnnotationType.UNDERLINE, R.string.annotations_color_section_underline),
    ColorSection(AnnotationType.STRIKEOUT, R.string.annotations_color_section_strikeout),
)

@StringRes
private fun colorName(argb: Int): Int = when (argb) {
    AnnotationColors.YELLOW -> R.string.annotations_color_yellow
    AnnotationColors.GREEN -> R.string.annotations_color_green
    AnnotationColors.CYAN -> R.string.annotations_color_cyan
    AnnotationColors.PINK -> R.string.annotations_color_pink
    AnnotationColors.ORANGE -> R.string.annotations_color_orange
    AnnotationColors.LAVENDER -> R.string.annotations_color_lavender
    AnnotationColors.BLACK -> R.string.annotations_color_black
    AnnotationColors.RED -> R.string.annotations_color_red
    AnnotationColors.BLUE -> R.string.annotations_color_blue
    AnnotationColors.DARK_GREEN -> R.string.annotations_color_dark_green
    AnnotationColors.DEEP_ORANGE -> R.string.annotations_color_deep_orange
    AnnotationColors.PURPLE -> R.string.annotations_color_purple
    else -> R.string.annotations_color_custom
}

@Composable
private fun ColorButton(
    currentColor: Int,
    colors: Map<AnnotationType, Int>,
    onSelect: (AnnotationType, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val description = stringResource(R.string.annotations_colors)
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(Color(currentColor), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (section in colorSections) {
                val selectedColor = colors[section.type] ?: AnnotationColors.defaultFor(section.type)
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        text = stringResource(section.label),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                    Row(Modifier.selectableGroup()) {
                        for (argb in AnnotationColors.paletteFor(section.type)) {
                            ColorSwatch(
                                argb = argb,
                                selected = argb == selectedColor,
                                onClick = { onSelect(section.type, argb) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val color = Color(argb)
    val name = stringResource(colorName(argb))
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(color, CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ---- Stroke width ----------------------------------------------------------------------

@StringRes
private fun strokeWidthLabel(width: StrokeWidth): Int = when (width) {
    StrokeWidth.THIN -> R.string.annotations_stroke_thin
    StrokeWidth.MEDIUM -> R.string.annotations_stroke_medium
    StrokeWidth.THICK -> R.string.annotations_stroke_thick
}

@Composable
private fun StrokeWidthButton(selected: StrokeWidth, onSelect: (StrokeWidth) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.LineWeight, contentDescription = stringResource(R.string.annotations_stroke_width))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (width in StrokeWidth.entries) {
                val isSelected = width == selected
                DropdownMenuItem(
                    text = { Text(stringResource(strokeWidthLabel(width))) },
                    onClick = {
                        onSelect(width)
                        expanded = false
                    },
                    modifier = Modifier.semantics { this.selected = isSelected },
                    leadingIcon = { StrokePreview(width) },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun StrokePreview(width: StrokeWidth) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val y = size.height / 2f
        drawLine(
            color = color,
            start = Offset(2.dp.toPx(), y),
            end = Offset(size.width - 2.dp.toPx(), y),
            strokeWidth = (width.points * 0.8f).dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
