package com.ugi.stock.notify;

import com.ugi.stock.dto.NotificationDigest;
import com.ugi.stock.dto.NotificationDigest.EntryCandidate;
import com.ugi.stock.entity.AppUser;
import com.ugi.stock.entity.NotificationLog;
import com.ugi.stock.entity.NotificationSetting;
import com.ugi.stock.repository.AppUserRepository;
import com.ugi.stock.repository.NotificationLogRepository;
import com.ugi.stock.repository.NotificationSettingRepository;
import com.ugi.stock.service.NotificationService;
import com.ugi.stock.service.NotificationText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 寄出當天的每日通知：開啟 Email 的每位使用者一封（沒有提醒時也寄，內容寫「今日無提醒」）。
 * <p>
 * 以 notification_log 確保每個交易日只寄一次：已成功的跳過（備援重跑、休市日執行時，依據的交易日沒有變，不會重寄），
 * 失敗的下次執行重試。單一使用者失敗不影響其他人。
 */
@Slf4j
@Component
public class NotificationJob {

    public record Result(int sent, int skipped, int failed) {
    }

    private final NotificationService notificationService;
    private final NotificationSettingRepository settingRepository;
    private final NotificationLogRepository logRepository;
    private final AppUserRepository userRepository;
    private final NotificationSender sender;
    private final String siteUrl;
    private final boolean dryRun;

    public NotificationJob(NotificationService notificationService, NotificationSettingRepository settingRepository,
                           NotificationLogRepository logRepository, AppUserRepository userRepository, NotificationSender sender,
                           @Value("${app.notify.site-url}") String siteUrl, @Value("${app.notify.dry-run}") boolean dryRun) {
        this.notificationService = notificationService;
        this.settingRepository = settingRepository;
        this.logRepository = logRepository;
        this.userRepository = userRepository;
        this.sender = sender;
        this.siteUrl = siteUrl;
        this.dryRun = dryRun;
    }

    public Result run() {
        List<NotificationSetting> recipients = settingRepository.findByEmailEnabledTrueOrderByUserIdAsc();
        if (recipients.isEmpty()) {
            log.info("沒有開啟 Email 通知的使用者");
            return new Result(0, 0, 0);
        }
        List<EntryCandidate> entries = notificationService.entryCandidates();
        int sent = 0, skipped = 0, failed = 0;
        for (NotificationSetting setting : recipients) {
            long userId = setting.getUserId();
            Optional<AppUser> user = userRepository.findById(userId);
            if (user.isEmpty()) {
                skipped++;
                continue;
            }
            NotificationDigest digest = notificationService.buildDigest(userId, entries);
            if (digest.asOf() == null) {
                log.warn("資料庫沒有行情資料，略過使用者 {}", userId);
                skipped++;
                continue;
            }
            NotificationLog record = logRepository.findByUserIdAndBasisDateAndChannel(userId, digest.asOf(), sender.channel())
                    .orElseGet(() -> new NotificationLog(userId, digest.asOf(), sender.channel()));
            if ("sent".equals(record.getStatus())) {
                log.info("使用者 {} 的 {} 通知已寄出，略過", userId, digest.asOf());
                skipped++;
                continue;
            }
            String subject = NotificationText.subject(digest);
            String body = NotificationText.body(digest, siteUrl);
            if (dryRun) {
                // 只寫進 log，不寄出、不記錄發送紀錄
                log.info("（dry-run，不寄出）使用者 {}：{}\n{}", userId, subject, body);
                skipped++;
                continue;
            }
            record.setAlertCount(digest.alertCount());
            record.setSentAt(Instant.now());
            try {
                sender.send(user.get().getEmail(), subject, body);
                record.setStatus("sent");
                record.setError(null);
                sent++;
                log.info("已寄出使用者 {} 的 {} 通知（{} 項提醒）", userId, digest.asOf(), digest.alertCount());
            } catch (Exception e) {
                record.setStatus("failed");
                String message = e.getClass().getSimpleName() + ": " + e.getMessage();
                record.setError(message.length() > NotificationLog.MAX_ERROR_LENGTH
                        ? message.substring(0, NotificationLog.MAX_ERROR_LENGTH) : message);
                failed++;
                log.error("使用者 {} 的通知寄送失敗：{}", userId, message);
            }
            logRepository.save(record);
        }
        log.info("每日通知：寄出 {}、略過 {}、失敗 {}", sent, skipped, failed);
        return new Result(sent, skipped, failed);
    }
}
