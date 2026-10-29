package com.ugi.stock.repository;

import com.ugi.stock.entity.StockIndicator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StockIndicatorRepository extends JpaRepository<StockIndicator, Long> {

    // 與 StockPriceRepository 的歷史查詢使用相同的日期區間，供合併成同一份回應
    List<StockIndicator> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(String symbol, LocalDate from, LocalDate to);
}
