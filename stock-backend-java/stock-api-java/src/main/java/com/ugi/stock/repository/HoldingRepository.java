package com.ugi.stock.repository;

import com.ugi.stock.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// 每個查詢都以 userId 過濾：只會取得登入者自己的庫存
@Repository
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findByUserIdOrderByIdAsc(long userId);

    Optional<Holding> findByIdAndUserId(long id, long userId);

    long countByUserId(long userId);

    boolean existsByUserIdAndSymbol(long userId, String symbol);
}
