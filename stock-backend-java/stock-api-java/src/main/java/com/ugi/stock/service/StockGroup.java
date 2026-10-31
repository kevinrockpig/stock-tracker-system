package com.ugi.stock.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 觀察名單的組別。代碼必須與 Python 端 scraper/config.py 的 STOCK_GROUPS 一致；
 * 每檔股票所屬的組別由 Python 寫入 stocks.group_codes（以逗號分隔）。
 */
public enum StockGroup {

    TW50("TW50", "0050 成分股"),
    CUSTOM("CUSTOM", "自選");

    private final String code;
    private final String label;

    StockGroup(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<StockGroup> fromCode(String code) {
        return Arrays.stream(values()).filter(group -> group.code.equals(code)).findFirst();
    }
}
