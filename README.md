# AI Office Java 本機版 v0.8.0

需要 Java 17 以上。辦公室在本機執行，模型回答仍需連線至所選服務。

## 啟動

Windows：解壓縮後雙擊 `Start-AI-Office.vbs`，會開啟瀏覽器，不需每次手動開 CMD。若 Windows 禁用 VBS，可執行 `start-windows.cmd`。

跨平台：`java -jar AI-Office-Java.jar`；Linux/macOS 也可執行 `sh start.sh`。

預設網址：http://127.0.0.1:4317。可用 `--port 4318` 改埠，`--data 路徑` 改紀錄目錄。紀錄預設存於使用者目錄 `.ai-office-java`。同一資料目錄禁止重複啟動；舊版資料會升級並保留紀錄。中斷的工作不會自動重跑。

## 五種工具

- Codex：需官方 Codex CLI，以 `codex login` 登入 ChatGPT 訂閱。保留原版整合。
- Gemini：需官方 Gemini CLI，先執行 `gemini` 並選 Google 帳號登入。保留原版整合。
- Antigravity：保留原版官方 headless `agy` CLI 接法；不是 IDE 啟動器。需所安裝版本支援 `--input-format`、`--output-format`。若已有 IDE，仍須確認官方 CLI 是否可用；此環境未驗證真實 agy 安裝。Use AI Credits 須關閉。
- Claude：安裝官方 Claude Code，設定頁可填完整 `claude.exe` 路徑，或使用 PATH 的 `claude`。按「開啟 Claude 訂閱登入」會開啟終端機，執行 `claude auth login`，依官方流程在瀏覽器登入。也可自行執行此指令。需另有支援 Claude Code 的 Claude 訂閱；ChatGPT/Gemini 訂閱不能代替。呼叫前檢查 `claude auth status`，只接受 `authMethod=claude.ai`。API Key、第三方、Console 登入會拒絕。請使用支援 auth 子命令及本版 CLI flags 的新版官方 CLI。
- Company：公司 OpenAI 相容服務，網址、模型、API Key 全部由設定頁填寫，不內建公司連線資料。

CLI 模型名稱可留白，使用 CLI 預設。檢查工具只檢查可執行檔與版本，不代表真實模型已成功呼叫。Windows 官方 npm `.cmd` 會解析成 Node + 官方 JS 啟動檔，避免 shell 指令拼接。

## 公司模型設定

Base URL 填公司提供的 API 根路徑，例如 `https://llm.example.com/v1`，本程式接上 `/chat/completions`；亦接受完整 `/chat/completions` URL。模型名稱填公司 model ID，API Key 無需認證時留白。逾時設定 5–600 秒。

支援 JSON `POST`、`messages`、`model`、`stream=false` 與可選 `Authorization: Bearer ...`。回覆使用 `choices[0].message.content`，保留 usage。支援文字或 text 區塊陣列。無 Azure 專屬 api-key header、任意額外 header、Responses API、SSE 或自訂憑證功能。HTTPS 使用 JVM/系統信任設定；不略過 TLS 驗證、不跟隨轉址。

「保存並測試公司連線」會送出一個要求回答 OK 的小請求，可能計入公司額度。正常任務與會議皆使用同一個接法。

API Key 存於本機 `office.json`，未加密；請保護使用者目錄。設定回覆、狀態與匯出不包含 Key。留白保留原 Key；勾「清除已保存 API Key」才會刪除。請勿把 `.ai-office-java` 放入版控。

## 工作方式

可派分析任務、附加專案現有檔案、產生修改提案。所有模型只取得明確附加的文字，不直接操作專案；修改提案由你核准，套用前比對原始內容並備份。保留原版檔案路徑、二進位與憑證檔限制。

會議可選 2–5 位不同組長，輪流看共享紀錄發言，討論 1–4 輪。可插話、停止；有 Codex 時由 Codex 整理，否則由最後一位整理。附加公司程式給訂閱工具時，內容會送到該服務，請依公司的資料使用規範選擇組長。

## 建置與測試

零第三方 Java 依賴；需 JDK 17 以上。使用 `sh build.sh`、Windows `build.ps1` 或 Gradle `build`。

`python3 test-integration.py`：本機模擬 CLI + 真實 HTTP transport，無模型費用。Linux 執行；涵蓋三種舊工具、Claude、公司模型與五方會議。`CoreTest` 為獨立 Java 檢查，可用 Gradle `verifyCore` 執行。

真實訂閱登入與公司端點需在你的電腦完成驗證；本次不包含真實帳號、API Key 或公司地址。測試範圍詳見 `VALIDATION.md`。

官方 Claude 文件：
https://code.claude.com/docs/en/cli-reference
https://code.claude.com/docs/en/authentication

## v0.7 額度與用量面板

左側「額度與用量」顯示五種 AI 的本系統累計回覆、回報用量次數、Token、快取讀取與最近回覆。畫面隨已完成任務更新；模型未回報 usage 時顯示未知，不能推估剩餘訂閱額度。訂閱額度通常以工作量／時間窗計算，不可直接用 Token 換算。

