package com.ugi.stock.repository;

import com.ugi.stock.entity.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    // 每日通知的收件人：開啟 Email 的使用者
    List<NotificationSetting> findByEmailEnabledTrueOrderByUserIdAsc();
}
