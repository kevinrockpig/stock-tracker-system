package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

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

    // 所屬組別代碼，以逗號分隔（例如 "TW50,CUSTOM"）；null 為已從觀察名單移除（資料保留）
    @Column(name = "group_codes", length = 100)
    private String groupCodes;

    // 上市（上櫃）日期，只有這天起的日 K；ETF 為 null
    @Column(name = "listed_date")
    private LocalDate listedDate;

    public List<String> groups() {
        return groupCodes == null || groupCodes.isBlank() ? List.of() : Arrays.asList(groupCodes.split(","));
    }
}
