package com.ugi.stock.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * 存取控制：
 * <ul>
 *   <li>行情、回測、交易計畫等既有 API 維持公開，不需登入</li>
 *   <li>個人資料（/api/holdings/**、/api/notifications/**）需要登入；未登入回 401（不轉址），由前端顯示登入按鈕</li>
 *   <li>登入：前端導向 /oauth2/authorization/google，Google 驗證後回到 /login/oauth2/code/google，
 *       成功轉回前端的庫存頁、失敗轉回並附原因；登入狀態存在伺服器端 session（HttpOnly cookie）</li>
 *   <li>CSRF：寫入請求（POST / PUT / DELETE，含登出）必須在 X-XSRF-TOKEN 標頭附上 XSRF-TOKEN cookie 的值</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthSettings authSettings,
            AllowedUserOidcService oidcUserService,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations) throws Exception {
        http
                .cors(Customizer.withDefaults()) // 沿用各 Controller 的 @CrossOrigin 設定
                .csrf(csrf -> csrf.spa())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/holdings", "/api/holdings/**", "/api/my-stocks", "/api/my-stocks/**", "/api/notifications/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .requestCache(cache -> cache.disable())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(login -> login
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                    .successHandler((request, response, authentication) ->
                            response.sendRedirect(authSettings.frontendUrl("/holdings")))
                    .failureHandler((request, response, exception) -> {
                        String reason = exception instanceof OAuth2AuthenticationException e
                                && AllowedUserOidcService.NOT_ALLOWED.equals(e.getError().getErrorCode())
                                ? AllowedUserOidcService.NOT_ALLOWED : "failed";
                        response.sendRedirect(authSettings.frontendUrl("/holdings?login=" + reason));
                    }));
        }
        return http.build();
    }

    // 有設定 GOOGLE_CLIENT_ID 才啟用 Google 登入；只要求 openid、email、profile 三項基本資料
    @Bean
    @ConditionalOnExpression("!'${app.auth.google.client-id:}'.isBlank()")
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.auth.google.client-id}") String clientId,
            @Value("${app.auth.google.client-secret}") String clientSecret) {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "email", "profile")
                .build());
    }

    // 不使用帳號密碼登入；提供空的使用者清單，避免 Spring Boot 自動產生一組預設密碼
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
