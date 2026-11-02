package com.ugi.stock.service;

import com.ugi.stock.dto.PortfolioResponse;
import com.ugi.stock.dto.PortfolioResponse.Benchmark;
import com.ugi.stock.dto.PortfolioResponse.Result;
import com.ugi.stock.entity.BacktestStat;
import com.ugi.stock.entity.PortfolioStat;
import com.ugi.stock.repository.BacktestStatRepository;
import com.ugi.stock.repository.PortfolioStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioService {

    private final PortfolioStatRepository portfolioStatRepository;
    private final BacktestStatRepository backtestStatRepository;
    private final BacktestService backtestService;

    /**
     * 某範圍（ALL 或組別）的投資組合回測，附上同一範圍個別交易回測的標準 1 判定。
     * Python 端若有 Java 尚未定義的策略或出場規則，先略過。
     */
    public PortfolioResponse getStats(String scope) {
        BacktestService.ScopeInfo info = backtestService.describeScope(scope, false);

        List<PortfolioStat> stats = portfolioStatRepository.findByScope(scope).stream()
                .filter(stat -> Strategy.fromCode(stat.getStrategy()).isPresent())
                .filter(stat -> ExitRule.fromCode(stat.getExitRule()).isPresent())
                .sorted(Comparator
                        .comparing((PortfolioStat stat) -> Strategy.fromCode(stat.getStrategy()).orElseThrow().ordinal())
                        .thenComparing(stat -> ExitRule.fromCode(stat.getExitRule()).orElseThrow().ordinal()))
                .toList();

        // 標準 1：個別交易回測中有優勢的（策略, 出場規則）
        Set<String> signalEdges = backtestStatRepository.findByScope(scope).stream()
                .filter(stat -> Boolean.TRUE.equals(stat.getHasEdge()))
                .map(stat -> key(stat.getStrategy(), stat.getExitRule()))
                .collect(Collectors.toSet());

        // 所有統計共用同一個期間、切分日與基準（同一次計算產生）
        PortfolioStat first = stats.isEmpty() ? null : stats.get(0);
        return new PortfolioResponse(
                scope,
                info.name(),
                info.stocks(),
                first == null ? null : first.getSlots(),
                first == null ? null : first.getSeeds(),
                first == null ? null : first.getPeriodStart(),
                first == null ? null : first.getPeriodEnd(),
                first == null ? null : first.getSplitDate(),
                stats.size(),
                first == null ? null : new Benchmark(first.getBhCagr(), first.getBhMaxDrawdown(), first.getBhCalmar(),
                        first.getBhSharpe(), first.getBhCalmarFirst(), first.getBhCalmarSecond()),
                first == null ? null : new Benchmark(first.getEtfCagr(), first.getEtfMaxDrawdown(), first.getEtfCalmar(),
                        null, null, null),
                Arrays.stream(ExitRule.values()).map(BacktestService::toResponse).toList(),
                stats.stream().map(stat -> toResult(stat, signalEdges.contains(key(stat.getStrategy(), stat.getExitRule()))))
                        .toList()
        );
    }

    private static String key(String strategy, String exitRule) {
        return strategy + "/" + exitRule;
    }

    private static Result toResult(PortfolioStat stat, boolean signalEdge) {
        boolean beats = Boolean.TRUE.equals(stat.getBeatsBh());
        return new Result(
                StrategyService.toResponse(Strategy.fromCode(stat.getStrategy()).orElseThrow()),
                stat.getExitRule(),
                stat.getTrades(),
                stat.getSkipped(),
                stat.getAvgInvested(),
                stat.getCagr(),
                stat.getCagrMin(),
                stat.getCagrMax(),
                stat.getMaxDrawdown(),
                stat.getCalmar(),
                stat.getCalmarFirst(),
                stat.getCalmarSecond(),
                stat.getSharpe(),
                beats,
                signalEdge,
                beats && signalEdge
        );
    }
}
