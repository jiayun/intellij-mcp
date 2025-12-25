# IntelliJ MCP Plugin 開發指南（多語言架構）

## 專案概述

開發一個 IntelliJ Platform Plugin，透過 MCP (Model Context Protocol) 將 IDE 的程式碼分析能力暴露給 Claude Code 使用。採用模組化架構，支援多語言擴充。

### 專案資訊

- **專案名稱**：intellij-mcp
- **Package**：`info.jiayun.intellijmcp`
- **目標 IDE**：IntelliJ IDEA、PyCharm、WebStorm、GoLand 等所有 JetBrains IDE

### 預期功能

| Tool 名稱 | 功能 |
|-----------|------|
| `list_projects` | 列出所有開啟的專案 |
| `find_symbol` | 根據名稱找到 symbol 定義位置 |
| `find_references` | 找到 symbol 的所有引用 |
| `get_symbol_info` | 取得 symbol 的詳細資訊 |
| `get_file_symbols` | 列出檔案中所有 symbols |
| `get_type_hierarchy` | 取得類別/類型的繼承階層 |

---

## 模組化架構

```
intellij-mcp/
├── settings.gradle.kts
├── build.gradle.kts                    # Root build script
├── gradle.properties
│
├── core/                               # 核心模組（必裝）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── kotlin/info/jiayun/intellijmcp/
│       │   ├── api/                    # 語言適配器介面
│       │   │   ├── LanguageAdapter.kt
│       │   │   ├── LanguageAdapterRegistry.kt
│       │   │   └── Models.kt           # 共用資料結構
│       │   ├── mcp/                    # MCP Server
│       │   │   ├── McpServer.kt
│       │   │   ├── McpProtocol.kt
│       │   │   └── McpToolExecutor.kt
│       │   ├── project/
│       │   │   └── ProjectResolver.kt
│       │   ├── settings/
│       │   │   ├── PluginSettings.kt
│       │   │   └── PluginSettingsConfigurable.kt
│       │   ├── actions/
│       │   │   ├── StartServerAction.kt
│       │   │   ├── StopServerAction.kt
│       │   │   └── CopyConfigAction.kt
│       │   ├── ui/
│       │   │   └── McpStatusWidgetFactory.kt
│       │   └── McpStartupActivity.kt
│       └── resources/META-INF/
│           └── plugin.xml
│
├── lang-python/                        # Python 語言支援
│   ├── build.gradle.kts
│   └── src/main/
│       ├── kotlin/info/jiayun/intellijmcp/python/
│       │   ├── PythonLanguageAdapter.kt
│       │   ├── PythonSymbolFinder.kt
│       │   ├── PythonReferenceFinder.kt
│       │   ├── PythonFileSymbolsExtractor.kt
│       │   └── PythonClassHierarchyProvider.kt
│       └── resources/META-INF/
│           └── plugin.xml
│
├── lang-java/                          # Java 語言支援（未來）
├── lang-kotlin/                        # Kotlin 語言支援（未來）
└── lang-go/                            # Go 語言支援（未來）
```

### 架構圖

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           JetBrains IDE                                 │
│  ┌────────────────────────────────────────────────────────────────────┐│
│  │                        intellij-mcp-core                           ││
│  │  ┌──────────────┐    ┌─────────────────────────────────────────┐  ││
│  │  │  MCP Server  │───▶│         LanguageAdapterRegistry         │  ││
│  │  │  (Port 9876) │    │                                         │  ││
│  │  └──────────────┘    │  ┌─────────┐ ┌─────────┐ ┌─────────┐   │  ││
│  │         │            │  │ Python  │ │  Java   │ │ Kotlin  │   │  ││
│  │         │            │  │ Adapter │ │ Adapter │ │ Adapter │   │  ││
│  │         ▼            │  └────┬────┘ └────┬────┘ └────┬────┘   │  ││
│  │  ┌──────────────┐    └───────┼───────────┼───────────┼────────┘  ││
│  │  │ToolExecutor  │────────────┴───────────┴───────────┘           ││
│  │  └──────────────┘                                                 ││
│  └────────────────────────────────────────────────────────────────────┘│
│                                    │                                    │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐        │
│  │ lang-python     │  │ lang-java       │  │ lang-kotlin     │        │
│  │                 │  │                 │  │                 │        │
│  │ PyClass         │  │ PsiClass        │  │ KtClass         │        │
│  │ PyFunction      │  │ PsiMethod       │  │ KtFunction      │        │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘        │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                            ┌─────────────┐
                            │ Claude Code │
                            └─────────────┘
```

---

## Gradle 配置

### settings.gradle.kts

```kotlin
rootProject.name = "intellij-mcp"

include(":core")
include(":lang-python")
// 未來擴充
// include(":lang-java")
// include(":lang-kotlin")
// include(":lang-go")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

### gradle.properties

```properties
# Plugin 資訊
pluginGroup = info.jiayun.intellijmcp
pluginName = IntelliJ MCP
pluginVersion = 1.0.0
pluginVendor = Jiayun

# IntelliJ Platform
platformVersion = 2024.3
platformType = IU

# Gradle
org.gradle.jvmargs = -Xmx2048m
org.gradle.configuration-cache = true

# Kotlin
kotlin.stdlib.default.dependency = false
```

### build.gradle.kts（Root）

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25" apply false
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false
}

