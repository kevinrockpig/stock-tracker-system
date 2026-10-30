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
 * 策略回測的單筆交易（backtest_trades 表）。由 Python 每日計算寫入，Java 只讀取。
 */
@Entity
@Immutable
@Table(name = "backtest_trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BacktestTrade {

    @Id
    private Long id;

    @Column(nullable = false, length = 30)
    private String strategy;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "exit_rule", nullable = false, length = 20)
    private String exitRule;

    @Column(name = "signal_date", nullable = false)
    private LocalDate signalDate;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "entry_price", nullable = false)
    private BigDecimal entryPrice;

    // 仍持有中時為 null
    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "exit_price")
    private BigDecimal exitPrice;

    // hold：持有期滿 / trailing：移動停利 / max_hold：移動停利達最長天數
    @Column(name = "exit_reason", length = 10)
    private String exitReason;

    @Column(nullable = false)
    private BigDecimal dividends;

    @Column(name = "holding_days", nullable = false)
    private Integer holdingDays;

    // 扣成本、含股利的報酬率（0.05 = 5%），持有中為 null
    @Column(name = "net_return")
    private BigDecimal netReturn;
}
