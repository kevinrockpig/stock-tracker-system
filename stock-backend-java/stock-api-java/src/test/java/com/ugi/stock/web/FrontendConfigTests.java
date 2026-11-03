package com.ugi.stock.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendConfigTests {

    @Test
    void pagePaths() {
        assertThat(FrontendConfig.isPagePath("")).isTrue();
        assertThat(FrontendConfig.isPagePath("holdings")).isTrue();
        assertThat(FrontendConfig.isPagePath("stocks/2330.TW")).isTrue();
        assertThat(FrontendConfig.isPagePath("stocks/6488.TWO")).isTrue();
        assertThat(FrontendConfig.isPagePath("api/stocks")).isFalse();
        assertThat(FrontendConfig.isPagePath("login/oauth2/code/google")).isFalse();
        assertThat(FrontendConfig.isPagePath("oauth2/authorization/google")).isFalse();
        assertThat(FrontendConfig.isPagePath("assets/index-abc.js")).isFalse();
        assertThat(FrontendConfig.isPagePath("favicon.ico")).isFalse();
        assertThat(FrontendConfig.isPagePath("logo.PNG")).isFalse();
    }
}
