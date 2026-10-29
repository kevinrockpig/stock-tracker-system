package com.ugi.stock.controller;

import com.ugi.stock.dto.ScreenerResponse;
import com.ugi.stock.dto.StrategyResponse;
import com.ugi.stock.service.Strategy;
import com.ugi.stock.service.StrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origin}") // 只允許前端開發伺服器跨網域呼叫
public class StrategyController {

    private static final int MAX_DAYS = 60;

    private final StrategyService strategyService;

    // 可用的選股策略（代碼、中文名稱、成立條件）
    @GetMapping("/strategies")
    public List<StrategyResponse> getStrategies() {
        return strategyService.getStrategies();
    }

    // 策略選股，例如 /api/screener?strategy=golden_cross&days=10（days 為交易日數，預設 1 = 只看最新交易日）
    @GetMapping("/screener")
    public ScreenerResponse screen(
            @RequestParam String strategy,
            @RequestParam(defaultValue = "1") int days) {
        Strategy target = Strategy.fromCode(strategy.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "未知的策略：" + strategy + "，可用：" + Arrays.stream(Strategy.values())
                                .map(Strategy::code)
                                .collect(Collectors.joining("、"))));
        if (days < 1 || days > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days 必須介於 1 到 " + MAX_DAYS + "（交易日數）");
        }
        return strategyService.screen(target, days);
    }
}
