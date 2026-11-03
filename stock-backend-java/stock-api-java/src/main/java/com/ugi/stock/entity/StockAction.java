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
 * 除權息與股本事件（stock_actions 表，來源 yfinance），由 Python 寫入，Java 只讀取。
 * 金額與 stock_prices 的價格同一基準：之後若有分割 / 配股 / 減資，之前的價格與股利都已等比例調整。
 */
@Entity
@Immutable
@Table(name = "stock_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockAction {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    // 每股現金股利
    @Column(name = "cash_dividend")
    private BigDecimal cashDividend;

    // 新股數 / 舊股數：配股 1.1 = 每股配 0.1 股、減資 0.8、1 拆 4 = 4
    @Column(name = "split_ratio")
    private BigDecimal splitRatio;
}
