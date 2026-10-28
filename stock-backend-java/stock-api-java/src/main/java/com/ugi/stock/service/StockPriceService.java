package com.ugi.stock.service;

import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StockPriceService {

    private final StockPriceRepository stockPriceRepository;

    public List<StockPrice> getAllStocks() {
        // 透過 Repository 取得資料庫中所有股票價格紀錄
        return stockPriceRepository.findAll();
    }

    public StockPrice addStock(StockPrice stockPrice) {
        return stockPriceRepository.save(stockPrice);
    }
}