allprojects {
    group = "info.jiayun.intellijmcp"
    version = "1.0.0"
    
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    
    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }
}
```

### core/build.gradle.kts

```kotlin
plugins {
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2024.3")
        
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }
    
    // JSON 處理
    implementation("com.google.code.gson:gson:2.10.1")
    
    // HTTP Server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-gson:2.3.7")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        id = "info.jiayun.intellij-mcp"
        name = "IntelliJ MCP"
        version = project.version.toString()
        
        description = """
            Exposes IDE code analysis capabilities via MCP (Model Context Protocol)
            for integration with AI coding assistants like Claude Code.
            
            <h3>Supported Languages</h3>
            <ul>
                <li>Python (with lang-python module)</li>
                <li>Java (with lang-java module)</li>
                <li>Kotlin (with lang-kotlin module)</li>
            </ul>
        """.trimIndent()
        
        vendor {
            name = "Jiayun"
            url = "https://github.com/jiayun/intellij-mcp"
        }
        
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }
}
```

### lang-python/build.gradle.kts

```kotlin
plugins {
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // 依賴 core 模組
    implementation(project(":core"))
    
    intellijPlatform {
        // 使用 IntelliJ Ultimate + Python Plugin
        intellijIdeaUltimate("2024.3")
        bundledPlugin("PythonCore")
        
        // 或者直接用 PyCharm
        // pycharmProfessional("2024.3")
        
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "info.jiayun.intellij-mcp-python"
        name = "IntelliJ MCP - Python Support"
        version = project.version.toString()
        
        description = "Python language support for IntelliJ MCP"
        
        vendor {
            name = "Jiayun"
        }
        
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "253.*"
        }
    }
}
```

---

## Core 模組實現

### 1. 共用資料結構

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/api/Models.kt

package info.jiayun.intellijmcp.api

/**
 * 位置資訊
 */
data class LocationInfo(
    val filePath: String,
    val line: Int,              // 0-based
    val column: Int,            // 0-based
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val preview: String? = null
)

/**
 * 專案資訊
 */
data class ProjectInfo(
    val name: String,
    val basePath: String,
    val isActive: Boolean
)

/**
 * Symbol 種類
 */
enum class SymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    FUNCTION,
    METHOD,
    PROPERTY,
    FIELD,
    VARIABLE,
    CONSTANT,
    PARAMETER,
    MODULE,
    PACKAGE
}

/**
 * Symbol 資訊
 */
data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val language: String,           // "python", "java", "kotlin", etc.
    val qualifiedName: String? = null,
    val signature: String? = null,
    val documentation: String? = null,
    val location: LocationInfo? = null,
    val returnType: String? = null,
    val parameters: List<ParameterInfo>? = null,
    val modifiers: List<String>? = null,
    val decorators: List<String>? = null,  // Python decorators
    val annotations: List<String>? = null, // Java/Kotlin annotations
    val superTypes: List<String>? = null
)

data class ParameterInfo(
    val name: String,
    val type: String? = null,
    val defaultValue: String? = null,
    val isOptional: Boolean = false
)

/**
 * 檔案中的所有 Symbols
 */
data class FileSymbols(
    val filePath: String,
    val language: String,
    val packageName: String? = null,
    val moduleName: String? = null,
    val imports: List<ImportInfo> = emptyList(),
    val symbols: List<SymbolNode> = emptyList()
)

data class ImportInfo(
    val module: String,
    val names: List<String>? = null,
    val alias: String? = null,
    val location: LocationInfo
)

/**
 * Symbol 樹節點（支援巢狀結構）
 */
data class SymbolNode(
    val symbol: SymbolInfo,
    val children: List<SymbolNode> = emptyList()
)

/**
 * 類型階層
 */
data class TypeHierarchy(
    val typeName: String,
    val qualifiedName: String?,
    val kind: SymbolKind,
    val superTypes: List<TypeRef> = emptyList(),
    val subTypes: List<TypeRef> = emptyList()
)

data class TypeRef(
    val name: String,
    val qualifiedName: String?,
    val location: LocationInfo?
)
```

### 2. 語言適配器介面

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/api/LanguageAdapter.kt

package info.jiayun.intellijmcp.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * 語言適配器介面
 * 
 * 每種語言需要實現此介面來提供 PSI 操作能力
 */
interface LanguageAdapter {
    
    companion object {
        val EP_NAME: ExtensionPointName<LanguageAdapter> = 
            ExtensionPointName.create("info.jiayun.intellij-mcp.languageAdapter")
    }
    
    /**
     * 語言識別碼（如 "python", "java", "kotlin"）
     */
    val languageId: String
    
    /**
     * 語言顯示名稱
     */
    val languageDisplayName: String
    
    /**
     * 支援的檔案副檔名
     */
    val supportedExtensions: Set<String>
    
    /**
     * 檢查是否支援該檔案
     */
    fun supports(file: VirtualFile): Boolean {
        return file.extension?.lowercase() in supportedExtensions
    }
    
    /**
     * 檢查是否支援該 PsiFile
     */
    fun supports(file: PsiFile): Boolean {
        return file.virtualFile?.let { supports(it) } ?: false
    }
    
    /**
     * 根據名稱查找 symbol
     * 
     * @param project 專案
     * @param name symbol 名稱
     * @param kind 可選的 symbol 種類過濾
     * @return 符合條件的 symbol 列表
     */
    fun findSymbol(
        project: Project, 
        name: String, 
        kind: SymbolKind? = null
    ): List<SymbolInfo>
    
    /**
     * 查找 symbol 的所有引用
     * 
     * @param project 專案
     * @param filePath 檔案路徑
     * @param offset 游標位置
     * @return 引用位置列表
     */
    fun findReferences(
        project: Project, 
        filePath: String, 
        offset: Int
    ): List<LocationInfo>
    
    /**
     * 取得指定位置的 symbol 資訊
     * 
     * @param project 專案
     * @param filePath 檔案路徑
     * @param offset 游標位置
     * @return symbol 資訊，如果該位置沒有 symbol 則返回 null
     */
    fun getSymbolInfo(
        project: Project, 
        filePath: String, 
        offset: Int
    ): SymbolInfo?
    
    /**
     * 取得檔案中所有 symbols
     * 
     * @param project 專案
     * @param filePath 檔案路徑
     * @return 檔案的 symbol 結構
     */
    fun getFileSymbols(
        project: Project, 
        filePath: String
    ): FileSymbols
    
    /**
     * 取得類型階層
     * 
     * @param project 專案
     * @param typeName 類型名稱（可以是 qualified name）
     * @return 類型階層資訊，如果找不到則返回 null
     */
    fun getTypeHierarchy(
        project: Project, 
        typeName: String
    ): TypeHierarchy?
    
