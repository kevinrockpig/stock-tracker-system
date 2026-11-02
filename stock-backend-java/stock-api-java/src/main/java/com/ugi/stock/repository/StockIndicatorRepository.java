package com.ugi.stock.repository;

import com.ugi.stock.entity.StockIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockIndicatorRepository extends JpaRepository<StockIndicator, Long> {

    // 與 StockPriceRepository 的歷史查詢使用相同的日期區間，供合併成同一份回應
    List<StockIndicator> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(String symbol, LocalDate from, LocalDate to);

    // 某日（含）以前最近一個交易日的指標（停牌時沿用前一天）
    Optional<StockIndicator> findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(String symbol, LocalDate date);
}
