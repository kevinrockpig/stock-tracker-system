package com.ugi.stock.repository;

import com.ugi.stock.entity.ReferenceLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReferenceLevelRepository extends JpaRepository<ReferenceLevel, Long> {

    // 最新一天的參考價位（每檔每天一筆，保留歷史）
    Optional<ReferenceLevel> findFirstBySymbolOrderByBasisDateDesc(String symbol);
}
