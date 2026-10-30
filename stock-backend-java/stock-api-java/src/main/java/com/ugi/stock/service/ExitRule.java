package com.ugi.stock.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 回測的出場規則。代碼必須與 Python 端 scraper/backtest.py 的 EXIT_RULES 一致，
 * 回測由 Python 每日計算，Java 只負責查詢結果。
 */
public enum ExitRule {

    HOLD_5("hold_5", "持有 5 日", "進場後第 5 個交易日（含進場日）收盤賣出"),
    HOLD_10("hold_10", "持有 10 日", "進場後第 10 個交易日（含進場日）收盤賣出"),
    HOLD_20("hold_20", "持有 20 日", "進場後第 20 個交易日（含進場日）收盤賣出"),
    TRAILING_ATR("trailing_atr", "移動停利",
            "停利線 = 進場後最高收盤價 − 3 × ATR(14)；收盤跌破則隔日開盤賣出，最長持有 60 個交易日");

    private final String code;
    private final String label;
    private final String description;

    ExitRule(String code, String label, String description) {
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

    public static Optional<ExitRule> fromCode(String code) {
        return Arrays.stream(values()).filter(rule -> rule.code.equals(code)).findFirst();
    }
}
