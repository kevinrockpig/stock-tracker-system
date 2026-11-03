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
 * 庫存（holdings 表）：使用者自行填寫的一次買進。資料表由 Python 建立（含 CHECK 約束），資料列由 Java 依登入者寫入。
 * 價格與股數為買進當時的數字；之後若有分割、配股、減資，分析時再依 stock_actions 換算成目前的基準。
 */
@Entity
@Table(name = "holdings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String symbol;

    // 選填：移動停利線、已領股利、同期比較需要
    @Column(name = "buy_date")
    private LocalDate buyDate;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer shares;

    // actual 實際金額 / standard 公定費率 / discount 公定費率 × 折數
    @Column(name = "fee_mode", nullable = false, length = 10)
    private String feeMode;

    @Column(name = "fee_discount")
    private BigDecimal feeDiscount;

    @Column(name = "min_fee")
    private BigDecimal minFee;

    // 買進手續費（實際填寫或計算後的金額）
    @Column(nullable = false)
    private BigDecimal fee;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    // 新增時由資料庫預設值填入，修改時由程式更新
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    public Holding(long userId) {
        this.userId = userId;
    }
}
