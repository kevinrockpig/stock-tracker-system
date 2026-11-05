package com.ugi.stock.controller;

import com.ugi.stock.dto.TodayResponse;
import com.ugi.stock.security.AppUserPrincipal;
import com.ugi.stock.service.TodayService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「今天」頁：不需登入；登入時候選另外包含自己的自選。
 */
@RestController
@RequestMapping("/api/today")
@RequiredArgsConstructor
public class TodayController {

    private final TodayService todayService;

    @GetMapping
    public TodayResponse today(@AuthenticationPrincipal AppUserPrincipal user) {
        return todayService.today(user == null ? null : user.getUserId());
    }
}
