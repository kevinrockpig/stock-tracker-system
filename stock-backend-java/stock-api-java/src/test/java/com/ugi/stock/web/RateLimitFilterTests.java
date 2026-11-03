package com.ugi.stock.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTests {

    private final AtomicLong now = new AtomicLong(1_000_000L * 60_000 + 15_000); // 某一分鐘的第 15 秒

    private MockHttpServletResponse call(RateLimitFilter filter, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stocks");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void limitPerIpPerMinute() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3, now::get);
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "1.1.1.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call(filter, "1.1.1.1");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("45"); // 到下一分鐘還有 45 秒
        assertThat(blocked.getContentType()).startsWith("application/problem+json");
        assertThat(blocked.getContentAsString()).contains("請求太頻繁");

        // 其他 IP 不受影響
        assertThat(call(filter, "2.2.2.2").getStatus()).isEqualTo(200);
        // 下一分鐘重新計算
        now.addAndGet(45_000);
        assertThat(call(filter, "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void zeroDisablesLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(0, now::get);
        for (int i = 0; i < 50; i++) {
            assertThat(call(filter, "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }
}
