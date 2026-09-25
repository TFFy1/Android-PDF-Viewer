package io.github.tffy1.pdfviewer.ui.tools

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tffy1.pdfviewer.AppContainer
import io.github.tffy1.pdfviewer.pdf.PdfPasswordException
import io.github.tffy1.pdfviewer.tools.PdfInput
import io.github.tffy1.pdfviewer.tools.PdfToolkit
import io.github.tffy1.pdfviewer.tools.ProgressListener
import io.github.tffy1.pdfviewer.tools.ToolError
import io.github.tffy1.pdfviewer.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class PdfFileStatus { LOADING, READY, LOCKED, WRONG_PASSWORD, UNREADABLE }

/** A PDF picked as tool input, with what we learned about it by opening it. */
data class SelectedPdf(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long? = null,
    val pageCount: Int = 0,
    val password: String? = null,
    val status: PdfFileStatus = PdfFileStatus.LOADING,
) {
    val isReady: Boolean get() = status == PdfFileStatus.READY
    val needsPassword: Boolean get() = status == PdfFileStatus.LOCKED || status == PdfFileStatus.WRONG_PASSWORD

    fun toInput(passwordOverride: String? = null) = PdfInput(uri, displayName, passwordOverride ?: password)
}

sealed interface OperationState {
    data object Idle : OperationState
    data class Running(val progress: Float) : OperationState
    data class Finished(val result: ToolResult.Success) : OperationState
    data class Failed(val error: ToolError) : OperationState
}

/**
 * Base for every tool screen's ViewModel. Operations run in [viewModelScope], so they survive
 * configuration changes and keep running while the user looks at another tab.
 */
abstract class ToolViewModel(protected val container: AppContainer) : ViewModel() {
    protected val toolkit = PdfToolkit(container.appContext, container.documentAccess)

    private val _operation = MutableStateFlow<OperationState>(OperationState.Idle)
    val operation: StateFlow<OperationState> = _operation.asStateFlow()

    private var operationJob: Job? = null
    private var nextId = 0L

    protected val isRunning: Boolean get() = _operation.value is OperationState.Running

    protected fun newId(): Long = nextId++

    /**
     * Starts [block] unless something is already running. On success, persistable read access
     * is taken for [persistUris] so "Open" keeps working after a restart.
     */
    protected fun runOperation(persistUris: List<Uri>, block: suspend (ProgressListener) -> ToolResult) {
        if (operationJob?.isActive == true) return
        _operation.value = OperationState.Running(0f)
        operationJob = viewModelScope.launch {
            val result = block { fraction ->
                _operation.update { state ->
                    // Throttle to whole percents; ignore late callbacks after cancel/finish.
                    if (state is OperationState.Running && abs(fraction - state.progress) >= 0.01f) {
                        OperationState.Running(fraction.coerceIn(0f, 1f))
                    } else {
                        state
                    }
                }
            }
            _operation.value = when (result) {
                is ToolResult.Success -> {
                    persistUris.forEach { container.documentAccess.takePersistableReadPermission(it) }
                    OperationState.Finished(result)
                }
                is ToolResult.Failure -> OperationState.Failed(result.error)
            }
        }
    }

    fun cancel() {
        operationJob?.cancel()
        operationJob = null
        _operation.value = OperationState.Idle
    }

    /** Hides a result or error card (inputs are kept). */
    fun dismissResult() {
        if (!isRunning) _operation.value = OperationState.Idle
    }

    /** Clears inputs, options and results. Subclasses clear their own state and call super. */
    open fun reset() {
        cancel()
    }

    /** Called when the user leaves the tool: keeps a running operation alive, else starts fresh. */
    fun leave() {
        if (!isRunning) reset()
    }

    /** Opens [pdf] with the engine to learn its name, size and page count. */
    protected suspend fun inspectPdf(pdf: SelectedPdf, password: String?): SelectedPdf {
        val info = container.documentAccess.queryInfo(pdf.uri)
        val base = pdf.copy(displayName = info.displayName, sizeBytes = info.sizeBytes, password = password)
        return try {
            container.pdfEngine.open(pdf.uri, password).use { doc ->
                base.copy(pageCount = doc.pageCount, status = PdfFileStatus.READY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfPasswordException) {
            base.copy(status = if (e.wrongPassword) PdfFileStatus.WRONG_PASSWORD else PdfFileStatus.LOCKED)
        } catch (e: Exception) {
            base.copy(status = PdfFileStatus.UNREADABLE)
        }
    }
}

/** Base for tools that take exactly one PDF. */
abstract class SinglePdfToolViewModel(container: AppContainer) : ToolViewModel(container) {
    var file by mutableStateOf<SelectedPdf?>(null)
        private set

    private var inspectJob: Job? = null

    fun selectFile(uri: Uri) {
        if (isRunning) return
        dismissResult()
        val placeholder = SelectedPdf(id = newId(), uri = uri, displayName = "")
        file = placeholder
        onFileChanged()
        inspect(placeholder, password = null)
    }

    fun unlock(password: String) {
        val current = file ?: return
        if (isRunning) return
        file = current.copy(status = PdfFileStatus.LOADING)
        inspect(current, password)
    }

    private fun inspect(pdf: SelectedPdf, password: String?) {
        inspectJob?.cancel()
        inspectJob = viewModelScope.launch {
            val inspected = inspectPdf(pdf, password)
            if (file?.id == pdf.id) {
                file = inspected
                onFileInspected(inspected)
            }
        }
    }

    /** A new file was picked (options that depend on the file should be reset). */
    protected open fun onFileChanged() = Unit

    protected open fun onFileInspected(file: SelectedPdf) = Unit

    override fun reset() {
        super.reset()
        inspectJob?.cancel()
        file = null
    }
}
