package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 庫存資產走勢（GET /api/holdings/history）：與庫存頁的「同期比較」納入同樣的庫存、同樣的算法，逐日計算。
 * 不另存每日快照，每次由買進紀錄、日 K 與除權息即時計算，所以修改、刪除、補填過去的買進日期後會自動一致。
 * 最後一天的三個數字等於庫存頁同期比較的投入成本、庫存價值與 0050 價值。
 *
 * @param asOf         計算依據的交易日（資料庫最新的收盤日）
 * @param etfSymbol    比較對象（0050）
 * @param lots         納入的庫存筆數（有買進日期，且 0050 當時已有資料）
 * @param excludedLots 未納入的筆數
 * @param points       由舊到新，從最早一筆納入的庫存買進的交易日開始；沒有納入的庫存時為空
 */
public record HoldingHistoryResponse(
        LocalDate asOf,
        String etfSymbol,
        int lots,
        int excludedLots,
        List<Point> points
) {

    /**
     * @param invested      當天以前買進的庫存成本合計（買進日當天起計入）
     * @param holdingsValue 這些庫存當天收盤的價值：市值 − 賣出成本 + 到當天為止已領的股利；當天沒有行情時為 null
     * @param etfValue      同樣金額在同一天收盤買 0050，到當天的價值（含股利，不計交易成本）
     */
    public record Point(LocalDate date, BigDecimal invested, BigDecimal holdingsValue, BigDecimal etfValue) {
    }
}
