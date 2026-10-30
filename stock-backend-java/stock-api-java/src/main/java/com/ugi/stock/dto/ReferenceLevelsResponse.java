package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 參考價位（GET /api/stocks/{symbol}/levels）：以 basisDate 收盤後的資料計算，供下一個交易日參考。
 * 都是公式計算的價位，不是漲跌預測。
 *
 * @param basisDate  計算依據的交易日（最新收盤日）
 * @param atrStop    ATR 停損；指標暖身期為 null
 * @param swing      最近 N 個交易日的波段與黃金分割
 * @param nextDay    下一個交易日的參考價與漲跌停價
 * @param exDividend 官方預告中最近一次的除權息；沒有預告時為 null
 */
public record ReferenceLevelsResponse(
        String symbol,
        String name,
        LocalDate basisDate,
        BigDecimal closePrice,
        AtrStop atrStop,
        Swing swing,
        NextDay nextDay,
        ExDividend exDividend
) {

    /**
     * @param multiple ATR 倍數（與回測「移動停利」相同）
     * @param price    收盤價 − multiple × atr14；進場後收盤創新高時，停利線改為「最高收盤價 − multiple × ATR」
     */
    public record AtrStop(BigDecimal atr14, BigDecimal multiple, BigDecimal price) {
    }

    /**
     * @param days   實際使用的交易日數（資料不足時少於 60）
     * @param trend  up：低點在前，levels 為自高點回檔的位置 / down：高點在前，levels 為自低點反彈的位置
     * @param levels 黃金分割 38.2%、50%、61.8%
     */
    public record Swing(
            int days,
            BigDecimal high,
            LocalDate highDate,
            BigDecimal low,
            LocalDate lowDate,
            String trend,
            List<FibLevel> levels
    ) {
    }

    /**
     * @param ratio 0.382 / 0.5 / 0.618
     */
    public record FibLevel(BigDecimal ratio, BigDecimal price) {
    }

    /**
     * @param referencePrice 平常為收盤價；下一個交易日除息時已扣除現金股利
     * @param referenceExact false：下一個交易日除權（或股利未公告），參考價與漲跌停價以官方公告為準
     */
    public record NextDay(BigDecimal referencePrice, boolean referenceExact, BigDecimal limitUp, BigDecimal limitDown) {
    }

    /**
     * @param type              權 / 息 / 權息
     * @param cashDividend      每股現金股利；尚未公告為 null
     * @param affectsNextDay    除權息日就是下一個交易日（參考價已調整，或需以官方公告為準）
     */
    public record ExDividend(LocalDate exDate, String type, BigDecimal cashDividend, boolean affectsNextDay) {
    }
}
