package com.ugi.stock.repository;

import com.ugi.stock.entity.BacktestTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, Long> {

    List<BacktestTrade> findBySymbolAndStrategyAndExitRuleOrderByEntryDateAsc(String symbol, String strategy, String exitRule);
}
