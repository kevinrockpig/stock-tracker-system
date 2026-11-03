package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.HoldingsResponse;
import com.ugi.stock.dto.HoldingsResponse.Benchmark;
import com.ugi.stock.dto.HoldingsResponse.FeeRules;
import com.ugi.stock.dto.HoldingsResponse.Lot;
import com.ugi.stock.dto.HoldingsResponse.Position;
import com.ugi.stock.dto.HoldingsResponse.Signal;
import com.ugi.stock.dto.HoldingsResponse.Summary;
import com.ugi.stock.entity.Holding;
import com.ugi.stock.entity.ReferenceLevel;
import com.ugi.stock.entity.Stock;
import com.ugi.stock.entity.StockAction;
import com.ugi.stock.entity.StockIndicator;
import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.repository.HoldingRepository;
import com.ugi.stock.repository.ReferenceLevelRepository;
import com.ugi.stock.repository.StockActionRepository;
import com.ugi.stock.repository.StockIndicatorRepository;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StockRepository;
import com.ugi.stock.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 使用者自行填寫的庫存：新增 / 修改 / 刪除與分析。所有方法都以登入者的 userId 過濾，取不到（包括別人的）一律回 404。
 */
@Service
@RequiredArgsConstructor
public class HoldingService {

    public static final int MAX_HOLDINGS = 200;
    public static final BigDecimal DEFAULT_MIN_FEE = new BigDecimal("20");
    static final String ETF_BENCHMARK = "0050.TW";

    // 欄位上限（資料表 NUMERIC 的精度）
    private static final int MAX_SHARES = 100_000_000;
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000000");
    private static final BigDecimal MAX_FEE = new BigDecimal("1000000000000");
    private static final BigDecimal MAX_MIN_FEE = new BigDecimal("100000000");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final String ROUNDING_NOTE = "手續費與證交稅無條件捨去到元，手續費低於最低手續費時以最低手續費計；各券商規則不同，僅為估算";

    private final HoldingRepository holdingRepository;
    private final StockRepository stockRepository;
    private final StockPriceRepository priceRepository;
    private final StockIndicatorRepository indicatorRepository;
    private final StockActionRepository actionRepository;
    private final ReferenceLevelRepository levelRepository;
    private final StrategySignalRepository signalRepository;

    // ---- 新增 / 修改 / 刪除 ----

    @Transactional
    public Lot create(long userId, HoldingRequest request) {
        if (holdingRepository.countByUserId(userId) >= MAX_HOLDINGS) {
            throw badRequest("庫存最多 " + MAX_HOLDINGS + " 筆");
        }
        Holding holding = new Holding(userId);
        apply(holding, request);
        holdingRepository.saveAndFlush(holding);
        return lot(holding, actions(holding.getSymbol()), latestTradeDate());
    }

    @Transactional
    public Lot update(long userId, long id, HoldingRequest request) {
        Holding holding = find(userId, id);
        apply(holding, request);
        holding.setUpdatedAt(Instant.now());
        holdingRepository.flush();
        return lot(holding, actions(holding.getSymbol()), latestTradeDate());
    }

    @Transactional
    public void delete(long userId, long id) {
        holdingRepository.delete(find(userId, id));
    }

    private Holding find(long userId, long id) {
        return holdingRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這筆庫存"));
    }

    // 檢查輸入並寫入欄位；手續費依方式計算後存下金額
    private void apply(Holding holding, HoldingRequest request) {
        if (request == null) {
            throw badRequest("缺少庫存內容");
        }
        String symbol = Optional.ofNullable(request.symbol()).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).orElseThrow(() -> badRequest("請填寫股票代號"));
        // 新增須在觀察名單內；已從觀察名單移除的股票仍可修改原本那筆（Python 每日更新會繼續抓庫存中的股票）
        boolean sameSymbol = symbol.equals(holding.getSymbol());
        Stock stock = stockRepository.findById(symbol).filter(s -> s.getGroupCodes() != null || sameSymbol)
                .orElseThrow(() -> badRequest("股票 " + symbol + " 不在觀察名單內"));

