package com.ugi.stock.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "stock_prices", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"symbol", "trade_date"})
})
public class StockPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(precision = 10, scale = 2)
    private BigDecimal openPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal highPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal lowPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal closePrice;

    private Long volume;
}