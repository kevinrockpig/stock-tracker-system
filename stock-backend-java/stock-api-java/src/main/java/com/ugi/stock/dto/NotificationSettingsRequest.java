package com.ugi.stock.dto;

/**
 * 修改每日通知設定（PUT /api/notifications/settings），三個欄位都必填。
 *
 * @param exDividendDays 幾天內除權息要提醒（0～30，0 表示不提醒）
 */
public record NotificationSettingsRequest(Boolean emailEnabled, Integer exDividendDays, Boolean entryAlerts) {
}
