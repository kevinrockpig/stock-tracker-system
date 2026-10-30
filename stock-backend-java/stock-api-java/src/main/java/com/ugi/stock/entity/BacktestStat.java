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
 * 策略回測統計（backtest_stats 表）：每個（策略, 範圍, 出場規則）一筆，範圍為股票代號或 ALL（全部合併）。
 * 比率皆為小數（0.55 = 55%）。由 Python 每日計算寫入，Java 只讀取。欄位定義見 Python 的 scraper/backtest.py。
 */
@Entity
@Immutable
@Table(name = "backtest_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BacktestStat {

    @Id
    private Long id;

    @Column(nullable = false, length = 30)
    private String strategy;

    @Column(nullable = false, length = 20)
    private String scope;

    @Column(name = "exit_rule", nullable = false, length = 20)
    private String exitRule;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(nullable = false)
    private Integer trades;

    @Column(name = "open_trades", nullable = false)
    private Integer openTrades;

    @Column(nullable = false)
    private Integer wins;

    @Column(name = "win_rate")
    private BigDecimal winRate;

    @Column(name = "win_rate_low")
    private BigDecimal winRateLow;

    @Column(name = "win_rate_high")
    private BigDecimal winRateHigh;

    @Column(name = "avg_return")
    private BigDecimal avgReturn;

    @Column(name = "median_return")
    private BigDecimal medianReturn;

    @Column(name = "avg_win")
    private BigDecimal avgWin;

    @Column(name = "avg_loss")
    private BigDecimal avgLoss;

    @Column(name = "payoff_ratio")
    private BigDecimal payoffRatio;

    @Column(name = "profit_factor")
    private BigDecimal profitFactor;

    @Column(name = "max_loss")
    private BigDecimal maxLoss;

    @Column(name = "max_consecutive_losses", nullable = false)
    private Integer maxConsecutiveLosses;

    @Column(name = "avg_holding_days")
    private BigDecimal avgHoldingDays;

    @Column(name = "baseline_trades", nullable = false)
    private Integer baselineTrades;

    @Column(name = "baseline_avg_return")
    private BigDecimal baselineAvgReturn;

    @Column(name = "baseline_win_rate")
    private BigDecimal baselineWinRate;

    @Column(name = "buy_hold_return")
    private BigDecimal buyHoldReturn;

    @Column(name = "excess_return")
    private BigDecimal excessReturn;

    @Column(name = "excess_low")
    private BigDecimal excessLow;

    @Column(name = "excess_high")
    private BigDecimal excessHigh;

    @Column(name = "split_date")
    private LocalDate splitDate;

    @Column(name = "trades_first")
    private Integer tradesFirst;

    @Column(name = "excess_first")
    private BigDecimal excessFirst;

    @Column(name = "trades_second")
    private Integer tradesSecond;

    @Column(name = "excess_second")
    private BigDecimal excessSecond;

    // 超額報酬 95% 信賴區間下限 > 0，且前後兩段的超額報酬都 > 0
    @Column(name = "has_edge")
    private Boolean hasEdge;
}
