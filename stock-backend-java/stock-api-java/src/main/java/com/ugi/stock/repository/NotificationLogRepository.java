package com.ugi.stock.repository;

import com.ugi.stock.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    Optional<NotificationLog> findByUserIdAndBasisDateAndChannel(long userId, LocalDate basisDate, String channel);
}
