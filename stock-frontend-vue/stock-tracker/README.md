# stock-tracker（Vue 3 + Vite）

資料只透過 Java API 取得，不直接連線資料庫。

| 頁面 | 網址 | 使用的 API |
|---|---|---|
| 股票清單 | `/` | `GET /api/stocks` |
| 個股 K 線圖 | `/stocks/2330.TW` | `GET /api/stocks/{symbol}/history?from=…` |
| 策略選股 | `/screener?strategy=golden_cross&days=10` | `GET /api/strategies`、`GET /api/screener?strategy=…&days=…` |

策略選股頁：選擇策略（均線多頭排列 / MACD 黃金交叉 / 突破 20 日均線）與區間（最新交易日 / 近 5、10、20 個交易日），
以卡片顯示符合的股票，點卡片進入 K 線圖。選擇記錄在網址上，可分享、可用上一頁返回。

K 線圖使用 ECharts（類別軸，只排列交易日；休市日不留空白），支援 1 個月 / 3 個月 / 6 個月 / 1 年區間、滑鼠滾輪縮放與下方滑桿。
成交量單位為「張」（1 張 = 1,000 股）。

技術指標（由後端計算，前端只負責繪製）：

- 均線：MA5 / MA10 / MA20 / MA60 疊加在 K 線上，可個別開關
- 副圖：MACD（DIF、MACD、OSC 柱）、RSI（6、12，虛線 30 / 70）、KD（9,3,3，虛線 20 / 80），可個別開關，預設顯示 MACD 與 RSI
- 指標顏色與副圖設定集中在 `src/utils/chartConfig.js`

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
