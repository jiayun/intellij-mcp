package info.jiayun.intellijmcp.smoke

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import info.jiayun.intellijmcp.api.LanguageAdapterRegistry
import info.jiayun.intellijmcp.intelligence.IntelligenceService
import info.jiayun.intellijmcp.settings.PluginSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Separate test-only plugin. Runs actual packaged backends in an isolated IDE process. */
class SmokeStarter : ApplicationStarter {
    override val requiredModality: Int get() = ApplicationStarter.NOT_IN_EDT
    override val isHeadless: Boolean get() = !java.lang.Boolean.getBoolean("mcp.smoke.ui")
    override fun main(args: List<String>) {
        val root = Path.of(args[1])
        val reportPath = Path.of(args[2])
        val gson = GsonBuilder().setPrettyPrinting().create()
        val report = linkedMapOf<String, Any?>()
        var exit = 1
        try {
            PluginSettings.getInstance().autoStart = false
            val plan = gson.fromJson(Files.readString(root.resolve("smoke.json")), JsonObject::class.java)
            val language = plan["language"].asString
            val project = ProjectUtil.openOrImport(root, OpenProjectTask.build().withForceOpenInNewFrame(true))
                ?: error("Could not open fixture")
            println("SMOKE project opened: $root")
            setup(project, root, language)
            if(language == "vue" && plan.has("vueService")) edt {
                project.getService(org.jetbrains.vuejs.options.VueSettings::class.java).serviceType =
                    org.jetbrains.vuejs.options.VueServiceSettings.valueOf(plan["vueService"].asString)
            }
            DumbService.getInstance(project).waitForSmartMode()
            println("SMOKE indexes ready")
            if(plan.has("openFiles")) edt {
                for(name in plan.getAsJsonArray("openFiles")) {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(root.resolve(name.asString).toString())!!
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file,false)
                }
            }
            report["language"] = language
            report["ide"] = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
            report["adapters"] = LanguageAdapterRegistry.getInstance().getSupportedLanguages()
            val results = mutableListOf<Any>()
            val observedRoots = mutableListOf<String?>()
            report["checks"] = results
            for (entry in plan.getAsJsonArray("checks")) {
                val check = entry.asJsonObject
                val path = root.resolve(check["file"].asString).toString()
                val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: error(path)
                val diskBefore = Files.readAllBytes(Path.of(path))
                val original = String(diskBefore,Charsets.UTF_8).replace("\r\n","\n")
                if (check.has("replace")) edt {
                    val doc = FileDocumentManager.getInstance().getDocument(file)!!
                    val replacement = check.getAsJsonArray("replace")
                    WriteCommandAction.runWriteCommandAction(project) {
                        doc.setText(original.replace(replacement[0].asString, replacement[1].asString))
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                    }
                }
                val tool = check["tool"].asString
                val arguments = linkedMapOf<String,Any?>("timeout" to (check.get("timeout")?.asInt ?: 60000))
                for(key in listOf("depth","maxNodes","limit")) if(check.has(key)) arguments[key] = check[key].asInt
                if (tool == "get_diagnostics") arguments["filePaths"] = listOf(path)
                else {
                    val needle = check["needle"].asString
                    var offset = original.indexOf(needle)
                    repeat(check.get("occurrence")?.asInt ?: 0) { offset = original.indexOf(needle,offset+1) }
                    require(offset >= 0) { "Needle $needle absent" }
                    arguments["filePath"] = path
                    arguments["line"] = original.take(offset).count { it == '\n' }+1
                    arguments["column"] = offset-original.lastIndexOf('\n',offset-1)
                    if(tool == "get_call_hierarchy") arguments["direction"] = check["direction"].asString
                }
                val watchdog = Thread {
                    try { Thread.sleep(20000); Thread.getAllStackTraces().forEach { (thread,trace) ->
                        println("SMOKE THREAD ${thread.name} ${thread.state}\n"+trace.joinToString("\n"))
                    } } catch(_: InterruptedException) {}
                }.apply { isDaemon = true; start() }
                var json = try { gson.toJsonTree(IntelligenceService(project).execute(tool,arguments)) } finally { watchdog.interrupt() }
                val readyBy = System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
                while(json.toString().contains("\"status\":\"not_ready\"") && System.nanoTime()<readyBy) {
                    println("SMOKE waiting for backend: $json")
                    Thread.sleep(300)
                    DumbService.getInstance(project).waitForSmartMode()
                    json = gson.toJsonTree(IntelligenceService(project).execute(tool,arguments))
                }
                if(language == "vue" && tool == "get_diagnostics" && !plan.has("openFiles")) edt {
                    require(!com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(file)) { "Temporary Vue editor was not closed" }
                }
                require(Files.readAllBytes(Path.of(path)).contentEquals(diskBefore)) { "Analysis saved or modified the file on disk" }
                results.add(mapOf("check" to check,"result" to json))
                println("SMOKE $tool: $json")
                val status = if(json.isJsonArray) json.asJsonArray[0].asJsonObject["status"].asString else json.asJsonObject["status"].asString
                val allowed = check.get("status")?.asString ?: "complete"
                require(status == allowed) { "Expected $allowed, got $status" }
                val thisRoot = if(json.isJsonObject) json.asJsonObject.get("root")?.asString else null
                if(check.has("rootSameAs")) require(thisRoot != null && thisRoot == observedRoots[check["rootSameAs"].asInt]) { "Declaration/use targets differ" }
                observedRoots.add(thisRoot)
                val names = if(json.isJsonObject) {
                    val obj = json.asJsonObject
                    if(obj.has("nodes")) obj.getAsJsonArray("nodes").map { it.asJsonObject["symbol"].asJsonObject["name"].asString }
                    else obj.getAsJsonArray("implementations")?.map { it.asJsonObject["name"].asString }.orEmpty()
                } else emptyList()
                if(check.has("names")) for(name in check.getAsJsonArray("names")) require(name.asString in names) { "Missing symbol $name in $names" }
                if(check.has("excludeNames")) for(name in check.getAsJsonArray("excludeNames")) require(name.asString !in names) { "Unexpected symbol $name in $names" }
                if(tool == "find_implementations") require(json.asJsonObject.getAsJsonArray("implementations").size() > 0) { "No implementations" }
                if(tool == "get_call_hierarchy" && allowed != "unsupported") require(json.asJsonObject.getAsJsonArray("edges").size() > 0) { "No call edges" }
                if(check.has("contains")) for (expected in check.getAsJsonArray("contains"))
                    require(json.toString().contains(expected.asString)) { "Missing ${expected.asString}" }
                if(check.has("diagnosticError")) {
                    val errors = json.asJsonArray[0].asJsonObject.getAsJsonArray("diagnostics").any { it.asJsonObject["severity"].asString == "error" }
                    require(errors == check["diagnosticError"].asBoolean) { "Unexpected error diagnostics" }
                }
            }
            report["passed"] = true
            exit = 0
        } catch(t: Throwable) {
            t.printStackTrace()
            report["passed"] = false
            report["error"] = t.stackTraceToString()
        } finally {
            Files.writeString(reportPath,gson.toJson(report))
            // This process and its configuration are dedicated to the smoke run.
            kotlin.system.exitProcess(exit)
        }
    }
    private fun setup(project: Project, root: Path, language: String) {
        when(language) {
            "rust" -> RustSetup.configure(project,root)
            "go" -> edt { com.goide.sdk.GoSdkService.getInstance(project).setSdkHomePath(System.getenv("MCP_GO_SDK")) }
            "dart" -> edt { WriteAction.run<RuntimeException> {
                com.jetbrains.lang.dart.sdk.DartSdkLibUtil.ensureDartSdkConfigured(project,System.getenv("MCP_DART_SDK"))
                ModuleManager.getInstance(project).modules.forEach { com.jetbrains.lang.dart.sdk.DartSdkLibUtil.enableDartSdk(it) }
            } }
            "java", "kotlin" -> edt { JavaSetup.configure(project) }
            "python" -> edt { PythonSetup.configure(project) }
            "php" -> edt { com.jetbrains.php.config.PhpProjectConfigurationFacade.getInstance(project).languageLevel = com.jetbrains.php.config.PhpLanguageLevel.PHP830 }
        }
        if(language == "dart") {
            val service = com.jetbrains.lang.dart.analyzer.DartAnalysisServerService.getInstance(project)
            val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
            while(!info.jiayun.intellijmcp.intelligence.psiRead { service.serverReadyForRequest() } && System.nanoTime()<end) Thread.sleep(200)
        }
    }
}
private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(block)
private object RustSetup {
    fun configure(project: Project,root: Path) {
        val settings = project.getService(org.rust.cargo.project.settings.RustProjectSettingsService::class.java)
        edt { settings.modify { it.toolchain = org.rust.cargo.toolchain.RsLocalToolchain(Path.of(System.getenv("MCP_RUST_BIN"))); it.useOffline = true } }
        project.getService(org.rust.cargo.project.model.CargoProjectsService::class.java)
            .discoverAndRefresh().get(120,TimeUnit.SECONDS)
    }
}
private object JavaSetup {
    fun configure(project: Project) = WriteAction.run<RuntimeException> {
        val sdk = com.intellij.openapi.projectRoots.JavaSdk.getInstance().createJdk("Smoke JDK",System.getProperty("java.home"),false)
        com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().addJdk(sdk)
        com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk = sdk
        ModuleManager.getInstance(project).modules.forEach { com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(it,sdk) }
    }
}
private object PythonSetup {
    fun configure(project: Project) = WriteAction.run<RuntimeException> {
        val type = com.jetbrains.python.sdk.PythonSdkType.getInstance()
        val sdk = com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().createSdk("Smoke Python",type)
        val model = sdk.sdkModificator
        model.homePath = System.getenv("MCP_PYTHON")
        model.commitChanges()
        type.setupSdkPaths(sdk)
        com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().addJdk(sdk)
        com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk = sdk
        ModuleManager.getInstance(project).modules.forEach { com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(it,sdk) }
    }
}
