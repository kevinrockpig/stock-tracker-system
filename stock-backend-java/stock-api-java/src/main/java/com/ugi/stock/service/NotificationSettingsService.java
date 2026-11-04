package com.ugi.stock.service;

import com.ugi.stock.dto.NotificationDigest;
import com.ugi.stock.dto.NotificationPreview;
import com.ugi.stock.dto.NotificationSettingsRequest;
import com.ugi.stock.dto.NotificationSettingsResponse;
import com.ugi.stock.dto.NotificationSettingsResponse.RiskOption;
import com.ugi.stock.entity.AppUser;
import com.ugi.stock.entity.HoldingRiskLevel;
import com.ugi.stock.entity.NotificationSetting;
import com.ugi.stock.repository.AppUserRepository;
import com.ugi.stock.repository.HoldingRepository;
import com.ugi.stock.repository.HoldingRiskLevelRepository;
import com.ugi.stock.repository.NotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 登入者自己的每日通知設定與預覽。所有方法都以登入者的 userId 操作。
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    public static final int MAX_EX_DIVIDEND_DAYS = 30;

    private final NotificationService notificationService;
    private final NotificationSettingRepository settingRepository;
    private final HoldingRiskLevelRepository riskLevelRepository;
    private final HoldingRepository holdingRepository;
    private final AppUserRepository userRepository;

    @Value("${app.notify.site-url}")
    private String siteUrl;

    @Transactional(readOnly = true)
    public NotificationSettingsResponse get(long userId) {
        NotificationSetting setting = notificationService.settings(userId);
        Map<String, String> levels = new TreeMap<>();
        notificationService.riskLevels(userId).forEach((symbol, level) -> levels.put(symbol, level.code()));
        String email = userRepository.findById(userId).map(AppUser::getEmail).orElse(null);
        NotificationDigest.Market market = notificationService.currentMarket();
        return new NotificationSettingsResponse(setting.isEmailEnabled(), setting.getExDividendDays(), MAX_EX_DIVIDEND_DAYS,
                setting.isEntryAlerts(), email, levels,
                Arrays.stream(RiskLevel.values()).map(l -> new RiskOption(l.code(), l.label(), l.atrMultiple())).toList(),
                RiskLevel.WEAK_MARKET_EXTRA, market == null ? null : market.weak());
    }

    @Transactional
    public NotificationSettingsResponse update(long userId, NotificationSettingsRequest request) {
        if (request == null || request.emailEnabled() == null || request.exDividendDays() == null || request.entryAlerts() == null) {
            throw badRequest("缺少通知設定（emailEnabled、exDividendDays、entryAlerts 都必填）");
        }
        if (request.exDividendDays() < 0 || request.exDividendDays() > MAX_EX_DIVIDEND_DAYS) {
            throw badRequest("除權息提醒天數必須是 0～" + MAX_EX_DIVIDEND_DAYS);
        }
        NotificationSetting setting = settingRepository.findById(userId).orElseGet(() -> NotificationSetting.defaults(userId));
        setting.setEmailEnabled(request.emailEnabled());
        setting.setExDividendDays(request.exDividendDays());
        setting.setEntryAlerts(request.entryAlerts());
        setting.setUpdatedAt(Instant.now());
        settingRepository.saveAndFlush(setting);
        return get(userId);
    }

    /** 只能設定自己持有的股票；設為標準時刪除這筆（沒有資料即為標準） */
    @Transactional
    public NotificationSettingsResponse setRiskLevel(long userId, String symbol, String code) {
        String normalized = Optional.ofNullable(symbol).map(s -> s.trim().toUpperCase(Locale.ROOT)).orElse("");
        if (!holdingRepository.existsByUserIdAndSymbol(userId, normalized)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "庫存中沒有 " + normalized);
        }
        RiskLevel level = RiskLevel.fromCode(code)
                .orElseThrow(() -> badRequest("風險設定必須是 conservative（保守）、standard（標準）或 aggressive（積極）"));
        HoldingRiskLevel.Key key = new HoldingRiskLevel.Key(userId, normalized);
        if (level == RiskLevel.STANDARD) {
            riskLevelRepository.findById(key).ifPresent(riskLevelRepository::delete);
        } else {
            HoldingRiskLevel row = riskLevelRepository.findById(key).orElseGet(() -> new HoldingRiskLevel(userId, normalized, level.code()));
            row.setRiskLevel(level.code());
            row.setUpdatedAt(Instant.now());
            riskLevelRepository.save(row);
        }
        riskLevelRepository.flush();
        return get(userId);
    }

    /** 依目前設定與最新收盤資料產生通知內容，與每日寄出的 Email 相同（不會寄出、不受「已寄出」影響） */
    @Transactional(readOnly = true)
    public NotificationPreview preview(long userId) {
        NotificationDigest digest = notificationService.buildDigest(userId);
        return new NotificationPreview(digest.asOf(), NotificationText.subject(digest), NotificationText.body(digest, siteUrl),
                digest.alertCount());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
