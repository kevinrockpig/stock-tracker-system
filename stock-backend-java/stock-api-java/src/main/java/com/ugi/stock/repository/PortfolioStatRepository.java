package com.ugi.stock.repository;

import com.ugi.stock.entity.PortfolioStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PortfolioStatRepository extends JpaRepository<PortfolioStat, Long> {

    // 某範圍（ALL 或組別代碼）的全部統計；排序由 Service 依策略與出場規則的定義順序處理
    List<PortfolioStat> findByScope(String scope);
}
