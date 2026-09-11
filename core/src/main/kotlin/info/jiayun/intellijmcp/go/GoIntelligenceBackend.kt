package info.jiayun.intellijmcp.go

import com.goide.sdk.GoSdkService
import com.intellij.openapi.module.ModuleUtilCore
import info.jiayun.intellijmcp.intelligence.*

class GoIntelligenceBackend : IdeIntelligenceBackend("go") {
    override fun checkBackendReady(context: AnalysisContext) {
        val valid = psiRead {
            val module = ModuleUtilCore.findModuleForFile(context.file,context.project)
            module != null && GoSdkService.getInstance(context.project).getSdk(module).isValid
        }
        if(!valid) throw AnalysisFailure(AnalysisStatus.not_ready,"No valid Go SDK configured for this file's module")
    }
}
