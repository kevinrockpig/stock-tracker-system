package com.ugi.stock.service;

import com.ugi.stock.dto.StockQuoteResponse;
import com.ugi.stock.dto.TodayResponse;
import com.ugi.stock.dto.TodayResponse.Candidate;
import com.ugi.stock.dto.TodayResponse.Market;
import com.ugi.stock.entity.StockIndicator;
import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.entity.StrategySignal;
import com.ugi.stock.entity.UserStock;
import com.ugi.stock.repository.StockIndicatorRepository;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StrategySignalRepository;
import com.ugi.stock.repository.UserStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 「今天」頁：最新交易日收盤後的大盤狀態（0050 對 60 日均線）與 R1 規則的候選（見 PlanRules）。
 * 候選範圍為觀察名單（不含核心 0050），登入時再加上自己的自選；依 120 日漲幅排序。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodayService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final StockPriceRepository priceRepository;
    private final StockIndicatorRepository indicatorRepository;
    private final StrategySignalRepository signalRepository;
    private final UserStockRepository userStockRepository;
    private final StockService stockService;

    public TodayResponse today(Long userId) {
        StockPrice core = priceRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(PlanRules.CORE_SYMBOL, LocalDate.of(9999, 12, 31))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "尚無 0050 的行情資料"));
        LocalDate asOf = core.getTradeDate();
        return new TodayResponse(asOf, market(core), candidates(asOf, userId));
    }

    private Market market(StockPrice core) {
        BigDecimal ma60 = indicatorRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(core.getSymbol(), core.getTradeDate())
                .filter(i -> i.getTradeDate().equals(core.getTradeDate()))
                .map(StockIndicator::getMa60).orElse(null);
        if (ma60 == null) {
            return new Market(core.getSymbol(), core.getClosePrice(), null, true, null); // 均線資料不足時不限制（與研究相同）
        }
        BigDecimal gap = core.getClosePrice().subtract(ma60).multiply(HUNDRED).divide(ma60, 2, RoundingMode.HALF_UP);
        return new Market(core.getSymbol(), core.getClosePrice(), ma60.setScale(2, RoundingMode.HALF_UP), core.getClosePrice().compareTo(ma60) >= 0, gap);
    }

    private List<Candidate> candidates(LocalDate asOf, Long userId) {
        Map<String, Set<String>> signals = new TreeMap<>();
        for (Strategy strategy : PlanRules.SIGNALS) {
            for (StrategySignal s : signalRepository.findByStrategyAndTradeDateBetween(strategy.code(), asOf, asOf)) {
                signals.computeIfAbsent(s.getSymbol(), k -> new LinkedHashSet<>()).add(strategy.code());
            }
        }
        signals.remove(PlanRules.CORE_SYMBOL);
        Set<String> mine = userId == null ? Set.of() : userStockRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream()
                .map(UserStock::getSymbol).collect(Collectors.toSet());
        Map<String, StockQuoteResponse> quotes = stockService.getLatestQuotes(signals.keySet());
        return signals.entrySet().stream()
                .filter(e -> quotes.containsKey(e.getKey()))
                .filter(e -> !quotes.get(e.getKey()).groups().isEmpty() || mine.contains(e.getKey()))
                .filter(e -> asOf.equals(quotes.get(e.getKey()).tradeDate()))
                .map(e -> candidate(quotes.get(e.getKey()), e.getValue(), mine.contains(e.getKey()), asOf))
                .sorted(Comparator.comparing(Candidate::strength, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Candidate::symbol))
                .toList();
    }

    private Candidate candidate(StockQuoteResponse quote, Set<String> signals, boolean mine, LocalDate asOf) {
        BigDecimal close = quote.closePrice();
        BigDecimal strength = priceRepository.findCloseBack(quote.symbol(), asOf, PlanRules.STRENGTH_DAYS)
                .filter(before -> before.signum() > 0)
                .map(before -> close.subtract(before).multiply(HUNDRED).divide(before, 2, RoundingMode.HALF_UP))
                .orElse(null);
        BigDecimal stop = close.multiply(BigDecimal.ONE.subtract(PlanRules.TRAILING_STOP)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal skip = close.multiply(BigDecimal.ONE.add(PlanRules.SKIP_GAP_UP)).setScale(2, RoundingMode.HALF_UP);
        return new Candidate(quote.symbol(), quote.name(), quote.groups(), mine && quote.groups().isEmpty(), close, quote.changePercent(),
                List.copyOf(signals), strength, stop, skip);
    }
}
