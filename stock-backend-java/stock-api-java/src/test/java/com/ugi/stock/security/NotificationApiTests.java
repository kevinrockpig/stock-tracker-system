package com.ugi.stock.security;

import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.service.AppUserService;
import com.ugi.stock.service.HoldingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 每日通知設定 API 的 HTTP 層：需要登入、寫入需要 CSRF、只能設定自己持有的股票、輸入檢查。交易結束時還原。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationApiTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserService users;
    @Autowired
    private HoldingService holdings;

    private static final String SETTINGS = """
            {"emailEnabled":true,"exDividendDays":5,"entryAlerts":false}""";

    @Test
    void settingsOverHttp() throws Exception {
        long id = users.recordLogin("google", "test-notify-http", "notify.http@example.com", "Http");
        var me = authentication(SecurityTests.loggedIn(id, "notify.http@example.com"));

        // 未登入 401；沒有 CSRF token 403
        mvc.perform(get("/api/notifications/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/preview")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/notifications/settings").with(me).contentType(MediaType.APPLICATION_JSON).content(SETTINGS))
                .andExpect(status().isForbidden());

        // 預設：未開啟、3 天、附上買進候選；寄到登入的 email
        mvc.perform(get("/api/notifications/settings").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false))
                .andExpect(jsonPath("$.exDividendDays").value(3))
                .andExpect(jsonPath("$.entryAlerts").value(true))
                .andExpect(jsonPath("$.email").value("notify.http@example.com"))
                .andExpect(jsonPath("$.riskOptions.length()").value(3))
                .andExpect(jsonPath("$.riskOptions[0].code").value("conservative"))
                .andExpect(jsonPath("$.riskOptions[0].atrMultiple").value(1.5))
                .andExpect(jsonPath("$.marketWeak").isBoolean());

        mvc.perform(put("/api/notifications/settings").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(SETTINGS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.exDividendDays").value(5))
                .andExpect(jsonPath("$.entryAlerts").value(false));

        // 輸入檢查
        mvc.perform(put("/api/notifications/settings").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(SETTINGS.replace("\"exDividendDays\":5", "\"exDividendDays\":31")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("除權息提醒天數必須是 0～30"));
        mvc.perform(put("/api/notifications/settings").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isBadRequest());

        // 預覽：與每日寄出的內容相同，不會寄出
        mvc.perform(get("/api/notifications/preview").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(startsWith("股票提醒 ")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("不構成投資建議")));
    }

    @Test
    void riskLevelOnlyForOwnHoldings() throws Exception {
        long id = users.recordLogin("google", "test-risk-http", "risk.http@example.com", "Risk");
        long otherId = users.recordLogin("google", "test-risk-http-2", "risk2.http@example.com", "Other");
        holdings.create(id, new HoldingRequest("2330.TW", null, BigDecimal.TEN, 1000, "standard", null, null, null));
        var me = authentication(SecurityTests.loggedIn(id, "risk.http@example.com"));
        var other = authentication(SecurityTests.loggedIn(otherId, "risk2.http@example.com"));
        String conservative = "{\"riskLevel\":\"conservative\"}";

        mvc.perform(put("/api/notifications/risk-levels/2330.tw").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(conservative))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevels['2330.TW']").value("conservative"));
        // 設回標準：刪除這筆（沒有列出即為標準）
        mvc.perform(put("/api/notifications/risk-levels/2330.TW").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskLevel\":\"standard\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevels['2330.TW']").doesNotExist());

        // 沒有持有的股票 404（包含別人持有的）、不認得的設定 400
        mvc.perform(put("/api/notifications/risk-levels/2330.TW").with(other).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(conservative))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/notifications/risk-levels/2454.TW").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(conservative))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/notifications/risk-levels/2330.TW").with(me).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskLevel\":\"yolo\"}"))
                .andExpect(status().isBadRequest());
    }
}
