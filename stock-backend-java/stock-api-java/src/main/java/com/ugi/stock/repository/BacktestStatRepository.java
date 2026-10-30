package com.ugi.stock.repository;

import com.ugi.stock.entity.BacktestStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BacktestStatRepository extends JpaRepository<BacktestStat, Long> {

    // 某範圍（股票代號或 ALL）的全部統計；排序由 Service 依策略與出場規則的定義順序處理
    List<BacktestStat> findByScope(String scope);
}
