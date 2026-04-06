# Test Execution & Results MCP Tools

> **狀態**: 可行性研究完成，待實現
> **對應 Issue**: [#5 - Please add MCP support for running tests from the IDE](https://github.com/jiayun/intellij-mcp/issues/5)

## 背景

目前 intellij-mcp 提供靜態程式碼分析能力（symbol search、references、type hierarchy 等），但缺乏執行與驗證的能力。Issue #5 請求加入測試執行支援，讓 AI agent 能完成完整的 edit → test → iterate 工作流。

### 設計方向

不自行實現測試框架的適配（JUnit、pytest、Jest 等差異過大），而是：

1. **列出 IDE 已定義的 Run Configuration** — 利用 IDE 已配好的測試執行設定
2. **執行 Run Configuration** — 透過 IDE 內建的執行框架觸發，語言/框架無關
3. **取得結構化測試結果** — 從 IDE 的 SM Test Runner 讀取樹狀測試結果

### 與 JetBrains 官方 MCP 的差異

JetBrains 從 2025.2 起內建 MCP Server，已有 `get_run_configurations` 和 `execute_run_configuration` 工具。

| 面向 | JetBrains 官方 MCP | intellij-mcp（本方案） |
|------|-------------------|----------------------|
| 列出 Run Configurations | `get_run_configurations` | `list_run_configurations`（同等功能） |
| 執行 Configuration | `execute_run_configuration` — 回傳 exit code + raw output | `run_configuration` — 回傳 exit code + raw output |
| **結構化測試結果** | **無** | **`get_test_results`** — 結構化 test tree |
| 結果內容 | 純文字 stdout/stderr | test name、status、duration、failure message、stack trace |

**核心差異化**：`get_test_results` 提供結構化測試結果，agent 能精確知道哪個 test 失敗及原因，不需 parse raw output。

---

## IntelliJ Platform API 分析

### 1. RunManager — 列出與查找 Run Configuration

```kotlin
// 取得 RunManager
val runManager = RunManager.getInstance(project)

// 列出所有 configurations
val allSettings: List<RunnerAndConfigurationSettings> = runManager.allSettings

// 依名稱查找
val settings = runManager.findConfigurationByName("MyTestConfig")

// 每個 RunnerAndConfigurationSettings 提供：
settings.name           // 名稱
settings.type           // ConfigurationType（類型：JUnit、pytest、npm 等）
settings.isTemporary     // 是否為臨時 configuration
settings.isShared        // 是否共享（存於 .idea/runConfigurations）
settings.configuration   // 底層 RunConfiguration 物件
```

### 2. ExecutionManager — 執行 Configuration

```kotlin
// 取得 Executor（Run 模式）
val executor = DefaultRunExecutor.getRunExecutorInstance()

// 建立 ExecutionEnvironment
val environment = ExecutionEnvironmentBuilder
    .create(executor, settings)
    .build()

// 執行
ProgramRunnerUtil.executeConfiguration(environment, false)
```

**非同步處理**：執行是非同步的，需透過 listener 等待完成。

```kotlin
// 方式一：ProcessHandler.waitFor()（阻塞）
processHandler.waitFor(timeoutMs)
val exitCode = processHandler.exitCode

// 方式二：ExecutionListener（事件驅動）
project.messageBus.connect(disposable).subscribe(
    ExecutionManager.EXECUTION_TOPIC,
    object : ExecutionListener {
        override fun processTerminated(
            executorId: String,
            env: ExecutionEnvironment,
            handler: ProcessHandler,
            exitCode: Int
        ) {
            // 執行完成
        }
    }
)
```

### 3. SM Test Runner — 結構化測試結果

IntelliJ 所有測試框架（JUnit、TestNG、pytest、Jest、go test 等）都透過統一的 SM Test Runner 框架呈現結果。核心類別：

#### AbstractTestProxy / SMTestProxy

測試結果以樹狀結構存放：

```
SMTestProxy.SMRootTestProxy (root)
├── SMTestProxy (suite: "com.example.UserServiceTest")
│   ├── SMTestProxy (test: "testCreateUser")      → PASSED
│   ├── SMTestProxy (test: "testDeleteUser")       → FAILED
│   └── SMTestProxy (test: "testUpdateUser")       → IGNORED
└── SMTestProxy (suite: "com.example.AuthTest")
    └── SMTestProxy (test: "testLogin")            → PASSED
```

每個節點提供：

```kotlin
proxy.name                    // 測試名稱
proxy.isPassed                // 是否通過
proxy.isDefect                // 是否失敗/錯誤
proxy.isIgnored               // 是否被忽略
proxy.isInProgress            // 是否執行中
proxy.duration                // 耗時（毫秒）
proxy.errorMessage            // 失敗訊息（null if passed）
proxy.stacktrace              // Stack trace（null if passed）
proxy.isSuite                 // 是否為 suite 節點
proxy.children                // 子節點列表
proxy.allTests                // 遞迴取得所有 leaf tests
```

#### SMTRunnerEventsListener — 監聽測試完成

```kotlin
project.messageBus.connect(disposable).subscribe(
    SMTRunnerEventsListener.TEST_STATUS,
    object : SMTRunnerEventsAdapter() {
        override fun onTestingFinished(testsRoot: TestResultsViewer.Data) {
            // testsRoot 包含完整的測試結果樹
            val root = testsRoot as? SMTestProxy.SMRootTestProxy
            // 遍歷結果...
        }
    }
)
```

### 4. 挑戰與解決方案

| 挑戰 | 說明 | 解決方案 |
|------|------|---------|
| 非同步執行 | MCP 是 request/response，測試執行需要時間 | `run_configuration` 使用 `ProcessHandler.waitFor(timeout)` 阻塞等待 |
| 結果關聯 | 如何將 SMTestProxy 結果與特定執行關聯 | 透過 ExecutionListener 捕獲 ProcessHandler，再從 RunContentDescriptor 取得 console |
| 執行緒安全 | MCP 在 Ktor 線程，IDE 操作需在 EDT | 使用 `ApplicationManager.getApplication().invokeAndWait {}` 切換到 EDT |
| 結果時序 | `run_configuration` 回傳時結果可能尚未完全解析 | `get_test_results` 獨立呼叫，允許 agent 在執行後延遲取得 |
| 多次執行 | 多個測試同時執行，結果如何區分 | 以 execution ID 或 configuration name + timestamp 標識 |

---

## 工具設計

### Tool 1: `list_run_configurations`

列出 IDE 中已定義的所有 Run Configuration。

**Parameters:**

| 參數 | 類型 | 必填 | 說明 |
|------|------|------|------|
| `projectPath` | string | 否 | 專案路徑，未指定時使用當前活躍專案 |
| `type` | string | 否 | 過濾類型（如 `"JUnit"`, `"pytest"`, `"npm"`） |

**Response:**

```json
[
  {
    "name": "UserServiceTest",
    "type": "JUnit",
    "isTemporary": false,
    "isShared": true
  },
  {
    "name": "All Tests",
    "type": "Gradle",
    "isTemporary": false,
    "isShared": true
  }
]
```

### Tool 2: `run_configuration`

執行指定的 Run Configuration 並等待完成。

**Parameters:**

| 參數 | 類型 | 必填 | 說明 |
|------|------|------|------|
| `name` | string | 是 | Configuration 名稱 |
| `projectPath` | string | 否 | 專案路徑 |
| `timeout` | number | 否 | 超時毫秒數（預設 60000） |

**Response:**

```json
{
  "exitCode": 1,
  "success": false,
  "timedOut": false,
  "executionId": "UserServiceTest-1704067200000"
}
```

> 注意：不回傳 raw output（過於冗長且非結構化）。agent 應使用 `get_test_results` 取得結構化結果。

### Tool 3: `get_test_results`

取得最近一次（或指定）測試執行的結構化結果。

**Parameters:**

| 參數 | 類型 | 必填 | 說明 |
|------|------|------|------|
| `projectPath` | string | 否 | 專案路徑 |
| `executionId` | string | 否 | 特定執行 ID（由 `run_configuration` 回傳），未指定時回傳最近一次 |
| `includeOutput` | boolean | 否 | 是否包含 stdout/stderr（預設 false，避免過大） |
| `failedOnly` | boolean | 否 | 只回傳失敗的測試（預設 false） |

**Response:**

```json
{
  "executionId": "UserServiceTest-1704067200000",
  "configurationName": "UserServiceTest",
  "status": "FAILED",
  "totalTests": 3,
  "passed": 1,
  "failed": 1,
  "ignored": 1,
  "duration": 2340,
  "tests": [
    {
      "name": "testCreateUser",
      "suite": "com.example.UserServiceTest",
      "status": "PASSED",
      "duration": 120
    },
    {
      "name": "testDeleteUser",
      "suite": "com.example.UserServiceTest",
      "status": "FAILED",
      "duration": 45,
      "errorMessage": "Expected 204 but got 404",
      "stackTrace": "at com.example.UserServiceTest.testDeleteUser(UserServiceTest.java:42)..."
    },
    {
      "name": "testUpdateUser",
      "suite": "com.example.UserServiceTest",
      "status": "IGNORED",
      "duration": 0
    }
  ]
}
```

---

## 資料模型

新增至 `Models.kt`：

```kotlin
// Run Configuration 資訊
data class RunConfigurationInfo(
    val name: String,
    val type: String,
    val isTemporary: Boolean = false,
    val isShared: Boolean = false
)

// 執行結果（不含測試細節）
data class ExecutionResult(
    val exitCode: Int,
    val success: Boolean,
    val timedOut: Boolean = false,
    val executionId: String
)

// 測試結果摘要
data class TestResults(
    val executionId: String,
    val configurationName: String,
    val status: TestRunStatus,
    val totalTests: Int,
    val passed: Int,
    val failed: Int,
    val ignored: Int,
    val duration: Long,
    val tests: List<TestCaseResult>
)

enum class TestRunStatus {
    PASSED, FAILED, ERROR, IN_PROGRESS, TERMINATED
}

// 單一測試結果
data class TestCaseResult(
    val name: String,
    val suite: String? = null,
    val status: TestCaseStatus,
    val duration: Long,
    val errorMessage: String? = null,
    val stackTrace: String? = null,
    val output: String? = null  // 僅 includeOutput=true 時填入
)

enum class TestCaseStatus {
    PASSED, FAILED, ERROR, IGNORED, IN_PROGRESS
}
```

---

## 架構設計

### 新增元件

```
core/src/main/kotlin/info/jiayun/intellijmcp/
├── execution/
│   ├── RunConfigurationService.kt    # 列出、查找、執行 Run Configuration
│   └── TestResultCollector.kt        # 從 SMTestProxy 收集結構化測試結果
├── mcp/
│   ├── McpServer.kt                  # + 3 個新 tool 定義
│   └── McpToolExecutor.kt            # + 3 個新 dispatch 分支
└── api/
    └── Models.kt                     # + 上述新資料模型
```

### 資料流

```
Agent                    McpServer              RunConfigurationService       IDE
  │                         │                          │                       │
  │ list_run_configurations │                          │                       │
  │────────────────────────►│  listRunConfigurations() │                       │
  │                         │─────────────────────────►│  RunManager.allSettings│
  │                         │                          │──────────────────────►│
  │                         │◄─────────────────────────│                       │
  │◄────────────────────────│                          │                       │
  │                         │                          │                       │
  │ run_configuration       │                          │                       │
  │────────────────────────►│  runConfiguration()      │                       │
  │                         │─────────────────────────►│  ProgramRunnerUtil    │
  │                         │                          │  .executeConfiguration│
  │                         │                          │──────────────────────►│
  │                         │                          │  waitFor(timeout)     │
  │                         │                          │◄─────────────────────│
  │◄────────────────────────│◄─────────────────────────│                       │
  │                         │                          │                       │
  │ get_test_results        │                          │  TestResultCollector  │
  │────────────────────────►│  getTestResults()        │                       │
  │                         │─────────────────────────►│  traverse SMTestProxy │
  │                         │                          │  tree → TestResults   │
  │◄────────────────────────│◄─────────────────────────│                       │
```

### RunConfigurationService

```kotlin
@Service(Service.Level.PROJECT)
class RunConfigurationService(private val project: Project) : Disposable {

    // 儲存最近的執行結果，供 get_test_results 查詢
    private val recentExecutions = ConcurrentHashMap<String, ExecutionRecord>()

    fun listConfigurations(type: String? = null): List<RunConfigurationInfo>

    fun runConfiguration(name: String, timeout: Long = 60_000): ExecutionResult

    fun getTestResults(executionId: String? = null): TestResults?

    override fun dispose() { recentExecutions.clear() }

    companion object {
        fun getInstance(project: Project): RunConfigurationService =
            project.getService(RunConfigurationService::class.java)
    }
}
```

> 注意：使用 `Service.Level.PROJECT`（非 APP），因為 Run Configuration 和測試結果是 per-project 的。

### TestResultCollector

```kotlin
object TestResultCollector {

    fun collect(
        root: AbstractTestProxy,
        includeOutput: Boolean = false,
        failedOnly: Boolean = false
    ): List<TestCaseResult> {
        return root.allTests
            .filter { it.isLeaf }
            .filter { !failedOnly || it.isDefect }
            .map { proxy ->
                TestCaseResult(
                    name = proxy.name,
                    suite = proxy.parent?.name,
                    status = mapStatus(proxy),
                    duration = proxy.duration,
                    errorMessage = proxy.errorMessage,
                    stackTrace = proxy.stacktrace,
                    output = if (includeOutput) captureOutput(proxy) else null
                )
            }
    }

    private fun mapStatus(proxy: AbstractTestProxy): TestCaseStatus = when {
        proxy.isPassed -> TestCaseStatus.PASSED
        proxy.isDefect -> if (proxy.isInterrupted) TestCaseStatus.ERROR else TestCaseStatus.FAILED
        proxy.isIgnored -> TestCaseStatus.IGNORED
        proxy.isInProgress -> TestCaseStatus.IN_PROGRESS
        else -> TestCaseStatus.ERROR
    }
}
```

### 執行緒模型

```
Ktor thread (MCP request)
    │
    ├── list_run_configurations → ReadAction { RunManager.allSettings } → 同步回傳
    │
    ├── run_configuration →
    │       invokeAndWait { ProgramRunnerUtil.executeConfiguration() }
    │       processHandler.waitFor(timeout)  // 阻塞 Ktor thread
    │       → 回傳 ExecutionResult
    │
    └── get_test_results →
            ReadAction { traverse SMTestProxy tree }
            → 回傳 TestResults
```

---

## Error Handling

新增 error codes（延續現有慣例）：

```kotlin
companion object {
    // ... 現有 codes ...
    const val CONFIGURATION_NOT_FOUND = -32006
    const val EXECUTION_FAILED = -32007
    const val EXECUTION_TIMEOUT = -32008
    const val NO_TEST_RESULTS = -32009
}
```

| 場景 | Error Code | 說明 |
|------|-----------|------|
| Configuration 名稱不存在 | `-32006` | `CONFIGURATION_NOT_FOUND` |
| 執行過程發生錯誤 | `-32007` | `EXECUTION_FAILED` |
| 執行超時 | `-32008` | `EXECUTION_TIMEOUT` |
| 無可用測試結果 | `-32009` | `NO_TEST_RESULTS` |

---

## 實現步驟

### Phase 1: 資料模型與基礎設施

1. 在 `Models.kt` 新增 `RunConfigurationInfo`、`ExecutionResult`、`TestResults`、`TestCaseResult` 等資料類別
2. 在 `McpProtocol.kt` 新增 error codes
3. 建立 `execution/` package

### Phase 2: list_run_configurations

1. 實現 `RunConfigurationService.listConfigurations()`
2. 在 `McpServer.kt` 新增 tool 定義
3. 在 `McpToolExecutor.kt` 新增 dispatch

### Phase 3: run_configuration

1. 實現 `RunConfigurationService.runConfiguration()`
   - EDT 切換：`invokeAndWait` 啟動執行
   - 阻塞等待：`ProcessHandler.waitFor(timeout)`
   - 捕獲 ProcessHandler 和 RunContentDescriptor
2. 實現 execution ID 生成與儲存
3. 在 `McpServer.kt` 和 `McpToolExecutor.kt` 註冊

### Phase 4: get_test_results

1. 實現 `TestResultCollector.collect()`
   - 遍歷 `AbstractTestProxy` 樹
   - 萃取 status、duration、error、stacktrace
2. 實現 `RunConfigurationService.getTestResults()`
   - 從 `recentExecutions` 查找對應的 SMTestProxy root
3. 在 `McpServer.kt` 和 `McpToolExecutor.kt` 註冊

### Phase 5: plugin.xml 註冊

```xml
<projectService
    serviceImplementation="info.jiayun.intellijmcp.execution.RunConfigurationService"/>
```

---

## Agent 使用範例

### 範例：修改程式碼後驗證測試

```
Agent: 我修改了 UserService.kt，讓我跑一下相關測試。

→ list_run_configurations(projectPath: "/path/to/project")
← [{ name: "UserServiceTest", type: "JUnit" }, { name: "All Tests", type: "Gradle" }, ...]

→ run_configuration(name: "UserServiceTest", timeout: 30000)
← { exitCode: 1, success: false, executionId: "UserServiceTest-1704067200000" }

→ get_test_results(executionId: "UserServiceTest-1704067200000")
← {
    status: "FAILED", totalTests: 3, passed: 2, failed: 1,
    tests: [
      { name: "testDeleteUser", status: "FAILED", errorMessage: "Expected 204 but got 404", ... }
    ]
  }

Agent: testDeleteUser 失敗了，預期 204 但收到 404。看起來是 delete endpoint 路徑
改錯了，讓我修正...
```

### 範例：搭配 JetBrains 官方 MCP 使用

如果用戶同時使用 JetBrains 官方 MCP 和 intellij-mcp：
- 用官方的 `execute_run_configuration` 執行測試（取得 raw output）
- 用 intellij-mcp 的 `get_test_results` 取得結構化結果（精確定位失敗原因）

---

## 已知限制

1. **僅支援 SM Test Runner 的測試框架** — 絕大多數 JetBrains IDE 測試框架都使用 SM Test Runner，但少數自訂框架可能不支援
2. **Run Configuration 需預先存在** — 不提供建立新 configuration 的功能，需使用者在 IDE 中預先設定
3. **阻塞等待** — `run_configuration` 會阻塞 Ktor 執行緒直到完成或超時，同時間大量執行可能影響 server 回應
4. **結果時效性** — 測試結果存在記憶體中，IDE 重啟後遺失
5. **並行執行** — 同一 configuration 同時執行多次時，結果可能混淆

---

## 未來擴展可能

- **`run_test_at_cursor`** — 給定 file + line，自動找到並執行對應的測試方法（需配合 LanguageAdapter 做 test discovery）
- **`rerun_failed_tests`** — 重跑上次失敗的測試（IDE 原生支援此功能）
- **Test coverage 整合** — 取得 coverage 結果（依賴 IDE 的 coverage runner）
- **Streaming 支援** — 透過 SSE 即時推送測試進度（目前已有 `/sse` endpoint 基礎設施）