        BigDecimal price = request.price();
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) >= 0) {
            throw badRequest("價格必須大於 0");
        }
        if (decimals(price) > 4) {
            throw badRequest("價格最多 4 位小數");
        }
        Integer shares = request.shares();
        if (shares == null || shares <= 0 || shares > MAX_SHARES) {
            throw badRequest("股數必須大於 0");
        }
        LocalDate buyDate = request.buyDate();
        if (buyDate != null) {
            if (buyDate.isAfter(LocalDate.now(TAIPEI))) {
                throw badRequest("買進日期不能晚於今天");
            }
            if (stock.getListedDate() != null && buyDate.isBefore(stock.getListedDate())) {
                throw badRequest("買進日期不能早於上市（櫃）日 " + stock.getListedDate());
            }
        }

        String mode = Optional.ofNullable(request.feeMode()).map(String::trim).orElse("");
        BigDecimal discount = null;
        BigDecimal minFee = null;
        BigDecimal fee;
        switch (mode) {
            case "actual" -> {
                fee = request.fee();
                if (fee == null || fee.signum() < 0 || fee.compareTo(MAX_FEE) >= 0) {
                    throw badRequest("請填寫實際手續費（0 以上）");
                }
                if (decimals(fee) > 2) {
                    throw badRequest("手續費最多 2 位小數");
                }
            }
            case "standard", "discount" -> {
                minFee = request.minFee() == null ? DEFAULT_MIN_FEE : request.minFee();
                if (minFee.signum() < 0 || minFee.compareTo(MAX_MIN_FEE) >= 0 || decimals(minFee) > 2) {
                    throw badRequest("最低手續費必須是 0 以上、最多 2 位小數的金額");
                }
                if (mode.equals("discount")) {
                    discount = request.feeDiscount();
                    if (discount == null || discount.signum() <= 0 || discount.compareTo(BigDecimal.ONE) > 0 || decimals(discount) > 4) {
                        throw badRequest("折數必須大於 0、最多為 1（2.8 折填 0.28），最多 4 位小數");
                    }
                }
                BigDecimal amount = price.multiply(BigDecimal.valueOf(shares));
                fee = TradingCosts.fee(amount, discount == null ? BigDecimal.ONE : discount, minFee);
            }
            default -> throw badRequest("手續費方式必須是 actual（實際金額）、standard（公定費率）或 discount（折數）");
        }

        holding.setSymbol(symbol);
        holding.setBuyDate(buyDate);
        holding.setPrice(price);
        holding.setShares(shares);
        holding.setFeeMode(mode);
        holding.setFeeDiscount(discount);
        holding.setMinFee(minFee);
        holding.setFee(fee.setScale(2, RoundingMode.HALF_UP));
    }

    private static int decimals(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // ---- 分析 ----

    @Transactional(readOnly = true)
    public HoldingsResponse getHoldings(long userId) {
        List<Holding> holdings = holdingRepository.findByUserIdOrderByIdAsc(userId);
        LocalDate asOf = latestTradeDate();
        Map<String, List<Holding>> bySymbol = holdings.stream()
                .collect(Collectors.groupingBy(Holding::getSymbol, LinkedHashMap::new, Collectors.toList()));
        Map<String, Stock> stocks = stockRepository.findAllById(bySymbol.keySet()).stream()
                .collect(Collectors.toMap(Stock::getSymbol, Function.identity()));

        List<PositionCalc> calcs = new ArrayList<>();
        bySymbol.forEach((symbol, lots) -> calcs.add(position(stocks.get(symbol), lots, asOf)));

        BigDecimal totalMarketValue = sum(calcs.stream().map(c -> c.marketValue));
        List<Position> positions = calcs.stream()
                .sorted(Comparator.comparing((PositionCalc c) -> c.marketValue, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(c -> c.toResponse(totalMarketValue))
                .toList();

        List<PositionCalc> priced = calcs.stream().filter(c -> c.marketValue != null).toList();
        BigDecimal cost = sum(priced.stream().map(c -> c.cost));
        BigDecimal sellCost = sum(priced.stream().map(c -> c.sellCost));
        BigDecimal unrealized = sum(priced.stream().map(c -> c.unrealizedPnl));
        BigDecimal dividends = sum(priced.stream().map(c -> c.dividends));
        BigDecimal totalPnl = unrealized.add(dividends);
        Summary summary = new Summary(money(cost), money(totalMarketValue), money(sellCost), money(unrealized),
                ratio(unrealized, cost), money(dividends), money(totalPnl), ratio(totalPnl, cost));

        FeeRules feeRules = new FeeRules(TradingCosts.FEE_RATE, DEFAULT_MIN_FEE, TradingCosts.TAX_STOCK, TradingCosts.TAX_ETF, ROUNDING_NOTE);
        return new HoldingsResponse(asOf, summary, benchmark(calcs, asOf), positions, holdings.size(), MAX_HOLDINGS, feeRules);
    }

    private LocalDate latestTradeDate() {
        return priceRepository.findRecentTradeDates(PageRequest.of(0, 1)).stream().findFirst().orElse(null);
    }

    private List<StockAction> actions(String symbol) {
        return actionRepository.findBySymbolOrderByExDateAsc(symbol);
    }

    // 一筆買進換算成目前的基準：買進日之後（到 asOf 為止）的股數變動相乘；現金股利依換算後的股數計算。
    // stock_actions 的股利已依之後的股數變動調整（與價格同一基準），所以乘上換算後的股數就是實際領到的金額
    private Lot lot(Holding h, List<StockAction> actions, LocalDate asOf) {
        BigDecimal splitRatio = BigDecimal.ONE;
        BigDecimal dividendPerShare = BigDecimal.ZERO;
        if (h.getBuyDate() != null) {
            for (StockAction action : actions) {
                if (!action.getExDate().isAfter(h.getBuyDate()) || (asOf != null && action.getExDate().isAfter(asOf))) {
                    continue;
                }
                if (action.getSplitRatio() != null) {
                    splitRatio = splitRatio.multiply(action.getSplitRatio(), MC);
                }
                if (action.getCashDividend() != null) {
                    dividendPerShare = dividendPerShare.add(action.getCashDividend());
                }
            }
        }
        BigDecimal currentShares = BigDecimal.valueOf(h.getShares()).multiply(splitRatio, MC);
        BigDecimal dividends = h.getBuyDate() == null ? null : money(dividendPerShare.multiply(currentShares, MC));
        BigDecimal cost = h.getPrice().multiply(BigDecimal.valueOf(h.getShares())).add(h.getFee());
        return new Lot(h.getId(), h.getSymbol(), h.getBuyDate(), h.getPrice(), h.getShares(), h.getFeeMode(), h.getFeeDiscount(),
                h.getMinFee(), h.getFee(), money(cost), splitRatio.stripTrailingZeros(), currentShares.setScale(4, RoundingMode.HALF_UP),
                dividends);
    }

    private PositionCalc position(Stock stock, List<Holding> holdings, LocalDate asOf) {
        String symbol = stock.getSymbol();
        List<StockAction> actions = actions(symbol);
        PositionCalc c = new PositionCalc();
        c.stock = stock;
        c.holdings = holdings;
        c.lots = holdings.stream().map(h -> lot(h, actions, asOf)).toList();
        c.cost = sum(c.lots.stream().map(Lot::cost));
        c.shares = sum(c.lots.stream().map(Lot::currentShares));
        BigDecimal amount = sum(holdings.stream().map(h -> h.getPrice().multiply(BigDecimal.valueOf(h.getShares()))));
        c.avgPrice = c.shares.signum() > 0 ? amount.divide(c.shares, MC) : null;
        c.costPerShare = c.shares.signum() > 0 ? c.cost.divide(c.shares, MC) : null;
        c.dividends = sum(c.lots.stream().map(Lot::dividends).filter(Objects::nonNull));
        c.dividendsComplete = c.lots.stream().allMatch(l -> l.dividends() != null);
        c.trailingFrom = holdings.stream().map(Holding::getBuyDate).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);

        if (asOf == null) {
            return c;
        }
        Optional<StockPrice> latest = priceRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(symbol, asOf);
        if (latest.isEmpty()) {
            return c;
        }
        c.price = latest.get().getClosePrice();
        c.priceDate = latest.get().getTradeDate();
        c.marketValue = c.shares.multiply(c.price, MC);
        c.sellCost = sellCost(symbol, c.marketValue, holdings.get(holdings.size() - 1));
        c.unrealizedPnl = c.marketValue.subtract(c.sellCost).subtract(c.cost);

        Optional<StockIndicator> indicator = indicatorRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(symbol, asOf);
        c.atr14 = indicator.map(StockIndicator::getAtr14).orElse(null);
        c.ma60 = indicator.map(StockIndicator::getMa60).orElse(null);
        if (c.trailingFrom != null && !c.trailingFrom.isAfter(asOf)) {
            c.historyComplete = priceRepository.findFirstBySymbolOrderByTradeDateAsc(symbol)
                    .map(first -> !c.trailingFrom.isBefore(first.getTradeDate())).orElse(false);
            c.highestClose = priceRepository.findMaxClose(symbol, c.trailingFrom, asOf).orElse(null);
            if (c.highestClose != null && c.atr14 != null) {
                c.exitLine = c.highestClose.subtract(ReferenceLevelService.ATR_MULTIPLE.multiply(c.atr14));
            }
        }
        levelRepository.findFirstBySymbolOrderByBasisDateDesc(symbol)
                .filter(level -> level.getNextExDate() != null && level.getNextExDate().isAfter(asOf))
                .ifPresent(level -> c.level = level);
        c.signals = signalRepository.findBySymbolAndTradeDate(symbol, asOf).stream()
                .map(s -> new Signal(s.getStrategy(), Strategy.fromCode(s.getStrategy()).map(Strategy::label).orElse(s.getStrategy())))
                .toList();
        return c;
    }

    // 全部賣出的成本估算：手續費依最近一筆的方式（「實際金額」無法得知折扣，以公定費率、最低 20 元估算）＋證交稅
    private static BigDecimal sellCost(String symbol, BigDecimal marketValue, Holding latest) {
        BigDecimal discount = "discount".equals(latest.getFeeMode()) ? latest.getFeeDiscount() : BigDecimal.ONE;
        BigDecimal minFee = latest.getMinFee() == null ? DEFAULT_MIN_FEE : latest.getMinFee();
        return TradingCosts.fee(marketValue, discount, minFee).add(TradingCosts.tax(marketValue, symbol));
    }

    // 同期比較：每筆有買進日期的庫存，假設同一天（非交易日則下一個交易日）以同樣的成本在收盤買 0050，持有到 asOf（含股利、不計成本）
    private Benchmark benchmark(List<PositionCalc> calcs, LocalDate asOf) {
        int included = 0;
        int excluded = 0;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal holdingsValue = BigDecimal.ZERO;
        BigDecimal etfValue = BigDecimal.ZERO;

        Optional<StockPrice> etfNow = asOf == null ? Optional.empty()
                : priceRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(ETF_BENCHMARK, asOf);
        Optional<LocalDate> etfFirst = priceRepository.findFirstBySymbolOrderByTradeDateAsc(ETF_BENCHMARK).map(StockPrice::getTradeDate);
        List<StockAction> etfActions = actions(ETF_BENCHMARK);

        for (PositionCalc c : calcs) {
            for (Lot lot : c.lots) {
                LocalDate buyDate = lot.buyDate();
                Optional<StockPrice> entry = buyDate == null || etfFirst.isEmpty() || buyDate.isBefore(etfFirst.get())
                        ? Optional.empty()
                        : priceRepository.findFirstBySymbolAndTradeDateGreaterThanEqualOrderByTradeDateAsc(ETF_BENCHMARK, buyDate)
                                .filter(p -> !p.getTradeDate().isAfter(asOf));
                if (entry.isEmpty() || etfNow.isEmpty() || c.marketValue == null) {
                    excluded++;
                    continue;
                }
                LocalDate entryDate = entry.get().getTradeDate();
                BigDecimal units = lot.cost().divide(entry.get().getClosePrice(), MC);
                BigDecimal dividends = sum(etfActions.stream()
                        .filter(a -> a.getCashDividend() != null && a.getExDate().isAfter(entryDate) && !a.getExDate().isAfter(asOf))
                        .map(StockAction::getCashDividend));
                etfValue = etfValue.add(units.multiply(etfNow.get().getClosePrice().add(dividends), MC));

                // 這筆庫存目前的價值：依股數比例分攤整檔的市值與賣出成本，加上這筆領到的股利
                BigDecimal share = lot.currentShares().divide(c.shares, MC);
                holdingsValue = holdingsValue.add(c.marketValue.subtract(c.sellCost).multiply(share, MC)).add(lot.dividends());
                invested = invested.add(lot.cost());
                included++;
            }
        }
        if (included == 0) {
            return new Benchmark(ETF_BENCHMARK, 0, excluded, null, null, null, null, null);
        }
        return new Benchmark(ETF_BENCHMARK, included, excluded, money(invested), money(holdingsValue),
                ratio(holdingsValue.subtract(invested), invested), money(etfValue), ratio(etfValue.subtract(invested), invested));
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> values) {
        return values.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() == 0 ? null
                : numerator.divide(denominator, MC).setScale(6, RoundingMode.HALF_UP);
    }

    // 一檔股票（同一檔的多筆合併）的計算過程；權重要等全部算完才知道總市值
    private static final class PositionCalc {
        Stock stock;
        List<Holding> holdings;
        List<Lot> lots;
        BigDecimal cost;
        BigDecimal shares;
        BigDecimal avgPrice;
        BigDecimal costPerShare;
        BigDecimal dividends;
        boolean dividendsComplete;
        LocalDate trailingFrom;
        BigDecimal price;
        LocalDate priceDate;
        BigDecimal marketValue;
        BigDecimal sellCost;
        BigDecimal unrealizedPnl;
        BigDecimal atr14;
        BigDecimal ma60;
        boolean historyComplete = true; // 只有買進日期早於資料起點時為 false（沒有買進日期時不適用）
        BigDecimal highestClose;
        BigDecimal exitLine;
        ReferenceLevel level;
        List<Signal> signals = List.of();

        Position toResponse(BigDecimal totalMarketValue) {
            BigDecimal totalPnl = unrealizedPnl == null ? null : unrealizedPnl.add(dividends);
            BigDecimal distance = exitLine == null || price == null ? null : ratio(price.subtract(exitLine), price);
            return new Position(
                    stock.getSymbol(), stock.getName(), stock.groups(), lots,
                    shares.setScale(4, RoundingMode.HALF_UP), money(cost), price(avgPrice), price(costPerShare),
                    price, priceDate, money(marketValue), marketValue == null ? null : ratio(marketValue, totalMarketValue),
                    money(sellCost), money(unrealizedPnl), ratio(unrealizedPnl, cost),
                    money(dividends), dividendsComplete, money(totalPnl), ratio(totalPnl, cost),
                    trailingFrom, historyComplete, highestClose, atr14, price(exitLine), distance,
                    exitLine != null && price != null && price.compareTo(exitLine) < 0,
                    ma60, ma60 != null && price != null && price.compareTo(ma60) < 0,
                    level == null ? null : level.getNextExDate(), level == null ? null : level.getNextExType(),
                    level == null ? null : level.getNextCashDividend(),
                    signals);
        }
    }
}
