package com.ugi.stock.repository;

import com.ugi.stock.entity.StrategySignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StrategySignalRepository extends JpaRepository<StrategySignal, Long> {

    // 某策略在日期區間內（含頭尾）的所有訊號；使用 (strategy, trade_date) 索引
    List<StrategySignal> findByStrategyAndTradeDateBetween(String strategy, LocalDate from, LocalDate to);
}
