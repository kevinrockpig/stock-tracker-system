package com.ugi.stock.controller;

import com.ugi.stock.dto.StockGroupResponse;
import com.ugi.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origin}") // 只允許前端開發伺服器跨網域呼叫
public class StockGroupController {

    private final StockService stockService;

    // 觀察名單的組別與股票數，例如 [{code: "TW50", name: "0050 成分股", stocks: 50}, ...]
    @GetMapping("/api/groups")
    public List<StockGroupResponse> getGroups() {
        return stockService.getGroups();
    }
}
