package com.ugi.stock.repository;

import com.ugi.stock.entity.Stock;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockRepository extends JpaRepository<Stock, String> {

    // 目前的觀察名單（從設定移除的股票 group_codes 為 null，資料保留但不列出）
    List<Stock> findByGroupCodesIsNotNull(Sort sort);
}
