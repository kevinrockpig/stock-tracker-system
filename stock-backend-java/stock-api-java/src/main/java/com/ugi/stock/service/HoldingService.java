package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.HoldingsResponse;
import com.ugi.stock.dto.HoldingsResponse.Benchmark;
import com.ugi.stock.dto.HoldingsResponse.ClosedPosition;
import com.ugi.stock.dto.HoldingsResponse.FeeRules;
import com.ugi.stock.dto.HoldingsResponse.Lot;
import com.ugi.stock.dto.HoldingsResponse.Position;
import com.ugi.stock.dto.HoldingsResponse.Realized;
import com.ugi.stock.dto.HoldingsResponse.Sale;
import com.ugi.stock.dto.HoldingsResponse.SaleLot;
import com.ugi.stock.dto.HoldingsResponse.Signal;
import com.ugi.stock.dto.HoldingsResponse.Summary;
import com.ugi.stock.entity.Holding;
import com.ugi.stock.entity.HoldingSale;
import com.ugi.stock.entity.ReferenceLevel;
import com.ugi.stock.entity.Stock;
import com.ugi.stock.entity.StockAction;
import com.ugi.stock.entity.StockIndicator;
import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.repository.HoldingRepository;
import com.ugi.stock.repository.HoldingSaleRepository;
import com.ugi.stock.repository.ReferenceLevelRepository;
import com.ugi.stock.repository.StockActionRepository;
import com.ugi.stock.repository.StockIndicatorRepository;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StockRepository;
import com.ugi.stock.repository.StrategySignalRepository;
import com.ugi.stock.service.LotLedger.LotState;
import com.ugi.stock.service.LotLedger.SaleState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 使用者自行填寫的庫存：新增 / 修改 / 刪除與分析。所有方法都以登入者的 userId 過濾，取不到（包括別人的）一律回 404。
 * 賣出紀錄見 HoldingSaleService；每筆賣出從哪幾筆買進扣、已實現損益由 LotLedger 依先進先出即時計算。
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
    static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final String ROUNDING_NOTE = "手續費與證交稅無條件捨去到元，手續費低於最低手續費時以最低手續費計；各券商規則不同，僅為估算";

    private final EntityManager entityManager;
    private final HoldingRepository holdingRepository;
    private final HoldingSaleRepository saleRepository;
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
        return lotOf(userId, holding);
    }

    @Transactional
    public Lot update(long userId, long id, HoldingRequest request) {
        Holding holding = find(userId, id);
        String oldSymbol = holding.getSymbol();
        // 先讀出全部紀錄（之後只在記憶體中檢查，不會先寫入資料庫）
        List<Holding> all = holdingRepository.findByUserIdOrderByIdAsc(userId);
        List<HoldingSale> sales = saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId);
        apply(holding, request);
        try {
            // 改了股數、日期或股票後，之前的賣出可能超過當時持有的股數
            withoutAutoFlush(() -> checkSales(new TreeSet<>(List.of(oldSymbol, holding.getSymbol())), all, sales, "修改後"));
        } catch (ResponseStatusException e) {
            entityManager.refresh(holding); // 還原這筆的修改
            throw e;
        }
        holding.setUpdatedAt(Instant.now());
        holdingRepository.flush();
        return lotOf(userId, holding);
    }

    @Transactional
    public void delete(long userId, long id) {
        Holding holding = find(userId, id);
        List<Holding> others = holdingRepository.findByUserIdOrderByIdAsc(userId).stream()
                .filter(h -> !h.getId().equals(holding.getId())).toList();
        checkSales(Set.of(holding.getSymbol()), others, saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId), "刪除這筆後");
        holdingRepository.delete(holding);
    }

    private Holding find(long userId, long id) {
        return holdingRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這筆庫存"));
    }

    // 檢查期間查詢其他資料表時，不先把記憶體中的修改寫入資料庫（擋下時才能還原）
    void withoutAutoFlush(Runnable check) {
        FlushModeType mode = entityManager.getFlushMode();
        entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            check.run();
        } finally {
            entityManager.setFlushMode(mode);
        }
    }

    // 某些股票的買進改變後，重新依先進先出分配賣出；有賣出找不到足夠的買進時擋下
    private void checkSales(Collection<String> symbols, List<Holding> holdings, List<HoldingSale> sales, String when) {
        for (String symbol : symbols) {
            SaleState unmatched = ledger(symbol, holdings, sales, latestTradeDate()).firstUnmatched();
            if (unmatched != null) {
                throw badRequest(when + "，" + unmatched.sale.getSellDate() + " 賣出的 " + unmatched.sale.getShares()
                        + " 股會超過當時持有的股數（" + unmatched.availableShares() + " 股），請先修改或刪除那筆賣出");
            }
        }
    }

    // 檢查輸入並寫入欄位；手續費依方式計算後存下金額
    private void apply(Holding holding, HoldingRequest request) {
        if (request == null) {
            throw badRequest("缺少庫存內容");
        }
        String symbol = symbol(request.symbol());
        // 新增須在觀察名單內；已從觀察名單移除的股票仍可修改原本那筆（Python 每日更新會繼續抓庫存中的股票）
        boolean sameSymbol = symbol.equals(holding.getSymbol());
        Stock stock = stockRepository.findById(symbol).filter(s -> s.getGroupCodes() != null || sameSymbol)
                .orElseThrow(() -> badRequest("股票 " + symbol + " 不在觀察名單內"));

        BigDecimal price = checkPrice(request.price());
        int shares = checkShares(request.shares());
        LocalDate buyDate = request.buyDate();
        if (buyDate != null) {
            if (buyDate.isAfter(LocalDate.now(TAIPEI))) {
                throw badRequest("買進日期不能晚於今天");
            }
            if (stock.getListedDate() != null && buyDate.isBefore(stock.getListedDate())) {
                throw badRequest("買進日期不能早於上市（櫃）日 " + stock.getListedDate());
            }
        }
        Fee fee = fee(request.feeMode(), request.feeDiscount(), request.minFee(), request.fee(), price.multiply(BigDecimal.valueOf(shares)));

        holding.setSymbol(symbol);
        holding.setBuyDate(buyDate);
        holding.setPrice(price);
        holding.setShares(shares);
        holding.setFeeMode(fee.mode());
        holding.setFeeDiscount(fee.discount());
        holding.setMinFee(fee.minFee());
        holding.setFee(fee.amount());
    }

    static String symbol(String value) {
        return Optional.ofNullable(value).map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).orElseThrow(() -> badRequest("請填寫股票代號"));
    }

    static BigDecimal checkPrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) >= 0) {
            throw badRequest("價格必須大於 0");
        }
        if (decimals(price) > 4) {
            throw badRequest("價格最多 4 位小數");
        }
        return price;
    }

    static int checkShares(Integer shares) {
        if (shares == null || shares <= 0 || shares > MAX_SHARES) {
            throw badRequest("股數必須大於 0");
        }
        return shares;
    }

    /** 手續費：方式、折數、最低手續費與金額（到分） */
    record Fee(String mode, BigDecimal discount, BigDecimal minFee, BigDecimal amount) {
    }

    // 買進與賣出共用：actual 照填；standard / discount 依成交金額計算
    static Fee fee(String feeMode, BigDecimal feeDiscount, BigDecimal minFeeInput, BigDecimal actualFee, BigDecimal amount) {
        String mode = Optional.ofNullable(feeMode).map(String::trim).orElse("");
        BigDecimal discount = null;
        BigDecimal minFee = null;
        BigDecimal fee;
        switch (mode) {
            case "actual" -> {
                fee = actualFee;
                if (fee == null || fee.signum() < 0 || fee.compareTo(MAX_FEE) >= 0) {
                    throw badRequest("請填寫實際手續費（0 以上）");
                }
                if (decimals(fee) > 2) {
                    throw badRequest("手續費最多 2 位小數");
                }
            }
            case "standard", "discount" -> {
                minFee = minFeeInput == null ? DEFAULT_MIN_FEE : minFeeInput;
                if (minFee.signum() < 0 || minFee.compareTo(MAX_MIN_FEE) >= 0 || decimals(minFee) > 2) {
                    throw badRequest("最低手續費必須是 0 以上、最多 2 位小數的金額");
                }
                if (mode.equals("discount")) {
                    discount = feeDiscount;
                    if (discount == null || discount.signum() <= 0 || discount.compareTo(BigDecimal.ONE) > 0 || decimals(discount) > 4) {
                        throw badRequest("折數必須大於 0、最多為 1（2.8 折填 0.28），最多 4 位小數");
                    }
                }
                fee = TradingCosts.fee(amount, discount == null ? BigDecimal.ONE : discount, minFee);
            }
            default -> throw badRequest("手續費方式必須是 actual（實際金額）、standard（公定費率）或 discount（折數）");
        }
        return new Fee(mode, discount, minFee, fee.setScale(2, RoundingMode.HALF_UP));
    }

    static void checkMoney(BigDecimal value, String message) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_FEE) >= 0 || decimals(value) > 2) {
            throw badRequest(message);
        }
    }

    private static int decimals(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // ---- 先進先出 ----

    LotLedger ledger(String symbol, List<Holding> holdings, List<HoldingSale> sales, LocalDate asOf) {
        return new LotLedger(holdings.stream().filter(h -> h.getSymbol().equals(symbol)).toList(),
                sales.stream().filter(s -> s.getSymbol().equals(symbol)).toList(), actions(symbol), asOf);
    }

    // 新增或修改後回傳的一筆（含先進先出後剩下的股數）
    private Lot lotOf(long userId, Holding holding) {
        LocalDate asOf = latestTradeDate();
        LotLedger ledger = ledger(holding.getSymbol(), holdingRepository.findByUserIdOrderByIdAsc(userId),
                saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId), asOf);
        LotState state = ledger.lots().stream().filter(l -> l.holding.getId().equals(holding.getId())).findFirst().orElseThrow();
        return lot(ledger, state, asOf);
    }

    static Lot lot(LotLedger ledger, LotState l, LocalDate asOf) {
        Holding h = l.holding;
        BigDecimal remaining = l.remaining();
        return new Lot(h.getId(), h.getSymbol(), h.getBuyDate(), h.getPrice(), h.getShares(), h.getFeeMode(), h.getFeeDiscount(),
                h.getMinFee(), h.getFee(), l.cost, l.splitRatio.stripTrailingZeros(), shares(l.shares), shares(remaining),
                shares(l.shares.subtract(remaining)), ledger.dividendsAt(l, asOf == null ? LocalDate.now(TAIPEI) : asOf));
    }

    static Sale sale(LotLedger ledger, SaleState s, String name) {
        HoldingSale sale = s.sale;
        BigDecimal cost = money(s.cost());
        BigDecimal proceeds = money(s.proceeds);
        BigDecimal realized = proceeds.subtract(cost);
        List<SaleLot> lots = s.pieces.stream().map(p -> new SaleLot(p.lot().holding.getId(), p.lot().holding.getBuyDate(),
                p.lot().holding.getPrice(), shares(p.shares()), money(p.cost()))).toList();
        return new Sale(sale.getId(), sale.getSymbol(), name, sale.getSellDate(), sale.getPrice(), sale.getShares(), sale.getFeeMode(),
                sale.getFeeDiscount(), sale.getMinFee(), sale.getFee(), sale.getTaxMode(), sale.getTax(), money(s.amount), proceeds, cost,
                realized, ratio(realized, cost), ledger.dividends(s), LotLedger.dividendsComplete(s), shares(s.unmatched), lots);
    }

    private static BigDecimal shares(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    // ---- 分析 ----

    @Transactional(readOnly = true)
    public HoldingsResponse getHoldings(long userId) {
        List<Holding> holdings = holdingRepository.findByUserIdOrderByIdAsc(userId);
        List<HoldingSale> sales = saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId);
        LocalDate asOf = latestTradeDate();
        Map<String, List<Holding>> bySymbol = holdings.stream()
                .collect(Collectors.groupingBy(Holding::getSymbol, LinkedHashMap::new, Collectors.toList()));
        Map<String, Stock> stocks = stockRepository.findAllById(bySymbol.keySet()).stream()
                .collect(Collectors.toMap(Stock::getSymbol, Function.identity()));

        List<PositionCalc> calcs = new ArrayList<>();
        bySymbol.forEach((symbol, lots) -> calcs.add(position(stocks.get(symbol), lots, sales, asOf)));
        List<PositionCalc> open = calcs.stream().filter(c -> c.shares.signum() > 0).toList();

        BigDecimal totalMarketValue = sum(open.stream().map(c -> c.marketValue));
        List<Position> positions = open.stream()
                .sorted(Comparator.comparing((PositionCalc c) -> c.marketValue, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(c -> c.toResponse(totalMarketValue))
                .toList();

        List<PositionCalc> priced = open.stream().filter(c -> c.marketValue != null).toList();
        BigDecimal cost = sum(priced.stream().map(c -> c.cost));
        BigDecimal sellCost = sum(priced.stream().map(c -> c.sellCost));
        BigDecimal unrealized = sum(priced.stream().map(c -> c.unrealizedPnl));
        BigDecimal dividends = sum(priced.stream().map(c -> c.dividends));
        BigDecimal totalPnl = unrealized.add(dividends);
        Summary summary = new Summary(money(cost), money(totalMarketValue), money(sellCost), money(unrealized),
                ratio(unrealized, cost), money(dividends), money(totalPnl), ratio(totalPnl, cost));

        FeeRules feeRules = new FeeRules(TradingCosts.FEE_RATE, DEFAULT_MIN_FEE, TradingCosts.TAX_STOCK, TradingCosts.TAX_ETF, ROUNDING_NOTE);
        return new HoldingsResponse(asOf, summary, benchmark(calcs, asOf), positions, holdings.size(), MAX_HOLDINGS, feeRules,
                realized(calcs, totalPnl, sales.size()));
    }

    LocalDate latestTradeDate() {
        return priceRepository.findRecentTradeDates(PageRequest.of(0, 1)).stream().findFirst().orElse(null);
    }

    List<StockAction> actions(String symbol) {
        return actionRepository.findBySymbolOrderByExDateAsc(symbol);
    }

    private PositionCalc position(Stock stock, List<Holding> holdings, List<HoldingSale> allSales, LocalDate asOf) {
        String symbol = stock.getSymbol();
        LotLedger ledger = ledger(symbol, holdings, allSales, asOf);
        PositionCalc c = new PositionCalc();
        c.stock = stock;
        c.ledger = ledger;
        c.latest = holdings.get(holdings.size() - 1);
        c.lots = ledger.lots().stream().map(l -> lot(ledger, l, asOf)).toList();
        List<LotState> kept = ledger.lots().stream().filter(l -> l.remaining().signum() > 0).toList();
        c.shares = ledger.remainingShares();
        c.cost = sum(kept.stream().map(LotState::remainingCost));
        // 平均成交價：還持有的部分的買進金額（買進當時的價格 × 對應的股數）÷ 目前股數
        BigDecimal amount = sum(kept.stream().map(l -> l.holding.getPrice().multiply(l.remaining(), MC).divide(l.splitRatio, MC)));
        c.avgPrice = c.shares.signum() > 0 ? amount.divide(c.shares, MC) : null;
        c.costPerShare = c.shares.signum() > 0 ? c.cost.divide(c.shares, MC) : null;
        c.dividends = sum(kept.stream().map(ledger::keptDividends));
        c.dividendsComplete = kept.stream().allMatch(l -> l.holding.getBuyDate() != null);
        c.trailingFrom = kept.stream().map(l -> l.holding.getBuyDate()).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        c.sales = ledger.sales().stream().map(s -> sale(ledger, s, stock.getName())).toList();
        c.realizedPnl = sum(c.sales.stream().map(Sale::realizedPnl));
        c.realizedDividends = sum(c.sales.stream().map(Sale::dividends));

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
        if (c.shares.signum() == 0) {
            c.sellCost = BigDecimal.ZERO; // 已全部賣出：不需要以下的分析
            c.unrealizedPnl = BigDecimal.ZERO;
            return c;
        }
        c.sellCost = sellCost(symbol, c.marketValue, c.latest);
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
    static BigDecimal sellCost(String symbol, BigDecimal marketValue, Holding latest) {
        BigDecimal discount = "discount".equals(latest.getFeeMode()) ? latest.getFeeDiscount() : BigDecimal.ONE;
        BigDecimal minFee = latest.getMinFee() == null ? DEFAULT_MIN_FEE : latest.getMinFee();
        return TradingCosts.fee(marketValue, discount, minFee).add(TradingCosts.tax(marketValue, symbol));
    }

    // 同期比較：每筆有買進日期的庫存（含之後賣出的），假設同一天（非交易日則下一個交易日）以同樣的成本在收盤買 0050，
    // 持有到 asOf（含股利、不計成本）。庫存這邊的價值見 LotLedger.valueAt：賣出拿回的金額視為現金保留
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
            BigDecimal net = c.marketValue == null ? null : c.marketValue.subtract(c.sellCost);
            for (LotState lot : c.ledger.lots()) {
                LocalDate buyDate = lot.holding.getBuyDate();
                Optional<StockPrice> entry = buyDate == null || etfFirst.isEmpty() || buyDate.isBefore(etfFirst.get())
                        ? Optional.empty()
                        : priceRepository.findFirstBySymbolAndTradeDateGreaterThanEqualOrderByTradeDateAsc(ETF_BENCHMARK, buyDate)
                                .filter(p -> !p.getTradeDate().isAfter(asOf));
                if (entry.isEmpty() || etfNow.isEmpty() || net == null) {
                    excluded++;
                    continue;
                }
                LocalDate entryDate = entry.get().getTradeDate();
                BigDecimal units = lot.cost.divide(entry.get().getClosePrice(), MC);
                BigDecimal dividends = sum(etfActions.stream()
                        .filter(a -> a.getCashDividend() != null && a.getExDate().isAfter(entryDate) && !a.getExDate().isAfter(asOf))
                        .map(StockAction::getCashDividend));
                etfValue = etfValue.add(units.multiply(etfNow.get().getClosePrice().add(dividends), MC));
                holdingsValue = holdingsValue.add(c.ledger.valueAt(lot, asOf, net, c.shares));
                invested = invested.add(lot.cost);
                included++;
            }
        }
        if (included == 0) {
            return new Benchmark(ETF_BENCHMARK, 0, excluded, null, null, null, null, null);
        }
        return new Benchmark(ETF_BENCHMARK, included, excluded, money(invested), money(holdingsValue),
                ratio(holdingsValue.subtract(invested), invested), money(etfValue), ratio(etfValue.subtract(invested), invested));
    }

    // 已實現損益：每筆賣出與已全部賣出的股票；總損益加上目前持股的未實現損益與股利（openPnl）
    private Realized realized(List<PositionCalc> calcs, BigDecimal openPnl, int count) {
        List<Sale> sales = calcs.stream().flatMap(c -> c.sales.stream())
                .sorted(Comparator.comparing(Sale::sellDate).thenComparing(Sale::id).reversed())
                .toList();
        BigDecimal proceeds = sum(sales.stream().map(Sale::proceeds));
        BigDecimal cost = sum(sales.stream().map(Sale::cost));
        BigDecimal realized = sum(sales.stream().map(Sale::realizedPnl));
        BigDecimal dividends = sum(sales.stream().map(Sale::dividends));
        boolean complete = sales.stream().allMatch(Sale::dividendsComplete);
        List<ClosedPosition> closed = calcs.stream().filter(c -> c.shares.signum() == 0)
                .map(c -> new ClosedPosition(c.stock.getSymbol(), c.stock.getName(), c.lots, money(c.realizedPnl),
                        money(c.realizedDividends), money(c.realizedPnl.add(c.realizedDividends))))
                .toList();
        return new Realized(count, HoldingSaleService.MAX_SALES, money(proceeds), money(cost), money(realized), ratio(realized, cost),
                money(dividends), complete, money(openPnl.add(realized).add(dividends)), sales, closed);
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> values) {
        return values.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() == 0 ? null
                : numerator.divide(denominator, MC).setScale(6, RoundingMode.HALF_UP);
    }

    // 一檔股票（同一檔的多筆合併）的計算過程；權重要等全部算完才知道總市值。股數與成本只計還沒賣出的部分
    private static final class PositionCalc {
        Stock stock;
        LotLedger ledger;
        Holding latest; // 最近新增的一筆：估算賣出手續費的方式
        List<Lot> lots;
        BigDecimal cost;
        BigDecimal shares;
        BigDecimal avgPrice;
        BigDecimal costPerShare;
        BigDecimal dividends;
        boolean dividendsComplete;
        LocalDate trailingFrom;
        List<Sale> sales;
        BigDecimal realizedPnl;
        BigDecimal realizedDividends;
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
            BigDecimal overall = totalPnl == null ? null : totalPnl.add(realizedPnl).add(realizedDividends);
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
                    signals, money(realizedPnl), money(realizedDividends), money(overall));
        }
    }
}
