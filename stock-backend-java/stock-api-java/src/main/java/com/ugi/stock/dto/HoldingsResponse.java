package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 庫存與分析（GET /api/holdings）。金額單位為元；報酬為小數（0.05 = 5%）。
 * 價格與股數都換算成目前的基準（買進日之後若有分割、配股、減資，依 stock_actions 調整）。
 *
 * @param asOf      分析依據的交易日（資料庫最新的收盤日）
 * @param summary   目前持股合計（已賣出的部分不計，見 realized）
 * @param benchmark 有填買進日期的庫存（含已賣出的部分），與「同一天、同樣金額買 0050」比較
 * @param positions 還有持股的股票（同一檔的多筆買進合併），依市值由大到小；股數、成本只計還沒賣出的部分
 * @param realized  已實現損益：每筆賣出（先進先出）與已全部賣出的股票
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
        FeeRules feeRules,
        Realized realized
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
     * @param invested     納入的庫存的買進成本合計（含之後賣出的）
     * @param holdingsValue 這些庫存目前的價值：還持有的市值 − 賣出成本 + 已領股利 + 已賣出拿回的金額（視為現金保留）
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
     * @param realizedPnl       這檔已賣出部分的已實現損益（沒有賣出時為 0）；realizedDividends 為賣出的股數持有期間領到的股利
     * @param overallPnl        整檔合計 = 未實現損益 + 已領股利 + 已實現損益 + 賣出部分的股利（與先進先出或平均成本無關）
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
            List<Signal> signals,
            BigDecimal realizedPnl,
            BigDecimal realizedDividends,
            BigDecimal overallPnl
    ) {
    }

    /**
     * 一筆買進（使用者填寫的原始數字），附換算結果。
     *
     * @param cost          買進成本 = 價格 × 股數 + 手續費
     * @param splitRatio    買進日之後的股數變動倍數（1 拆 4 為 4；沒有買進日期時為 1，視為已是目前基準）
     * @param currentShares 換算成目前基準的股數（買進的全部）；remainingShares 為還沒賣出的、soldShares 為已賣出的
     * @param dividends     這筆領到的現金股利（賣出的部分算到賣出日；沒有買進日期時為 null）
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
            BigDecimal remainingShares,
            BigDecimal soldShares,
            BigDecimal dividends
    ) {
    }

    public record Signal(String code, String name) {
    }

    /**
     * 已實現損益（賣出依先進先出分配到買進，與券商相同）。
     *
     * @param proceeds          賣出拿回的金額合計（成交金額 − 手續費 − 證交稅）
     * @param cost              這些股數的買進成本合計
     * @param realizedPnl       proceeds − cost
     * @param dividends         賣出的股數持有期間領到的股利；dividendsComplete 為 false 表示有來自未填買進日期的部分未計入
     * @param overallPnl        總損益 = 目前持股的未實現損益與已領股利（summary.totalPnl）+ 已實現損益 + 賣出部分的股利
     * @param sales             每筆賣出，依賣出日期由新到舊
     * @param closedPositions   已全部賣出的股票
     */
    public record Realized(
            int count,
            int maxCount,
            BigDecimal proceeds,
            BigDecimal cost,
            BigDecimal realizedPnl,
            BigDecimal realizedReturn,
            BigDecimal dividends,
            boolean dividendsComplete,
            BigDecimal overallPnl,
            List<Sale> sales,
            List<ClosedPosition> closedPositions
    ) {
    }

    /**
     * 一筆賣出（使用者填寫的原始數字），附先進先出的分配與損益。
     *
     * @param amount         成交金額 = 價格 × 股數
     * @param proceeds       拿回的金額 = 成交金額 − 手續費 − 證交稅
     * @param cost           扣掉的買進的成本（依股數比例）
     * @param realizedPnl    proceeds − cost；realizedReturn = realizedPnl ÷ cost
     * @param dividends      這些股數從買進到賣出領到的股利；dividendsComplete 為 false 表示有來自未填買進日期的部分未計入
     * @param unmatchedShares 找不到可扣的買進的股數（目前基準；正常為 0，修改紀錄後不一致時才會出現）
     * @param lots           從哪幾筆買進扣（股數為目前基準）
     */
    public record Sale(
            long id,
            String symbol,
            String name,
            LocalDate sellDate,
            BigDecimal price,
            int shares,
            String feeMode,
            BigDecimal feeDiscount,
            BigDecimal minFee,
            BigDecimal fee,
            String taxMode,
            BigDecimal tax,
            BigDecimal amount,
            BigDecimal proceeds,
            BigDecimal cost,
            BigDecimal realizedPnl,
            BigDecimal realizedReturn,
            BigDecimal dividends,
            boolean dividendsComplete,
            BigDecimal unmatchedShares,
            List<SaleLot> lots
    ) {
    }

    /** 一筆賣出從一筆買進扣掉的部分 */
    public record SaleLot(long holdingId, LocalDate buyDate, BigDecimal buyPrice, BigDecimal shares, BigDecimal cost) {
    }

    /**
     * 已全部賣出的股票（買進紀錄仍保留，可修改或刪除）。
     *
     * @param overallPnl 已實現損益 + 賣出部分的股利
     */
    public record ClosedPosition(
            String symbol,
            String name,
            List<Lot> lots,
            BigDecimal realizedPnl,
            BigDecimal dividends,
            BigDecimal overallPnl
    ) {
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
