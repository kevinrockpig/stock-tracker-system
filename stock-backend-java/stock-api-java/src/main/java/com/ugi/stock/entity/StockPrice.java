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
 * 每日行情（stock_prices 表，一檔股票一個交易日一筆）。
 * 資料表由 Python 擷取服務建立與寫入，Java 只讀取，因此標記為 @Immutable。
 */
@Entity
@Immutable
@Table(name = "stock_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockPrice {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "open_price", precision = 12, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 12, scale = 4)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 12, scale = 4)
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal closePrice;

    private Long volume;
}
