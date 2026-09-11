package info.jiayun.intellijmcp.intelligence

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

interface IntelligenceBackend {
    fun capabilities(project: Project?): IntelligenceCapabilities
    fun diagnostics(context: AnalysisContext): DiagnosticsResult = DiagnosticsResult(context.file.path,
        AnalysisStatus.unsupported, "Diagnostics are not supported by this adapter")
    fun implementations(context: AnalysisContext): ImplementationsResult = ImplementationsResult(
        AnalysisStatus.unsupported, "Implementations are not supported by this adapter")
    fun calls(context: AnalysisContext): CallHierarchyResult = CallHierarchyResult(
        AnalysisStatus.unsupported, "Call hierarchy is not supported by this adapter")
}
data class AnalysisContext(val project: Project, val file: VirtualFile, val text: String, val stamp: Long,
    val offset: Int, val line: Int, val column: Int, val options: IntelligenceOptions, val deadline: Deadline)

object UnsupportedIntelligence : IntelligenceBackend {
    override fun capabilities(project: Project?): IntelligenceCapabilities {
        val no = Capability("unsupported","none")
        return IntelligenceCapabilities(no,no,no,no)
    }
}
