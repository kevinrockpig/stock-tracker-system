package com.ugi.stock.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSettingsTests {

    private AuthSettings settings(String clientId, String allowed) {
        return new AuthSettings(clientId, allowed, "http://localhost:5173/");
    }

    @Test
    void allowsOnlyVerifiedEmailsInList() {
        AuthSettings settings = settings("id", " Me@Example.com , other@example.com ");
        assertThat(settings.isAllowed("me@example.com", true)).isTrue();
        assertThat(settings.isAllowed("ME@EXAMPLE.COM", true)).isTrue(); // 不分大小寫
        assertThat(settings.isAllowed("me@example.com", false)).isFalse(); // email 未驗證
        assertThat(settings.isAllowed("me@example.com", null)).isFalse();
        assertThat(settings.isAllowed("stranger@example.com", true)).isFalse();
        assertThat(settings.isAllowed(null, true)).isFalse();
    }

    @Test
    void emptyListAllowsNobody() {
        assertThat(settings("id", "").isAllowed("me@example.com", true)).isFalse();
        assertThat(settings("id", " , ").isAllowed("", true)).isFalse();
    }

    @Test
    void loginEnabledOnlyWithClientId() {
        assertThat(settings("id", "").loginEnabled()).isTrue();
        assertThat(settings(" ", "").loginEnabled()).isFalse();
    }

    @Test
    void frontendUrlWithoutDoubleSlash() {
        assertThat(settings("id", "").frontendUrl("/holdings")).isEqualTo("http://localhost:5173/holdings");
    }
}
