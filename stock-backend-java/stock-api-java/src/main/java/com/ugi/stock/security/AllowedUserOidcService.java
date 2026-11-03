package com.ugi.stock.security;

import com.ugi.stock.service.AppUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Google 登入成功後：檢查允許名單，通過才建立（或找出）本系統的使用者。不在名單內的帳號不會寫入任何資料。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AllowedUserOidcService extends OidcUserService {

    public static final String NOT_ALLOWED = "not_allowed";

    private final AuthSettings authSettings;
    private final AppUserService appUserService;

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser google = super.loadUser(request);
        if (!authSettings.isAllowed(google.getEmail(), google.getEmailVerified())) {
            log.warn("拒絕登入：帳號不在允許名單（或 email 未驗證）");
            throw new OAuth2AuthenticationException(new OAuth2Error(NOT_ALLOWED, "此帳號不在允許名單內", null));
        }
        String provider = request.getClientRegistration().getRegistrationId();
        long userId = appUserService.recordLogin(provider, google.getSubject(), google.getEmail(), google.getFullName());
        return new AppUserPrincipal(userId, google.getAuthorities(), google.getIdToken(), google.getUserInfo());
    }
}
