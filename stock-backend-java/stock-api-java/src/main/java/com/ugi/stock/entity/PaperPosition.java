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
 * 模擬帳戶的部位（paper_positions 表）。exitDate 為 null 時為持有中。
 */
@Entity
@Immutable
@Table(name = "paper_positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaperPosition {

    @Id
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "signal_date", nullable = false)
    private LocalDate signalDate;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "entry_price", nullable = false)
    private BigDecimal entryPrice;

    // 股數（可為零股，依金額買進）
    @Column(nullable = false)
    private BigDecimal shares;

    // 買進金額（含手續費）
    @Column(nullable = false)
    private BigDecimal cost;

    @Column(name = "highest_close")
    private BigDecimal highestClose;

    @Column(name = "held_days", nullable = false)
    private Integer heldDays;

    // 已入帳的現金股利（元）
    @Column(nullable = false)
    private BigDecimal dividends;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Column(name = "exit_price")
    private BigDecimal exitPrice;

    // trailing：跌破移動停利線 / ma_break：跌破季線 / hold：持有期滿 / max_hold：達最長天數
    @Column(name = "exit_reason", length = 10)
    private String exitReason;

    @Column(name = "net_return")
    private BigDecimal netReturn;
}
