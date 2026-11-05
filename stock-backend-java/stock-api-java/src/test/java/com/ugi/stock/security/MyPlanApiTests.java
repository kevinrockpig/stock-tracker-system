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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 我的計畫 API：需要登入、寫入需要 CSRF、每人一筆（只能讀寫自己的）、欄位檢查。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyPlanApiTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserService users;

    private static String body(String version, String frequency, String capital, String maxLoss, String invest) {
        return String.format("{\"version\":\"%s\",\"frequency\":\"%s\",\"capital\":%s,\"maxLoss\":%s,\"investAmount\":%s}",
                version, frequency, capital, maxLoss, invest);
    }

    @Test
    void planOverHttp() throws Exception {
        long id = users.recordLogin("google", "test-plan", "plan.http@example.com", "Plan");
        long otherId = users.recordLogin("google", "test-plan-2", "plan.http2@example.com", "Other");
        var me = authentication(SecurityTests.loggedIn(id, "plan.http@example.com"));
        var other = authentication(SecurityTests.loggedIn(otherId, "plan.http2@example.com"));
        String ok = body("balanced", "daily", "1000000", "0.35", "1000000");

        mvc.perform(get("/api/my-plan")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/my-plan").with(me).contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isForbidden());

        mvc.perform(get("/api/my-plan").with(me)).andExpect(status().isOk()).andExpect(jsonPath("$.plan").isEmpty());
        mvc.perform(put("/api/my-plan").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("balanced"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        // 再存一次是修改，不是新增
        mvc.perform(put("/api/my-plan").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("core", "weekly", "500000", "0.2", "300000")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/my-plan").with(me))
                .andExpect(jsonPath("$.plan.version").value("core"))
                .andExpect(jsonPath("$.plan.frequency").value("weekly"))
                .andExpect(jsonPath("$.plan.capital").value(500000))
                .andExpect(jsonPath("$.plan.maxLoss").value(0.2))
                .andExpect(jsonPath("$.plan.investAmount").value(300000));
        mvc.perform(get("/api/my-plan").with(other)).andExpect(jsonPath("$.plan").isEmpty());

        for (String bad : new String[]{
                body("aggressive", "daily", "1000000", "0.35", "1000000"),
                body("balanced", "monthly", "1000000", "0.35", "1000000"),
                body("balanced", "daily", "5000", "0.35", "5000"),
                body("balanced", "daily", "1000000.5", "0.35", "1000000"),
                body("balanced", "daily", "1000000", "0.9", "1000000"),
                body("balanced", "daily", "1000000", "0.01", "1000000"),
                body("balanced", "daily", "1000000", "0.35", "1000001"),
                body("balanced", "daily", "1000000", "0.35", "-1")}) {
            mvc.perform(put("/api/my-plan").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/my-plan").with(me)).andExpect(jsonPath("$.plan.version").value("core")); // 錯誤的不會寫入

        mvc.perform(delete("/api/my-plan").with(me).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/my-plan").with(me)).andExpect(jsonPath("$.plan").isEmpty());
        mvc.perform(delete("/api/my-plan").with(me).with(csrf())).andExpect(status().isNoContent()); // 沒有計畫時刪除也不出錯
    }
}
