package io.github.tffy1.pdfviewer.navigation

import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

@Serializable
data object ToolsRoute

@Serializable
data object SettingsRoute

/** [initialPage] < 0 means "resume at the remembered page". */
@Serializable
data class ViewerRoute(val uri: String, val initialPage: Int = -1)
