package com.ugi.stock.service;

import com.ugi.stock.dto.ScreenerResponse;
import com.ugi.stock.dto.ScreenerResponse.Match;
import com.ugi.stock.dto.StockQuoteResponse;
import com.ugi.stock.dto.StrategyResponse;
import com.ugi.stock.entity.StrategySignal;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StrategyService {

    private final StrategySignalRepository strategySignalRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockService stockService;

    public List<StrategyResponse> getStrategies() {
        return Arrays.stream(Strategy.values()).map(StrategyService::toResponse).toList();
    }

    /**
     * 最近 days 個交易日內符合策略的股票。
     * 交易日取自資料庫中實際有行情的日期，週末、國定假日與颱風假不計入；days = 1 即只看最新交易日。
     */
    public ScreenerResponse screen(Strategy strategy, int days) {
        List<LocalDate> recentDates = stockPriceRepository.findRecentTradeDates(PageRequest.of(0, days));
        if (recentDates.isEmpty()) {
            return new ScreenerResponse(toResponse(strategy), days, null, null, List.of());
        }
        LocalDate marketDate = recentDates.get(0);
        LocalDate fromDate = recentDates.get(recentDates.size() - 1);

        Map<String, List<StrategySignal>> signalsBySymbol = strategySignalRepository
                .findByStrategyAndTradeDateBetween(strategy.code(), fromDate, marketDate)
                .stream()
                .collect(Collectors.groupingBy(StrategySignal::getSymbol));

        // 沿用 GET /api/stocks 的報價計算，前端卡片可直接顯示最新價與漲跌
        Map<String, StockQuoteResponse> quotes = stockService.getLatestQuotes().stream()
                .collect(Collectors.toMap(StockQuoteResponse::symbol, Function.identity()));

        List<Match> matches = signalsBySymbol.entrySet().stream()
                .map(entry -> {
                    LocalDate lastSignalDate = entry.getValue().stream()
                            .map(StrategySignal::getTradeDate)
                            .max(Comparator.naturalOrder())
                            .orElseThrow();
                    StockQuoteResponse quote = quotes.get(entry.getKey());
                    return new Match(
                            entry.getKey(),
                            quote == null ? null : quote.name(),
                            lastSignalDate,
                            entry.getValue().size(),
                            quote);
                })
                .sorted(Comparator.comparing(Match::lastSignalDate).reversed().thenComparing(Match::symbol))
                .toList();

        return new ScreenerResponse(toResponse(strategy), days, fromDate, marketDate, matches);
    }

    static StrategyResponse toResponse(Strategy strategy) {
        return new StrategyResponse(strategy.code(), strategy.label(), strategy.description());
    }
}