    /**
     * 將行號列號轉換為 offset
     */
    fun getOffset(
        project: Project, 
        filePath: String, 
        line: Int, 
        column: Int
    ): Int?
}
```

### 3. 語言適配器 Registry

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/api/LanguageAdapterRegistry.kt

package info.jiayun.intellijmcp.api

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.APP)
class LanguageAdapterRegistry {
    
    private val logger = Logger.getInstance(LanguageAdapterRegistry::class.java)
    
    /**
     * 取得所有已註冊的語言適配器
     */
    fun getAllAdapters(): List<LanguageAdapter> {
        return LanguageAdapter.EP_NAME.extensionList
    }
    
    /**
     * 根據檔案取得適配器
     */
    fun getAdapter(file: VirtualFile): LanguageAdapter? {
        return getAllAdapters().find { it.supports(file) }
    }
    
    /**
     * 根據副檔名取得適配器
     */
    fun getAdapterByExtension(extension: String): LanguageAdapter? {
        val ext = extension.lowercase().removePrefix(".")
        return getAllAdapters().find { ext in it.supportedExtensions }
    }
    
    /**
     * 根據語言 ID 取得適配器
     */
    fun getAdapterByLanguage(languageId: String): LanguageAdapter? {
        return getAllAdapters().find { it.languageId == languageId.lowercase() }
    }
    
    /**
     * 取得所有支援的語言
     */
    fun getSupportedLanguages(): List<String> {
        return getAllAdapters().map { it.languageId }
    }
    
    /**
     * 取得所有支援的副檔名
     */
    fun getSupportedExtensions(): Set<String> {
        return getAllAdapters().flatMap { it.supportedExtensions }.toSet()
    }
    
    /**
     * 記錄已載入的適配器（用於除錯）
     */
    fun logLoadedAdapters() {
        val adapters = getAllAdapters()
        if (adapters.isEmpty()) {
            logger.warn("No language adapters loaded!")
        } else {
            logger.info("Loaded ${adapters.size} language adapter(s): ${adapters.map { it.languageId }}")
        }
    }
    
    companion object {
        fun getInstance(): LanguageAdapterRegistry =
            ApplicationManager.getApplication().getService(LanguageAdapterRegistry::class.java)
    }
}
```

### 4. MCP 協議

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/mcp/McpProtocol.kt

package info.jiayun.intellijmcp.mcp

data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: Any,
    val method: String,
    val params: Map<String, Any?>? = null
)

data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: Any,
    val result: Any? = null,
    val error: McpError? = null
)

data class McpError(
    val code: Int,
    val message: String,
    val data: Any? = null
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        
        // 自訂錯誤碼
        const val PROJECT_NOT_FOUND = -32001
        const val FILE_NOT_FOUND = -32002
        const val UNSUPPORTED_LANGUAGE = -32003
        const val INDEX_NOT_READY = -32004
        const val SYMBOL_NOT_FOUND = -32005
    }
}

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)
```

### 5. 專案解析器

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/project/ProjectResolver.kt

package info.jiayun.intellijmcp.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import info.jiayun.intellijmcp.api.ProjectInfo

class ProjectResolver {
    
    /**
     * 解析要操作的專案
     */
    fun resolve(projectPath: String?): Project {
        if (projectPath != null) {
            return findProjectByPath(projectPath)
                ?: throw ProjectNotFoundException("Project not found: $projectPath")
        }
        return getActiveProject()
            ?: throw NoActiveProjectException("No active project")
    }
    
    /**
     * 取得當前活躍的專案
     */
    fun getActiveProject(): Project? {
        val windowManager = WindowManager.getInstance()
        val projects = ProjectManager.getInstance().openProjects
        
        // 找到當前 focus 的視窗對應的專案
        for (project in projects) {
            if (project.isDisposed) continue
            val frame = windowManager.getFrame(project)
            if (frame != null && frame.isActive) {
                return project
            }
        }
        
        // 如果沒有 active 的，返回第一個開啟的
        return projects.firstOrNull { !it.isDisposed }
    }
    
    /**
     * 根據路徑找專案
     */
    fun findProjectByPath(path: String): Project? {
        val normalizedPath = path.trimEnd('/', '\\')
        return ProjectManager.getInstance().openProjects.find { project ->
            !project.isDisposed && 
            project.basePath?.trimEnd('/', '\\') == normalizedPath
        }
    }
    
    /**
     * 列出所有開啟的專案
     */
    fun listProjects(): List<ProjectInfo> {
        val activeProject = getActiveProject()
        return ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .map { project ->
                ProjectInfo(
                    name = project.name,
                    basePath = project.basePath ?: "",
                    isActive = project == activeProject
                )
            }
    }
}

class ProjectNotFoundException(message: String) : Exception(message)
class NoActiveProjectException(message: String) : Exception(message)
```

### 6. Tool 執行器

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/mcp/McpToolExecutor.kt

package info.jiayun.intellijmcp.mcp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import info.jiayun.intellijmcp.api.*
import info.jiayun.intellijmcp.project.ProjectResolver

class McpToolExecutor {
    
    private val projectResolver = ProjectResolver()
    private val registry = LanguageAdapterRegistry.getInstance()
    
    fun listProjects(): List<ProjectInfo> {
        return projectResolver.listProjects()
    }
    
    fun getSupportedLanguages(): List<Map<String, Any>> {
        return registry.getAllAdapters().map { adapter ->
            mapOf(
                "id" to adapter.languageId,
                "name" to adapter.languageDisplayName,
                "extensions" to adapter.supportedExtensions.toList()
            )
        }
    }
    
    fun findSymbol(
        name: String,
        kind: String?,
        language: String?,
        projectPath: String?
    ): List<SymbolInfo> {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)
        
        val symbolKind = kind?.let { parseSymbolKind(it) }
        
        val adapters = if (language != null) {
            listOfNotNull(registry.getAdapterByLanguage(language))
        } else {
            registry.getAllAdapters()
        }
        
        if (adapters.isEmpty()) {
            throw UnsupportedLanguageException(
                language ?: "No language adapters available"
            )
        }
        
