package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 交易計畫與往後實測（GET /api/paper）：每個觀察中策略一個模擬帳戶。
 * 金額單位為元；報酬為小數（0.05 = 5%）。紀錄由 Python 每個交易日處理一次，不會事後修改。
 *
 * @param inceptionDate   啟用日（從這天收盤後開始產生計畫）
 * @param lastProcessed   已處理到的交易日；nextPlans 依這天收盤產生，於下一個交易日開盤執行
 * @param tradingDays     啟用以來的交易日數（含啟用日）
 * @param totalReturn     帳戶累積報酬（以收盤計值）
 * @param benchmarkReturn 同期間同一批股票自啟用日收盤等額買進持有的報酬（含股利，不計成本）
 * @param etfReturn       同期間 0050 ETF 買進持有的報酬（參考）
 * @param maxDrawdown     啟用以來帳戶資金的最大回撤（負值）
 * @param nextPlans       待執行的計畫（賣出在前，買進依優先順序）
 * @param latestResults   lastProcessed 當天執行或略過的計畫
 * @param positions       持有中部位
 * @param closedTrades    已出場交易（由新到舊）
 * @param equityCurve     每日資金與基準（基準換算成與起始資金相同的金額）
 */
public record PaperResponse(
        StrategyResponse strategy,
        ExitRuleResponse exitRule,
        int slots,
        BigDecimal initialCapital,
        LocalDate inceptionDate,
        LocalDate lastProcessed,
        int tradingDays,
        BigDecimal cash,
        BigDecimal equity,
        BigDecimal totalReturn,
        BigDecimal benchmarkReturn,
        BigDecimal etfReturn,
        BigDecimal maxDrawdown,
        List<Plan> nextPlans,
        List<Plan> latestResults,
        List<Position> positions,
        List<ClosedTrade> closedTrades,
        List<EquityPoint> equityCurve
) {

    /**
     * @param action    buy / sell
     * @param reason    買進：策略代碼；賣出：trailing（跌破移動停利線）/ ma_break（跌破季線）
     * @param priority  同一天多筆買進的執行順序（0 起算，隨機排定）
     * @param skipAbove 開盤高於此價視為接近漲停而放棄（除息日會再扣除股利）
     * @param atrStop   買進時的 ATR 停損參考（收盤 − 3 × ATR）
     * @param status    pending / executed / skipped_no_slot（沒有空位）/ skipped_limit_up（開盤近漲停）/ skipped_held（已持有）/ skipped_no_data
     */
    public record Plan(
            LocalDate planDate,
            String symbol,
            String name,
            String action,
            String reason,
            int priority,
            BigDecimal closePrice,
            BigDecimal skipAbove,
            BigDecimal trailingLine,
            BigDecimal atrStop,
            String status,
            LocalDate executedDate
    ) {
    }

    /**
     * @param price            最近一個交易日（≤ lastProcessed）的收盤價；priceDate 為其日期
     * @param unrealizedReturn 以 price 賣出（扣賣出成本）加上已領股利的報酬，與已出場交易的報酬算法相同
     * @param weight           市值占帳戶資金的比例
     * @param exitLine         目前的出場線：移動停利為「進場後最高收盤 − 3 × 最新 ATR」，跌破季線為最新 MA60；收盤跌破則隔日開盤賣出
     */
    public record Position(
            String symbol,
            String name,
            LocalDate signalDate,
            LocalDate entryDate,
            BigDecimal entryPrice,
            BigDecimal shares,
            BigDecimal cost,
            BigDecimal price,
            LocalDate priceDate,
            BigDecimal marketValue,
            BigDecimal weight,
            BigDecimal dividends,
            BigDecimal unrealizedReturn,
            BigDecimal highestClose,
            BigDecimal exitLine,
            int heldDays
    ) {
    }

    public record ClosedTrade(
            String symbol,
            String name,
            LocalDate signalDate,
            LocalDate entryDate,
            BigDecimal entryPrice,
            LocalDate exitDate,
            BigDecimal exitPrice,
            String exitReason,
            int heldDays,
            BigDecimal dividends,
            BigDecimal netReturn
    ) {
    }

    /**
     * @param benchmark 同一批股票買進持有的價值（換算成起始資金的金額）
     * @param etf       0050 ETF 買進持有的價值（換算成起始資金的金額）
     */
    public record EquityPoint(LocalDate date, BigDecimal equity, BigDecimal cash, int positions, BigDecimal benchmark, BigDecimal etf) {
    }
}
