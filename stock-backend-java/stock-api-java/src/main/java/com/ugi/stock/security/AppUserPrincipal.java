package com.ugi.stock.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.Collection;

/**
 * 登入後的使用者：Google 提供的資料，加上本系統的使用者 ID（app_users.id）。
 * 庫存等個人資料一律以 userId 過濾，不使用前端傳來的任何使用者識別。
 */
public class AppUserPrincipal extends DefaultOidcUser {

    private final long userId;

    public AppUserPrincipal(long userId, Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken, OidcUserInfo userInfo) {
        super(authorities, idToken, userInfo);
        this.userId = userId;
    }

    public long getUserId() {
        return userId;
    }
}
