# 專案維護指引

本專案是 JetBrains IDE plugin，透過 MCP 提供程式碼查詢。此文件是後續工作的入口；工具規格、操作步驟與實測結果分別由下列文件維護，避免在此複製會過期的測試數量或版本矩陣。

## 先讀哪些文件

- [README.md](README.md)：產品功能、安裝與使用方式。
- [docs/CODE_INTELLIGENCE.md](docs/CODE_INTELLIGENCE.md)：diagnostics、call hierarchy、implementations 的參數、回傳狀態及語言限制。
- [docs/fixtures/README.md](docs/fixtures/README.md)：真實 IDE／SDK fixture 的準備與執行方式。
- [docs/verification/code-intelligence.md](docs/verification/code-intelligence.md)：已執行的驗證、環境版本、已知限制與未驗證組合。

## 檔案地圖

| 位置 | 用途／何時修改 |
| --- | --- |
| `build.gradle.kts` | 全專案版本號。 |
| `core/build.gradle.kts` | IDE／語言 plugin 依賴、打包、Marketplace 描述與更新說明、測試及 verifier tasks。 |
| `core/src/main/resources/META-INF/` | Plugin descriptor 與各 optional language plugin 的啟用邊界。 |
| `core/src/main/kotlin/info/jiayun/intellijmcp/api/` | `LanguageAdapter`、註冊及共用符號資料模型。 |
| `core/src/main/kotlin/info/jiayun/intellijmcp/mcp/` | MCP 工具宣告、JSON-RPC dispatch、executor；三個新工具的 schema 在 `IntelligenceTools.kt`。 |
| `core/src/main/kotlin/info/jiayun/intellijmcp/intelligence/` | 共用結果／能力模型、期限與查詢服務、IDE backend、LSP backend／狀態及 URI 正規化。 |
| `core/src/main/kotlin/info/jiayun/intellijmcp/<language>/` | 各語言 adapter 與專屬 backend；Swift／C# 的 LSP client 也在各自目錄。 |
| `core/src/test/kotlin/info/jiayun/intellijmcp/intelligence/` | 共用邏輯、真實 PSI fixture、受控 LSP 狀態與傳輸測試。既有 MCP／Rust 回歸測試在相鄰目錄。 |
| `core/src/test/kotlin/info/jiayun/intellijmcp/smoke/SmokeStarter.kt` | 載入正式 ZIP 後執行 fixture 的 IDE 測試入口；不隨正式 plugin 出貨。 |
| `scripts/ide-smoke/META-INF/plugin.xml` | 獨立 smoke harness plugin descriptor。 |
| `scripts/run_ide_smoke.py` | 單一 fixture：複製專案、隔離 IDE config／plugins，執行後輸出 JSON 與 log。 |
| `scripts/run_smoke_matrix.py` | 依序執行多個 fixture，即使某組失敗仍記錄後續結果與 summary。 |
| `scripts/lsp_smoke.py` | 直接對已安裝 LSP server 做協定 smoke；不能取代正式 plugin 的 IDE 整合測試。 |
| `docs/fixtures/` | 可重現的各語言小型專案。每組 `smoke.json` 定義查詢、未存檔修改及預期結果。 |
| `docs/verification/` | 經整理的測試與 verifier 證據；不是執行時依賴。 |

## 修改時須維持的行為

- 公開座標是 **1-based UTF-16 line／column**。沿用既有 JSON-RPC request ID 型別，不能把整數轉成浮點數。
- 一次查詢共用期限；數量限制、取消、來源變動與後端未就緒必須反映在狀態中。只有 `complete` 的空結果能代表沒有問題／關係。
- 從引用處查詢須先解析到宣告。節點識別使用宣告位置與簽名；遞迴保留連線並停止該路徑展開。
- 未存檔 IDE Document 優先；不得為分析自動存檔或修改 inspections。PSI 操作使用短 ReadAction，等待 server／分析時不持有 read lock，也不阻塞 EDT。
- 共用程式不得硬引用 optional language plugin 類別。Java 依賴保持 optional，使用 `com.intellij.java` 與 `java-support.xml`；不要改回 `com.intellij.modules.java`。
- Rust 新版 hierarchy API 留在執行時相容層；舊版直接呼叫 fallback 必須保留 `partial` 與限制說明。不能因簡單 fixture 通過就宣稱完整語意支援。
- Swift／C# 共用 LSP 狀態須保留版本化文件同步、capabilities／動態註冊、診斷新鮮度及 canonical URI。列舉能力不能啟動 server。
- Vue 符號查詢限 script／script setup。未開啟 SFC 診斷依賴暫時的 IDE editor lifecycle；保留 cleanup、未存檔設定保護與磁碟內容不變的驗證。
- 不要重新打包 IDE 擁有的 JVM Kotlin／coroutine runtime 或 Gson；目前的排除與 Ktor classpath 順序是為了避免語言 plugin classloader 衝突。
- IDE 最低版本與編譯版本以 Gradle／descriptor 為準。提高編譯版本不能當作舊版相容性的證據。

