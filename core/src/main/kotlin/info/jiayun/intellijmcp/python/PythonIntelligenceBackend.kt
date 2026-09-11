package info.jiayun.intellijmcp.python

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyImportElement
import info.jiayun.intellijmcp.intelligence.IdeIntelligenceBackend
import info.jiayun.intellijmcp.intelligence.AnalysisFailure
import info.jiayun.intellijmcp.intelligence.AnalysisStatus

class PythonIntelligenceBackend : IdeIntelligenceBackend("python") {
    override fun resolvedDeclaration(element: PsiElement): PsiElement {
        if(element !is PyImportElement) return element
        val results = element.multiResolve().filter { it.isValidResult }
        val bestRate = results.maxOfOrNull { it.rate } ?: return element
        val targets = results.filter { it.rate == bestRate }.mapNotNull { it.element?.navigationElement }.distinct()
        if(targets.size > 1) throw AnalysisFailure(AnalysisStatus.partial,
            "Ambiguous Python import resolves to multiple declarations")
        return targets.singleOrNull() ?: element
    }
}
