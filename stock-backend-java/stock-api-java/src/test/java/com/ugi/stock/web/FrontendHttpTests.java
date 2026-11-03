package com.ugi.stock.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以實際 HTTP 驗證前端由 Java 提供（測試用的 index.html 與 assets 放在 src/test/resources/static/）：
 * 頁面路徑回傳 index.html、assets 長期快取、API 與不存在的檔案照常 404，以及回應壓縮。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendHttpTests {

    private static final String INDEX_MARKER = "frontend-test-index";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pagePathsServeIndex() throws Exception {
        for (String path : new String[]{"/", "/holdings", "/holdings?login=failed", "/stocks/2330.TW", "/about"}) {
            HttpResponse<String> response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains(INDEX_MARKER);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).as(path).startsWith("text/html");
            assertThat(response.headers().firstValue("Cache-Control")).as(path).hasValue("no-cache");
        }
    }

    @Test
    void hashedAssetsCachedLongTerm() throws Exception {
        HttpResponse<String> asset = get("/assets/app-test123.js");
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.body()).contains("frontend-test-asset");
        assertThat(asset.headers().firstValue("Cache-Control").orElse(""))
                .contains("max-age=31536000").contains("immutable");
    }

    @Test
    void apiAndMissingFilesAreNotPages() throws Exception {
        for (String path : new String[]{"/api/no-such-api", "/assets/missing.js", "/favicon.ico", "/robots.txt"}) {
            HttpResponse<String> response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).as(path).doesNotContain(INDEX_MARKER);
        }
        // 既有 API 不受影響
        HttpResponse<String> groups = get("/api/groups");
        assertThat(groups.statusCode()).isEqualTo(200);
        assertThat(groups.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    }

    @Test
    void largeResponsesCompressed() throws Exception {
        HttpResponse<String> stocks = get("/api/stocks", "Accept-Encoding", "gzip");
        assertThat(stocks.statusCode()).isEqualTo(200);
        assertThat(stocks.headers().firstValue("Content-Encoding")).hasValue("gzip");
        // 不支援壓縮的用戶端照常收到原始內容
        assertThat(get("/api/stocks").headers().firstValue("Content-Encoding")).isEmpty();
    }
}
