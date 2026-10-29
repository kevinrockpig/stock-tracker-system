package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;

/**
 * 策略訊號（strategy_signals 表）：某檔股票在某個交易日符合某個策略，只存符合的組合。
 * 由 Python 每日掃描寫入，Java 只讀取，因此標記為 @Immutable。
 */
@Entity
@Immutable
@Table(name = "strategy_signals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StrategySignal {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    // 策略代碼，對應 Strategy.code
    @Column(nullable = false, length = 30)
    private String strategy;
}
