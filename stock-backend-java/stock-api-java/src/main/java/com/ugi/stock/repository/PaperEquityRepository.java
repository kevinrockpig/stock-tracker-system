package com.ugi.stock.repository;

import com.ugi.stock.entity.PaperEquity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperEquityRepository extends JpaRepository<PaperEquity, Long> {

    List<PaperEquity> findByAccountIdOrderByTradeDateAsc(Long accountId);
}
