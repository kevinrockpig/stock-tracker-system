package com.ugi.stock.repository;

import com.ugi.stock.entity.StockPrice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    // 指定股票最新的兩個交易日（我的自選用，寫法同上）
    @Query(value = """
            SELECT p.* FROM stocks s
            CROSS JOIN LATERAL (
                SELECT * FROM stock_prices sp
                WHERE sp.symbol = s.symbol
                ORDER BY sp.trade_date DESC
                LIMIT 2
            ) p
            WHERE s.symbol IN (:symbols)
            """, nativeQuery = true)
    List<StockPrice> findLatestTwo(@Param("symbols") Collection<String> symbols);

    // 某日（含）以前最近一個交易日的行情（停牌時沿用前一天）
    Optional<StockPrice> findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(String symbol, LocalDate date);

    // 日期區間內的歷史行情（含頭尾），依日期由舊到新，供 K 線圖使用
    List<StockPrice> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(String symbol, LocalDate from, LocalDate to);

    // 市場最近的交易日（所有股票的不重複日期，由新到舊）；資料庫只存交易日，因此休市日不會出現
    @Query("select distinct p.tradeDate from StockPrice p order by p.tradeDate desc")
    List<LocalDate> findRecentTradeDates(Pageable pageable);

    // 某日（含）以後最近一個交易日的行情（買進日不是交易日時往後找）
    Optional<StockPrice> findFirstBySymbolAndTradeDateGreaterThanEqualOrderByTradeDateAsc(String symbol, LocalDate date);

    // 資料庫中最早的一筆行情（判斷買進日是否早於資料起點）
    Optional<StockPrice> findFirstBySymbolOrderByTradeDateAsc(String symbol);

    /** 只取收盤價的投影（不讀開高低與成交量，減少資料庫傳輸量） */
    interface DailyClose {
        String getSymbol();

        LocalDate getTradeDate();

        BigDecimal getClosePrice();
    }

    // 多檔股票在日期區間（含頭尾）內的收盤價，依日期由舊到新，供庫存資產走勢使用
    @Query("""
            select p.symbol as symbol, p.tradeDate as tradeDate, p.closePrice as closePrice from StockPrice p
            where p.symbol in :symbols and p.tradeDate between :from and :to order by p.tradeDate""")
    List<DailyClose> findCloses(@Param("symbols") Collection<String> symbols, @Param("from") LocalDate from, @Param("to") LocalDate to);

    // 日期區間（含頭尾）內的最高收盤價，供移動停利線使用
    @Query("select max(p.closePrice) from StockPrice p where p.symbol = :symbol and p.tradeDate between :from and :to")
    Optional<BigDecimal> findMaxClose(@Param("symbol") String symbol, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
