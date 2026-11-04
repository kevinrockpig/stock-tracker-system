package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 登入者的每日通知設定（GET /api/notifications/settings）。
 *
 * @param email           通知寄到的信箱（登入的 Google email）
 * @param riskLevels      股票代號 → 風險設定代碼；沒有列出的股票為 standard
 * @param riskOptions     可選的風險設定與對應的提醒範圍（幾個 ATR）
 * @param weakMarketExtra 大盤轉弱（0050 收盤低於季線）時提醒範圍再加幾個 ATR
 * @param marketWeak      最新交易日大盤是否轉弱（是則目前的提醒範圍已加上 weakMarketExtra）；缺資料時為 null
 */
public record NotificationSettingsResponse(
        boolean emailEnabled,
        int exDividendDays,
        int maxExDividendDays,
        boolean entryAlerts,
        String email,
        Map<String, String> riskLevels,
        List<RiskOption> riskOptions,
        BigDecimal weakMarketExtra,
        Boolean marketWeak
) {

    public record RiskOption(String code, String label, BigDecimal atrMultiple) {
    }
}