        return ReadAction.compute<List<SymbolInfo>, Exception> {
            adapters.flatMap { adapter ->
                adapter.findSymbol(project, name, symbolKind)
            }
        }
    }
    
    fun findReferences(
        filePath: String,
        line: Int,
        column: Int,
        projectPath: String?
    ): List<LocationInfo> {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)
        
        val adapter = getAdapterForFile(filePath)
        
        return ReadAction.compute<List<LocationInfo>, Exception> {
            val offset = adapter.getOffset(project, filePath, line, column)
                ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")
            
            adapter.findReferences(project, filePath, offset)
        }
    }
    
    fun getSymbolInfo(
        filePath: String,
        line: Int,
        column: Int,
        projectPath: String?
    ): SymbolInfo {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)
        
        val adapter = getAdapterForFile(filePath)
        
        return ReadAction.compute<SymbolInfo, Exception> {
            val offset = adapter.getOffset(project, filePath, line, column)
                ?: throw InvalidPositionException("Invalid position: $filePath:$line:$column")
            
            adapter.getSymbolInfo(project, filePath, offset)
                ?: throw SymbolNotFoundException("No symbol found at $filePath:$line:$column")
        }
    }
    
    fun getFileSymbols(
        filePath: String,
        projectPath: String?
    ): FileSymbols {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)
        
        val adapter = getAdapterForFile(filePath)
        
        return ReadAction.compute<FileSymbols, Exception> {
            adapter.getFileSymbols(project, filePath)
        }
    }
    
    fun getTypeHierarchy(
        typeName: String,
        language: String?,
        projectPath: String?
    ): TypeHierarchy {
        val project = projectResolver.resolve(projectPath)
        checkIndexReady(project)
        
        val adapters = if (language != null) {
            listOfNotNull(registry.getAdapterByLanguage(language))
        } else {
            registry.getAllAdapters()
        }
        
        return ReadAction.compute<TypeHierarchy, Exception> {
            adapters.firstNotNullOfOrNull { adapter ->
                adapter.getTypeHierarchy(project, typeName)
            } ?: throw SymbolNotFoundException("Type not found: $typeName")
        }
    }
    
    private fun getAdapterForFile(filePath: String): LanguageAdapter {
        val extension = filePath.substringAfterLast('.', "")
        return registry.getAdapterByExtension(extension)
            ?: throw UnsupportedLanguageException("Unsupported file type: .$extension")
    }
    
    private fun parseSymbolKind(kind: String): SymbolKind {
        return when (kind.lowercase()) {
            "class" -> SymbolKind.CLASS
            "interface" -> SymbolKind.INTERFACE
            "function", "func" -> SymbolKind.FUNCTION
            "method" -> SymbolKind.METHOD
            "variable", "var" -> SymbolKind.VARIABLE
            "field" -> SymbolKind.FIELD
            "property" -> SymbolKind.PROPERTY
            "constant", "const" -> SymbolKind.CONSTANT
            else -> throw IllegalArgumentException("Unknown symbol kind: $kind")
        }
    }
    
    private fun checkIndexReady(project: Project) {
        if (DumbService.getInstance(project).isDumb) {
            throw IndexNotReadyException("IDE is still indexing. Please wait.")
        }
    }
}

