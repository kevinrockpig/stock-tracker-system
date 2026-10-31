package com.ugi.stock.service;

import com.ugi.stock.dto.BacktestResponse;
import com.ugi.stock.dto.BacktestResponse.HorizonResponse;
import com.ugi.stock.dto.BacktestResponse.Result;
import com.ugi.stock.dto.BacktestTradesResponse;
import com.ugi.stock.dto.ExitRuleResponse;
import com.ugi.stock.dto.StrategyResponse;
import com.ugi.stock.entity.BacktestStat;
import com.ugi.stock.entity.Stock;
import com.ugi.stock.repository.BacktestStatRepository;
import com.ugi.stock.repository.BacktestTradeRepository;
import com.ugi.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BacktestService {

    public static final String ALL_SCOPE = "ALL";

    private final BacktestStatRepository backtestStatRepository;
    private final BacktestTradeRepository backtestTradeRepository;
    private final StockRepository stockRepository;

    /**
     * 某範圍的回測統計。scope：ALL、組別代碼（StockGroup）或股票代號。
     * Python 端若有 Java 尚未定義的策略或出場規則（例如剛新增、Java 還沒更新），先略過。
     */
    public BacktestResponse getStats(String scope) {
        String scopeName;
        int scopeStocks;
        if (ALL_SCOPE.equals(scope)) {
            scopeName = "全部合併";
            scopeStocks = stockRepository.findByGroupCodesIsNotNull(Sort.unsorted()).size();
        } else if (StockGroup.fromCode(scope).isPresent()) {
            StockGroup group = StockGroup.fromCode(scope).orElseThrow();
            scopeName = group.label();
            scopeStocks = (int) stockRepository.findByGroupCodesIsNotNull(Sort.unsorted()).stream()
                    .filter(stock -> stock.groups().contains(group.code()))
                    .count();
        } else {
            Stock stock = stockRepository.findById(scope)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到股票代號或組別：" + scope));
            scopeName = stock.getName();
            scopeStocks = 1;
        }

        List<BacktestStat> stats = backtestStatRepository.findByScope(scope).stream()
                .filter(stat -> Strategy.fromCode(stat.getStrategy()).isPresent())
                .filter(stat -> ExitRule.fromCode(stat.getExitRule()).isPresent())
                .sorted(Comparator
                        .comparing((BacktestStat stat) -> Strategy.fromCode(stat.getStrategy()).orElseThrow().ordinal())
                        .thenComparing(stat -> ExitRule.fromCode(stat.getExitRule()).orElseThrow().ordinal()))
                .toList();

        // 所有統計共用同一個期間與切分日（同一次計算產生）
        BacktestStat first = stats.isEmpty() ? null : stats.get(0);
        List<HorizonResponse> horizons = Arrays.stream(Horizon.values())
                .map(horizon -> new HorizonResponse(horizon.code(), horizon.label(), (int) stats.stream()
                        .filter(stat -> ExitRule.fromCode(stat.getExitRule()).orElseThrow().horizon() == horizon)
                        .count()))
                .toList();
        return new BacktestResponse(
                scope,
                scopeName,
                scopeStocks,
                first == null ? null : first.getPeriodStart(),
                first == null ? null : first.getPeriodEnd(),
                first == null ? null : first.getSplitDate(),
                horizons,
                Arrays.stream(ExitRule.values()).map(BacktestService::toResponse).toList(),
                stats.stream().map(BacktestService::toResult).toList()
        );
    }

    public BacktestTradesResponse getTrades(String symbol, Strategy strategy, ExitRule exitRule) {
        Stock stock = stockRepository.findById(symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到股票代號：" + symbol));

        List<BacktestTradesResponse.Trade> trades = backtestTradeRepository
                .findBySymbolAndStrategyAndExitRuleOrderByEntryDateAsc(symbol, strategy.code(), exitRule.code())
                .stream()
                .map(trade -> new BacktestTradesResponse.Trade(
                        trade.getSignalDate(),
                        trade.getEntryDate(),
                        trade.getEntryPrice(),
                        trade.getExitDate(),
                        trade.getExitPrice(),
                        trade.getExitReason(),
                        trade.getDividends(),
                        trade.getHoldingDays(),
                        trade.getNetReturn()))
                .toList();

        return new BacktestTradesResponse(stock.getSymbol(), stock.getName(),
                StrategyService.toResponse(strategy), toResponse(exitRule), trades);
    }

    public static ExitRuleResponse toResponse(ExitRule rule) {
        return new ExitRuleResponse(rule.code(), rule.label(), rule.description(), rule.horizon().code());
    }

    private static Result toResult(BacktestStat stat) {
        StrategyResponse strategy = StrategyService.toResponse(Strategy.fromCode(stat.getStrategy()).orElseThrow());
        return new Result(
                strategy,
                stat.getExitRule(),
                stat.getTrades(),
                stat.getOpenTrades(),
                stat.getWins(),
                stat.getWinRate(),
                stat.getWinRateLow(),
                stat.getWinRateHigh(),
                stat.getAvgReturn(),
                stat.getMedianReturn(),
                stat.getAvgWin(),
                stat.getAvgLoss(),
                stat.getPayoffRatio(),
                stat.getProfitFactor(),
                stat.getMaxLoss(),
                stat.getMaxConsecutiveLosses(),
                stat.getAvgHoldingDays(),
                stat.getBaselineAvgReturn(),
                stat.getBaselineWinRate(),
                stat.getBuyHoldReturn(),
                stat.getExcessReturn(),
                stat.getExcessLow(),
                stat.getExcessHigh(),
                stat.getExcessClusters(),
                stat.getTradesFirst(),
                stat.getExcessFirst(),
                stat.getTradesSecond(),
                stat.getExcessSecond(),
                Objects.equals(stat.getHasEdge(), Boolean.TRUE),
                stat.getTotalReturn(),
                stat.getCagr(),
                stat.getMaxDrawdown(),
                stat.getExposure(),
                stat.getBhCagr(),
                stat.getBhMaxDrawdown()
        );
    }
}
