package com.ugi.stock.controller;

import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.service.StockPriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // 允許前端 Vue 跨網域呼叫此 API
public class StockPriceController {

    private final StockPriceService stockPriceService;

    @GetMapping
    public List<StockPrice> getAllStocks() {
        return stockPriceService.getAllStocks();
    }

    @PostMapping
    public StockPrice addStock(@RequestBody StockPrice stockPrice) {
        return stockPriceService.addStock(stockPrice);
    }
}