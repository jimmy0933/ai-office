# AI Office Java v0.7.2 測案與第二輪 Review

日期：2026-10-10。範圍：Java 本機服務、五種 AI 接法、任務／會議／核准、額度與用量、前端操作、資料保存。

## 結果與環境

Java 17 相容建置成功。Java 核心 27 個斷言、整合 90 個斷言通過；Chromium 實際操作流程通過，六頁在 1440、1024、390px 三種寬度下沒有頁面橫向溢出，也沒有 JavaScript 頁面錯誤。斷言數是程式檢查點，包含同一流程中的多次驗證，不代表 117 個獨立端到端測案。

CLI 使用模擬程式；公司介接使用真正 Java HTTP Client 連接本機測試 HTTP 服務。瀏覽器操作真正 JAR 的頁面與 API。以下「通過」只涵蓋此測試環境；沒有使用真實訂閱帳號或公司服務。

## 第二輪 Review 發現與修正

| 編號 | 發現／重現方式 | 影響 | v0.7.2 修正與回歸 |
|---|---|---|---|
| R01 | JSON `1e309` 被解析成 Infinity，重新序列化成非法 JSON | 極端上游數值可能使保存資料無法再讀取 | 解析／寫出拒絕非有限數值；兩個核心斷言通過 |
| R02 | Gemini `stats.models.m.tokens` 回傳字串；計算用量時拋出「需要 JSON 物件」 | 狀態 API／頁面更新失敗 | 略過異常模型資料；非數字用量保持未知；核心及任務後狀態 API 回歸通過 |
| R03 | 一般命名的私鑰檔可被附加 | 私鑰可能進入模型提示 | 阻擋常見私鑰名稱／副檔名，另檢查 PEM/OpenSSH 私鑰標頭；大寫副檔名、改名檔案及正常原始碼回歸通過 |
| R04 | 公司「測試連線」成功回覆 usage，但統計未納入 | 本系統用量少算一次測試呼叫 | 保存測試 usage 並列出連線測試次數；驗證新增 1 次／5 Token、重啟保持、匯出不含 Key、畫面顯示 |

上述已重現問題均已修正。這次 review 並非全面滲透測試，也不表示不存在其他缺陷。私鑰檢查限於已列出的名稱與標頭，無法辨識任意文字中的全部機密。

## 已執行測案清單

| 編號 | 測案／操作 | 預期結果 | 層級 | 結果 |
|---|---|---|---|---|
| T01 | 繁體中文、跳脫字元、陣列、null JSON 往返 | 內容保持一致 | 核心 | 通過 |
| T02 | 重複鍵、尾隨內容、錯誤數字、過深巢狀 | 拒絕非法 JSON | 核心 | 通過 |
| T03 | 解析 `1e309`、寫出 Infinity | 明確拒絕，避免保存非法 JSON | 核心 | 通過 |
| T04 | 重複附件路徑 | 僅附加一次 | 核心 | 通過 |
| T05 | `../` 路徑、符號連結、`.env` | 拒絕存取 | 核心／API | 通過 |
| T06 | `id_ed25519`、`CERT.PEM`、改名私鑰文字 | 拒絕附加；正常 Java 原始碼仍可讀 | 核心 | 通過 |
| T07 | NUL 二進位及無效 UTF-8 | 拒絕讀取 | 核心 | 通過 |
| T08 | 提案修改未附加檔案 | 拒絕提案 | 核心 | 通過 |
| T09 | 提案送出、尚未核准 | 原始檔案未改動 | API／瀏覽器 | 通過 |
| T10 | 核准前檔案被外部修改 | 阻擋過期提案，保留外部修改 | 核心／API | 通過 |
| T11 | 核准或拒絕提案 | 核准寫入並備份；拒絕標示已拒絕 | 核心／API／瀏覽器 | 通過 |
| T12 | API Token 錯誤、外站 Host／Origin、外站 POST | 回傳 403 | API | 通過 |
| T13 | 檢查 CLI 版本與設定保存 | 五種接法可設定；只找到版本不宣稱已登入 | API／瀏覽器 | 通過 |
| T14 | Codex 分析、訂閱失敗與 API 登入模式 | 解析結果；顯示額度失敗；拒絕 API 登入 | 模擬 CLI／API | 通過 |
| T15 | Gemini 任務、個人 OAuth 設定與工具禁用 | 正確傳入設定及解析模型結果 | 模擬 CLI／API | 通過 |
| T16 | Gemini 異常 stats、非數字 usage、正常 Token 明細 | 任務仍完成；狀態可讀；未知不冒充已回報；正常明細正確 | 核心／模擬 CLI／API | 通過 |
| T17 | Antigravity NDJSON、步驟事件與實際模型 | 正確取得回覆、事件與 usage | 模擬 CLI／API | 通過 |
| T18 | Antigravity ERROR／WAITING、危險偏好與恢復 | 不誤判成功；不安全設定於呼叫前阻擋；修正後可用 | 模擬 CLI／API | 通過 |
| T19 | Claude auth status、無工具 JSON 任務、API auth | 接受 claude.ai 訂閱狀態，拒絕 API 模式 | 模擬 CLI／API | 通過 |
| T20 | 公司 Endpoint／Bearer／模型／回覆 | 實際 HTTP 傳輸與回覆解析正確 | 本機 HTTP／API | 通過 |
| T21 | 公司 HTTP 401、狀態／匯出／清除 Key | 錯誤不洩漏回應中的 Key；狀態及匯出不含 Key；可清除 | 本機 HTTP／API | 通過 |
| T22 | 公司測試連線 usage 與重啟 | 次數及 Token 增加、列出診斷次數、重啟後保持 | 核心／HTTP／API／瀏覽器 | 通過 |
| T23 | 兩方／三方／五方會議與結論 | 正確輪流發言、累積共享內容並產生結論 | 模擬 CLI／API／瀏覽器 | 通過 |
| T24 | 會議插話、重複或未知參與者 | 插話送入後續提示；無效參與者拒絕 | API | 通過 |
| T25 | 任務停止、子程序取消、停止後再執行 | 任務標示停止、衍生程序結束、工作通道可再用 | API／瀏覽器 | 通過 |
| T26 | 執行中修改設定 | 拒絕修改 | API | 通過 |
| T27 | 保存設定／歷史、重啟、舊版資料升級 | 內容保留；新增接法；未完成任務標示中斷，不自動重跑 | 核心／API | 通過 |
| T28 | 同資料目錄啟動第二程序 | 拒絕啟動且不覆寫資料 | JAR／API | 通過 |
| T29 | Codex 額度 RPC、Claude statusLine 擷取、公司 GET 額度 | 測試百分比 55／23.5／20 正確顯示 | 模擬 CLI／本機 HTTP／API | 通過 |
| T30 | Codex 額度程序無回應，後續恢復 | 20 秒內返回、不顯示虛假餘額；恢復後可查 | 模擬 CLI／API | 通過 |
| T31 | 公司跨站額度 URL | 設定遭拒絕 | API | 通過 |
| T32 | 任務、會議發言、結論、成功連線測試統計 | 全部納入本系統回報用量；缺少 usage 標示未知 | 核心／API | 通過 |
| T33 | 相同設定再次保存、成功公司測試 | 已確認狀態保留；公司成功更新連線狀態 | API | 通過 |
| T34 | 五種工具任務、檔案預覽與附加、核准、停止、錯誤提示 | 真實瀏覽器完成全部操作 | Chromium | 通過 |
| T35 | 六頁 × 1440／1024／390px；任務頁 980px 額外檢查 | 無橫向頁面溢出，無 JavaScript 頁面錯誤 | Chromium | 通過 |
| T36 | Java 17 建置、前端語法、JAR 及 ZIP 結構 | 可啟動 JAR，包含資源；ZIP 可解壓並包含原始碼與測試 | 建置／封裝 | 通過 |

