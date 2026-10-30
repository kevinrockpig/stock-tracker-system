package com.ugi.stock.service;

import com.ugi.stock.dto.BacktestResponse;
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
     * 某範圍的回測統計。Python 端若有 Java 尚未定義的策略或出場規則（例如剛新增、Java 還沒更新），先略過。
     */
    public BacktestResponse getStats(String scope) {
        if (!ALL_SCOPE.equals(scope) && !stockRepository.existsById(scope)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到股票代號：" + scope);
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
        return new BacktestResponse(
                scope,
                first == null ? null : first.getPeriodStart(),
                first == null ? null : first.getPeriodEnd(),
                first == null ? null : first.getSplitDate(),
                stats.size(),
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
        return new ExitRuleResponse(rule.code(), rule.label(), rule.description());
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
                stat.getTradesFirst(),
                stat.getExcessFirst(),
                stat.getTradesSecond(),
                stat.getExcessSecond(),
                Objects.equals(stat.getHasEdge(), Boolean.TRUE)
        );
    }
}
