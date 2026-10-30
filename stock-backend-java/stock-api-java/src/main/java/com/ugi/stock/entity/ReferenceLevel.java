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
 * 參考價位（reference_levels 表）：以 basisDate 收盤後的資料計算、供下一個交易日參考。
 * 由 Python 每日計算寫入（scraper/levels.py），每檔每天一筆；Java 只讀取。
 */
@Entity
@Immutable
@Table(name = "reference_levels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReferenceLevel {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "basis_date", nullable = false)
    private LocalDate basisDate;

    @Column(name = "close_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal closePrice;

    // 指標暖身期為 null
    @Column(precision = 12, scale = 4)
    private BigDecimal atr14;

    // 收盤價 − 3 × ATR14
    @Column(name = "atr_stop", precision = 12, scale = 4)
    private BigDecimal atrStop;

    @Column(name = "swing_days", nullable = false)
    private Integer swingDays;

    @Column(name = "swing_high", nullable = false, precision = 12, scale = 4)
    private BigDecimal swingHigh;

    @Column(name = "swing_high_date", nullable = false)
    private LocalDate swingHighDate;

    @Column(name = "swing_low", nullable = false, precision = 12, scale = 4)
    private BigDecimal swingLow;

    @Column(name = "swing_low_date", nullable = false)
    private LocalDate swingLowDate;

    // up：低點在前（黃金分割為回檔位置）/ down：高點在前（反彈位置）
    @Column(name = "swing_trend", nullable = false, length = 4)
    private String swingTrend;

    @Column(name = "fib_382", nullable = false, precision = 12, scale = 4)
    private BigDecimal fib382;

    @Column(name = "fib_500", nullable = false, precision = 12, scale = 4)
    private BigDecimal fib500;

    @Column(name = "fib_618", nullable = false, precision = 12, scale = 4)
    private BigDecimal fib618;

    @Column(name = "reference_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal referencePrice;

    // false：下一個交易日除權（或股利未公告），參考價以官方公告為準
    @Column(name = "reference_exact", nullable = false)
    private Boolean referenceExact;

    @Column(name = "limit_up", nullable = false, precision = 12, scale = 4)
    private BigDecimal limitUp;

    @Column(name = "limit_down", nullable = false, precision = 12, scale = 4)
    private BigDecimal limitDown;

    @Column(name = "next_ex_date")
    private LocalDate nextExDate;

    // 權 / 息 / 權息
    @Column(name = "next_ex_type", length = 4)
    private String nextExType;

    @Column(name = "next_cash_dividend", precision = 18, scale = 8)
    private BigDecimal nextCashDividend;
}
