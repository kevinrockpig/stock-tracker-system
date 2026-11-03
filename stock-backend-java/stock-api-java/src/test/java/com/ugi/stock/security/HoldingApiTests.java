package com.ugi.stock.security;

import com.ugi.stock.service.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 庫存 API 的 HTTP 層：需要登入、寫入需要 CSRF、狀態碼與錯誤訊息。交易結束時還原。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HoldingApiTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserService users;

    private static final String BODY = """
            {"symbol":"2330.TW","buyDate":"2026-06-01","price":1000,"shares":1000,"feeMode":"standard"}""";

    @Test
    void crudOverHttp() throws Exception {
        long id = users.recordLogin("google", "test-http", "http.test@example.com", "Http");
        long otherId = users.recordLogin("google", "test-http-2", "http2.test@example.com", "Other");
        var me = authentication(SecurityTests.loggedIn(id, "http.test@example.com"));
        var other = authentication(SecurityTests.loggedIn(otherId, "http2.test@example.com"));

        // 未登入 401；沒有 CSRF token 403
        mvc.perform(post("/api/holdings").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/holdings").with(me).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        String created = mvc.perform(post("/api/holdings").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fee").value(1425))
                .andExpect(jsonPath("$.cost").value(1001425))
                .andReturn().getResponse().getContentAsString();
        long lotId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/holdings").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.positions[0].symbol").value("2330.TW"))
                .andExpect(jsonPath("$.positions[0].name").value("台積電"));
        mvc.perform(get("/api/holdings").with(other)).andExpect(jsonPath("$.count").value(0));

        // 錯誤輸入：400 與中文說明
        mvc.perform(post("/api/holdings").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"2330.TW\",\"price\":0,\"shares\":1,\"feeMode\":\"standard\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("價格必須大於 0"));
        mvc.perform(post("/api/holdings").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{壞掉的 JSON"))
                .andExpect(status().isBadRequest());

        // 別人的資料：404
        mvc.perform(put("/api/holdings/" + lotId).with(other).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/holdings/" + lotId).with(other).with(csrf())).andExpect(status().isNotFound());

        mvc.perform(put("/api/holdings/" + lotId).with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"price\":1000", "\"price\":900")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(900));
        mvc.perform(delete("/api/holdings/" + lotId).with(me).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/holdings").with(me)).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void noCrossOriginAccess() throws Exception {
        long id = users.recordLogin("google", "test-cors", "cors.test@example.com", "Cors");
        mvc.perform(get("/api/holdings").with(authentication(SecurityTests.loggedIn(id, "cors.test@example.com")))
                        .header("Origin", "http://evil.example.com"))
                // 庫存 API 沒有跨網域設定：回應不帶 Access-Control-Allow-Origin，瀏覽器不會讓其他網站讀取結果
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