class UnsupportedLanguageException(message: String) : Exception(message)
class InvalidPositionException(message: String) : Exception(message)
class SymbolNotFoundException(message: String) : Exception(message)
class IndexNotReadyException(message: String) : Exception(message)
```

### 7. MCP Server

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/mcp/McpServer.kt

package info.jiayun.intellijmcp.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import info.jiayun.intellijmcp.api.*
import info.jiayun.intellijmcp.project.*
import info.jiayun.intellijmcp.settings.PluginSettings

@Service(Service.Level.APP)
class McpServer {
    
    private val logger = Logger.getInstance(McpServer::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val executor = McpToolExecutor()
    
    private var server: NettyApplicationEngine? = null
    
    @Volatile
    var isRunning: Boolean = false
        private set
    
    var port: Int = 9876
        private set
    
    // ===== Tool 定義 =====
    
    private val tools: List<McpToolDefinition> by lazy {
        val languages = LanguageAdapterRegistry.getInstance().getSupportedLanguages()
        val extensions = LanguageAdapterRegistry.getInstance().getSupportedExtensions()
        
        listOf(
            McpToolDefinition(
                name = "list_projects",
                description = "List all currently open projects in the IDE.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>()
                )
            ),
            McpToolDefinition(
                name = "get_supported_languages",
                description = "Get list of supported programming languages.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>()
                )
            ),
            McpToolDefinition(
                name = "find_symbol",
                description = "Find symbol (class, function, variable) definition by name.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf(
                            "type" to "string",
                            "description" to "Symbol name to find"
                        ),
                        "kind" to mapOf(
                            "type" to "string",
                            "enum" to listOf("class", "interface", "function", "method", "variable", "field", "property"),
                            "description" to "Optional: filter by symbol kind"
                        ),
                        "language" to mapOf(
                            "type" to "string",
                            "enum" to languages,
                            "description" to "Optional: limit search to specific language"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path. Uses active project if not provided."
                        )
                    ),
                    "required" to listOf("name")
                )
            ),
            McpToolDefinition(
                name = "find_references",
                description = "Find all references to the symbol at the given location.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "filePath" to mapOf(
                            "type" to "string",
                            "description" to "Absolute file path"
                        ),
                        "line" to mapOf(
                            "type" to "integer",
                            "description" to "0-based line number"
                        ),
                        "column" to mapOf(
                            "type" to "integer",
                            "description" to "0-based column number"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("filePath", "line", "column")
                )
            ),
            McpToolDefinition(
                name = "get_symbol_info",
                description = "Get detailed information about the symbol at the given location.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "filePath" to mapOf(
                            "type" to "string",
                            "description" to "Absolute file path"
                        ),
                        "line" to mapOf(
                            "type" to "integer",
                            "description" to "0-based line number"
                        ),
                        "column" to mapOf(
                            "type" to "integer",
                            "description" to "0-based column number"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("filePath", "line", "column")
                )
            ),
            McpToolDefinition(
                name = "get_file_symbols",
                description = "List all symbols in a file. Supported extensions: ${extensions.joinToString(", ") { ".$it" }}",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "filePath" to mapOf(
                            "type" to "string",
                            "description" to "Absolute file path"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("filePath")
                )
            ),
            McpToolDefinition(
                name = "get_type_hierarchy",
                description = "Get the type hierarchy (base types and subtypes) for a class/interface.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "typeName" to mapOf(
                            "type" to "string",
                            "description" to "Type name (can be qualified name)"
                        ),
                        "language" to mapOf(
                            "type" to "string",
                            "enum" to languages,
                            "description" to "Optional: specify language if type name is ambiguous"
                        ),
                        "projectPath" to mapOf(
                            "type" to "string",
                            "description" to "Optional: project root path"
                        )
                    ),
                    "required" to listOf("typeName")
                )
            )
        )
    }
    
    // ===== Server 生命週期 =====
    
    fun start(port: Int = PluginSettings.getInstance().port) {
        if (isRunning) {
            logger.info("MCP Server is already running on port ${this.port}")
            return
        }
        
        this.port = port
        
        // 記錄已載入的適配器
        LanguageAdapterRegistry.getInstance().logLoadedAdapters()
        
        server = embeddedServer(Netty, port = port) {
            routing {
                get("/health") {
                    call.respondText("OK")
                }
                
                get("/info") {
                    val info = mapOf(
                        "name" to "intellij-mcp",
                        "version" to "1.0.0",
                        "languages" to LanguageAdapterRegistry.getInstance().getSupportedLanguages()
                    )
                    call.respondText(gson.toJson(info), ContentType.Application.Json)
                }
                
                post("/mcp") {
                    val body = call.receiveText()
                    logger.debug("MCP Request: $body")
                    
                    val response = try {
                        val request = gson.fromJson(body, McpRequest::class.java)
                        handleRequest(request)
                    } catch (e: Exception) {
                        logger.error("Failed to parse request", e)
                        McpResponse(
                            id = 0,
                            error = McpError(McpError.PARSE_ERROR, "Parse error: ${e.message}")
                        )
                    }
                    
                    val responseJson = gson.toJson(response)
                    logger.debug("MCP Response: $responseJson")
                    call.respondText(responseJson, ContentType.Application.Json)
                }
                
                get("/sse") {
                    call.response.cacheControl(CacheControl.NoCache(null))
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        write("data: {\"status\": \"connected\"}\n\n")
                        flush()
                        
                        while (isRunning) {
                            delay(30000)
                            write(": keepalive\n\n")
                            flush()
                        }
                    }
                }
            }
        }.start(wait = false)
        
        isRunning = true
        logger.info("MCP Server started on port $port")
    }
    
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        isRunning = false
        logger.info("MCP Server stopped")
    }
    
    // ===== Request 處理 =====
    
    private fun handleRequest(request: McpRequest): McpResponse {
        return when (request.method) {
            "initialize" -> handleInitialize(request)
            "initialized" -> McpResponse(id = request.id, result = emptyMap<String, Any>())
            "tools/list" -> handleToolsList(request)
            "tools/call" -> handleToolCall(request)
            else -> McpResponse(
                id = request.id,
                error = McpError(McpError.METHOD_NOT_FOUND, "Method not found: ${request.method}")
            )
        }
    }
    
    private fun handleInitialize(request: McpRequest): McpResponse {
        return McpResponse(
            id = request.id,
            result = mapOf(
                "protocolVersion" to "2024-11-05",
                "serverInfo" to mapOf(
                    "name" to "intellij-mcp",
                    "version" to "1.0.0"
                ),
                "capabilities" to mapOf(
                    "tools" to mapOf("listChanged" to false)
                )
            )
        )
    }
    
    private fun handleToolsList(request: McpRequest): McpResponse {
        return McpResponse(
            id = request.id,
            result = mapOf("tools" to tools)
        )
    }
    
    private fun handleToolCall(request: McpRequest): McpResponse {
        val params = request.params ?: return McpResponse(
            id = request.id,
            error = McpError(McpError.INVALID_PARAMS, "Missing params")
        )
        
        val toolName = params["name"] as? String ?: return McpResponse(
            id = request.id,
            error = McpError(McpError.INVALID_PARAMS, "Missing tool name")
        )
        
        @Suppress("UNCHECKED_CAST")
        val arguments = params["arguments"] as? Map<String, Any?> ?: emptyMap()
        
        return try {
            val result = executeTool(toolName, arguments)
            McpResponse(
                id = request.id,
                result = mapOf(
                    "content" to listOf(
                        mapOf("type" to "text", "text" to gson.toJson(result))
                    )
                )
            )
        } catch (e: ProjectNotFoundException) {
            McpResponse(id = request.id, error = McpError(McpError.PROJECT_NOT_FOUND, e.message ?: ""))
        } catch (e: UnsupportedLanguageException) {
            McpResponse(id = request.id, error = McpError(McpError.UNSUPPORTED_LANGUAGE, e.message ?: ""))
        } catch (e: IndexNotReadyException) {
            McpResponse(id = request.id, error = McpError(McpError.INDEX_NOT_READY, e.message ?: ""))
        } catch (e: SymbolNotFoundException) {
            McpResponse(id = request.id, error = McpError(McpError.SYMBOL_NOT_FOUND, e.message ?: ""))
        } catch (e: Exception) {
            logger.error("Tool execution failed: $toolName", e)
            McpResponse(id = request.id, error = McpError(McpError.INTERNAL_ERROR, e.message ?: "Unknown error"))
        }
    }
    
    private fun executeTool(name: String, args: Map<String, Any?>): Any {
        return when (name) {
            "list_projects" -> executor.listProjects()
            
            "get_supported_languages" -> executor.getSupportedLanguages()
            
            "find_symbol" -> executor.findSymbol(
                name = args["name"] as String,
                kind = args["kind"] as? String,
                language = args["language"] as? String,
                projectPath = args["projectPath"] as? String
            )
            
            "find_references" -> executor.findReferences(
                filePath = args["filePath"] as String,
                line = (args["line"] as Number).toInt(),
                column = (args["column"] as Number).toInt(),
                projectPath = args["projectPath"] as? String
            )
            
            "get_symbol_info" -> executor.getSymbolInfo(
                filePath = args["filePath"] as String,
                line = (args["line"] as Number).toInt(),
                column = (args["column"] as Number).toInt(),
                projectPath = args["projectPath"] as? String
            )
            
            "get_file_symbols" -> executor.getFileSymbols(
                filePath = args["filePath"] as String,
                projectPath = args["projectPath"] as? String
            )
            
            "get_type_hierarchy" -> executor.getTypeHierarchy(
                typeName = args["typeName"] as String,
                language = args["language"] as? String,
                projectPath = args["projectPath"] as? String
            )
            
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }
    
    companion object {
        fun getInstance(): McpServer =
            ApplicationManager.getApplication().getService(McpServer::class.java)
    }
}
```

### 8. plugin.xml (Core)

