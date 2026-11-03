package com.ugi.stock.controller;

import com.ugi.stock.dto.MeResponse;
import com.ugi.stock.security.AppUserPrincipal;
import com.ugi.stock.security.AuthSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController {

    static final String GOOGLE_LOGIN_URL = "/oauth2/authorization/google";

    private final AuthSettings authSettings;

    // 目前的登入狀態。前端在載入時呼叫；同時寫入 XSRF-TOKEN cookie，之後的寫入請求以它通過 CSRF 檢查
    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal AppUserPrincipal user, CsrfToken csrfToken) {
        csrfToken.getToken(); // 讀取一次才會產生並寫入 cookie
        String loginUrl = authSettings.loginEnabled() ? GOOGLE_LOGIN_URL : null;
        if (user == null) {
            return new MeResponse(authSettings.loginEnabled(), false, null, null, loginUrl);
        }
        return new MeResponse(authSettings.loginEnabled(), true, user.getEmail(), user.getFullName(), loginUrl);
    }
}
