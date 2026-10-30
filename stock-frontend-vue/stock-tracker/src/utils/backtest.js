// 回測結果的判定文字：策略選股頁、個股頁、說明頁共用，確保說法一致

// 判定「有優勢」用的是單邊檢定：95% 信賴區間的下限 > 0，即誤判機率約 2.5%
const ONE_SIDED_ALPHA = 0.025

// 回測規則的數字，供說明頁顯示；必須與 Python 端 scraper/backtest.py 一致（回測在 Python 計算）
export const BACKTEST_RULES = {
  feeRate: 0.001425, // 手續費，買賣各收一次，不計折扣
  stockTaxRate: 0.003, // 證交稅（賣出）：股票
  etfTaxRate: 0.001, // 證交稅（賣出）：ETF
  limitUpSkip: 0.095, // 開盤比參考價高這麼多就視為買不到
}

// 回測交易的出場原因（Python 端 scraper/backtest.py）
export const EXIT_REASONS = {
  hold: '持有期滿',
  trailing: '跌破移動停利線',
  max_hold: '達最長持有天數',
}

// 同時比較 n 組、而且全都沒有真正的優勢時，至少一組「剛好通過」的機率上限（%）
export function falsePassChance(comparisons) {
  return Math.round((1 - (1 - ONE_SIDED_ALPHA) ** comparisons) * 100)
}

// 判定結果與原因。key：edge 有優勢 / none 無明顯優勢 / insufficient 樣本不足
export function verdict(result) {
  if (result.excessLow == null || result.excessHigh == null) {
    return { key: 'insufficient', label: '樣本不足', reason: '交易次數太少，無法估計超額報酬的區間。' }
  }
  if (result.hasEdge) {
    return {
      key: 'edge',
      label: '歷史上有優勢',
      reason: '超額報酬的 95% 區間在 0 以上，前後兩段也都勝過同期的任意日進場。',
    }
  }
  const none = { key: 'none', label: '歷史上無明顯優勢' }
  if (result.excessHigh < 0) {
    return { ...none, reason: '超額報酬的 95% 區間在 0 以下：比任意一天進場還差。' }
  }
  if (result.excessLow <= 0) {
    return { ...none, reason: '超額報酬的 95% 區間包含 0：與任意一天進場差不多，可能只是運氣。' }
  }
  // 整體區間在 0 以上，但前段或後段沒有勝過同期基準
  const weak = [
    [result.tradesFirst, result.excessFirst, '前段'],
    [result.tradesSecond, result.excessSecond, '後段'],
  ].filter(([trades, excess]) => !trades || excess == null || excess <= 0)
  const parts = weak.map(([trades, , label]) => (trades ? `${label}輸給同期基準` : `${label}沒有交易`))
  return { ...none, reason: `整體區間在 0 以上，但${parts.join('、')}，結果不穩定。` }
}
