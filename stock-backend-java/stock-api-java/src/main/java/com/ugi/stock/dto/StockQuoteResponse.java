package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/stocks 的單筆回應：股票基本資料 + 最新可取得的日行情。
 *
 * @param quoteType  報價性質。目前資料來源為 yfinance 日 K，固定為 DAILY_CLOSE（日收盤價），並非即時報價
 * @param groups     所屬組別代碼（TW50、CUSTOM），見 GET /api/groups
 * @param listedDate 上市（上櫃）日期，只有這天起的日 K；ETF 為 null
 */
public record StockQuoteResponse(
        String symbol,
        String name,
        String currency,
        List<String> groups,
        LocalDate listedDate,
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
