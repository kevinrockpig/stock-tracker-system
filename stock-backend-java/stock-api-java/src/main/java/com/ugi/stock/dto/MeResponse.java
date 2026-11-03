package com.ugi.stock.dto;

/**
 * 目前的登入狀態（GET /api/me）。
 *
 * @param loginEnabled  伺服器已設定 Google 登入
 * @param authenticated 已登入
 * @param loginUrl      前端導向這個網址開始 Google 登入（loginEnabled 為 false 時為 null）
 */
public record MeResponse(boolean loginEnabled, boolean authenticated, String email, String displayName, String loginUrl) {
}
