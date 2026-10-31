package com.ugi.stock.service;

/**
 * 回測出場規則的分組。代碼必須與 Python 端 scraper/backtest.py 的 SHORT / SWING 一致。
 * 「同時比較的組數」以組為單位計算：兩組回答的是不同問題（短線進出 vs. 波段持有）。
 */
public enum Horizon {

    SHORT("short", "短線"),
    SWING("swing", "波段");

    private final String code;
    private final String label;

    Horizon(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
