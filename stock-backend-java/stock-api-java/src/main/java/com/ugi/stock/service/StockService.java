package com.ugi.stock.service;

import com.ugi.stock.dto.StockHistoryResponse;
import com.ugi.stock.dto.StockHistoryResponse.Indicators;
import com.ugi.stock.dto.StockHistoryResponse.PriceBar;
import com.ugi.stock.dto.StockQuoteResponse;
import com.ugi.stock.entity.Stock;
import com.ugi.stock.entity.StockIndicator;
import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.repository.StockIndicatorRepository;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockService {

    private static final String QUOTE_TYPE_DAILY_CLOSE = "DAILY_CLOSE";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // 未指定日期區間時使用的上下限（PostgreSQL date 不接受 LocalDate.MIN / MAX）
    private static final LocalDate EARLIEST_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST_DATE = LocalDate.of(9999, 12, 31);

    private final StockRepository stockRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockIndicatorRepository stockIndicatorRepository;

    /**
     * 所有股票的最新報價（依代號排序）。
     * 觀察名單只有數檔，逐檔查詢最新兩筆即可；名單變大時再改為單一查詢。
     */
    public List<StockQuoteResponse> getLatestQuotes() {
        return stockRepository.findAll(Sort.by("symbol")).stream()
                .map(this::toLatestQuote)
                .toList();
    }

    /**
     * 指定股票的歷史日 K 與當日技術指標。from / to 為 null 時不限制該端點。
     * 區間內若全是休市日（週末、國定假日、颱風假），回傳空的 prices，而不是錯誤。
     */
    public StockHistoryResponse getHistory(String symbol, LocalDate from, LocalDate to) {
        Stock stock = stockRepository.findById(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到股票代號：" + symbol));

        LocalDate start = from == null ? EARLIEST_DATE : from;
        LocalDate end = to == null ? LATEST_DATE : to;

        // 兩張表以 (symbol, trade_date) 一對一對應，依日期合併
        Map<LocalDate, StockIndicator> indicatorsByDate = stockIndicatorRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, start, end)
                .stream()
                .collect(Collectors.toMap(StockIndicator::getTradeDate, Function.identity()));

        List<PriceBar> prices = stockPriceRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, start, end)
                .stream()
                .map(price -> new PriceBar(
                        price.getTradeDate(),
                        price.getOpenPrice(),
                        price.getHighPrice(),
                        price.getLowPrice(),
                        price.getClosePrice(),
                        price.getVolume(),
                        toIndicators(indicatorsByDate.get(price.getTradeDate()))))
                .toList();

        return new StockHistoryResponse(stock.getSymbol(), stock.getName(), stock.getCurrency(), prices);
    }

    private Indicators toIndicators(StockIndicator indicator) {
        if (indicator == null) {
            return null;
        }
        return new Indicators(
                indicator.getMa5(),
                indicator.getMa10(),
                indicator.getMa20(),
                indicator.getMa60(),
                indicator.getMacdDif(),
                indicator.getMacdSignal(),
                indicator.getMacdOsc(),
                indicator.getRsi6(),
                indicator.getRsi12(),
                indicator.getKdK(),
                indicator.getKdD()
        );
    }

    private StockQuoteResponse toLatestQuote(Stock stock) {
        List<StockPrice> recent = stockPriceRepository.findTop2BySymbolOrderByTradeDateDesc(stock.getSymbol());
        StockPrice latest = recent.isEmpty() ? null : recent.get(0);
        StockPrice previous = recent.size() > 1 ? recent.get(1) : null;

        if (latest == null) {
            // 有股票資料但尚未寫入任何行情：仍回傳，讓前端顯示「無資料」
            return new StockQuoteResponse(stock.getSymbol(), stock.getName(), stock.getCurrency(),
                    QUOTE_TYPE_DAILY_CLOSE, null, null, null, null, null, null, null, null, null);
        }

        BigDecimal previousClose = previous == null ? null : previous.getClosePrice();
        BigDecimal change = null;
        BigDecimal changePercent = null;
        if (previousClose != null) {
            change = latest.getClosePrice().subtract(previousClose);
            if (previousClose.signum() != 0) {
                changePercent = change.multiply(HUNDRED).divide(previousClose, 2, RoundingMode.HALF_UP);
            }
        }

        return new StockQuoteResponse(
                stock.getSymbol(),
                stock.getName(),
                stock.getCurrency(),
                QUOTE_TYPE_DAILY_CLOSE,
                latest.getTradeDate(),
                latest.getClosePrice(),
                previousClose,
                change,
                changePercent,
                latest.getOpenPrice(),
                latest.getHighPrice(),
                latest.getLowPrice(),
                latest.getVolume()
        );
    }
}
