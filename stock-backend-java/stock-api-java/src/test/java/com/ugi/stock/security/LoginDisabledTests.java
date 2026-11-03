package com.ugi.stock.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 未設定 GOOGLE_CLIENT_ID：服務照常啟動，既有 API 可用，登入功能關閉。 */
@SpringBootTest(properties = "app.auth.google.client-id=")
@AutoConfigureMockMvc
class LoginDisabledTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void startsWithoutGoogleSettings() throws Exception {
        mvc.perform(get("/api/groups")).andExpect(status().isOk());
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginEnabled").value(false))
                .andExpect(jsonPath("$.loginUrl").doesNotExist());
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isNotFound());
        mvc.perform(get("/api/holdings")).andExpect(status().isUnauthorized());
    }
}
