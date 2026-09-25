package io.github.tffy1.pdfviewer.ui.tools

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tffy1.pdfviewer.R

/** The tools offered on the Tools tab, in display order. */
enum class PdfTool(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
) {
    MERGE(R.string.tools_merge_title, R.string.tools_merge_description, Icons.AutoMirrored.Outlined.CallMerge),
    SPLIT(R.string.tools_split_title, R.string.tools_split_description, Icons.AutoMirrored.Outlined.CallSplit),
    EXTRACT(R.string.tools_extract_title, R.string.tools_extract_description, Icons.Outlined.ContentCut),
    ORGANIZE(R.string.tools_organize_title, R.string.tools_organize_description, Icons.Outlined.GridView),
    IMAGES_TO_PDF(R.string.tools_images_title, R.string.tools_images_description, Icons.Outlined.AddPhotoAlternate),
    COMPRESS(R.string.tools_compress_title, R.string.tools_compress_description, Icons.Outlined.Compress),
    REMOVE_PASSWORD(
        R.string.tools_remove_password_title,
        R.string.tools_remove_password_description,
        Icons.Outlined.LockOpen,
    ),
    ADD_PASSWORD(R.string.tools_add_password_title, R.string.tools_add_password_description, Icons.Outlined.Lock),
}

/*
 * CONTRACT (scaffold). Owner: tools agent.
 * The Tools tab: a list of tools, each opening its own screen. Navigation between the list and
 * a tool is internal (saved across rotation / process death); [modifier] carries the app
 * scaffold's padding (status bar + bottom navigation).
 */
@Composable
fun ToolsScreen(onOpenDocument: (Uri) -> Unit, modifier: Modifier = Modifier) {
    var currentToolName by rememberSaveable { mutableStateOf<String?>(null) }
    val currentTool = currentToolName?.let { name -> PdfTool.entries.firstOrNull { it.name == name } }

    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(targetState = currentTool, label = "tools") { tool ->
            if (tool == null) {
                ToolList(onSelect = { currentToolName = it.name })
            } else {
                ToolHost(tool = tool, onBack = { currentToolName = null }, onOpenDocument = onOpenDocument)
            }
        }
    }
}

@Composable
private fun ToolHost(tool: PdfTool, onBack: () -> Unit, onOpenDocument: (Uri) -> Unit) {
    when (tool) {
        PdfTool.MERGE -> MergeToolScreen(onBack, onOpenDocument)
        PdfTool.SPLIT -> SplitToolScreen(onBack, onOpenDocument)
        PdfTool.EXTRACT -> ExtractToolScreen(onBack, onOpenDocument)
        PdfTool.ORGANIZE -> OrganizeToolScreen(onBack, onOpenDocument)
        PdfTool.IMAGES_TO_PDF -> ImagesToPdfToolScreen(onBack, onOpenDocument)
        PdfTool.COMPRESS -> CompressToolScreen(onBack, onOpenDocument)
        PdfTool.REMOVE_PASSWORD -> RemovePasswordToolScreen(onBack, onOpenDocument)
        PdfTool.ADD_PASSWORD -> AddPasswordToolScreen(onBack, onOpenDocument)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolList(onSelect: (PdfTool) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_title)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(PdfTool.entries, key = { it.name }) { tool ->
                ToolCard(tool = tool, onClick = { onSelect(tool) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                HelpText(
                    text = stringResource(R.string.tools_privacy_note),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: PdfTool, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(tool.icon, contentDescription = null)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(tool.titleRes), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(tool.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
