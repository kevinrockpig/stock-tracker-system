package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 賣出紀錄（holding_sales 表）：使用者自行填寫的一次賣出。資料表由 Python 建立，資料列由 Java 依登入者寫入。
 * 價格與股數為賣出當時的數字；從哪幾筆買進扣（先進先出）與已實現損益每次即時計算，不另存。
 */
@Entity
@Table(name = "holding_sales")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HoldingSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "sell_date", nullable = false)
    private LocalDate sellDate;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer shares;

    // actual 實際金額 / standard 公定費率 / discount 公定費率 × 折數（同 holdings）
    @Column(name = "fee_mode", nullable = false, length = 10)
    private String feeMode;

    @Column(name = "fee_discount")
    private BigDecimal feeDiscount;

    @Column(name = "min_fee")
    private BigDecimal minFee;

    @Column(nullable = false)
    private BigDecimal fee;

    // standard 依法定稅率計算 / actual 實際金額
    @Column(name = "tax_mode", nullable = false, length = 10)
    private String taxMode;

    @Column(nullable = false)
    private BigDecimal tax;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    // 新增時由資料庫預設值填入，修改時由程式更新
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    public HoldingSale(long userId) {
        this.userId = userId;
    }
}
