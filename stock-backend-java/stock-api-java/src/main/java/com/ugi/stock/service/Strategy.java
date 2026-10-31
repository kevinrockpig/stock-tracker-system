package com.ugi.stock.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 可查詢的選股策略。代碼必須與 Python 端 scraper/strategies.py 的 STRATEGIES 一致，
 * 判斷邏輯由 Python 每日掃描執行，Java 只負責查詢結果。
 */
public enum Strategy {

    MA_BULLISH("ma_bullish", "均線多頭排列", "MA5 > MA10 > MA20 > MA60", false),
    GOLDEN_CROSS("golden_cross", "MACD 黃金交叉", "DIF 由下往上穿越 MACD（只在交叉當天成立）", false),
    BREAK_MA20("break_ma20", "突破 20 日均線", "收盤價由下往上突破 MA20（只在突破當天成立）", false),
    // 波段策略：只搭配波段出場規則回測（Python 端 strategies.SWING_STRATEGIES）
    HIGH_120("high_120", "創 120 日新高", "收盤價高於前 120 個交易日的最高收盤價（創新高的每一天都成立）", true),
    MA20_CROSS_MA60("ma20_cross_ma60", "月線穿越季線", "MA20 由下往上穿越 MA60（只在穿越當天成立）", true),
    GOLDEN_CROSS_TREND("golden_cross_trend", "季線上揚時 MACD 黃金交叉",
            "MACD 黃金交叉當天，收盤在季線（MA60）之上，且季線比 20 個交易日前高", true);

    private final String code;
    private final String label;
    private final String description;
    private final boolean swingOnly;

    Strategy(String code, String label, String description, boolean swingOnly) {
        this.code = code;
        this.label = label;
        this.description = description;
        this.swingOnly = swingOnly;
    }

    public boolean swingOnly() {
        return swingOnly;
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
