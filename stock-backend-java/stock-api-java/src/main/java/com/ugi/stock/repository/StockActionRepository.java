package com.ugi.stock.repository;

import com.ugi.stock.entity.StockAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockActionRepository extends JpaRepository<StockAction, Long> {

    List<StockAction> findBySymbolOrderByExDateAsc(String symbol);
}
