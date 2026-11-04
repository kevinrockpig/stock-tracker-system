package com.ugi.stock.controller;

import com.ugi.stock.dto.NotificationPreview;
import com.ugi.stock.dto.NotificationSettingsRequest;
import com.ugi.stock.dto.NotificationSettingsResponse;
import com.ugi.stock.dto.RiskLevelRequest;
import com.ugi.stock.security.AppUserPrincipal;
import com.ugi.stock.service.NotificationSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登入者自己的每日通知設定與預覽（需要登入，見 SecurityConfig）。使用者一律取自登入狀態；
 * 不允許跨網域呼叫（沒有 @CrossOrigin），前端經由同一個來源呼叫。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationSettingsService settingsService;

    @GetMapping("/settings")
    public NotificationSettingsResponse getSettings(@AuthenticationPrincipal AppUserPrincipal user) {
        return settingsService.get(user.getUserId());
    }

    @PutMapping("/settings")
    public NotificationSettingsResponse updateSettings(@AuthenticationPrincipal AppUserPrincipal user,
                                                       @RequestBody NotificationSettingsRequest request) {
        return settingsService.update(user.getUserId(), request);
    }

    @PutMapping("/risk-levels/{symbol}")
    public NotificationSettingsResponse setRiskLevel(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String symbol,
                                                     @RequestBody RiskLevelRequest request) {
        return settingsService.setRiskLevel(user.getUserId(), symbol, request == null ? null : request.riskLevel());
    }

    @GetMapping("/preview")
    public NotificationPreview preview(@AuthenticationPrincipal AppUserPrincipal user) {
        return settingsService.preview(user.getUserId());
    }
}
