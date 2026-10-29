package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * GET /api/stocks 的單筆回應：股票基本資料 + 最新可取得的日行情。
 *
 * @param quoteType 報價性質。目前資料來源為 yfinance 日 K，固定為 DAILY_CLOSE（日收盤價），並非即時報價
 */
public record StockQuoteResponse(
        String symbol,
        String name,
        String currency,
        String quoteType,
        LocalDate tradeDate,
        BigDecimal closePrice,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        Long volume
) {
}
