package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingHistoryResponse;
import com.ugi.stock.dto.HoldingHistoryResponse.Point;
import com.ugi.stock.entity.Holding;
import com.ugi.stock.entity.HoldingSale;
import com.ugi.stock.entity.StockAction;
import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.repository.HoldingRepository;
import com.ugi.stock.repository.HoldingSaleRepository;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StockPriceRepository.DailyClose;
import com.ugi.stock.service.LotLedger.LotState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 庫存資產走勢：把庫存頁「同期比較」的算法逐日套用（見 HoldingService.benchmark），不存快照、每次即時計算。
 * <p>
 * 價格與股利都是目前的基準（之後若有分割、配股、減資，之前的數字已等比例調整），所以一筆買進在任何一天的市值
 * 都是「換算後的股數 × 當天的收盤價」，不必逐日換算股數。每筆買進在某天的價值由 LotLedger.valueAt 計算
 * （與同期比較相同）：還持有的部分依整檔「市值 − 賣出成本」按股數分攤，加上已領股利與賣出拿回的金額（視為現金）。
 */
@Service
@RequiredArgsConstructor
public class HoldingHistoryService {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final HoldingService holdingService;
    private final HoldingRepository holdingRepository;
    private final HoldingSaleRepository saleRepository;
    private final StockPriceRepository priceRepository;

    @Transactional(readOnly = true)
    public HoldingHistoryResponse getHistory(long userId) {
        String etf = HoldingService.ETF_BENCHMARK;
        List<Holding> holdings = holdingRepository.findByUserIdOrderByIdAsc(userId);
        LocalDate asOf = holdingService.latestTradeDate();
        LocalDate etfFirst = priceRepository.findFirstBySymbolOrderByTradeDateAsc(etf).map(StockPrice::getTradeDate).orElse(null);
        if (asOf == null || etfFirst == null) {
            return new HoldingHistoryResponse(asOf, etf, 0, holdings.size(), List.of());
        }

        // 可能納入的：有買進日期、不早於 0050 的資料起點、不晚於最新交易日
        LocalDate from = holdings.stream().map(Holding::getBuyDate)
                .filter(d -> d != null && !d.isBefore(etfFirst) && !d.isAfter(asOf))
                .min(LocalDate::compareTo).orElse(null);
        if (from == null) {
            return new HoldingHistoryResponse(asOf, etf, 0, holdings.size(), List.of());
        }

        // 0050 的交易日與收盤價：買進日不是交易日時，以下一個交易日收盤買 0050（與同期比較相同）
        List<DailyClose> etfCloses = priceRepository.findCloses(List.of(etf), from, asOf);
        List<LocalDate> etfDates = etfCloses.stream().map(DailyClose::getTradeDate).toList();
        Map<LocalDate, BigDecimal> etfCloseByDate = etfCloses.stream()
                .collect(Collectors.toMap(DailyClose::getTradeDate, DailyClose::getClosePrice));
        Dividends etfDividends = new Dividends(holdingService.actions(etf));

        Map<String, List<Holding>> bySymbol = holdings.stream()
                .collect(Collectors.groupingBy(Holding::getSymbol, LinkedHashMap::new, Collectors.toList()));
        // 只讀有可能納入的股票的行情（只有未填日期的股票不需要）
        List<String> symbols = bySymbol.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(h -> h.getBuyDate() != null && !h.getBuyDate().isBefore(from)))
                .map(Map.Entry::getKey).toList();
        // 起算日（含）以前最近的收盤價：停牌或起算日剛好沒有行情時沿用
        Map<String, BigDecimal> close = new HashMap<>();
        for (String symbol : symbols) {
            priceRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(symbol, from)
                    .ifPresent(p -> close.put(symbol, p.getClosePrice()));
        }
        List<DailyClose> stockCloses = priceRepository.findCloses(symbols, from, asOf);
        Map<String, Boolean> priced = new HashMap<>();
        close.keySet().forEach(s -> priced.put(s, true));
        stockCloses.forEach(c -> priced.put(c.getSymbol(), true));

