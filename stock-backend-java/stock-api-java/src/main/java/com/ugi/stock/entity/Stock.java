package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 股票基本資料（stocks 表）。
 * 資料表由 Python 擷取服務建立與寫入，Java 只讀取，因此標記為 @Immutable。
 */
@Entity
@Immutable
@Table(name = "stocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Id
    @Column(length = 20)
    private String symbol;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 10)
    private String currency;
}
