package com.ugi.stock.notify;

import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.entity.NotificationLog;
import com.ugi.stock.entity.NotificationSetting;
import com.ugi.stock.repository.AppUserRepository;
import com.ugi.stock.repository.NotificationLogRepository;
import com.ugi.stock.repository.NotificationSettingRepository;
import com.ugi.stock.service.AppUserService;
import com.ugi.stock.service.HoldingService;
import com.ugi.stock.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每日通知的寄送流程：以假的寄送器取代 Email（不會真的寄出）。測試在交易中執行並於結束時還原。
 */
@SpringBootTest
@Transactional
class NotificationJobTests {

    /** 只記錄、不寄出；fail 為 true 時模擬寄送失敗 */
    static class RecordingSender implements NotificationSender {
        final List<String[]> messages = new ArrayList<>();
        boolean fail;

        @Override
        public String channel() {
            return "email";
        }

        @Override
        public void send(String to, String subject, String body) {
            if (fail) {
                throw new IllegalStateException("SMTP 連線失敗（測試）");
            }
            messages.add(new String[]{to, subject, body});
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @Autowired
    private NotificationJob job;
    @Autowired
    private RecordingSender sender;
    @Autowired
    private AppUserService users;
    @Autowired
    private HoldingService holdings;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationSettingRepository settingRepository;
    @Autowired
    private NotificationLogRepository logRepository;
    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private long alice;
    private long bob;
    private LocalDate asOf;

    @BeforeEach
    void setUp() {
        sender.messages.clear();
        sender.fail = false;
        alice = users.recordLogin("google", "test-notify-alice", "alice.notify@example.com", "Alice");
        bob = users.recordLogin("google", "test-notify-bob", "bob.notify@example.com", "Bob");
        asOf = jdbc.queryForObject("select max(trade_date) from stock_prices", LocalDate.class);
        holdings.create(alice, new HoldingRequest("2330.TW", asOf.minusDays(180), BigDecimal.TEN, 1000, "standard", null, null, null));
        enableEmail(alice, true);
        enableEmail(bob, false);
    }

    private void enableEmail(long userId, boolean enabled) {
        NotificationSetting setting = settingRepository.findById(userId).orElseGet(() -> NotificationSetting.defaults(userId));
        setting.setEmailEnabled(enabled);
        settingRepository.saveAndFlush(setting);
    }

    private NotificationLog log(long userId) {
        return logRepository.findByUserIdAndBasisDateAndChannel(userId, asOf, "email").orElseThrow();
    }

    @Test
    void sendsOncePerTradingDayToEnabledUsers() {
        NotificationJob.Result first = job.run();
        assertThat(first.sent()).isEqualTo(1);
        assertThat(sender.messages).hasSize(1);
        String[] mail = sender.messages.get(0);
        assertThat(mail[0]).isEqualTo("alice.notify@example.com"); // 只寄給開啟的使用者，收件人為登入的 email
        assertThat(mail[1]).startsWith("股票提醒 " + asOf.getMonthValue() + "/" + asOf.getDayOfMonth() + "：");
        assertThat(mail[2]).contains("收盤後的提醒").contains("不構成投資建議");
        assertThat(log(alice).getStatus()).isEqualTo("sent");
        assertThat(log(alice).getAlertCount()).isEqualTo(notificationService.buildDigest(alice).alertCount());
        assertThat(logRepository.findByUserIdAndBasisDateAndChannel(bob, asOf, "email")).isEmpty();

        // 同一個交易日再執行（備援重跑、休市日）：不重寄
        NotificationJob.Result second = job.run();
        assertThat(second.sent()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(sender.messages).hasSize(1);
    }

    @Test
    void failedSendIsRetriedNextRun() {
        sender.fail = true;
        NotificationJob.Result failed = job.run();
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(log(alice).getStatus()).isEqualTo("failed");
        assertThat(log(alice).getError()).contains("SMTP 連線失敗");

        sender.fail = false;
        NotificationJob.Result retry = job.run();
        assertThat(retry.sent()).isEqualTo(1);
        assertThat(log(alice).getStatus()).isEqualTo("sent");
        assertThat(log(alice).getError()).isNull();
        assertThat(logRepository.findAll().stream().filter(l -> l.getUserId() == alice).count()).isOne(); // 同一筆紀錄更新
    }

    @Test
    void dryRunOnlyLogs(@Autowired NotificationLogRepository logs) {
        NotificationJob dryRun = new NotificationJob(notificationService, settingRepository, logs, userRepository, sender, "", true);
        NotificationJob.Result result = dryRun.run();
        assertThat(result.sent()).isZero();
        assertThat(sender.messages).isEmpty();
        assertThat(logs.findByUserIdAndBasisDateAndChannel(alice, asOf, "email")).isEmpty();
    }
}