- Codex：進入面板即查詢，面板開啟且視窗可見時每 60 秒刷新，亦可按更新。使用官方 `codex app-server` 的 `account/rateLimits/read`；沒有發送模型任務、不讀 token 檔、不更改帳號。需安裝版本支援此 API 並以 ChatGPT 登入。可顯示多個額度視窗、已用／剩餘比例與重置時間。這是 Codex 額度，並非 ChatGPT 網頁全部模型的額度。
- Claude：每 60 秒讀取資料目錄 `claude-quota.json`，來源是官方 statusLine 快照。快照超過 2 分鐘會標示舊資料；沒有快照就不顯示餘額。需要你在官方 Claude Code 的互動模式設定下列狀態列（先合併進既有設定，不要覆蓋其他設定）。無需擷取 OAuth 憑證。CLI 初次模型回覆後、且官方提供 `rate_limits` 時才有資料；本系統使用 print 模式，不保證會觸發 statusLine。因此它是外部互動 CLI 的快照橋接，並非背景即時輪詢 Claude 帳號。
- Gemini：自動統計本系統 stats 回報的用量，訂閱額度請按「開啟官方額度查詢」，然後輸入 `/stats model`。未實作官方互動 TUI 的背景解析。
- Antigravity：自動統計官方 usage 回報的用量，額度請按「開啟官方額度查詢」，然後輸入 `/usage`（亦稱 `/quota`）。未實作官方互動 TUI 的背景解析。
- 公司 LLM：可選填額度查詢 URL，需與公司對話 API 同協定、主機及埠號，使用相同 Bearer Key。面板每 60 秒 GET 一次，JSON 回覆格式如下。此格式是 AI Office 轉接規格，不是 OpenAI 通用標準；公司需提供或轉接額度端點。沒有此端點時，仍可看本系統 Token。

公司額度 JSON：
```json
{"windows":[{"name":"每日 Token","usedPercent":20,"resetsAt":1791590400}]}
```
`usedPercent` 為 0–100 的已用百分比，`resetsAt` 為 Unix epoch 秒（可省略）。服務錯誤時顯示查詢失敗，不回傳原始錯誤正文或 Key。

Claude statusLine 的 Windows 設定範例（請換成你實際的 JAR 路徑和使用者目錄；自訂 `--data` 也要換快照目錄）：
```json
{
  "statusLine": {
    "type": "command",
    "command": "java -jar \"C:\\AI-Office\\AI-Office-Java.jar\" --capture-claude-quota \"C:\\Users\\Jimmy\\.ai-office-java\\claude-quota.json\""
  }
}
```
macOS/Linux 可使用同樣 Java 指令，換成絕對路徑。擷取指令只保存 `rate_limits`，不保存 statusLine 其他欄位，不讀取登入憑證。

Token 是「已回報的本系統用量」，不包含其他應用程式或未儲存結果；會議結論從 v0.7 開始保存 usage，舊版結論無法補回遺失資料。失敗或停止的呼叫亦可能已耗額度，但本系統無法自行補算。

來源：
https://developers.openai.com/codex/app-server
https://code.claude.com/docs/en/statusline
https://geminicli.com/docs/reference/commands/
https://antigravity.google/docs/cli/commands/usage/


## v0.7.1 修正與畫面測試

- 修正任務清單在平板／窄視窗寬度撐出頁面，以及組長卡片在窄視窗溢出的問題。
- Codex 額度子程序無回應時，先結束程序再關閉串流，避免背景讀取鎖住額度查詢。
- 公司連線測試成功會更新連線狀態；相同設定再次保存不清除已確認的連線及額度資訊。

驗證：17 項 Java 核心、84 項整合檢查通過。Chromium 實際操作五種工具的模擬任務、五方會議、公司測試連線、額度、檔案附加、修改核准、停止任務與錯誤提示。六頁在 1440、1024、390px 寬度下皆無橫向頁面溢出或 JavaScript 頁面錯誤。

`test-view.cjs` 需 Node.js 與 Playwright，執行 `node test-view.cjs`；可用 CHROME_PATH 指定既有 Chromium 執行檔，PLAYWRIGHT_MODULE 指定 Playwright 模組。測試啟動臨時資料目錄與本機模擬服務，不使用真實帳號或公司網路。

## v0.8.0 第二輪 Review

修正非有限 JSON 數值、Gemini 異常 usage 導致狀態更新失敗、常見私鑰檔附加，以及公司測試連線的 Token 漏算。成功公司連線測試保存最近 200 筆，統計包含診斷呼叫；非永久帳單。

驗證：27 個核心斷言、90 個整合斷言通過；Chromium 五工具任務、五方會議及六頁三種寬度操作通過。完整測案、review 發現及真實環境待驗項目見 `AI-Office-Test-Review-v0.8.0.md`。
