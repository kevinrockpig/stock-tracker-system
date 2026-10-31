package com.ugi.stock.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * 回測的出場規則。代碼必須與 Python 端 scraper/backtest.py 的 EXIT_RULES 一致，
 * 回測由 Python 每日計算，Java 只負責查詢結果。
 */
public enum ExitRule {

    HOLD_5("hold_5", "持有 5 日", "進場後第 5 個交易日（含進場日）收盤賣出", Horizon.SHORT),
    HOLD_10("hold_10", "持有 10 日", "進場後第 10 個交易日（含進場日）收盤賣出", Horizon.SHORT),
    HOLD_20("hold_20", "持有 20 日", "進場後第 20 個交易日（含進場日）收盤賣出", Horizon.SHORT),
    TRAILING_ATR("trailing_atr", "移動停利",
            "停利線 = 進場後最高收盤價 − 3 × ATR(14)；收盤跌破則隔日開盤賣出，最長持有 60 個交易日", Horizon.SHORT),
    MA60_EXIT("ma60_exit", "跌破季線出場", "收盤跌破季線（MA60）則隔日開盤賣出，不設最長持有天數", Horizon.SWING),
    TRAILING_NOCAP("trailing_nocap", "移動停利（不設上限）",
            "停利線 = 進場後最高收盤價 − 3 × ATR(14)；收盤跌破則隔日開盤賣出，不設最長持有天數", Horizon.SWING);

    private final String code;
    private final String label;
    private final String description;
    private final Horizon horizon;

    ExitRule(String code, String label, String description, Horizon horizon) {
        this.code = code;
        this.label = label;
        this.description = description;
        this.horizon = horizon;
    }

    public Horizon horizon() {
        return horizon;
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
