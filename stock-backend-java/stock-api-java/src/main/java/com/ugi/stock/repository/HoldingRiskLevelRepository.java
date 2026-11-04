package com.ugi.stock.repository;

import com.ugi.stock.entity.HoldingRiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

// 每個查詢都以 userId 過濾：只會取得該使用者自己的設定
@Repository
public interface HoldingRiskLevelRepository extends JpaRepository<HoldingRiskLevel, HoldingRiskLevel.Key> {

    List<HoldingRiskLevel> findByUserId(long userId);
}
