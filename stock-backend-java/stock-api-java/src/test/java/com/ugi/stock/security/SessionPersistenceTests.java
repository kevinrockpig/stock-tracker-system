package com.ugi.stock.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登入狀態存在資料庫（spring_session，Python 建立）：登入後的 session 寫入後可完整讀回，
 * 等同 Java 重新啟動或部署後，使用者不需要重新登入。
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=") // 其他測試停用資料庫 session（見 src/test/resources/config/），這裡重新啟用
class SessionPersistenceTests {

    @Autowired
    private JdbcIndexedSessionRepository sessions;
    @Autowired
    private JdbcTemplate jdbc;

    // JdbcIndexedSessionRepository 的 session 類別不公開，以泛型方法取得
    private static <S extends Session> String saveLoggedIn(SessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(SecurityTests.loggedIn(42, "me@example.com")));
        repository.save(session);
        return session.getId();
    }

    @Test
    void loggedInSessionSurvivesRoundTripThroughDatabase() {
        String id = saveLoggedIn(sessions);
        try {
            Integer rows = jdbc.queryForObject("select count(*) from spring_session where session_id = ?", Integer.class, id);
            assertThat(rows).isOne();

            Session loaded = sessions.findById(id);
            assertThat(loaded).isNotNull();
            SecurityContext context = loaded.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            OAuth2AuthenticationToken authentication = (OAuth2AuthenticationToken) context.getAuthentication();
            AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
            assertThat(principal.getUserId()).isEqualTo(42);
            assertThat(principal.getEmail()).isEqualTo("me@example.com");
            assertThat(loaded.getMaxInactiveInterval()).hasHours(12);
        } finally {
            sessions.deleteById(id);
        }
        assertThat(sessions.findById(id)).isNull();
    }
}
