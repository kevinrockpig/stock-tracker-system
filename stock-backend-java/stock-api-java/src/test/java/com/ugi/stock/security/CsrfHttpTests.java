package com.ugi.stock.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以實際 HTTP 驗證 CSRF：/api/me 寫入前端可讀的 XSRF-TOKEN cookie；寫入請求要把 cookie 的值放進 X-XSRF-TOKEN 標頭。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsrfHttpTests {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void csrfTokenCookieThenHeader() throws Exception {
        HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/me")));
        assertThat(me.statusCode()).isEqualTo(200);
        String setCookie = me.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(setCookie).doesNotContainIgnoringCase("HttpOnly"); // 前端要讀取它放進標頭
        String token = setCookie.substring("XSRF-TOKEN=".length(), setCookie.indexOf(';'));

        HttpRequest.Builder logout = HttpRequest.newBuilder(uri("/api/logout")).POST(HttpRequest.BodyPublishers.noBody());
        assertThat(send(logout).statusCode()).isEqualTo(403); // 沒有 token
        assertThat(send(logout.copy().header("Cookie", "XSRF-TOKEN=" + token)).statusCode()).isEqualTo(403); // 只有 cookie
        assertThat(send(logout.copy().header("Cookie", "XSRF-TOKEN=" + token).header("X-XSRF-TOKEN", "wrong")).statusCode())
                .isEqualTo(403); // 標頭與 cookie 不符
        assertThat(send(logout.copy().header("Cookie", "XSRF-TOKEN=" + token).header("X-XSRF-TOKEN", token)).statusCode())
                .isEqualTo(204);
    }

    @Test
    void securityHeaders() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/groups")));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(List.copyOf(response.headers().allValues("Set-Cookie"))).noneMatch(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID")); // 沒登入不建立 session
    }
}
