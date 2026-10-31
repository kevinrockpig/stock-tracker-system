package com.ugi.stock.repository;

import com.ugi.stock.entity.StockPrice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    // 觀察名單每檔股票最新的兩個交易日（最新報價與前一交易日，用來計算漲跌），一次查詢取得；
    // LATERAL 子查詢對每檔股票走 (symbol, trade_date) 唯一索引，只讀兩筆
    @Query(value = """
            SELECT p.* FROM stocks s
            CROSS JOIN LATERAL (
                SELECT * FROM stock_prices sp
                WHERE sp.symbol = s.symbol
                ORDER BY sp.trade_date DESC
                LIMIT 2
            ) p
            WHERE s.group_codes IS NOT NULL
            """, nativeQuery = true)
    List<StockPrice> findLatestTwoForWatchlist();

    // 日期區間內的歷史行情（含頭尾），依日期由舊到新，供 K 線圖使用
    List<StockPrice> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(String symbol, LocalDate from, LocalDate to);

    // 市場最近的交易日（所有股票的不重複日期，由新到舊）；資料庫只存交易日，因此休市日不會出現
    @Query("select distinct p.tradeDate from StockPrice p order by p.tradeDate desc")
    List<LocalDate> findRecentTradeDates(Pageable pageable);
}
