package com.ugi.stock.service;

import com.ugi.stock.repository.AppUserRepository;
import com.ugi.stock.repository.UserIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 實際寫入資料庫，確認欄位對應與資料庫預設值（created_at）正確。
 * 測試在交易中執行並於結束時還原（rollback），不會留下資料。
 */
@SpringBootTest
@Transactional
class AppUserServiceDatabaseTests {

    @Autowired
    private AppUserService service;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserIdentityRepository identities;

    @Test
    void recordLoginPersistsUserAndIdentity() {
        long id = service.recordLogin("google", "test-sub-rollback", "Rollback.Test@Example.com", "Rollback Test");
        assertThat(service.recordLogin("google", "test-sub-rollback", "rollback.test@example.com", "Renamed")).isEqualTo(id);
        users.flush();

        var user = users.findById(id).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("rollback.test@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Renamed");
        assertThat(user.getLastLoginAt()).isNotNull();
        var identity = identities.findByProviderAndSubject("google", "test-sub-rollback").orElseThrow();
        assertThat(identity.getUser().getId()).isEqualTo(id);
    }
}