## 如何驗證變更

1. 共用邏輯、MCP 或後端行為變更，先執行相關測試；交付正式 ZIP 時執行：

   ```sh
   ./gradlew :core:test :core:buildPlugin
   ```

2. 修改語言語意、文件同步或 IDE 生命週期時，使用對應的真實 fixture。先建置獨立 harness：

   ```sh
   ./gradlew :core:smokeHarnessJar
   ```

   完整命令與 SDK 環境變數見 [fixture 指引](docs/fixtures/README.md)。`MCP_GO_SDK`、`MCP_DART_SDK`、`MCP_RUST_BIN`、`MCP_PYTHON` 從環境提供，不硬編碼個人路徑。Go／Vue 需要圖形登入環境；Swift 跨檔案 fixture 需要建立 index store。

   `<language>-semantics` 補充引用／宣告一致性及語言特有案例；`dart-crlf` 檢查座標；Vue 各組涵蓋 script、script setup、Volar 與已開啟 editor。C# fixture 必須搭配對應伺服器版本。Rust runner 的 `--rust-fallback` **只改預期結果，不強制切換 backend**，須真的使用缺少原生 hierarchy 的舊 plugin。

3. 修改 IDE API、optional dependencies 或打包依賴時，另外執行相容性驗證：

   ```sh
   ./gradlew :core:verifyPlugin
   # 或指定本機 IDE distribution：
   ./gradlew :core:verifyPlugin -PverificationIdePath=/absolute/path/to/IDE
   ```

   binary verifier、受控協定測試、真實 SDK／IDE fixture 是不同證據。保留各自實際環境；反射載入的 API 特別需要真實 fixture 驗證。

4. 純文件、ignore 或 fixture lockfile 整理不需重跑整個 IDE 矩陣。執行對應格式、連結、lockfile 檢查即可；通過的測試只在新變更或未解決疑慮需要時重跑。

## 驗證資料與 Git 提交

- 新跑的 log／report 先放 `/tmp` 等工作目錄。需要保留為證據時，再將已核對的結果整理進 `docs/verification/`，移除個人路徑與敏感資料。
- `tests.json` 記錄 Gradle test XML 的 suite 計數；`live/*.json` 記錄真實查詢，`live/summary.json` 是摘要；各 IDE 子目錄保存 verifier verdict、依賴與 API 使用報告。
- `artifact.json` 的版本路徑、大小與 SHA-256 必須對應實際驗證的 ZIP。重建或換版後不得沿用舊 hash，也不能把舊報告描述成新產物的驗證。
- 更新 [驗證報告](docs/verification/code-intelligence.md) 時，明列預期 `partial`／`unsupported`、失敗與未驗證組合。不要把通過測試的數量硬編碼到此文件。
- 應提交：正式程式、建置設定、測試、harness／runner、fixture 原始碼與 lockfiles、規格文件及整理後的驗證證據。
- 不提交：ZIP／JAR 產物、IDE workspace 狀態、SDK、憑證、原始執行 log、`node_modules`、`.build`、`target`、`obj`、`.dart_tool`、Python 快取。以 `.gitignore` 維護排除。
- 改版本時同時檢查 Gradle 更新說明、runner 中的 ZIP 名稱與驗證文件。正式 ZIP 在 `core/build/distributions/`，harness 在 `core/build/smoke/`；兩者用途不同。
- Smoke runner 使用獨立設定與 fixture 副本；若使用既有 license 檔，只傳路徑供隔離環境連結，不讀取或記錄內容。清理暫存目錄前確認測試程序已結束。
