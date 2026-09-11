package info.jiayun.intellijmcp.python

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyImportElement
import info.jiayun.intellijmcp.intelligence.IdeIntelligenceBackend

class PythonIntelligenceBackend : IdeIntelligenceBackend("python") {
    override fun resolvedDeclaration(element: PsiElement): PsiElement =
        if(element is PyImportElement) element.resolve() ?: element else element
}
