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
 * 投資組合回測統計（portfolio_stats 表）：一筆共用資金、最多同時持有 slots 檔，依策略訊號輪流進出場。
 * 由 Python 每日計算寫入（scraper/portfolio.py），Java 只讀取。比率皆為小數。
 */
@Entity
@Immutable
@Table(name = "portfolio_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PortfolioStat {

    @Id
    private Long id;

    @Column(nullable = false, length = 30)
    private String strategy;

    // ALL 或組別代碼
    @Column(nullable = false, length = 20)
    private String scope;

    @Column(name = "exit_rule", nullable = false, length = 20)
    private String exitRule;

    private Integer slots;

    // 訊號多於空位時隨機排序，以幾個亂數種子各算一次（取年化報酬中位數的那一次）
    private Integer seeds;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "split_date")
    private LocalDate splitDate;

    private Integer trades;

    // 訊號出現但沒有空位而略過的次數
    private Integer skipped;

    @Column(name = "avg_invested")
    private BigDecimal avgInvested;

    private BigDecimal cagr;

    @Column(name = "max_drawdown")
    private BigDecimal maxDrawdown;

    // 年化報酬 ÷ |最大回撤|
    private BigDecimal calmar;

    private BigDecimal sharpe;

    @Column(name = "cagr_min")
    private BigDecimal cagrMin;

    @Column(name = "cagr_max")
    private BigDecimal cagrMax;

    @Column(name = "calmar_first")
    private BigDecimal calmarFirst;

    @Column(name = "calmar_second")
    private BigDecimal calmarSecond;

    // 基準：同一批股票等額買進持有
    @Column(name = "bh_cagr")
    private BigDecimal bhCagr;

    @Column(name = "bh_max_drawdown")
    private BigDecimal bhMaxDrawdown;

    @Column(name = "bh_calmar")
    private BigDecimal bhCalmar;

    @Column(name = "bh_sharpe")
    private BigDecimal bhSharpe;

    @Column(name = "bh_calmar_first")
    private BigDecimal bhCalmarFirst;

    @Column(name = "bh_calmar_second")
    private BigDecimal bhCalmarSecond;

    // 參考：0050 ETF 買進持有
    @Column(name = "etf_cagr")
    private BigDecimal etfCagr;

    @Column(name = "etf_max_drawdown")
    private BigDecimal etfMaxDrawdown;

    @Column(name = "etf_calmar")
    private BigDecimal etfCalmar;

    // 標準 2：整段與前後兩段的 Calmar 都高於同一批股票的買進持有
    @Column(name = "beats_bh")
    private Boolean beatsBh;
}
