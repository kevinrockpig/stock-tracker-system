package com.ugi.stock.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 可查詢的選股策略。代碼必須與 Python 端 scraper/strategies.py 的 STRATEGIES 一致，
 * 判斷邏輯由 Python 每日掃描執行，Java 只負責查詢結果。
 */
public enum Strategy {

    MA_BULLISH("ma_bullish", "均線多頭排列", "MA5 > MA10 > MA20 > MA60"),
    GOLDEN_CROSS("golden_cross", "MACD 黃金交叉", "DIF 由下往上穿越 MACD（只在交叉當天成立）"),
    BREAK_MA20("break_ma20", "突破 20 日均線", "收盤價由下往上突破 MA20（只在突破當天成立）");

    private final String code;
    private final String label;
    private final String description;

    Strategy(String code, String label, String description) {
        this.code = code;
        this.label = label;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static Optional<Strategy> fromCode(String code) {
        return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
    }
}
