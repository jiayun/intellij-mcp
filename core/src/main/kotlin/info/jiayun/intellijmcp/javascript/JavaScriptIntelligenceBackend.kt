package info.jiayun.intellijmcp.javascript

import com.intellij.lang.javascript.integration.JSAnnotationRangeError
import com.intellij.lang.javascript.service.JSLanguageServiceProvider
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import com.intellij.openapi.progress.runBlockingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withTimeout
import info.jiayun.intellijmcp.api.LocationInfo
import info.jiayun.intellijmcp.intelligence.*

/** Includes semantic errors supplied by the installed JS/TS or Vue language service. */
class JavaScriptIntelligenceBackend(language: String) : IdeIntelligenceBackend(language) {
    override fun capabilities(project: com.intellij.openapi.project.Project?): IntelligenceCapabilities {
        val capabilities = super.capabilities(project)
        return capabilities.copy(diagnostics=capabilities.diagnostics.copy(
            backend="IDE CodeSmellDetector + language services",
            limitations=capabilities.diagnostics.limitations+"Requires the configured JS/TS/Vue service; unsaved TypeScript configuration reports not_ready"))
    }
    override fun diagnostics(context: AnalysisContext): DiagnosticsResult {
        val changedConfigs = psiRead {
            val psi = PsiManager.getInstance(context.project).findFile(context.file)
            psi != null && changedConfigs(psi)
        }
        if (changedConfigs) return DiagnosticsResult(context.file.path,AnalysisStatus.not_ready,
            "Unsaved TypeScript configuration prevents language service analysis without saving files")
        if(language != "vue") return analyze(context)
        // Vue's hybrid services initialize and track files through the editor lifecycle.
        // A temporary background editor supplies that lifecycle for an unopened SFC.
        val opened = java.util.concurrent.atomic.AtomicBoolean()
        val loaded = java.util.concurrent.CompletableFuture<Unit>()
        try {
            context.deadline.await(onEdt(context) {
                val manager = FileEditorManager.getInstance(context.project)
                if(!manager.isFileOpen(context.file)) {
                    manager.openFile(context.file,false)
                    opened.set(manager.isFileOpen(context.file))
                    val editor = manager.getEditors(context.file).filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>().firstOrNull()?.editor
                    if(editor != null) manager.runWhenLoaded(editor) { loaded.complete(Unit) } else loaded.complete(Unit)
                } else loaded.complete(Unit)
            })
            context.deadline.await(loaded)
            return analyze(context)
        } finally {
            if(opened.get()) {
                val closed = onEdt(context,checkDeadline=false) {
                    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(context.file)
                    if(document?.modificationStamp == context.stamp)
                        FileEditorManager.getInstance(context.project).closeFile(context.file)
                }
                // On cancellation disposal still runs later on EDT; never extend the request deadline.
                if(runCatching { context.deadline.check() }.isSuccess) context.deadline.await(closed)
            }
        }
    }
    private fun analyze(context: AnalysisContext): DiagnosticsResult {
        val inspections = super.diagnostics(context)
        val file = psiRead { PsiManager.getInstance(context.project).findFile(context.file) }
            ?: throw AnalysisFailure(AnalysisStatus.not_ready,"PSI is unavailable")
        val services = psiRead {
            JSLanguageServiceProvider.getProviders(context.project).filter { it.isHighlightingCandidate(context.file) }
                .flatMap { it.allServices }.distinct().filter { it.isAcceptable(context.file) && it.canHighlight(file) }
        }
        if (services.isEmpty()) return inspections
        val diagnostics = inspections.diagnostics.toMutableList()
        val errors = mutableListOf<String>()
        for (service in services) {
            val opened = psiRead { !FileEditorManager.getInstance(context.project).isFileOpen(context.file) }
            try {
                val annotations = runBlockingCancellable {
                    withTimeout(context.deadline.remainingMillis()) {
                        if(service is com.intellij.lang.typescript.compiler.TypeScriptService && !psiRead { service.javaClass.methods.firstOrNull { it.name == "ensureServiceAvailable" && it.parameterCount == 0 }?.invoke(service) as? Boolean ?: true })
                            throw AnalysisFailure(AnalysisStatus.not_ready,"The IDE language service package is not available yet")
                        if (opened && service is com.intellij.lang.typescript.compiler.languageService.TypeScriptServerServiceImpl) {
                            val command = psiRead {
                                val factory = com.intellij.lang.typescript.compiler.languageService.TypeScriptServerServiceImpl::class.java
                                    .getDeclaredMethod("createOpenEditorCommand",com.intellij.openapi.vfs.VirtualFile::class.java)
                                factory.isAccessible = true
                                factory.invoke(service,context.file) as com.intellij.lang.javascript.service.protocol.JSLanguageServiceSimpleCommand
                            }
                            // Startup owns its coroutine read actions. Do not inherit read access.
                            sendOpenCommand(service,command)
                        } else if(opened) service.openEditor(context.file)
                        withContext(PsiContinuationDispatcher) { service.highlightSuspending(file) }
                    }
                } ?: throw AnalysisFailure(AnalysisStatus.not_ready,"Language service returned no analysis response")
                context.checkFresh()
                for (annotation in annotations) {
                    if (annotation.absoluteFilePath != context.file.path) continue
                    val severity = when {
                        annotation.severity >= com.intellij.lang.annotation.HighlightSeverity.ERROR -> "error"
                        annotation.severity >= com.intellij.lang.annotation.HighlightSeverity.WARNING -> "warning"
                        else -> "information"
                    }
                    if (severityRank(severity) < severityRank(context.options.minSeverity)) continue
                    val range = annotation as? JSAnnotationRangeError
                    diagnostics += DiagnosticInfo(severity,annotation.description,LocationInfo(context.file.path,
                        annotation.line+1,annotation.column+1,range?.endLine?.plus(1),range?.endColumn?.plus(1)),
                        "IDE language service")
                }
            } catch (e: Exception) { errors += failureMessage(e) }
            finally { if (opened && !psiRead { FileEditorManager.getInstance(context.project).isFileOpen(context.file) }) psiRead { service.closeLastEditor(context.file) } }
        }
        return inspections.copy(status=if(errors.isEmpty()) inspections.status else AnalysisStatus.partial,
            reason=(listOfNotNull(inspections.reason)+errors).joinToString("; ").ifEmpty { null },
            diagnostics=diagnostics.distinct().sortedWith(compareBy({it.location.line},{it.location.column},{it.message})),
            backend="IDE CodeSmellDetector + language services")
    }
}

