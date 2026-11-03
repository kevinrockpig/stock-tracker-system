package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 庫存與分析（GET /api/holdings）。金額單位為元；報酬為小數（0.05 = 5%）。
 * 價格與股數都換算成目前的基準（買進日之後若有分割、配股、減資，依 stock_actions 調整）。
 *
 * @param asOf      分析依據的交易日（資料庫最新的收盤日）
 * @param summary   全部庫存合計
 * @param benchmark 有填買進日期的庫存，與「同一天、同樣金額買 0050」比較
 * @param positions 依股票合併（同一檔的多筆買進合併），依市值由大到小
 * @param count     庫存筆數；maxCount 為上限
 * @param feeRules  手續費與證交稅的估算規則（前端預覽用）
 */
public record HoldingsResponse(
        LocalDate asOf,
        Summary summary,
        Benchmark benchmark,
        List<Position> positions,
        int count,
        int maxCount,
        FeeRules feeRules
) {

    /**
     * @param sellCost        以最新收盤全部賣出的手續費與證交稅估算
     * @param unrealizedPnl   市值 − 賣出成本 − 買進成本（不含股利）
     * @param dividends       已領現金股利（只計有填買進日期的庫存）
     * @param totalPnl        未實現損益 + 已領股利
     */
    public record Summary(
            BigDecimal cost,
            BigDecimal marketValue,
            BigDecimal sellCost,
            BigDecimal unrealizedPnl,
            BigDecimal unrealizedReturn,
            BigDecimal dividends,
            BigDecimal totalPnl,
            BigDecimal totalReturn
    ) {
    }

    /**
     * @param lots         納入比較的庫存筆數（有買進日期，且 0050 當時已有資料）
     * @param excludedLots 未納入的筆數
     * @param invested     納入的庫存的買進成本合計
     * @param holdingsValue 這些庫存目前的價值（市值 − 賣出成本 + 已領股利）
     * @param etfValue      同樣金額在同一天買 0050 的目前價值（含股利，不計交易成本）
     */
    public record Benchmark(
            String etfSymbol,
            int lots,
            int excludedLots,
            BigDecimal invested,
            BigDecimal holdingsValue,
            BigDecimal holdingsReturn,
            BigDecimal etfValue,
            BigDecimal etfReturn
    ) {
    }

    /**
     * @param shares            目前的股數（換算後；配股可能有小數）
     * @param avgPrice          平均成交價（不含手續費，目前基準）
     * @param costPerShare      每股成本（含買進手續費，目前基準）
     * @param price             最新收盤價；priceDate 為其日期
     * @param weight            市值占全部庫存市值的比例
     * @param sellCost          賣出成本估算（手續費依最近一筆的手續費方式，「實際金額」以公定費率估算）
     * @param dividends         已領現金股利；dividendsComplete 為 false 表示有未填買進日期的筆數未計入
     * @param trailingFrom      移動停利線的起算日（最早的買進日期）；沒有買進日期時為 null
     * @param historyComplete   false：買進日期早於系統的資料起點，最高收盤價只從資料起點算起
     * @param highestClose      起算日以來的最高收盤價
     * @param exitLine          移動停利線 = 最高收盤價 − 3 × ATR(14)；distanceToExit = (收盤 − 停利線) ÷ 收盤
     * @param signals           最新交易日符合的策略
     */
    public record Position(
            String symbol,
            String name,
            List<String> groups,
            List<Lot> lots,
            BigDecimal shares,
            BigDecimal cost,
            BigDecimal avgPrice,
            BigDecimal costPerShare,
            BigDecimal price,
            LocalDate priceDate,
            BigDecimal marketValue,
            BigDecimal weight,
            BigDecimal sellCost,
            BigDecimal unrealizedPnl,
            BigDecimal unrealizedReturn,
            BigDecimal dividends,
            boolean dividendsComplete,
            BigDecimal totalPnl,
            BigDecimal totalReturn,
            LocalDate trailingFrom,
            boolean historyComplete,
            BigDecimal highestClose,
            BigDecimal atr14,
            BigDecimal exitLine,
            BigDecimal distanceToExit,
            boolean belowExitLine,
            BigDecimal ma60,
            boolean belowMa60,
            LocalDate nextExDate,
            String nextExType,
            BigDecimal nextCashDividend,
            List<Signal> signals
    ) {
    }

    /**
     * 一筆買進（使用者填寫的原始數字），附換算結果。
     *
     * @param cost          買進成本 = 價格 × 股數 + 手續費
     * @param splitRatio    買進日之後的股數變動倍數（1 拆 4 為 4；沒有買進日期時為 1，視為已是目前基準）
     * @param currentShares 換算成目前基準的股數
     * @param dividends     已領現金股利（沒有買進日期時為 null）
     */
    public record Lot(
            long id,
            String symbol,
            LocalDate buyDate,
            BigDecimal price,
            int shares,
            String feeMode,
            BigDecimal feeDiscount,
            BigDecimal minFee,
            BigDecimal fee,
            BigDecimal cost,
            BigDecimal splitRatio,
            BigDecimal currentShares,
            BigDecimal dividends
    ) {
    }

    public record Signal(String code, String name) {
    }

    /**
     * @param feeRate      手續費公定費率
     * @param defaultMinFee 未填最低手續費時使用的金額
     * @param taxStock     證交稅（股票）；taxEtf 為 ETF
     * @param rounding     捨入方式說明
     */
    public record FeeRules(BigDecimal feeRate, BigDecimal defaultMinFee, BigDecimal taxStock, BigDecimal taxEtf, String rounding) {
    }
}
