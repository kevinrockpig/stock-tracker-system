package com.ugi.stock.controller;

import com.ugi.stock.dto.PortfolioResponse;
import com.ugi.stock.service.BacktestService;
import com.ugi.stock.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origin}") // 只允許前端開發伺服器跨網域呼叫
public class PortfolioController {

    private final PortfolioService portfolioService;

    // 投資組合回測，例如 /api/portfolio-backtests（全部合併）或 /api/portfolio-backtests?scope=TW50
    @GetMapping("/api/portfolio-backtests")
    public PortfolioResponse getPortfolio(@RequestParam(defaultValue = BacktestService.ALL_SCOPE) String scope) {
        return portfolioService.getStats(scope.trim().toUpperCase(Locale.ROOT));
    }
}
