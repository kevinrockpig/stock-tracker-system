package com.ugi.stock.security;

import com.ugi.stock.service.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 我的自選 API：需要登入、寫入需要 CSRF、使用者取自登入狀態（只能動自己的）、錯誤回 400 / 404 與訊息。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyStockApiTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserService users;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void myStocksOverHttp() throws Exception {
        jdbc.update("insert into official_daily_quotes (market, code, name, trade_date, source) "
                + "values ('TPEX', '6488', '環球晶', (select max(trade_date) from stock_prices), 'test')");
        long id = users.recordLogin("google", "test-my-http", "my.http@example.com", "Http");
        long otherId = users.recordLogin("google", "test-my-http-2", "my.http2@example.com", "Other");
        var me = authentication(SecurityTests.loggedIn(id, "my.http@example.com"));
        var other = authentication(SecurityTests.loggedIn(otherId, "my.http2@example.com"));
        String body = "{\"code\":\"6488\"}";

        mvc.perform(get("/api/my-stocks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/my-stocks").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/my-stocks").with(me).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()); // 沒有 CSRF token

        mvc.perform(post("/api/my-stocks").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("6488.TWO"))
                .andExpect(jsonPath("$.name").value("環球晶"))
                .andExpect(jsonPath("$.ready").value(false));
        mvc.perform(post("/api/my-stocks").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/my-stocks").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].symbol").value("6488.TWO"))
                .andExpect(jsonPath("$.limit").value(30));
        mvc.perform(get("/api/my-stocks").with(other)).andExpect(jsonPath("$.stocks").isEmpty());

        mvc.perform(delete("/api/my-stocks/6488.TWO").with(other).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/my-stocks/6488.TWO").with(me).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/my-stocks").with(me)).andExpect(jsonPath("$.stocks").isEmpty());
    }
}
