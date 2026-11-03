package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 使用者（app_users 表）：資料表由 Python 建立，資料列由 Java 在登入時寫入。
 * email 為登入方式提供的已驗證 email（小寫），一個 email 只對應一位使用者。
 */
@Entity
@Table(name = "app_users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", length = 100)
    private String displayName;

    // 由資料庫預設值填入
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public AppUser(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
    }

    public void recordLogin(String displayName, Instant at) {
        if (displayName != null) this.displayName = displayName;
        this.lastLoginAt = at;
    }
}
