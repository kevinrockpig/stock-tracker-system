package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * 個股風險設定（holding_risk_levels 表）：「接近停利線」的提醒距離，代碼見 RiskLevel；沒有資料的股票為標準。
 */
@Entity
@Table(name = "holding_risk_levels")
@IdClass(HoldingRiskLevel.Key.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HoldingRiskLevel {

    @Id
    @Column(name = "user_id")
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Id
    @Column(length = 20)
    @Setter(AccessLevel.NONE)
    private String symbol;

    // conservative / standard / aggressive
    @Column(name = "risk_level", nullable = false, length = 12)
    private String riskLevel;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public HoldingRiskLevel(long userId, String symbol, String riskLevel) {
        this.userId = userId;
        this.symbol = symbol;
        this.riskLevel = riskLevel;
        this.updatedAt = Instant.now();
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long userId;
        private String symbol;
    }
}
