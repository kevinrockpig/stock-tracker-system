package com.ugi.stock.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * 個股風險設定：收盤價距離移動停利線幾個 ATR 以內，每日通知就提醒「接近停利線」。
 * 保守的提醒範圍較寬（較早提醒），積極的較窄；大盤轉弱（0050 收盤低於季線）時各加 {@link #WEAK_MARKET_EXTRA}。
 */
public enum RiskLevel {
    CONSERVATIVE("conservative", "保守", new BigDecimal("1.5")),
    STANDARD("standard", "標準", BigDecimal.ONE),
    AGGRESSIVE("aggressive", "積極", new BigDecimal("0.5"));

    public static final BigDecimal WEAK_MARKET_EXTRA = new BigDecimal("0.5");

    private final String code;
    private final String label;
    private final BigDecimal atrMultiple;

    RiskLevel(String code, String label, BigDecimal atrMultiple) {
        this.code = code;
        this.label = label;
        this.atrMultiple = atrMultiple;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public BigDecimal atrMultiple() {
        return atrMultiple;
    }

    public static Optional<RiskLevel> fromCode(String code) {
        return Arrays.stream(values()).filter(level -> level.code.equals(code)).findFirst();
    }
}
