package com.ugi.stock.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 策略選股結果（GET /api/screener）。
 *
 * @param strategy   查詢的策略
 * @param days       查詢最近幾個交易日
 * @param fromDate   區間第一個交易日（資料庫沒有任何行情時為 null）
 * @param marketDate 市場最新交易日（資料庫沒有任何行情時為 null）
 * @param stocks     區間內出現過訊號的股票，依最近一次訊號日期由新到舊排序
 */
public record ScreenerResponse(
        StrategyResponse strategy,
        int days,
        LocalDate fromDate,
        LocalDate marketDate,
        List<Match> stocks
) {

    /**
     * 符合策略的股票。
     *
     * @param lastSignalDate 區間內最近一次符合的交易日
     * @param signalCount    區間內符合的交易日數（事件型策略為發生次數）
     * @param quote          最新報價（與 GET /api/stocks 相同格式）
     */
    public record Match(
            String symbol,
            String name,
            LocalDate lastSignalDate,
            int signalCount,
            StockQuoteResponse quote
    ) {
    }
}