```xml
<!-- core/src/main/resources/META-INF/plugin.xml -->
<idea-plugin>
    <id>info.jiayun.intellij-mcp</id>
    <name>IntelliJ MCP</name>
    <vendor url="https://github.com/jiayun/intellij-mcp">Jiayun</vendor>
    
    <description><![CDATA[
        <p>Exposes IDE code analysis capabilities via MCP (Model Context Protocol)
        for integration with AI coding assistants like Claude Code.</p>
        
        <h3>Features</h3>
        <ul>
            <li>Find symbol definitions across the project</li>
            <li>Find all references to a symbol</li>
            <li>Get detailed symbol information (type, documentation, signature)</li>
            <li>List all symbols in a file</li>
            <li>Get type hierarchy (inheritance)</li>
        </ul>
        
        <h3>Language Support</h3>
        <p>Install additional language modules for:</p>
        <ul>
            <li>Python (intellij-mcp-python)</li>
            <li>Java (intellij-mcp-java) - coming soon</li>
            <li>Kotlin (intellij-mcp-kotlin) - coming soon</li>
        </ul>
    ]]></description>

    <!-- 只依賴平台 -->
    <depends>com.intellij.modules.platform</depends>

    <!-- Extension Point：讓語言模組註冊適配器 -->
    <extensionPoints>
        <extensionPoint 
            name="languageAdapter"
            interface="info.jiayun.intellijmcp.api.LanguageAdapter"
            dynamic="true"/>
    </extensionPoints>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Application 級服務 -->
        <applicationService 
            serviceImplementation="info.jiayun.intellijmcp.api.LanguageAdapterRegistry"/>
        <applicationService 
            serviceImplementation="info.jiayun.intellijmcp.mcp.McpServer"/>
        <applicationService 
            serviceImplementation="info.jiayun.intellijmcp.settings.PluginSettings"/>
        
        <!-- 設定頁面 -->
        <applicationConfigurable
            parentId="tools"
            instance="info.jiayun.intellijmcp.settings.PluginSettingsConfigurable"
            id="info.jiayun.intellijmcp.settings"
            displayName="IntelliJ MCP"/>
        
        <!-- 狀態列 -->
        <statusBarWidgetFactory
            id="IntellijMcpStatus"
            implementation="info.jiayun.intellijmcp.ui.McpStatusWidgetFactory"/>
        
        <!-- 啟動活動 -->
        <postStartupActivity 
            implementation="info.jiayun.intellijmcp.McpStartupActivity"/>
        
        <!-- 通知 -->
        <notificationGroup 
            id="IntelliJ MCP" 
            displayType="BALLOON"/>
    </extensions>

    <actions>
        <group id="IntellijMcp.Menu" text="IntelliJ MCP" popup="true">
            <add-to-group group-id="ToolsMenu" anchor="last"/>
            
            <action id="IntellijMcp.StartServer"
                    class="info.jiayun.intellijmcp.actions.StartServerAction"
                    text="Start MCP Server"/>
            
            <action id="IntellijMcp.StopServer"
                    class="info.jiayun.intellijmcp.actions.StopServerAction"
                    text="Stop MCP Server"/>
            
            <separator/>
            
            <action id="IntellijMcp.CopyConfig"
                    class="info.jiayun.intellijmcp.actions.CopyConfigAction"
                    text="Copy Claude Code Config"/>
        </group>
    </actions>
</idea-plugin>
```

---

## Python 語言模組實現

### 1. Python 語言適配器

