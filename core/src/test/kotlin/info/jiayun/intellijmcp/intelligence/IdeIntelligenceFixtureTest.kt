package info.jiayun.intellijmcp.intelligence

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeIntelligenceFixtureTest : BasePlatformTestCase() {
    @BeforeEach fun startFixture() = EdtTestUtil.runInEdtAndWait<Throwable> {
        name = "intelligence"
        super.setUp()
        // Exercise native PSI backends; do not start optional Node language servers in fixtures.
        com.intellij.testFramework.ExtensionTestUtil.maskExtensions(
            com.intellij.openapi.extensions.ExtensionPointName.create<com.intellij.lang.javascript.service.JSLanguageServiceProvider>("JavaScript.languageServiceProvider"),
            emptyList(),testRootDisposable,false,project)
        val sdk = ProjectJdkTable.getInstance().createSdk("Intelligence fixture SDK",JavaSdk.getInstance())
        WriteAction.run<RuntimeException> {
            ProjectJdkTable.getInstance().addJdk(sdk,testRootDisposable)
            ModuleRootModificationUtil.setModuleSdk(module,sdk)
        }
    }
    @AfterEach fun stopFixture() = EdtTestUtil.runInEdtAndWait<Throwable> { super.tearDown() }

    private lateinit var currentFile: com.intellij.psi.PsiFile
    private var cursor = 0
    private val currentDocument get() = FileDocumentManager.getInstance().getDocument(currentFile.virtualFile)!!
    private fun configure(path: String,text: String) {
        cursor = text.indexOf("<caret>")
        currentFile = myFixture.addFileToProject(path,text.replace("<caret>",""))
    }
    private fun context(options: IntelligenceOptions = IntelligenceOptions()): AnalysisContext {
        val file = currentFile.virtualFile
        val document = currentDocument
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val offset = cursor
        val line = document.getLineNumber(offset)
        return AnalysisContext(project,file,document.text,document.modificationStamp,offset,line+1,
            offset-document.getLineStartOffset(line)+1,options,Deadline(10000))
    }

    @Test fun javaDeclarationAndUseResolveToSameRoot() = EdtTestUtil.runInEdtAndWait<Throwable> {
        val backend = IdeIntelligenceBackend("java")
        configure("Example.java","class Example { void <caret>callee() {} void caller() { callee(); } }")
        val declaration = backend.calls(context())
        assertEquals(AnalysisStatus.complete,declaration.status,declaration.reason)
        assertTrue(declaration.nodes.any { it.symbol.name == "caller" },declaration.toString())
        cursor = currentDocument.text.lastIndexOf("callee")
        val use = backend.calls(context())
        assertEquals(declaration.root,use.root)
        assertEquals(declaration.edges,use.edges)
    }
    @Test fun javaCrossFileOverrideSearch() = EdtTestUtil.runInEdtAndWait<Throwable> {
        myFixture.addFileToProject("Impl.java","class Impl implements Base { public void run() {} }")
        configure("Base.java","interface Base { void <caret>run(); }")
        val result = IdeIntelligenceBackend("java").implementations(context())
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertEquals(1,result.implementations.size,result.toString())
        assertTrue(result.implementations.single().location.filePath.endsWith("Impl.java"))
    }
    @Test fun javaOutgoingRecursionKeepsSelfEdge() = EdtTestUtil.runInEdtAndWait<Throwable> {
        configure("Recursive.java","class Recursive { void <caret>run() { run(); } }")
        val result = IdeIntelligenceBackend("java").calls(context(IntelligenceOptions(direction="outgoing",depth=3)))
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertEquals(1,result.nodes.size)
        assertEquals(result.root,result.edges.single().from)
        assertEquals(result.root,result.edges.single().to)
    }
    @Test fun javaUnsavedCallEditIsVisible() = EdtTestUtil.runInEdtAndWait<Throwable> {
        configure("Edit.java","class Edit { void <caret>run() {} void next() {} }")
        val backend = IdeIntelligenceBackend("java")
        assertTrue(backend.calls(context(IntelligenceOptions(direction="outgoing"))).edges.isEmpty())
        val document = currentDocument
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.replaceString(document.text.indexOf("{}"),document.text.indexOf("{}")+2,"{ next(); }") }
        val result = backend.calls(context(IntelligenceOptions(direction="outgoing")))
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertTrue(result.nodes.any { it.symbol.name == "next" },result.toString())
    }
    private fun <T> withProgress(block: () -> T): T = com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
        com.intellij.openapi.util.Computable { block() },com.intellij.openapi.progress.util.ProgressIndicatorBase())

    private fun configured(path: String,text: String,options: IntelligenceOptions = IntelligenceOptions()): AnalysisContext =
        EdtTestUtil.runInEdtAndGet<AnalysisContext,Throwable> { configure(path,text); context(options) }

    @Test fun servicePreservesWorkerFailures() {
        val result = IntelligenceService(project).execute("get_diagnostics", mapOf("filePaths" to listOf("/nonexistent-intelligence-fixture.java"))) as List<*>
        val fileResult = result.single() as DiagnosticsResult
        assertEquals(AnalysisStatus.error,fileResult.status)
        assertTrue(fileResult.reason.orEmpty().contains("File not found"),fileResult.toString())
    }
    @Test fun javascriptNativeCalls() {
        val ctx = configured("calls.js","function <caret>callee() {} function caller() { callee(); }")
        val result = IdeIntelligenceBackend("javascript").calls(ctx)
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertTrue(result.nodes.any { it.symbol.name == "caller" },result.toString())
    }
    @Test fun unsavedTypeScriptConfigIsNotSavedByDiagnostics() {
        val (ctx,config) = EdtTestUtil.runInEdtAndGet<Pair<AnalysisContext,com.intellij.openapi.vfs.VirtualFile>,Throwable> {
            val config = myFixture.addFileToProject("tsconfig.json","{\"compilerOptions\":{\"strict\":false}}")
            configure("guard.ts","function <caret>run() {}")
            val document = FileDocumentManager.getInstance().getDocument(config.virtualFile)!!
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                document.setText("{\"compilerOptions\":{\"strict\":true}}")
            }
            context() to config.virtualFile
        }
        val before = config.contentsToByteArray()
        try {
            val result = info.jiayun.intellijmcp.javascript.JavaScriptIntelligenceBackend("javascript").diagnostics(ctx)
            assertEquals(AnalysisStatus.not_ready,result.status,result.reason)
            assertTrue(before.contentEquals(config.contentsToByteArray()))
            assertTrue(FileDocumentManager.getInstance().isFileModified(config))
        } finally {
            // Delete while language services are still masked; fixture teardown restores their EP.
            EdtTestUtil.runInEdtAndWait<Throwable> { WriteAction.run<RuntimeException> { config.delete(this) } }
        }
    }
    @Test fun typescriptNativeImplementations() {
        val ctx = configured("impl.ts","interface Base { <caret>run(): void; } class Impl implements Base { run() {} }")
        val result = IdeIntelligenceBackend("javascript").implementations(ctx)
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertTrue(result.implementations.isNotEmpty(),result.toString())
    }
    @Test fun kotlinK2NativeCalls() {
        val ctx = configured("calls.kt","fun <caret>callee() {}\nfun caller() { callee() }")
        val result = IdeIntelligenceBackend("kotlin").calls(ctx)
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertTrue(result.nodes.any { it.symbol.name == "caller" },result.toString())
    }
    @Test fun javaAndKotlinCrossLanguageCalls() {
        EdtTestUtil.runInEdtAndWait<Throwable> {
            myFixture.addFileToProject("JavaSide.java", "public class JavaSide { public static void javaCallee() {} public void javaCaller() { new KotlinSide().kotlinCallee(); } }")
        }
        val kotlin = configured("KotlinSide.kt", "class KotlinSide { fun <caret>kotlinCallee() {} fun kotlinCaller() { JavaSide.javaCallee() } }")
        val kotlinCalls = IdeIntelligenceBackend("kotlin").calls(kotlin)
        assertEquals(AnalysisStatus.complete, kotlinCalls.status, kotlinCalls.reason)
        assertTrue(kotlinCalls.nodes.any { it.symbol.name == "javaCaller" }, kotlinCalls.toString())
        val java = EdtTestUtil.runInEdtAndGet<AnalysisContext, Throwable> {
            currentFile = myFixture.findFileInTempDir("JavaSide.java")
                .let { com.intellij.psi.PsiManager.getInstance(project).findFile(it)!! }
            cursor = currentDocument.text.indexOf("javaCallee")
            context()
        }
        val javaCalls = IdeIntelligenceBackend("java").calls(java)
        assertEquals(AnalysisStatus.complete, javaCalls.status, javaCalls.reason)
        assertTrue(javaCalls.nodes.any { it.symbol.name == "kotlinCaller" }, javaCalls.toString())
    }
    @Test fun pythonNativeCalls() {
        val ctx = configured("calls.py","def <caret>callee():\n    pass\ndef caller():\n    callee()\n")
        val result = IdeIntelligenceBackend("python").calls(ctx)
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertTrue(result.nodes.any { it.symbol.name == "caller" },result.toString())
    }
    @Test fun phpNativeCalls() {
        val ctx = configured("calls.php","<?php function <caret>callee() {} function caller() { callee(); }")
        val result = IdeIntelligenceBackend("php").calls(ctx)
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertTrue(result.nodes.any { it.symbol.name == "caller" },result.toString())
    }
    @Test fun vueScriptLocationRemainsInOriginalFile() {
        val ctx = configured("calls.vue","<template><div /></template>\n<script setup>\nfunction <caret>callee() {}\nfunction caller() { callee(); }\n</script>")
        val result = IdeIntelligenceBackend("vue").calls(ctx)
        assertEquals(AnalysisStatus.complete,result.status,result.reason)
        assertTrue(result.nodes.all { it.symbol.location.filePath.endsWith("calls.vue") },result.toString())
        assertTrue(result.nodes.any { it.symbol.name == "caller" && it.symbol.location.line == 4 },result.toString())
    }
    @Test fun javaDiagnosticsReflectUnsavedFix() {
        val ctx = configured("Errors.java","class Errors { int <caret>x = missing; }")
        val backend = IdeIntelligenceBackend("java")
        val broken = withProgress { backend.diagnostics(ctx) }
        assertTrue(broken.diagnostics.any { it.message.contains("missing") },broken.toString())
        val fixed = EdtTestUtil.runInEdtAndGet<AnalysisContext,Throwable> {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val start = currentDocument.text.indexOf("missing")
                currentDocument.replaceString(start,start+7,"1")
            }
            context()
        }
        val clean = withProgress { backend.diagnostics(fixed) }
        assertEquals(AnalysisStatus.complete,clean.status,clean.reason)
        assertTrue(clean.diagnostics.none { it.message.contains("missing") },clean.toString())
    }

}
