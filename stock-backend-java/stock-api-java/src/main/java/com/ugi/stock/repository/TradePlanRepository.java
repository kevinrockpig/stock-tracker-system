package com.ugi.stock.repository;

import com.ugi.stock.entity.TradePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradePlanRepository extends JpaRepository<TradePlan, Long> {

    // 尚待執行的計畫（最新一天產生的，以及因停牌等原因延後的）
    List<TradePlan> findByAccountIdAndStatusOrderByActionDescPriorityAsc(Long accountId, String status);

    // 某天執行（或略過）的計畫
    List<TradePlan> findByAccountIdAndExecutedDateOrderByActionDescPriorityAsc(Long accountId, java.time.LocalDate executedDate);
}