/** The plugin's suspending highlighter expects read access on each continuation.
 * Each suspension releases it, including server startup and diagnostic response waits. */
private object PsiContinuationDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            psiRead { block.run() }
        }
    }
}

private fun changedConfigs(file: com.intellij.psi.PsiFile): Boolean {
    val type = com.intellij.lang.typescript.compiler.languageService.TypeScriptLanguageServiceUtil::class.java
    val method = type.getMethod("getChangedConfigs",com.intellij.psi.PsiElement::class.java)
    val receiver = if(java.lang.reflect.Modifier.isStatic(method.modifiers)) null else type.getField("INSTANCE").get(null)
    return (method.invoke(receiver,file) as Collection<*>).isNotEmpty()
}

/** 251 exposes the suspending send method directly; 253+ provides a public forwarding API. */
private suspend fun sendOpenCommand(service: com.intellij.lang.javascript.service.JSLanguageService,
    command: com.intellij.lang.javascript.service.protocol.JSLanguageServiceSimpleCommand): Any? =
    kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
        val methods = service.javaClass.methods.filter { it.parameterCount == 2 &&
            it.parameterTypes[1] == kotlin.coroutines.Continuation::class.java }
        val method = methods.firstOrNull { it.name == "sendCommandSuspending" }
            ?: methods.first { it.name == "sendCommand" }
        try { method.invoke(service,command,continuation) }
        catch(e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
    }

private fun onEdt(context: AnalysisContext,checkDeadline: Boolean = true,action: () -> Unit): java.util.concurrent.CompletableFuture<Unit> {
    val future = java.util.concurrent.CompletableFuture<Unit>()
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
        if(!future.isDone) try {
            if(context.project.isDisposed) throw AnalysisFailure(AnalysisStatus.not_ready,"Project is disposed")
            if(checkDeadline) context.deadline.check()
            action(); future.complete(Unit)
        } catch(t: Throwable) { future.completeExceptionally(t) }
    },com.intellij.openapi.application.ModalityState.nonModal())
    return future
}
