package com.ugi.stock.dto;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/my-stocks：登入者自行加入的股票（依加入時間由舊到新）。
 *
 * @param limit 每人最多幾檔
 */
public record MyStocksResponse(List<Item> stocks, int limit) {

    /**
     * @param ready     是否已有資料（每日更新抓過）；false 時要等下一次每日更新，quote 為 null、name 取自官方每日資料
     * @param watchlist 是否也在觀察名單內（觀察名單本來就每天更新）
     * @param quote     最新報價，格式同 GET /api/stocks
     */
    public record Item(String symbol, String name, Instant addedAt, boolean ready, boolean watchlist, StockQuoteResponse quote) {
    }
}