```kotlin
// lang-python/src/main/kotlin/info/jiayun/intellijmcp/python/PythonLanguageAdapter.kt

package info.jiayun.intellijmcp.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import com.jetbrains.python.psi.stubs.PyFunctionNameIndex
import com.jetbrains.python.psi.stubs.PyVariableNameIndex
import com.jetbrains.python.psi.search.PyClassInheritorsSearch
import info.jiayun.intellijmcp.api.*

class PythonLanguageAdapter : LanguageAdapter {
    
    override val languageId = "python"
    override val languageDisplayName = "Python"
    override val supportedExtensions = setOf("py", "pyi")
    
    // ===== Find Symbol =====
    
    override fun findSymbol(
        project: Project,
        name: String,
        kind: SymbolKind?
    ): List<SymbolInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolInfo>()
        
        // 查找 Class
        if (kind == null || kind == SymbolKind.CLASS) {
            PyClassNameIndex.find(name, project, scope).forEach { pyClass ->
                results.add(buildClassInfo(project, pyClass))
            }
        }
        
        // 查找 Function
        if (kind == null || kind == SymbolKind.FUNCTION || kind == SymbolKind.METHOD) {
            PyFunctionNameIndex.find(name, project, scope).forEach { pyFunc ->
                results.add(buildFunctionInfo(project, pyFunc))
            }
        }
        
        // 查找 Variable
        if (kind == null || kind == SymbolKind.VARIABLE) {
            PyVariableNameIndex.find(name, project, scope).forEach { pyVar ->
                results.add(buildVariableInfo(project, pyVar))
            }
        }
        
        return results
    }
    
    // ===== Find References =====
    
    override fun findReferences(
        project: Project,
        filePath: String,
        offset: Int
    ): List<LocationInfo> {
        val pyFile = getPyFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")
        
        val element = pyFile.findElementAt(offset)
            ?: throw IllegalArgumentException("No element at offset")
        
        val targetElement = findMeaningfulElement(element)
            ?: throw IllegalArgumentException("No symbol at position")
        
        val scope = GlobalSearchScope.projectScope(project)
        return ReferencesSearch.search(targetElement, scope)
            .findAll()
            .mapNotNull { ref -> getLocation(project, ref.element) }
    }
    
    // ===== Get Symbol Info =====
    
    override fun getSymbolInfo(
        project: Project,
        filePath: String,
        offset: Int
    ): SymbolInfo? {
        val pyFile = getPyFile(project, filePath) ?: return null
        val element = pyFile.findElementAt(offset) ?: return null
        val targetElement = findMeaningfulElement(element) ?: return null
        
        return when (targetElement) {
            is PyClass -> buildClassInfo(project, targetElement)
            is PyFunction -> buildFunctionInfo(project, targetElement)
            is PyTargetExpression -> buildVariableInfo(project, targetElement)
            is PyNamedParameter -> buildParameterInfo(project, targetElement)
            else -> null
        }
    }
    
    // ===== Get File Symbols =====
    
    override fun getFileSymbols(
        project: Project,
        filePath: String
    ): FileSymbols {
        val pyFile = getPyFile(project, filePath)
            ?: throw IllegalArgumentException("File not found: $filePath")
        
        val imports = mutableListOf<ImportInfo>()
        val symbols = mutableListOf<SymbolNode>()
        
        // 提取 imports
        pyFile.importTargets.forEach { target ->
            getLocation(project, target)?.let { loc ->
                imports.add(ImportInfo(
                    module = target.importedQName?.toString() ?: "",
                    names = null,
                    alias = target.asName,
                    location = loc
                ))
            }
        }
        
        pyFile.fromImports.forEach { fromImport ->
            getLocation(project, fromImport)?.let { loc ->
                imports.add(ImportInfo(
                    module = fromImport.importSourceQName?.toString() ?: "",
                    names = fromImport.importElements.mapNotNull { it.visibleName },
                    alias = null,
                    location = loc
                ))
            }
        }
        
        // 提取頂層 classes
        pyFile.topLevelClasses.forEach { pyClass ->
            symbols.add(buildClassNode(project, pyClass))
        }
        
        // 提取頂層 functions
        pyFile.topLevelFunctions.forEach { pyFunc ->
            symbols.add(SymbolNode(buildFunctionInfo(project, pyFunc)))
        }
        
        // 提取頂層 variables
        pyFile.topLevelAttributes.forEach { attr ->
            symbols.add(SymbolNode(buildVariableInfo(project, attr)))
        }
        
        return FileSymbols(
            filePath = filePath,
            language = languageId,
            moduleName = pyFile.name.removeSuffix(".py").removeSuffix(".pyi"),
            imports = imports,
            symbols = symbols
        )
    }
    
    // ===== Get Type Hierarchy =====
    
    override fun getTypeHierarchy(
        project: Project,
        typeName: String
    ): TypeHierarchy? {
        val scope = GlobalSearchScope.projectScope(project)
        
        // 找到類
        val classes = PyClassNameIndex.find(typeName.substringAfterLast('.'), project, scope)
        val pyClass = classes.find { it.qualifiedName == typeName }
            ?: classes.firstOrNull()
            ?: return null
        
        // 取得父類
        val superTypes = pyClass.getSuperClasses(null).mapNotNull { superClass ->
            TypeRef(
                name = superClass.name ?: return@mapNotNull null,
                qualifiedName = superClass.qualifiedName,
                location = getLocation(project, superClass)
            )
        }
        
        // 取得子類
        val subTypes = PyClassInheritorsSearch.search(pyClass, true)
            .findAll()
            .mapNotNull { subClass ->
                TypeRef(
                    name = subClass.name ?: return@mapNotNull null,
                    qualifiedName = subClass.qualifiedName,
                    location = getLocation(project, subClass)
                )
            }
        
        return TypeHierarchy(
            typeName = pyClass.name ?: "",
            qualifiedName = pyClass.qualifiedName,
            kind = SymbolKind.CLASS,
            superTypes = superTypes,
            subTypes = subTypes
        )
    }
    
    // ===== Get Offset =====
    
    override fun getOffset(
        project: Project,
        filePath: String,
        line: Int,
        column: Int
    ): Int? {
        val pyFile = getPyFile(project, filePath) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(pyFile)
            ?: return null
        
        if (line < 0 || line >= document.lineCount) return null
        
        val lineStartOffset = document.getLineStartOffset(line)
        val lineEndOffset = document.getLineEndOffset(line)
        val offset = lineStartOffset + column
        
        return if (offset <= lineEndOffset) offset else null
    }
    
    // ===== Helper Methods =====
    
    private fun getPyFile(project: Project, filePath: String): PyFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? PyFile
    }
    
    private fun findMeaningfulElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PyClass,
                is PyFunction,
                is PyTargetExpression,
                is PyNamedParameter,
                is PyReferenceExpression -> return current
            }
            current = current.parent
        }
        return null
    }
    
    private fun getLocation(project: Project, element: PsiElement): LocationInfo? {
        val file = element.containingFile?.virtualFile?.path ?: return null
        val document = PsiDocumentManager.getInstance(project)
            .getDocument(element.containingFile) ?: return null
        
        val startOffset = element.textRange.startOffset
        val endOffset = element.textRange.endOffset
        
        val startLine = document.getLineNumber(startOffset)
        val startColumn = startOffset - document.getLineStartOffset(startLine)
        val endLine = document.getLineNumber(endOffset)
        val endColumn = endOffset - document.getLineStartOffset(endLine)
        
        val preview = element.text.take(100).let {
            if (element.text.length > 100) "$it..." else it
        }
        
        return LocationInfo(
            filePath = file,
            line = startLine,
            column = startColumn,
            endLine = endLine,
            endColumn = endColumn,
            preview = preview
        )
    }
    
    private fun buildClassInfo(project: Project, pyClass: PyClass): SymbolInfo {
        return SymbolInfo(
            name = pyClass.name ?: "",
            kind = SymbolKind.CLASS,
            language = languageId,
            qualifiedName = pyClass.qualifiedName,
            documentation = pyClass.docStringValue,
            location = getLocation(project, pyClass),
            decorators = pyClass.decoratorList?.decorators?.map { it.text },
            superTypes = pyClass.superClassExpressions.mapNotNull { it.text }
        )
    }
    
    private fun buildFunctionInfo(project: Project, pyFunc: PyFunction): SymbolInfo {
        val isMethod = pyFunc.containingClass != null
        
        return SymbolInfo(
            name = pyFunc.name ?: "",
            kind = if (isMethod) SymbolKind.METHOD else SymbolKind.FUNCTION,
            language = languageId,
            qualifiedName = pyFunc.qualifiedName,
            signature = buildSignature(pyFunc),
            documentation = pyFunc.docStringValue,
            location = getLocation(project, pyFunc),
            returnType = pyFunc.annotation?.text,
            parameters = pyFunc.parameterList.parameters.map { param ->
                ParameterInfo(
                    name = param.name ?: "",
                    type = (param as? PyNamedParameter)?.annotation?.text,
                    defaultValue = (param as? PyNamedParameter)?.defaultValue?.text,
                    isOptional = (param as? PyNamedParameter)?.hasDefaultValue() ?: false
                )
            },
            decorators = pyFunc.decoratorList?.decorators?.map { it.text }
        )
    }
    
    private fun buildVariableInfo(project: Project, pyVar: PyTargetExpression): SymbolInfo {
        return SymbolInfo(
            name = pyVar.name ?: "",
            kind = SymbolKind.VARIABLE,
            language = languageId,
            qualifiedName = pyVar.qualifiedName,
            location = getLocation(project, pyVar),
            returnType = pyVar.annotation?.text
        )
    }
    
    private fun buildParameterInfo(project: Project, param: PyNamedParameter): SymbolInfo {
        return SymbolInfo(
            name = param.name ?: "",
            kind = SymbolKind.PARAMETER,
            language = languageId,
            location = getLocation(project, param),
            returnType = param.annotation?.text
        )
    }
    
    private fun buildClassNode(project: Project, pyClass: PyClass): SymbolNode {
        val children = mutableListOf<SymbolNode>()
        
        // Methods
        pyClass.methods.forEach { method ->
            children.add(SymbolNode(buildFunctionInfo(project, method)))
        }
        
        // Class attributes
        pyClass.classAttributes.forEach { attr ->
            children.add(SymbolNode(buildVariableInfo(project, attr)))
        }
        
        // Nested classes
        pyClass.nestedClasses.forEach { nested ->
            children.add(buildClassNode(project, nested))
        }
        
        return SymbolNode(
            symbol = buildClassInfo(project, pyClass),
            children = children
        )
    }
    
    private fun buildSignature(pyFunc: PyFunction): String {
        val asyncPrefix = if (pyFunc.isAsync) "async " else ""
        val params = pyFunc.parameterList.parameters.joinToString(", ") { param ->
            buildString {
                append(param.name ?: "_")
                (param as? PyNamedParameter)?.annotation?.let { append(": ${it.text}") }
                (param as? PyNamedParameter)?.defaultValue?.let { append(" = ${it.text}") }
            }
        }
        val returnType = pyFunc.annotation?.let { " -> ${it.text}" } ?: ""
        return "${asyncPrefix}def ${pyFunc.name}($params)$returnType"
    }
}
```

