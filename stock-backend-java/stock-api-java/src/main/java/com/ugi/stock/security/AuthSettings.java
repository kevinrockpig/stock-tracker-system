package com.ugi.stock.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登入設定（application.properties 的 app.auth.*，值由環境變數提供）。
 */
@Component
public class AuthSettings {

    private final String googleClientId;
    private final Set<String> allowedEmails;
    private final String frontendUrl;

    public AuthSettings(
            @Value("${app.auth.google.client-id}") String googleClientId,
            @Value("${app.auth.allowed-emails}") String allowedEmails,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.googleClientId = googleClientId;
        this.allowedEmails = Arrays.stream(allowedEmails.split(","))
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** 已設定 Google OAuth 用戶端（未設定時登入功能關閉，其餘 API 照常） */
    public boolean loginEnabled() {
        return !googleClientId.isBlank();
    }

    /** email 必須已由 Google 驗證，且在允許名單內；名單空白時任何人都不能登入 */
    public boolean isAllowed(String email, Boolean emailVerified) {
        return email != null && Boolean.TRUE.equals(emailVerified)
                && allowedEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    /** 登入成功或失敗後要回到的前端網址 */
    public String frontendUrl(String path) {
        return frontendUrl + path;
    }
}
