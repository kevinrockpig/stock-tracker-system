package com.ugi.stock.dto;

/**
 * 設定一檔持股的風險設定（PUT /api/notifications/risk-levels/{symbol}）。
 *
 * @param riskLevel conservative / standard / aggressive
 */
public record RiskLevelRequest(String riskLevel) {
}
