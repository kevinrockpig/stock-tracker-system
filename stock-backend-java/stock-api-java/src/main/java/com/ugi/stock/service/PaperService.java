package com.ugi.stock.service;

import com.ugi.stock.dto.PaperResponse;
import com.ugi.stock.dto.PaperResponse.ClosedTrade;
import com.ugi.stock.dto.PaperResponse.EquityPoint;
import com.ugi.stock.dto.PaperResponse.Plan;
import com.ugi.stock.dto.PaperResponse.Position;
import com.ugi.stock.entity.PaperAccount;
import com.ugi.stock.entity.PaperEquity;
import com.ugi.stock.entity.PaperPosition;
import com.ugi.stock.entity.Stock;
import com.ugi.stock.entity.StockIndicator;
import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.entity.TradePlan;
import com.ugi.stock.repository.PaperAccountRepository;
import com.ugi.stock.repository.PaperEquityRepository;
import com.ugi.stock.repository.PaperPositionRepository;
import com.ugi.stock.repository.StockIndicatorRepository;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StockRepository;
import com.ugi.stock.repository.TradePlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaperService {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final PaperAccountRepository accountRepository;
    private final PaperPositionRepository positionRepository;
    private final PaperEquityRepository equityRepository;
    private final TradePlanRepository planRepository;
    private final StockRepository stockRepository;
    private final StockPriceRepository priceRepository;
    private final StockIndicatorRepository indicatorRepository;

    /**
     * 所有模擬帳戶（Java 尚未定義的策略或出場規則先略過）。
     */
    public List<PaperResponse> getAccounts() {
        return accountRepository.findAll().stream()
                .filter(account -> Strategy.fromCode(account.getStrategy()).isPresent())
                .filter(account -> ExitRule.fromCode(account.getExitRule()).isPresent())
                .sorted(Comparator.comparing(PaperAccount::getId))
                .map(this::toResponse)
                .toList();
    }

    private PaperResponse toResponse(PaperAccount account) {
        Strategy strategy = Strategy.fromCode(account.getStrategy()).orElseThrow();
        ExitRule rule = ExitRule.fromCode(account.getExitRule()).orElseThrow();
        List<PaperPosition> positions = positionRepository.findByAccountIdOrderByEntryDateAscIdAsc(account.getId());
        List<PaperEquity> curve = equityRepository.findByAccountIdOrderByTradeDateAsc(account.getId());
        List<TradePlan> pending = planRepository.findByAccountIdAndStatusOrderByActionDescPriorityAsc(account.getId(), "pending");
        List<TradePlan> latest = planRepository.findByAccountIdAndExecutedDateOrderByActionDescPriorityAsc(
                account.getId(), account.getLastProcessed());

        Map<String, String> names = stockRepository.findAllById(
                        Stream.of(positions.stream().map(PaperPosition::getSymbol), pending.stream().map(TradePlan::getSymbol),
                                        latest.stream().map(TradePlan::getSymbol))
                                .flatMap(Function.identity()).distinct().toList())
                .stream().collect(Collectors.toMap(Stock::getSymbol, Stock::getName));

        PaperEquity last = curve.isEmpty() ? null : curve.get(curve.size() - 1);
        BigDecimal capital = account.getInitialCapital();
        BigDecimal equity = last == null ? capital : last.getEquity();

        List<Position> open = positions.stream()
                .filter(p -> p.getExitDate() == null)
                .map(p -> toPosition(p, account, rule, names, equity))
                .toList();
        List<ClosedTrade> closed = positions.stream()
                .filter(p -> p.getExitDate() != null)
                .sorted(Comparator.comparing(PaperPosition::getExitDate).thenComparing(PaperPosition::getId).reversed())
                .map(p -> new ClosedTrade(p.getSymbol(), names.getOrDefault(p.getSymbol(), p.getSymbol()), p.getSignalDate(),
                        p.getEntryDate(), p.getEntryPrice(), p.getExitDate(), p.getExitPrice(), p.getExitReason(),
                        p.getHeldDays(), p.getDividends(), p.getNetReturn()))
                .toList();

        return new PaperResponse(
                StrategyService.toResponse(strategy),
                BacktestService.toResponse(rule),
                account.getSlots(),
                capital,
                account.getInceptionDate(),
                account.getLastProcessed(),
                curve.size(),
                account.getCash(),
                equity,
                ratio(equity, capital).subtract(BigDecimal.ONE),
                last == null || last.getBenchmark() == null ? null : last.getBenchmark().subtract(BigDecimal.ONE),
                last == null || last.getEtf() == null ? null : last.getEtf().subtract(BigDecimal.ONE),
                maxDrawdown(curve),
                pending.stream().map(plan -> toPlan(plan, names)).toList(),
                latest.stream().map(plan -> toPlan(plan, names)).toList(),
                open,
                closed,
                curve.stream().map(point -> new EquityPoint(point.getTradeDate(), point.getEquity(), point.getCash(),
                        point.getPositions(),
                        point.getBenchmark() == null ? null : point.getBenchmark().multiply(capital, MC),
                        point.getEtf() == null ? null : point.getEtf().multiply(capital, MC))).toList()
        );
    }

    private Position toPosition(PaperPosition p, PaperAccount account, ExitRule rule, Map<String, String> names,
                                BigDecimal equity) {
        Optional<StockPrice> price = priceRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(
                p.getSymbol(), account.getLastProcessed());
        Optional<StockIndicator> indicator = indicatorRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(
                p.getSymbol(), account.getLastProcessed());
        BigDecimal close = price.map(StockPrice::getClosePrice).orElse(p.getEntryPrice());
        BigDecimal marketValue = p.getShares().multiply(close, MC);
        // 與 Python 回測相同：賣出成本以比例計算（不捨入、無最低手續費）
        BigDecimal sellCost = TradingCosts.FEE_RATE.add(TradingCosts.taxRate(p.getSymbol()));
        BigDecimal proceeds = marketValue.multiply(BigDecimal.ONE.subtract(sellCost), MC).add(p.getDividends());

        BigDecimal exitLine = null;
        if (rule == ExitRule.TRAILING_ATR || rule == ExitRule.TRAILING_NOCAP) {
            exitLine = indicator.map(StockIndicator::getAtr14)
                    .filter(atr -> p.getHighestClose() != null)
                    .map(atr -> p.getHighestClose().subtract(ReferenceLevelService.ATR_MULTIPLE.multiply(atr)))
                    .orElse(null);
        } else if (rule == ExitRule.MA60_EXIT) {
            exitLine = indicator.map(StockIndicator::getMa60).orElse(null);
        }
        return new Position(
                p.getSymbol(),
                names.getOrDefault(p.getSymbol(), p.getSymbol()),
                p.getSignalDate(),
                p.getEntryDate(),
                p.getEntryPrice(),
                p.getShares(),
                p.getCost(),
                close,
                price.map(StockPrice::getTradeDate).orElse(null),
                marketValue.setScale(2, RoundingMode.HALF_UP),
                ratio(marketValue, equity),
                p.getDividends(),
                ratio(proceeds, p.getCost()).subtract(BigDecimal.ONE),
                p.getHighestClose(),
                exitLine == null ? null : exitLine.setScale(4, RoundingMode.HALF_UP),
                p.getHeldDays()
        );
    }

    private static Plan toPlan(TradePlan plan, Map<String, String> names) {
        return new Plan(plan.getPlanDate(), plan.getSymbol(), names.getOrDefault(plan.getSymbol(), plan.getSymbol()),
                plan.getAction(), plan.getReason(), plan.getPriority(), plan.getClosePrice(), plan.getSkipAbove(),
                plan.getTrailingLine(), plan.getAtrStop(), plan.getStatus(), plan.getExecutedDate());
    }

    private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return a.divide(b, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxDrawdown(List<PaperEquity> curve) {
        BigDecimal peak = null;
        BigDecimal worst = BigDecimal.ZERO;
        for (PaperEquity point : curve) {
            peak = peak == null || point.getEquity().compareTo(peak) > 0 ? point.getEquity() : peak;
            BigDecimal drawdown = ratio(point.getEquity(), peak).subtract(BigDecimal.ONE);
            worst = drawdown.compareTo(worst) < 0 ? drawdown : worst;
        }
        return worst;
    }
}
