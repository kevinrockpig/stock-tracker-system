package com.ugi.stock.repository;

import com.ugi.stock.entity.HoldingSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// 每個查詢都以 userId 過濾：只會取得登入者自己的賣出紀錄
@Repository
public interface HoldingSaleRepository extends JpaRepository<HoldingSale, Long> {

    // 依賣出日期由舊到新（先進先出依這個順序分配）
    List<HoldingSale> findByUserIdOrderBySellDateAscIdAsc(long userId);

    Optional<HoldingSale> findByIdAndUserId(long id, long userId);

    long countByUserId(long userId);
}
