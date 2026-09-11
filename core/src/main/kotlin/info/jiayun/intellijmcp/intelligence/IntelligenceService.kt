package info.jiayun.intellijmcp.intelligence

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import info.jiayun.intellijmcp.api.LanguageAdapterRegistry
import java.io.File
import java.util.concurrent.CompletableFuture

class IntelligenceService(private val project: Project) {
    fun execute(tool: String, args: Map<String, Any?>): Any {
        val options = IntelligenceOptions.parse(args)
        val deadline = Deadline(options.timeout)
        if (tool == "get_diagnostics") {
            val paths = args["filePaths"] as? List<*> ?: throw IllegalArgumentException("filePaths must be an array")
            require(paths.isNotEmpty() && paths.all { it is String && File(it).isAbsolute }) { "filePaths must contain absolute paths" }
            return paths.distinct().map { path ->
                try { run(deadline,useCoroutineContext = false) { val (ctx, backend) = prepare(path as String,1,1,options,deadline); backend.diagnostics(ctx) } }
                catch(e: Exception) { DiagnosticsResult(path as String,failureStatus(e),failureMessage(e)) }
            }
        }
        val path = args["filePath"] as? String ?: throw IllegalArgumentException("filePath is required")
        require(File(path).isAbsolute) { "filePath must be absolute" }
        fun coordinate(key: String): Int {
            val n = (args[key] as? Number)?.toDouble() ?: throw IllegalArgumentException("$key is required")
            require(n.isFinite() && n % 1 == 0.0 && n in 1.0..Int.MAX_VALUE.toDouble()) { "$key must be a positive integer" }
            return n.toInt()
        }
        val line = coordinate("line"); val column = coordinate("column")
        if (tool == "get_call_hierarchy") require(args.containsKey("direction")) { "direction is required" }
        return try {
            run(deadline) {
                val (ctx, backend) = prepare(path,line,column,options,deadline)
                if (tool == "find_implementations") backend.implementations(ctx) else backend.calls(ctx)
            }
        } catch(e: Exception) {
            if (tool == "find_implementations") ImplementationsResult(failureStatus(e),failureMessage(e))
            else CallHierarchyResult(failureStatus(e),failureMessage(e))
        }
    }

    private fun <T> run(deadline: Deadline, useCoroutineContext: Boolean = true, block: () -> T): T {
        deadline.check()
        if (project.isDisposed) throw AnalysisFailure(AnalysisStatus.not_ready,"Project is disposed")
        val indicator = ProgressIndicatorBase()
        // Application.executeOnPooledThread logs and swallows Callable exceptions on some IDEs.
        // Carry completion ourselves so indexing/errors cannot turn into a successful null result.
        val future = CompletableFuture<T>()
        val worker = ApplicationManager.getApplication().executeOnPooledThread {
            try {
                future.complete(ProgressManager.getInstance().runProcess(com.intellij.openapi.util.Computable {
                    // Native hierarchy/search APIs need a coroutine Job; CodeSmellDetector still
                    // requires the legacy progress indicator and must retain that context.
                    if(useCoroutineContext) com.intellij.openapi.progress.runBlockingCancellable { block() } else block()
                },indicator))
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        val cleanup = Disposable { indicator.cancel(); worker.cancel(true); future.cancel(true) }
        Disposer.register(project,cleanup)
        return try { deadline.await(future) } finally { Disposer.dispose(cleanup) }
    }

    private fun prepare(path: String, line: Int, column: Int, options: IntelligenceOptions,
        deadline: Deadline): Pair<AnalysisContext,IntelligenceBackend> {
        deadline.check()
        val adapter = LanguageAdapterRegistry.getInstance().getAdapterByExtension(path.substringAfterLast('.'))
            ?: throw AnalysisFailure(AnalysisStatus.unsupported,"No adapter for $path")
        if (adapter.requiresReadAction && DumbService.isDumb(project))
            throw AnalysisFailure(AnalysisStatus.not_ready,"IDE indexing is in progress")
        // Refresh outside read access; FileDocumentManager preserves unsaved IDE documents.
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            ?: throw AnalysisFailure(AnalysisStatus.error,"File not found: $path")
        val ready = CompletableFuture<AnalysisContext>()
        ApplicationManager.getApplication().invokeLater({
            if (!ready.isDone) try {
                deadline.check()
                if (project.isDisposed) throw AnalysisFailure(AnalysisStatus.not_ready,"Project is disposed")
                val document = FileDocumentManager.getInstance().getDocument(file)
                    ?: throw AnalysisFailure(AnalysisStatus.unsupported,"File is not a text document")
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                require(line <= document.lineCount) { "Line outside document" }
                val start = document.getLineStartOffset(line-1)
                val end = document.getLineEndOffset(line-1)
                require(column.toLong()-1 <= end-start) { "Column outside line" }
                ready.complete(AnalysisContext(project,file,document.text,document.modificationStamp,
                    start+column-1,line,column,options,deadline))
            } catch (e: Exception) { ready.completeExceptionally(e) }
        }, ModalityState.nonModal())
        return deadline.await(ready) to adapter.intelligence
    }
}

fun <T> psiRead(block: () -> T): T = ReadAction.compute<T,RuntimeException> { block() }
fun AnalysisContext.checkFresh() {
    deadline.check()
    val current = psiRead { FileDocumentManager.getInstance().getDocument(file)?.modificationStamp }
    if (current != stamp) throw AnalysisFailure(AnalysisStatus.partial,"Document changed during analysis; retry")
}
