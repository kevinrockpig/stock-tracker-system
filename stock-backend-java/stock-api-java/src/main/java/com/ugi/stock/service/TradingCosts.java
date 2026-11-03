package com.ugi.stock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 台股交易成本。費率必須與 Python 端 scraper/backtest.py 的 SELL_FEE / SELL_TAX_* 一致。
 */
public final class TradingCosts {

    /** 手續費公定費率（買賣各收一次） */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.001425");
    /** 證交稅（賣出）：股票 */
    public static final BigDecimal TAX_STOCK = new BigDecimal("0.003");
    /** 證交稅（賣出）：ETF（代號 00 開頭） */
    public static final BigDecimal TAX_ETF = new BigDecimal("0.001");

    private TradingCosts() {
    }

    public static boolean isEtf(String symbol) {
        return symbol.startsWith("00");
    }

    public static BigDecimal taxRate(String symbol) {
        return isEtf(symbol) ? TAX_ETF : TAX_STOCK;
    }

    /**
     * 手續費估算：成交金額 × 公定費率 × 折數，無條件捨去到元，低於最低手續費時以最低手續費計。
     * 各券商的捨入方式與最低手續費不同，結果只是估算。
     */
    public static BigDecimal fee(BigDecimal amount, BigDecimal discount, BigDecimal minFee) {
        BigDecimal fee = amount.multiply(FEE_RATE).multiply(discount).setScale(0, RoundingMode.FLOOR);
        return fee.max(minFee);
    }

    /** 證交稅估算：成交金額 × 稅率，無條件捨去到元 */
    public static BigDecimal tax(BigDecimal amount, String symbol) {
        return amount.multiply(taxRate(symbol)).setScale(0, RoundingMode.FLOOR);
    }
}
