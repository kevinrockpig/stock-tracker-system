package com.ugi.stock.repository;

import com.ugi.stock.entity.UserStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// 以 userId 過濾的查詢只會取得登入者自己的自選；全站合計只回傳數字
@Repository
public interface UserStockRepository extends JpaRepository<UserStock, Long> {

    List<UserStock> findByUserIdOrderByCreatedAtAscIdAsc(long userId);

    Optional<UserStock> findByUserIdAndSymbol(long userId, String symbol);

    boolean existsByUserIdAndSymbol(long userId, String symbol);

    boolean existsBySymbol(String symbol);

    long countByUserId(long userId);

    // 全站自行加入、且不在觀察名單內的股票檔數（每日更新要額外抓的；上限與 Python 的 MAX_REQUESTED_STOCKS 相同）
    @Query(value = """
            SELECT count(DISTINCT u.symbol) FROM user_stocks u
            LEFT JOIN stocks s ON s.symbol = u.symbol
            WHERE s.group_codes IS NULL
            """, nativeQuery = true)
    long countRequestedOutsideWatchlist();

    /**
     * 官方每日資料（證交所 / 櫃買中心開放資料，只有股票與 ETF）中最近 7 天出現過的這個代號，取最新一筆。
     * 用 7 天而不是只看最新一天：某天其中一個市場的資料沒抓到時，另一個市場的股票不會被誤判為不存在；已下市的一週後就查不到。
     */
    @Query(value = """
            SELECT q.market AS market, q.name AS name FROM official_daily_quotes q
            WHERE q.code = :code
              AND q.trade_date >= (SELECT max(trade_date) FROM official_daily_quotes) - 7
            ORDER BY q.trade_date DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<OfficialListing> findOfficialListing(@Param("code") String code);

    interface OfficialListing {
        String getMarket();

        String getName();
    }
}
