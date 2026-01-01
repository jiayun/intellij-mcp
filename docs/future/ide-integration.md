# 將 Swift Symbol 整合到 IDE Find Symbol 功能

> **狀態**: 可行性研究完成，待實現

## 背景

目前 Swift 支援已透過 SourceKit-LSP 實現，但只能透過 MCP 工具使用。此計畫旨在將 Swift symbols 整合到 IDE 的 Find Symbol (Cmd+Shift+O) 對話框。

## 可行性分析

### IntelliJ Platform 擴展點

| 擴展點 | 用途 |
|--------|------|
| `com.intellij.gotoSymbolContributor` | 貢獻 symbols 到 Go to Symbol 對話框 |
| `com.intellij.gotoClassContributor` | 貢獻 classes 到 Go to Class 對話框 |

### 需要實現的介面

```kotlin
interface ChooseByNameContributorEx {
    // 提供所有 symbol 名稱（用於搜尋）
    fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?
    )

    // 根據名稱返回 NavigationItem
    fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters
    )
}
```

### 挑戰

1. **效能問題**：`processNames()` 會頻繁調用，需要快取
2. **NavigationItem 包裝**：IDE 期望 PSI 元素，需要創建自訂 NavigationItem
3. **非同步 LSP**：SourceKit-LSP 是非同步的，但 processNames 需要同步返回
4. **索引等待**：首次打開專案時 LSP 需要索引時間

---

## 設計方案

### 架構

```
IDE Find Symbol Dialog
    │
    ▼
SwiftSymbolContributor (implements ChooseByNameContributorEx)
    │
    ├── SwiftSymbolCache (快取 symbol 名稱)
    │       │
    │       └── 背景更新 ← SwiftLspClient.workspaceSymbol()
    │
    └── SwiftNavigationItem (implements NavigationItem)
            │
            └── 導航到檔案位置
```

### 新增檔案

```
core/src/main/kotlin/info/jiayun/intellijmcp/swift/
├── SwiftSymbolContributor.kt   # IDE 整合
├── SwiftSymbolCache.kt         # Symbol 快取
└── SwiftNavigationItem.kt      # NavigationItem 實現
```

---

## 實現步驟

### Phase 1：建立 Symbol 快取

建立 `SwiftSymbolCache` 管理 symbol 索引：

```kotlin
class SwiftSymbolCache(private val project: Project) : Disposable {
    private val symbolNames = ConcurrentHashMap<String, List<SymbolInfo>>()

    // 背景更新 symbol 清單
    fun refreshAsync() { ... }

    // 同步取得 symbol 名稱
    fun getSymbolNames(): Set<String>

    // 根據名稱取得 symbols
    fun getSymbols(name: String): List<SymbolInfo>
}
```

### Phase 2：實現 NavigationItem

建立 `SwiftNavigationItem` 包裝 SymbolInfo：

```kotlin
class SwiftNavigationItem(
    private val symbolInfo: SymbolInfo,
    private val project: Project
) : NavigationItem, ItemPresentation {

    override fun getName() = symbolInfo.name

    override fun navigate(requestFocus: Boolean) {
        // 使用 OpenFileDescriptor 導航到檔案位置
        val location = symbolInfo.location ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(location.filePath)
        file?.let {
            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, it, location.line - 1, location.column - 1), requestFocus)
        }
    }

    override fun getPresentation() = this
    override fun getPresentableText() = symbolInfo.name
    override fun getLocationString() = symbolInfo.location?.filePath?.substringAfterLast('/')
    override fun getIcon(unused: Boolean) = getIconForKind(symbolInfo.kind)
}
```

### Phase 3：實現 SymbolContributor

建立 `SwiftSymbolContributor`：

```kotlin
class SwiftSymbolContributor : ChooseByNameContributorEx {

    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?
    ) {
        val project = scope.project ?: return
        if (!SwiftLspClient.isAvailable()) return

        val cache = project.getService(SwiftSymbolCache::class.java)
        cache.getSymbolNames().forEach { processor.process(it) }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters
    ) {
        val project = parameters.project
        if (!SwiftLspClient.isAvailable()) return

        val cache = project.getService(SwiftSymbolCache::class.java)
        cache.getSymbols(name).forEach { symbolInfo ->
            processor.process(SwiftNavigationItem(symbolInfo, project))
        }
    }
}
```

### Phase 4：註冊擴展

在 `plugin.xml` 添加：

```xml
<extensions defaultExtensionNs="com.intellij">
    <!-- Swift symbol navigation -->
    <gotoSymbolContributor
        implementation="info.jiayun.intellijmcp.swift.SwiftSymbolContributor"/>

    <!-- Swift symbol cache as project service -->
    <projectService
        serviceImplementation="info.jiayun.intellijmcp.swift.SwiftSymbolCache"/>
</extensions>
```

---

## 效能考量

1. **快取策略**：
   - 專案開啟時背景索引
   - 檔案變更時增量更新
   - 使用 ConcurrentHashMap 支援並行存取

2. **索引觸發時機**：
   - 專案開啟後延遲 5 秒開始
   - 使用 `DumbService.getInstance(project).smartInvokeLater` 等待 IDE 索引完成

3. **LSP 請求優化**：
   - 批次請求 symbol
   - 設定合理的超時時間
   - 錯誤時使用快取的舊資料

---

## 已知限制

1. **首次開啟延遲**：LSP 索引需要 10-30 秒
2. **僅限 macOS**：SourceKit-LSP 只在 macOS 上可用
3. **需要 SwiftPM 專案**：最佳支援 Package.swift 專案

---

## 測試計劃

1. 開啟 Swift 專案
2. 等待索引完成（觀察 IDE 日誌）
3. 按 Cmd+Shift+O 開啟 Find Symbol
4. 輸入 Swift symbol 名稱
5. 驗證能搜尋到並導航到正確位置

---

## 參考檔案

- `core/src/main/kotlin/info/jiayun/intellijmcp/swift/SwiftLspClient.kt` - 現有 LSP 客戶端
- `core/src/main/kotlin/info/jiayun/intellijmcp/swift/SwiftLanguageAdapter.kt` - 現有適配器
- `core/src/main/kotlin/info/jiayun/intellijmcp/api/Models.kt` - SymbolInfo 模型
