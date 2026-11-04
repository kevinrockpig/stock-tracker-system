package com.ugi.stock.dto;

import java.time.LocalDate;

/**
 * 依目前設定與最新收盤資料產生的通知內容（GET /api/notifications/preview），與每日寄出的 Email 相同，不會寄出。
 */
public record NotificationPreview(LocalDate asOf, String subject, String body, int alertCount) {
}
