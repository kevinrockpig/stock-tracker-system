package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一位使用者某個交易日的每日通知內容（依當天收盤資料計算）。
 *
 * @param asOf        依據的交易日（資料庫最新的交易日）；資料庫沒有任何行情時為 null
 * @param market      大盤狀態（0050 與季線）；缺資料時為 null
 * @param belowExit   收盤價低於移動停利線的持股
 * @param nearExit    收盤價距離移動停利線在提醒範圍內的持股（依個股風險設定與大盤狀態）
 * @param exDividends 幾天內除權息的持股
 * @param entries     交易計畫下一個交易日的買進候選（符合條件，非建議）；設定為不附上時為空
 */
public record NotificationDigest(
        LocalDate asOf,
        Market market,
        List<ExitAlert> belowExit,
        List<ExitAlert> nearExit,
        List<ExDividend> exDividends,
        List<EntryCandidate> entries
) {

    /** 需要留意的項目數（不含大盤狀態） */
    public int alertCount() {
        return belowExit.size() + nearExit.size() + exDividends.size() + entries.size();
    }

    /**
     * @param weak 收盤價低於季線（MA60）
     */
    public record Market(String symbol, String name, BigDecimal close, BigDecimal ma60, boolean weak) {
    }

    /**
     * @param distance     （收盤價 − 停利線）÷ 收盤價，跌破時為負
     * @param atrDistance  （收盤價 − 停利線）÷ ATR(14)
     * @param thresholdAtr 這檔的提醒範圍（幾個 ATR，已含大盤轉弱的加成）
     */
    public record ExitAlert(
            String symbol, String name, BigDecimal price, BigDecimal exitLine, BigDecimal atr14,
            BigDecimal distance, BigDecimal atrDistance, String riskLevel, String riskLabel, BigDecimal thresholdAtr
    ) {
    }

    /**
     * @param days 距離依據交易日幾天（日曆天）
     */
    public record ExDividend(String symbol, String name, LocalDate exDate, String exType, BigDecimal cashDividend, long days) {
    }

    /**
     * @param planDate  計畫依據的收盤日（於下一個交易日開盤執行）
     * @param skipAbove 開盤高於此價視為接近漲停，不買進
     */
    public record EntryCandidate(
            String strategy, String exitRule, String symbol, String name, LocalDate planDate,
            BigDecimal closePrice, BigDecimal skipAbove
    ) {
    }
}
