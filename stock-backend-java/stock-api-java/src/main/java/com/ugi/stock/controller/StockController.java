package com.ugi.stock.controller;

import com.ugi.stock.dto.StockHistoryResponse;
import com.ugi.stock.dto.StockQuoteResponse;
import com.ugi.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origin}") // 只允許前端開發伺服器跨網域呼叫
public class StockController {

    private final StockService stockService;

    // 股票清單 + 最新可取得的日收盤報價
    @GetMapping
    public List<StockQuoteResponse> getStocks() {
        return stockService.getLatestQuotes();
    }

    // 歷史日 K 與當日技術指標，例如 /api/stocks/2330.TW/history?from=2026-01-01&to=2026-06-30（from、to 皆可省略）
    @GetMapping("/{symbol}/history")
    public StockHistoryResponse getHistory(
            @PathVariable String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from 不可晚於 to");
        }
        return stockService.getHistory(symbol.trim().toUpperCase(Locale.ROOT), from, to);
    }
}
