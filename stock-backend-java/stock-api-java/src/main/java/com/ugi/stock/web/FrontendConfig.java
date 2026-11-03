package com.ugi.stock.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 部署時由 Java 一起提供前端（Vue build 後的檔案，Docker 映像建置時放進 classpath:/static/），
 * 網頁與 API 同一個網址，登入的 session cookie 才會隨 API 請求送出。
 * <ul>
 *   <li>/assets/**：檔名含內容雜湊（內容改變時檔名也會變），可讓瀏覽器長期快取</li>
 *   <li>其他路徑：有對應檔案就回傳該檔案；沒有時回傳 index.html，由 Vue Router 顯示對應頁面（例如 /holdings）。
 *       index.html 每次都向伺服器確認，部署新版後立即生效</li>
 *   <li>API、登入路徑與不存在的靜態檔案（.js、.png 等）不回傳 index.html，照常回 404</li>
 * </ul>
 * 開發時（沒有 static/index.html）不影響任何行為，前端由 Vite 開發伺服器提供。
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {

    static final List<String> NON_PAGE_PREFIXES = List.of("api/", "oauth2/", "login/", "assets/");
    // 頁面路徑本身可能有點（例如 /stocks/2330.TW），所以只把常見的靜態檔案副檔名當成檔案
    static final Set<String> FILE_EXTENSIONS = Set.of(
            "html", "js", "mjs", "css", "map", "json", "txt", "xml", "webmanifest",
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "woff", "woff2", "ttf");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }

    static class SpaResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = super.getResource(resourcePath, location);
            if (file != null) {
                return file;
            }
            if (!isPagePath(resourcePath)) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }
    }

    /** 前端頁面的路徑：不是 API、登入或 assets 路徑，最後一段也不是靜態檔案 */
    static boolean isPagePath(String resourcePath) {
        if (NON_PAGE_PREFIXES.stream().anyMatch(resourcePath::startsWith)) {
            return false;
        }
        String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        String extension = lastSegment.substring(lastSegment.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return !lastSegment.contains(".") || !FILE_EXTENSIONS.contains(extension);
    }
}
