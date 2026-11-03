package com.ugi.stock.security;

import com.ugi.stock.service.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Google 登入後的允許名單檢查：只有名單內、email 已驗證的帳號會寫入使用者資料。 */
class AllowedUserOidcServiceTests {

    private final AppUserService appUserService = mock(AppUserService.class);
    private final AllowedUserOidcService service = new AllowedUserOidcService(
            new AuthSettings("client-id", "me@example.com", "http://localhost:5173"), appUserService);

    // 不設定 userinfo 網址：只用 ID token 的內容建立使用者，不連線 Google
    private static OidcUserRequest request(String email, boolean verified) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("client-id").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .userNameAttributeName("sub").scope("openid", "email", "profile").build();
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token").subject("google-sub")
                .claim("email", email).claim("email_verified", verified).claim("name", "Me")
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now,
                now.plusSeconds(60), Set.of("openid", "email", "profile"));
        return new OidcUserRequest(registration, accessToken, idToken);
    }

    @Test
    void allowedUserIsRecorded() {
        when(appUserService.recordLogin(any(), any(), any(), any())).thenReturn(42L);

        OidcUser user = service.loadUser(request("me@example.com", true));

        assertThat(user).isInstanceOf(AppUserPrincipal.class);
        assertThat(((AppUserPrincipal) user).getUserId()).isEqualTo(42L);
        verify(appUserService).recordLogin("google", "google-sub", "me@example.com", "Me");
    }

    @Test
    void strangerIsRejectedWithoutWritingAnything() {
        assertThatThrownBy(() -> service.loadUser(request("stranger@example.com", true)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode())
                .isEqualTo(AllowedUserOidcService.NOT_ALLOWED);
        verifyNoInteractions(appUserService);
    }

    @Test
    void unverifiedEmailIsRejected() {
        assertThatThrownBy(() -> service.loadUser(request("me@example.com", false)))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(appUserService);
    }
}
