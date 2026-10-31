package com.ugi.stock.controller;

import com.ugi.stock.dto.BacktestResponse;
import com.ugi.stock.dto.BacktestTradesResponse;
import com.ugi.stock.service.BacktestService;
import com.ugi.stock.service.ExitRule;
import com.ugi.stock.service.Horizon;
import com.ugi.stock.service.Strategy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origin}") // 只允許前端開發伺服器跨網域呼叫
public class BacktestController {

    private final BacktestService backtestService;

    // 策略回測統計，例如 /api/backtests（全部股票合併）或 /api/backtests?scope=2330.TW
    @GetMapping("/backtests")
    public BacktestResponse getBacktests(@RequestParam(defaultValue = BacktestService.ALL_SCOPE) String scope) {
        return backtestService.getStats(scope.trim().toUpperCase(Locale.ROOT));
    }

    // 某檔股票的回測交易，例如 /api/stocks/2330.TW/trades?strategy=golden_cross&exitRule=trailing_atr
    @GetMapping("/stocks/{symbol}/trades")
    public BacktestTradesResponse getTrades(
            @PathVariable String symbol,
            @RequestParam String strategy,
            @RequestParam String exitRule) {
        Strategy targetStrategy = Strategy.fromCode(normalize(strategy))
                .orElseThrow(() -> badRequest("策略", strategy,
                        Arrays.stream(Strategy.values()).map(Strategy::code)));
        ExitRule targetRule = ExitRule.fromCode(normalize(exitRule))
                .orElseThrow(() -> badRequest("出場規則", exitRule,
                        Arrays.stream(ExitRule.values()).map(ExitRule::code)));
        // 波段策略只回測波段出場規則（與 Python 端 backtest.rules_for 一致），其他組合沒有資料
        if (targetStrategy.swingOnly() && targetRule.horizon() != Horizon.SWING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "「" + targetStrategy.label()
                    + "」為波段策略，只搭配波段出場規則：" + Arrays.stream(ExitRule.values())
                    .filter(rule -> rule.horizon() == Horizon.SWING).map(ExitRule::code)
                    .collect(Collectors.joining("、")));
        }
        return backtestService.getTrades(symbol.trim().toUpperCase(Locale.ROOT), targetStrategy, targetRule);
    }

    private static String normalize(String code) {
        return code.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException badRequest(String kind, String value, Stream<String> available) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "未知的" + kind + "：" + value + "，可用：" + available.collect(Collectors.joining("、")));
    }
}
