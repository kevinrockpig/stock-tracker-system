package com.ugi.stock.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 每個 IP 每分鐘的請求數上限（app.rate-limit.per-minute，含網頁檔案），超過回 429。
 * 主機的流量超過免費額度會另外計費，這裡避免單一來源大量下載造成流量暴增；
 * 一般使用（開一頁約 10～15 個請求）不會碰到。計數只存在記憶體，重新啟動後歸零。
 * <p>
 * IP 取自 request.getRemoteAddr()：部署在反向代理之後時由 server.forward-headers-strategy 換成使用者的 IP；
 * 若平台的代理不在 Tomcat 預設信任的內部網段，所有人會共用同一個計數（等同全站上限），部署後需確認。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000;
    // 計數表超過這個筆數時清除過期的視窗，避免大量不同 IP 讓記憶體持續增加
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final int limit;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitFilter(@Value("${app.rate-limit.per-minute}") int limit) {
        this(limit, System::currentTimeMillis);
    }

    RateLimitFilter(int limit, LongSupplier clock) {
        this.limit = limit;
        this.clock = clock;
    }

    private record Window(long minute, int count) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limit <= 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = clock.getAsLong();
        long minute = now / WINDOW_MILLIS;
        Window window = windows.merge(request.getRemoteAddr(), new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> w.minute() < minute);
        }
        if (window.count() <= limit) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, ((minute + 1) * WINDOW_MILLIS - now + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"請求太頻繁，請 "
                + retryAfter + " 秒後再試\"}");
    }
}
