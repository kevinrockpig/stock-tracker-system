package com.ugi.stock.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存取控制：既有 API 維持公開、個人資料需要登入、寫入需要 CSRF token、Google 登入的轉址內容。
 * 需要資料庫連線（與 contextLoads 相同，只讀取）。
 */
@SpringBootTest(properties = {
        "app.auth.google.client-id=test-client-id",
        "app.auth.google.client-secret=test-secret",
        "app.auth.allowed-emails=me@example.com",
})
@AutoConfigureMockMvc
class SecurityTests {

    @Autowired
    private MockMvc mvc;

    static OAuth2AuthenticationToken loggedIn(long userId, String email) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("token").subject("sub-" + userId)
                .claim("email", email).claim("name", "Test User").issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        return new OAuth2AuthenticationToken(new AppUserPrincipal(userId, authorities, idToken, null), authorities, "google");
    }

    @Test
    void existingApisStayPublic() throws Exception {
        mvc.perform(get("/api/groups")).andExpect(status().isOk());
        mvc.perform(get("/api/strategies")).andExpect(status().isOk());
    }

    @Test
    void corsUnchanged() throws Exception {
        mvc.perform(get("/api/groups").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(get("/api/groups").header("Origin", "http://evil.example.com")).andExpect(status().isForbidden());
    }

    @Test
    void meWhenAnonymous() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginEnabled").value(true))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.loginUrl").value("/oauth2/authorization/google"));
    }

    @Test
    void meWhenLoggedIn() throws Exception {
        mvc.perform(get("/api/me").with(authentication(loggedIn(1, "me@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.displayName").value("Test User"));
    }

    @Test
    void personalDataRequiresLogin() throws Exception {
        mvc.perform(get("/api/holdings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/holdings/1")).andExpect(status().isUnauthorized());
        // 登入後通過存取控制（使用者 1 沒有庫存）
        mvc.perform(get("/api/holdings").with(authentication(loggedIn(-1, "me@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    // 帶 token 的情形見 CsrfHttpTests（MockMvc 會替換 CSRF token 的儲存方式，需以實際 HTTP 測試）
    @Test
    void writesWithoutCsrfTokenRejected() throws Exception {
        mvc.perform(post("/api/logout").with(authentication(loggedIn(1, "me@example.com")))).andExpect(status().isForbidden());
    }

    @Test
    void loginRedirectsToGoogleWithBasicScopesOnly() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        assertThat(decoded).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(decoded).contains("client_id=test-client-id");
        assertThat(decoded).contains("scope=openid email profile");
        assertThat(decoded).contains("redirect_uri=http://localhost/login/oauth2/code/google");
        assertThat(decoded).doesNotContain("test-secret");
    }
}
