package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 交易計畫（trade_plans 表）：依 planDate 收盤產生，於下一個交易日開盤執行。
 */
@Entity
@Immutable
@Table(name = "trade_plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradePlan {

    @Id
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(nullable = false, length = 20)
    private String symbol;

    // buy / sell
    @Column(nullable = false, length = 4)
    private String action;

    // 買進：策略代碼；賣出：trailing / ma_break
    @Column(nullable = false, length = 30)
    private String reason;

    // 同一天多筆買進的執行順序（隨機排定）
    @Column(nullable = false)
    private Integer priority;

    @Column(name = "close_price")
    private BigDecimal closePrice;

    @Column(name = "reference_price")
    private BigDecimal referencePrice;

    // 開盤高於此價視為接近漲停而放棄
    @Column(name = "skip_above")
    private BigDecimal skipAbove;

    @Column(name = "trailing_line")
    private BigDecimal trailingLine;

    @Column(name = "atr_stop")
    private BigDecimal atrStop;

    // pending / executed / skipped_no_slot / skipped_limit_up / skipped_held / skipped_no_data
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "executed_date")
    private LocalDate executedDate;
}
