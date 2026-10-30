package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 策略回測統計（GET /api/backtests）。比率皆為小數（0.55 = 55%），報酬已扣交易成本並含現金股利。
 *
 * @param scope       統計範圍：ALL（觀察名單全部合併）或股票代號
 * @param periodStart 回測期間起點（指標暖身完成後）
 * @param periodEnd   回測期間終點（最新交易日）
 * @param splitDate   穩健性檢查的前後兩段切分日
 * @param comparisons 同時比較的組數（策略 × 出場規則）；組數越多，越可能有組別靠運氣通過
 * @param exitRules   出場規則說明
 * @param results     每個（策略, 出場規則）一筆，依策略與出場規則的定義順序排列
 */
public record BacktestResponse(
        String scope,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate splitDate,
        int comparisons,
        List<ExitRuleResponse> exitRules,
        List<Result> results
) {

    /**
     * 單一（策略, 出場規則）的統計。資料不足時數值為 null。
     *
     * @param trades               已出場的交易次數
     * @param openTrades           資料到最新一天仍持有中的交易數（不計入統計）
     * @param winRateLow           勝率 95% 信賴區間下限（Wilson）
     * @param payoffRatio          盈虧比 = 平均獲利 ÷ 平均虧損（絕對值）
     * @param profitFactor         獲利因子 = 總獲利 ÷ 總虧損（絕對值）
     * @param baselineAvgReturn    基準：同一檔股票任意一個交易日都以相同規則進場的平均報酬（依交易所屬股票加權）
     * @param buyHoldReturn        同期間買進持有的報酬（ALL 為各股票的平均）
     * @param excessReturn         超額報酬 = 平均報酬 − 基準；excessLow / excessHigh 為 95% 信賴區間
     * @param excessFirst          前段（splitDate 之前進場）的超額報酬，與同一段的基準比較
     * @param excessSecond         後段的超額報酬
     * @param hasEdge              有優勢：信賴區間下限 > 0，且前後兩段的超額報酬都 > 0
     */
    public record Result(
            StrategyResponse strategy,
            String exitRule,
            int trades,
            int openTrades,
            int wins,
            BigDecimal winRate,
            BigDecimal winRateLow,
            BigDecimal winRateHigh,
            BigDecimal avgReturn,
            BigDecimal medianReturn,
            BigDecimal avgWin,
            BigDecimal avgLoss,
            BigDecimal payoffRatio,
            BigDecimal profitFactor,
            BigDecimal maxLoss,
            int maxConsecutiveLosses,
            BigDecimal avgHoldingDays,
            BigDecimal baselineAvgReturn,
            BigDecimal baselineWinRate,
            BigDecimal buyHoldReturn,
            BigDecimal excessReturn,
            BigDecimal excessLow,
            BigDecimal excessHigh,
            Integer tradesFirst,
            BigDecimal excessFirst,
            Integer tradesSecond,
            BigDecimal excessSecond,
            boolean hasEdge
    ) {
    }
}
