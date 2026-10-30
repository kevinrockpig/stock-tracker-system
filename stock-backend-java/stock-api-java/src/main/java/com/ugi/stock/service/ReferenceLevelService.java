package com.ugi.stock.service;

import com.ugi.stock.dto.ReferenceLevelsResponse;
import com.ugi.stock.dto.ReferenceLevelsResponse.AtrStop;
import com.ugi.stock.dto.ReferenceLevelsResponse.ExDividend;
import com.ugi.stock.dto.ReferenceLevelsResponse.FibLevel;
import com.ugi.stock.dto.ReferenceLevelsResponse.NextDay;
import com.ugi.stock.dto.ReferenceLevelsResponse.Swing;
import com.ugi.stock.entity.ReferenceLevel;
import com.ugi.stock.entity.Stock;
import com.ugi.stock.repository.ReferenceLevelRepository;
import com.ugi.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceLevelService {

    // 必須與 Python 端 scraper/backtest.py 的 TRAILING_ATR_MULTIPLE 一致（參考價位由 Python 計算）
    static final BigDecimal ATR_MULTIPLE = new BigDecimal("3");

    private final ReferenceLevelRepository referenceLevelRepository;
    private final StockRepository stockRepository;

    public ReferenceLevelsResponse getLatest(String symbol) {
        Stock stock = stockRepository.findById(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到股票代號：" + symbol));
        ReferenceLevel level = referenceLevelRepository.findFirstBySymbolOrderByBasisDateDesc(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "這檔股票還沒有參考價位（每日更新程式執行後產生）：" + symbol));

        AtrStop atrStop = level.getAtrStop() == null
                ? null
                : new AtrStop(level.getAtr14(), ATR_MULTIPLE, level.getAtrStop());
        Swing swing = new Swing(
                level.getSwingDays(),
                level.getSwingHigh(),
                level.getSwingHighDate(),
                level.getSwingLow(),
                level.getSwingLowDate(),
                level.getSwingTrend(),
                List.of(
                        new FibLevel(new BigDecimal("0.382"), level.getFib382()),
                        new FibLevel(new BigDecimal("0.5"), level.getFib500()),
                        new FibLevel(new BigDecimal("0.618"), level.getFib618())));
        boolean exact = Boolean.TRUE.equals(level.getReferenceExact());
        NextDay nextDay = new NextDay(level.getReferencePrice(), exact, level.getLimitUp(), level.getLimitDown());
        ExDividend exDividend = level.getNextExDate() == null
                ? null
                : new ExDividend(level.getNextExDate(), level.getNextExType(), level.getNextCashDividend(),
                // Python 只在除權息日是下一個交易日時調整參考價或標示不精確
                !exact || level.getReferencePrice().compareTo(level.getClosePrice()) != 0);

        return new ReferenceLevelsResponse(stock.getSymbol(), stock.getName(), level.getBasisDate(),
                level.getClosePrice(), atrStop, swing, nextDay, exDividend);
    }
}