### 2. plugin.xml (Python Module)

```xml
<!-- lang-python/src/main/resources/META-INF/plugin.xml -->
<idea-plugin>
    <id>info.jiayun.intellij-mcp-python</id>
    <name>IntelliJ MCP - Python Support</name>
    <vendor>Jiayun</vendor>
    
    <description><![CDATA[
        Python language support for IntelliJ MCP.
        Provides Python-specific code analysis capabilities.
    ]]></description>

    <!-- 依賴核心模組 -->
    <depends>info.jiayun.intellij-mcp</depends>
    
    <!-- 依賴 Python 支援 -->
    <depends>com.intellij.modules.python</depends>

    <!-- 註冊 Python 適配器 -->
    <extensions defaultExtensionNs="info.jiayun.intellij-mcp">
        <languageAdapter 
            implementation="info.jiayun.intellijmcp.python.PythonLanguageAdapter"/>
    </extensions>
</idea-plugin>
```

---

## 其他必要檔案

### Settings

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/settings/PluginSettings.kt

package info.jiayun.intellijmcp.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "IntellijMcpSettings",
    storages = [Storage("intellij-mcp.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {
    
    data class State(
        var autoStart: Boolean = true,
        var port: Int = 9876
    )
    
    private var state = State()
    
    override fun getState() = state
    override fun loadState(state: State) { this.state = state }
    
    var autoStart: Boolean
        get() = state.autoStart
        set(value) { state.autoStart = value }
    
    var port: Int
        get() = state.port
        set(value) { state.port = value }
    
    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
```

### Startup Activity

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/McpStartupActivity.kt

package info.jiayun.intellijmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import info.jiayun.intellijmcp.mcp.McpServer
import info.jiayun.intellijmcp.settings.PluginSettings

class McpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = PluginSettings.getInstance()
        val server = McpServer.getInstance()
        
        if (settings.autoStart && !server.isRunning) {
            server.start(settings.port)
        }
    }
}
```

### Actions

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/actions/StartServerAction.kt

package info.jiayun.intellijmcp.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import info.jiayun.intellijmcp.mcp.McpServer
import info.jiayun.intellijmcp.settings.PluginSettings

class StartServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val server = McpServer.getInstance()
        if (server.isRunning) {
            notify(e, "MCP Server already running on port ${server.port}", NotificationType.INFORMATION)
            return
        }
        server.start(PluginSettings.getInstance().port)
        notify(e, "MCP Server started on port ${server.port}", NotificationType.INFORMATION)
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !McpServer.getInstance().isRunning
    }
    
    private fun notify(e: AnActionEvent, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("IntelliJ MCP")
            .createNotification(message, type)
            .notify(e.project)
    }
}
```

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/actions/StopServerAction.kt

package info.jiayun.intellijmcp.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import info.jiayun.intellijmcp.mcp.McpServer

class StopServerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val server = McpServer.getInstance()
        if (!server.isRunning) {
            notify(e, "MCP Server is not running", NotificationType.INFORMATION)
            return
        }
        server.stop()
        notify(e, "MCP Server stopped", NotificationType.INFORMATION)
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = McpServer.getInstance().isRunning
    }
    
    private fun notify(e: AnActionEvent, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("IntelliJ MCP")
            .createNotification(message, type)
            .notify(e.project)
    }
}
```

```kotlin
// core/src/main/kotlin/info/jiayun/intellijmcp/actions/CopyConfigAction.kt

package info.jiayun.intellijmcp.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import info.jiayun.intellijmcp.settings.PluginSettings
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyConfigAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val port = PluginSettings.getInstance().port
        val config = "claude mcp add intellij-mcp --transport http http://localhost:$port/mcp"
        
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(config), null)
        
        NotificationGroupManager.getInstance()
            .getNotificationGroup("IntelliJ MCP")
            .createNotification("Claude Code config copied!", NotificationType.INFORMATION)
            .notify(e.project)
    }
}
```

---

## Claude Code 整合

### 添加 MCP Server

```bash
claude mcp add intellij-mcp --transport http http://localhost:9876/mcp
```

### 使用範例

```
> 列出支援的語言
[get_supported_languages]
→ [{"id": "python", "name": "Python", "extensions": ["py", "pyi"]}]

> 找到 UserService 類
[find_symbol { name: "UserService", kind: "class" }]

> 這個方法被哪些地方使用？
[find_references { filePath: "/path/to/file.py", line: 25, column: 8 }]

> 顯示這個檔案的結構
[get_file_symbols { filePath: "/path/to/models.py" }]
```

---

## 開發命令

```bash
# 執行測試 IDE
./gradlew :core:runIde
./gradlew :lang-python:runIde

# 建置 Plugin
./gradlew buildPlugin

# 驗證 Plugin
./gradlew verifyPlugin
```

---

## 未來擴充

新增語言只需：

1. 建立 `lang-xxx/` 目錄
2. 實現 `XxxLanguageAdapter : LanguageAdapter`
3. 在 `plugin.xml` 中註冊

```kotlin
// lang-java/src/.../JavaLanguageAdapter.kt
class JavaLanguageAdapter : LanguageAdapter {
    override val languageId = "java"
    override val languageDisplayName = "Java"
    override val supportedExtensions = setOf("java")
    // ... 實現各方法
}
```

```xml
<!-- lang-java plugin.xml -->
<depends>info.jiayun.intellij-mcp</depends>
<depends>com.intellij.modules.java</depends>

<extensions defaultExtensionNs="info.jiayun.intellij-mcp">
    <languageAdapter implementation="info.jiayun.intellijmcp.java.JavaLanguageAdapter"/>
</extensions>
```
