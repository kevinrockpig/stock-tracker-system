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
 * 模擬帳戶每個交易日收盤後的資金（paper_equity 表）。
 */
@Entity
@Immutable
@Table(name = "paper_equity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaperEquity {

    @Id
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private BigDecimal cash;

    @Column(nullable = false)
    private BigDecimal holdings;

    @Column(nullable = false)
    private BigDecimal equity;

    @Column(nullable = false)
    private Integer positions;

    // 啟用日 = 1，含股利：同一批股票自啟用日收盤等額買進持有；0050 ETF 買進持有（參考）
    private BigDecimal benchmark;

    private BigDecimal etf;
}
