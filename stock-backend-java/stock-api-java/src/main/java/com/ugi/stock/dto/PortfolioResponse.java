package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 投資組合回測（GET /api/portfolio-backtests）：一筆共用資金、最多同時持有 slots 檔。比率皆為小數。
 *
 * @param scope       ALL 或組別代碼（投資組合回測不提供單一股票）
 * @param slots       最多同時持有的股票數
 * @param seeds       訊號多於空位時隨機排序的次數（結果取年化報酬中位數的那一次）
 * @param comparisons 這個範圍同時比較的組數（策略 × 出場規則）
 * @param benchmark   同一批股票等額買進持有（判定的基準）
 * @param etf         0050 ETF 買進持有（參考；觀察名單有倖存者偏差，策略勝過它不代表有效）
 * @param exitRules   出場規則說明（含短線 / 波段分組）
 * @param results     每個（策略, 出場規則）一筆，依定義順序排列
 */
public record PortfolioResponse(
        String scope,
        String scopeName,
        int scopeStocks,
        Integer slots,
        Integer seeds,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate splitDate,
        int comparisons,
        Benchmark benchmark,
        Benchmark etf,
        List<ExitRuleResponse> exitRules,
        List<Result> results
) {

    /**
     * @param calmar       年化報酬 ÷ |最大回撤|；calmarFirst / calmarSecond 為前後兩段（以 splitDate 切分）
     */
    public record Benchmark(
            BigDecimal cagr,
            BigDecimal maxDrawdown,
            BigDecimal calmar,
            BigDecimal sharpe,
            BigDecimal calmarFirst,
            BigDecimal calmarSecond
    ) {
    }

    /**
     * @param skipped          訊號出現但沒有空位而略過的次數
     * @param avgInvested      平均持股比例
     * @param cagrMin          各亂數種子的年化報酬最小值；cagrMax 為最大值
     * @param beatsBenchmark   標準 2：整段與前後兩段的 Calmar 都高於同一批股票的買進持有
     * @param signalEdge       標準 1：同一範圍的個別交易回測中，訊號時機勝過任意日進場（has_edge）
     * @param effective        有效策略：標準 1 與標準 2 都通過
     */
    public record Result(
            StrategyResponse strategy,
            String exitRule,
            Integer trades,
            Integer skipped,
            BigDecimal avgInvested,
            BigDecimal cagr,
            BigDecimal cagrMin,
            BigDecimal cagrMax,
            BigDecimal maxDrawdown,
            BigDecimal calmar,
            BigDecimal calmarFirst,
            BigDecimal calmarSecond,
            BigDecimal sharpe,
            boolean beatsBenchmark,
            boolean signalEdge,
            boolean effective
    ) {
    }
}
