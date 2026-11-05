package com.ugi.stock.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * R1 研究得出的共同規則（2026-11-05，見 stock-scraper-python/research 的 simulator.py、report.py 與 TODO.md 的 R1）。
 * 三個版本（核心為主 / 均衡 / 策略為主）只差核心 0050 的比例，其餘規則相同。數值需與研究程式一致。
 */
public final class PlanRules {

    // 選股訊號（複選）：任一出現即為候選
    public static final List<Strategy> SIGNALS = List.of(Strategy.HIGH_120, Strategy.GOLDEN_CROSS_TREND);
    // 核心
    public static final String CORE_SYMBOL = "0050.TW";
    // 大盤濾網：0050 收盤低於 60 日均線時不開新倉（已持有的照出場規則處理）
    public static final int MARKET_MA_DAYS = 60;
    // 出場：從買進後的最高收盤價回落 10%
    public static final BigDecimal TRAILING_STOP = new BigDecimal("0.10");
    // 開盤比前一天收盤高 9.5% 以上（接近漲停、實際很難買到）就放棄
    public static final BigDecimal SKIP_GAP_UP = new BigDecimal("0.095");
    // 候選太多時，優先買 120 個交易日漲幅較大的
    public static final int STRENGTH_DAYS = 120;
    // 部位大小：每筆最多虧衛星資金的 1%（停損距離 10% → 每檔約衛星資金的 10%），單檔上限 20%，最多同時 10 檔
    public static final BigDecimal RISK_PER_TRADE = new BigDecimal("0.01");
    public static final BigDecimal MAX_WEIGHT = new BigDecimal("0.20");
    public static final int SLOTS = 10;

    private PlanRules() {
    }
}
