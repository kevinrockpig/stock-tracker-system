package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 每日通知的設定（notification_settings 表）：每位使用者一筆，資料表由 Python 建立。
 * 沒有資料時視為未開啟（{@link #defaults(long)}）；Email 寄到登入的 Google email。
 */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting {

    public static final int DEFAULT_EX_DIVIDEND_DAYS = 3;

    @Id
    @Column(name = "user_id")
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    // 幾天內除權息要提醒
    @Column(name = "ex_dividend_days", nullable = false)
    private int exDividendDays;

    // 是否附上交易計畫的買進候選
    @Column(name = "entry_alerts", nullable = false)
    private boolean entryAlerts;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static NotificationSetting defaults(long userId) {
        NotificationSetting setting = new NotificationSetting();
        setting.userId = userId;
        setting.emailEnabled = false;
        setting.exDividendDays = DEFAULT_EX_DIVIDEND_DAYS;
        setting.entryAlerts = true;
        setting.updatedAt = Instant.now();
        return setting;
    }
}
