package com.ugi.stock.repository;

import com.ugi.stock.entity.PaperPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperPositionRepository extends JpaRepository<PaperPosition, Long> {

    // 全部部位（持有中與已出場），依進場日排序；由 Service 分開
    List<PaperPosition> findByAccountIdOrderByEntryDateAscIdAsc(Long accountId);
}
