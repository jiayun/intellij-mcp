package info.jiayun.intellijmcp.php

import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import info.jiayun.intellijmcp.intelligence.*

class PhpIntelligenceBackend : IdeIntelligenceBackend("php") {
    override fun implementations(context: AnalysisContext): ImplementationsResult {
        val target = resolveTarget(context)
        if(target !is PhpClass || !psiRead { target.isTrait }) return super.implementations(context)
        val root = psiRead { symbol(target) }
        val results = linkedMapOf<String,IntelligenceSymbol>()
        var truncated = false
        var failure: Exception? = null
        try {
            psiRead {
                PhpIndex.getInstance(context.project).processNestedTraitUsages(target,mutableSetOf(),com.intellij.util.Processor { user ->
                    context.deadline.check()
                    if(inScope(context,user)) {
                        val next = symbol(user)
                        if(next.id != root.id) results[next.id] = next
                    }
                    truncated = results.size > context.options.limit
                    !truncated
                })
            }
            context.checkFresh()
        } catch(e: Exception) { failure = e }
        return ImplementationsResult(if(failure != null || truncated) AnalysisStatus.partial else AnalysisStatus.complete,
            failure?.let(::failureMessage) ?: if(truncated) "limit reached" else null,root,
            results.values.sortedBy { it.id }.take(context.options.limit),truncated,"IDE PHP trait usage index")
    }
}
