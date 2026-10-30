package com.ugi.stock.controller;

import com.ugi.stock.dto.ReferenceLevelsResponse;
import com.ugi.stock.service.ReferenceLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origin}") // 只允許前端開發伺服器跨網域呼叫
public class ReferenceLevelController {

    private final ReferenceLevelService referenceLevelService;

    // 最新的參考價位，例如 /api/stocks/2330.TW/levels
    @GetMapping("/{symbol}/levels")
    public ReferenceLevelsResponse getLevels(@PathVariable String symbol) {
        return referenceLevelService.getLatest(symbol.trim().toUpperCase(Locale.ROOT));
    }
}
