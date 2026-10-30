# stock-tracker（Vue 3 + Vite）

資料只透過 Java API 取得，不直接連線資料庫。

| 頁面 | 網址 | 使用的 API |
|---|---|---|
| 股票清單 | `/` | `GET /api/stocks` |
| 個股 K 線圖 | `/stocks/2330.TW?strategy=ma_bullish&exitRule=hold_5` | `GET /api/stocks/{symbol}/history?from=…`、`GET /api/stocks/{symbol}/levels`、`GET /api/backtests?scope={symbol}`、`GET /api/stocks/{symbol}/trades?strategy=…&exitRule=…` |
| 策略選股 | `/screener?strategy=golden_cross&days=10&scope=2330.TW` | `GET /api/strategies`、`GET /api/screener?strategy=…&days=…`、`GET /api/stocks`、`GET /api/backtests?scope=…` |
| 使用說明 | `/about#backtest` | `GET /api/strategies`、`GET /api/backtests` |

策略選股頁：選擇策略（均線多頭排列 / MACD 黃金交叉 / 突破 20 日均線）與區間（最新交易日 / 近 5、10、20 個交易日），
以卡片顯示符合的股票，點卡片進入 K 線圖（預先標出這個策略的歷史進出場點）。選擇記錄在網址上，可分享、可用上一頁返回。

回測結果（資料由 Python 每日計算，前端只負責顯示）：

- 策略選股頁：策略說明下方一句話摘要（全部合併的結果中哪些出場方式有優勢），下方「歷史回測」區塊以 4 張卡片
  比較 4 種出場方式：判定（歷史上有優勢 / 無明顯優勢）與原因、每筆超額報酬與 95% 區間、勝率與平均報酬（附任意日進場基準）、
  前後兩段超額報酬，「更多統計」可展開。可切換「全部合併」或單一股票（`scope`），單一股票的卡片可連到 K 線圖查看每筆交易。
  並提醒同時比較多組的運氣成分。
- 個股頁：「回測進出場點」選擇策略與出場方式（記錄在網址，預設不顯示），K 線圖標出 ▲ 進場、▼ 出場，
  持有期間底色紅為獲利、綠為虧損、藍為持有中，tooltip 顯示交易細節；下方顯示這檔股票的判定與全部交易清單。
- 選「移動停利」出場方式時，K 線圖另畫出每筆交易的移動停利線（進場後最高收盤價 − 3 × 當天 ATR，與 Python 回測的計算相同）。
- 判定文字與說明頁的成本數字集中在 `src/utils/backtest.js`（數字須與 Python 的 `scraper/backtest.py` 一致）。

參考價位（個股頁，資料由 Python 每日收盤後計算）：價位表依價格由高到低列出漲跌停、60 日高低點、黃金分割、收盤價、ATR 停損，
並顯示距收盤的百分比；有除權息預告時顯示提醒（下一個交易日除息時說明參考價已扣除股利）。
K 線圖的「參考價位線」開關畫出漲跌停、黃金分割與 ATR 停損的水平線（設定記在瀏覽器）。轉換邏輯在 `src/utils/levels.js`。

K 線圖使用 ECharts（類別軸，只排列交易日；休市日不留空白），支援 1 個月 / 3 個月 / 6 個月 / 1 年區間、滑鼠滾輪縮放與下方滑桿。
成交量單位為「張」（1 張 = 1,000 股）。

技術指標（由後端計算，前端只負責繪製）：

- 均線：MA5 / MA10 / MA20 / MA60 疊加在 K 線上，可個別開關
- 副圖：MACD（DIF、MACD、OSC 柱）、RSI（6、12，虛線 30 / 70）、KD（9,3,3，虛線 20 / 80），可個別開關，預設顯示 MACD 與 RSI
- 指標顏色與副圖設定集中在 `src/utils/chartConfig.js`
- 區間、指標與參考價位線的選擇存在瀏覽器的 localStorage（`src/utils/chartSettings.js`），所有個股共用，重新整理後沿用；可按「恢復預設」

使用說明頁（`/about`）：免責聲明、資料性質、指標計算方式與常見解讀、策略條件（取自 API）、回測方法與限制、參考價位的算法、資料來源。
每頁頁尾有免責聲明連結；個股頁與策略選股頁的說明文字也連到對應段落（例如 `/about#indicators`）。

導覽：導覽列固定在頂端；說明頁目錄固定（桌面為左側欄、手機為導覽列下方橫列）並標示目前段落；捲動超過約一個畫面高時出現「回到頂端」按鈕；
上一頁會回到原本的捲動位置（`src/router.js` 的 `scrollBehavior`）。系統設定「減少動態效果」時不使用平滑捲動。

## 需求

- Node.js `^20.19.0` 或 `>=22.12.0`（建議 Node 24 LTS）
- Java API 已在 `http://localhost:8081` 啟動

## 執行

```bash
cd stock-frontend-vue/stock-tracker
npm install
npm run dev          # http://localhost:5173
```

Java 端 CORS 只允許 `http://localhost:5173`；若 5173 被占用，Vite 會直接報錯而不是改用其他 port。

## API 位址

預設 `http://localhost:8081`。要改用其他位址時，複製 `.env.example` 為 `.env.local` 並修改 `VITE_API_BASE_URL`，再重新執行 `npm run dev`。

## 建置

```bash
npm run build        # 輸出到 dist/
```
