package com.ugi.stock.repository;

import com.ugi.stock.entity.PaperAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaperAccountRepository extends JpaRepository<PaperAccount, Long> {
}
