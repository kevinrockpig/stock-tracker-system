package com.ugi.stock.controller;

import com.ugi.stock.dto.PaperResponse;
import com.ugi.stock.service.PaperService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "${app.cors.allowed-origin}") // 只允許前端開發伺服器跨網域呼叫
public class PaperController {

    private final PaperService paperService;

    // 交易計畫與往後實測：每個觀察中策略一個模擬帳戶（今天的計畫、持股、已出場交易、每日資金與基準）
    @GetMapping("/api/paper")
    public List<PaperResponse> getPaper() {
        return paperService.getAccounts();
    }
}