        List<HoldingSale> sales = saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId);
        List<PositionSeries> positions = new ArrayList<>();
        int excluded = 0;
        for (Map.Entry<String, List<Holding>> entry : bySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<Holding> lots = entry.getValue();
            LotLedger ledger = holdingService.ledger(symbol, holdings, sales, asOf);
            PositionSeries p = new PositionSeries(symbol, lots.get(lots.size() - 1), ledger);
            for (LotState lot : ledger.lots()) {
                LocalDate buyDate = lot.holding.getBuyDate();
                LocalDate entryDate = buyDate == null || buyDate.isBefore(etfFirst) ? null : firstOnOrAfter(etfDates, buyDate);
                if (entryDate == null || !priced.containsKey(symbol)) {
                    excluded++;
                    continue;
                }
                p.lots.add(new LotSeries(lot, entryDate, lot.cost.divide(etfCloseByDate.get(entryDate), MC)));
            }
            if (!p.lots.isEmpty()) {
                positions.add(p);
            }
        }
        int included = positions.stream().mapToInt(p -> p.lots.size()).sum();
        if (included == 0) {
            return new HoldingHistoryResponse(asOf, etf, 0, excluded, List.of());
        }

        // 走勢的日期：最早一筆納入的庫存買進那天起，庫存的股票或 0050 有行情的交易日，最後一天為 asOf
        LocalDate start = positions.stream().flatMap(p -> p.lots.stream()).map(LotSeries::entryDate).min(LocalDate::compareTo).orElseThrow();
        TreeSet<LocalDate> dates = new TreeSet<>(etfDates);
        stockCloses.forEach(c -> dates.add(c.getTradeDate()));
        dates.add(asOf);

        List<Point> points = new ArrayList<>();
        BigDecimal etfClose = null;
        int next = 0;
        for (LocalDate day : dates.tailSet(start, true)) {
            while (next < stockCloses.size() && !stockCloses.get(next).getTradeDate().isAfter(day)) {
                close.put(stockCloses.get(next).getSymbol(), stockCloses.get(next).getClosePrice());
                next++;
            }
            etfClose = etfCloseByDate.getOrDefault(day, etfClose);
            BigDecimal etfDividendsToDay = etfDividends.upTo(day);

            BigDecimal invested = BigDecimal.ZERO;
            BigDecimal holdingsValue = BigDecimal.ZERO;
            BigDecimal etfValue = BigDecimal.ZERO;
            boolean missing = false;
            for (PositionSeries p : positions) {
                // 整檔當天收盤的市值 − 賣出成本（與庫存頁相同，依最近一筆的手續費方式估算）
                BigDecimal shares = p.ledger.positionSharesAt(day);
                BigDecimal price = close.get(p.symbol);
                boolean noPrice = shares.signum() > 0 && price == null;
                BigDecimal net = BigDecimal.ZERO;
                if (shares.signum() > 0 && price != null) {
                    BigDecimal marketValue = shares.multiply(price, MC);
                    net = marketValue.subtract(HoldingService.sellCost(p.symbol, marketValue, p.latest));
                }
                for (LotSeries s : p.lots) {
                    if (s.entryDate.isAfter(day)) {
                        continue;
                    }
                    invested = invested.add(s.lot.cost);
                    etfValue = etfValue.add(s.etfUnits.multiply(etfClose.add(etfDividendsToDay.subtract(etfDividends.upTo(s.entryDate))), MC));
                    if (noPrice) {
                        missing = true;
                    } else {
                        holdingsValue = holdingsValue.add(p.ledger.valueAt(s.lot, day, net, shares));
                    }
                }
            }
            points.add(new Point(day, money(invested), missing ? null : money(holdingsValue), money(etfValue)));
        }
        return new HoldingHistoryResponse(asOf, etf, included, excluded, points);
    }

    // 某日（含）以後的第一個交易日；沒有則為 null
    private static LocalDate firstOnOrAfter(List<LocalDate> sortedDates, LocalDate date) {
        int index = Collections.binarySearch(sortedDates, date);
        int at = index >= 0 ? index : -index - 1;
        return at < sortedDates.size() ? sortedDates.get(at) : null;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 0050 的每股現金股利，依除息日累計 */
    private static final class Dividends {
        private final List<StockAction> cash;

        Dividends(List<StockAction> actions) {
            this.cash = actions.stream().filter(a -> a.getCashDividend() != null).toList();
        }

        // 除息日在這天（含）以前的每股現金股利合計；兩天相減即為之間（不含前一天）的股利
        BigDecimal upTo(LocalDate day) {
            BigDecimal total = BigDecimal.ZERO;
            for (StockAction action : cash) {
                if (action.getExDate().isAfter(day)) {
                    break;
                }
                total = total.add(action.getCashDividend());
            }
            return total;
        }
    }

    /** 一檔股票：賣出成本依整檔當天的持股估算（與庫存頁相同），納入的每筆依股數分攤 */
    private static final class PositionSeries {
        final String symbol;
        final Holding latest;
        final LotLedger ledger;
        final List<LotSeries> lots = new ArrayList<>();

        PositionSeries(String symbol, Holding latest, LotLedger ledger) {
            this.symbol = symbol;
            this.latest = latest;
            this.ledger = ledger;
        }
    }

    /** 一筆納入的買進：進場日（0050 的交易日）與同樣金額買進 0050 的單位數 */
    private record LotSeries(LotState lot, LocalDate entryDate, BigDecimal etfUnits) {
    }
}
