package info.jiayun.intellijmcp.rust

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import info.jiayun.intellijmcp.intelligence.*
import org.rust.lang.core.psi.*

class RustIntelligenceBackend : IdeIntelligenceBackend("rust") {
    override fun checkBackendReady(context: AnalysisContext) {
        val ready = psiRead {
            val cargo = context.project.getService(org.rust.cargo.project.model.CargoProjectsService::class.java)
            cargo != null && cargo.initialized && !cargo.isRefreshInProgress && cargo.findProjectForFile(context.file) != null
        }
        if(!ready) throw AnalysisFailure(AnalysisStatus.not_ready,"Rust Cargo project is not loaded or is being refreshed; check the toolchain")
    }

    override fun directCalls(element: PsiElement, context: AnalysisContext): List<Pair<PsiElement,PsiElement?>> {
        val function = element as? RsFunction
            ?: throw AnalysisFailure(AnalysisStatus.unsupported,"Direct call analysis requires a Rust function or method")
        val result = mutableListOf<Pair<PsiElement,PsiElement?>>()
        fun resolved(call: PsiElement): PsiElement? = when(call) {
            is RsCallExpr -> (call.expr as? RsPathExpr)?.path?.reference?.resolve()
            is RsMethodCall -> call.reference?.resolve()
            else -> null
        }
        if(context.options.direction == "incoming") {
            ReferencesSearch.search(function,scope(context)).forEach(com.intellij.util.Processor { reference ->
                context.deadline.check()
                val site = reference.element
                // The reference must be the callee, not an argument or function-pointer assignment.
                val call = when {
                    site is RsMethodCall -> site
                    site is RsPath && site.parent is RsPathExpr && site.parent.parent is RsCallExpr &&
                        (site.parent.parent as RsCallExpr).expr == site.parent -> site.parent.parent
                    else -> null
                }
                if(call != null && resolved(call) == function) {
                    val caller = PsiTreeUtil.getParentOfType(call,RsFunction::class.java)
                    if(caller != null && inScope(context,caller)) result.add(caller to site)
                }
                true
            })
        } else {
            function.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(current: PsiElement) {
                    context.deadline.check()
                    if(current is RsFunction && current != function) return
                    // A lambda's body is not executed by declaring it.
                    if(current is RsLambdaExpr) return
                    if(current is RsCallExpr || current is RsMethodCall) {
                        val target = resolved(current)
                        if(target is RsFunction && inScope(context,target)) result.add(target to current)
                    }
                    super.visitElement(current)
                }
            })
        }
        return result
    }
}
