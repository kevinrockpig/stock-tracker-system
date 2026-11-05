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

import java.time.Instant;

/**
 * 使用者自行加入的股票（user_stocks 表，我的自選）。資料表由 Python 建立；這裡只新增與刪除。
 * symbol 為 yfinance 代號（上市 .TW、上櫃 .TWO），加入時可能還沒有 stocks 那一筆（等下一次每日更新補抓）。
 */
@Entity
@Table(name = "user_stocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserStock(long userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
        this.createdAt = Instant.now();
    }
}
