package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/stocks/{symbol}/history 的回應：股票基本資料 + 日 K 清單（依日期由舊到新）。
 * 價格未還原除息（股票分割已由資料來源調整）。
 */
public record StockHistoryResponse(
        String symbol,
        String name,
        String currency,
        List<PriceBar> prices
) {

    /**
     * 單一交易日的日 K（OHLCV）與當日技術指標。
     * 只包含實際交易日；週末、國定假日與颱風假等休市日不會出現。
     * 尚未計算指標時 indicators 為 null。
     */
    public record PriceBar(
            LocalDate tradeDate,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            Long volume,
            Indicators indicators
    ) {
    }

    /**
     * 技術指標（由 Python 以交易日計算）。暖身期或資料不足時個別欄位為 null。
     * MACD 使用台股名稱：macdDif = DIF、macdSignal = MACD、macdOsc = OSC。
     * atr14 為 14 日平均真實區間（價格單位），用於移動停利線：進場後最高收盤價 − 3 × ATR。
     */
    public record Indicators(
            BigDecimal ma5,
            BigDecimal ma10,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal macdDif,
            BigDecimal macdSignal,
            BigDecimal macdOsc,
            BigDecimal rsi6,
            BigDecimal rsi12,
            BigDecimal kdK,
            BigDecimal kdD,
            BigDecimal atr14
    ) {
    }
}