## 尚待真實環境驗證的測案

| 編號 | 操作 | 驗收方式 | 狀態 |
|---|---|---|---|
| U01 | Windows／macOS 安裝 Java 17+，執行啟動腳本／JAR | 視窗開啟、系統匣正常、退出後程序結束 | 未執行 |
| U02 | 以真正 Codex、Gemini、Antigravity、Claude CLI 完成訂閱登入 | 各完成一次分析任務並核對實際輸出 | 未執行 |
| U03 | 公司設定頁填 URL、模型、Key，透過 VPN／TLS 連線 | 測試連線、任務、錯誤處理皆符合公司服務 | 未執行 |
| U04 | 真實 Codex 額度及 Claude statusLine 快照 | 核對官方畫面、更新時間、重設時間及過期快照 | 未執行 |
| U05 | 公司實際額度端點 | 端點格式符合 README 且可取得 windows 百分比 | 未執行 |
| U06 | 安裝環境含空白／中文路徑、實機終端機登入按鈕 | 路徑與登入終端機可正常使用 | 未執行 |

## 功能邊界

- Gemini 與 Antigravity 沒有自動額度資料接入；目前顯示本系統回報用量，另開啟官方 CLI 查詢。無法承諾所有 AI 均能即時取得訂閱餘額。
- Codex 額度可查詢、頁面每 60 秒更新一次；Claude 依官方 statusLine 快照，有來源更新時間；公司額度依設定的相同來源 GET 端點。
- Token 只涵蓋保存紀錄中的已回報 usage；不涵蓋其他應用程式、未回報用量或失敗／停止但已耗用的呼叫。成功連線測試保存最近 200 筆，不是永久帳單。
- 公司 Key 保存在本機設定檔，未加密；狀態 API／匯出不提供 Key。附加檔案內容會交給所選模型。

## 重跑方式

```sh
JAVA_HOME=/path/to/jdk sh build.sh
javac --release 17 -encoding UTF-8 -cp build/classes -d build/tests src/test/java/tw/jimmy/aioffice/CoreTest.java
java -cp build/classes:build/tests tw.jimmy.aioffice.CoreTest
python3 test-integration.py
node --check src/main/resources/public/app.js
node test-view.cjs
```

瀏覽器測試需 Node.js、Playwright 與 Chromium；可用 `CHROME_PATH` 指定 Chromium，`PLAYWRIGHT_MODULE` 指定 Playwright。截圖在 `build/view-check`；畫面用量皆為模擬資料。
