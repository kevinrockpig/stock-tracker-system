package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 我的計畫（user_plans 表）：首次設定的結果，每位使用者一筆，資料表由 Python 建立。
 * version 為 core / balanced / strategy（核心 0050 比例 70% / 50% / 30%，見 PlanRules.VERSIONS），frequency 為 daily / weekly。
 */
@Entity
@Table(name = "user_plans")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPlan {

    @Id
    @Column(name = "user_id")
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false, length = 10)
    private String version;

    @Column(nullable = false, length = 10)
    private String frequency;

    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal capital;

    @Column(name = "max_loss", nullable = false, precision = 5, scale = 4)
    private BigDecimal maxLoss;

    @Column(name = "invest_amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal investAmount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserPlan(long userId) {
        this.userId = userId;
    }
}
