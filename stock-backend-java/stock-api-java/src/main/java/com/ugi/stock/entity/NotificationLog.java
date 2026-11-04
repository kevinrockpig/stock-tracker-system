package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 通知發送紀錄（notification_log 表）：每位使用者、每個提醒依據的交易日、每個管道一筆。
 * 已成功的不再重送；失敗的下次執行重試並更新這一筆。
 */
@Entity
@Table(name = "notification_log")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog {

    public static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    // 提醒依據的交易日（收盤資料的日期）
    @Column(name = "basis_date", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private LocalDate basisDate;

    // email / line
    @Column(nullable = false, length = 10, updatable = false)
    @Setter(AccessLevel.NONE)
    private String channel;

    // sent / failed
    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "alert_count", nullable = false)
    private int alertCount;

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    public NotificationLog(long userId, LocalDate basisDate, String channel) {
        this.userId = userId;
        this.basisDate = basisDate;
        this.channel = channel;
    }
}
