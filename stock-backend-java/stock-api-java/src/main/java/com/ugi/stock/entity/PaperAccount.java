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
 * 交易計畫的模擬帳戶（paper_accounts 表）：每個觀察中策略一個，由 Python 每日逐日執行（scraper/paper.py），Java 只讀取。
 */
@Entity
@Immutable
@Table(name = "paper_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaperAccount {

    @Id
    private Long id;

    @Column(nullable = false, length = 30)
    private String strategy;

    @Column(name = "exit_rule", nullable = false, length = 20)
    private String exitRule;

    @Column(nullable = false)
    private Integer slots;

    @Column(name = "initial_capital", nullable = false)
    private BigDecimal initialCapital;

    // 啟用日：從這天收盤後開始產生計畫
    @Column(name = "inception_date", nullable = false)
    private LocalDate inceptionDate;

    // 已處理到的交易日
    @Column(name = "last_processed", nullable = false)
    private LocalDate lastProcessed;

    @Column(nullable = false)
    private BigDecimal cash;
}
