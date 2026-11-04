package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingsResponse;
import com.ugi.stock.dto.HoldingsResponse.Position;
import com.ugi.stock.dto.NotificationDigest;
import com.ugi.stock.dto.NotificationDigest.EntryCandidate;
import com.ugi.stock.dto.NotificationDigest.ExDividend;
import com.ugi.stock.dto.NotificationDigest.ExitAlert;
import com.ugi.stock.dto.NotificationDigest.Market;
import com.ugi.stock.entity.HoldingRiskLevel;
import com.ugi.stock.entity.NotificationSetting;
import com.ugi.stock.entity.StockIndicator;
import com.ugi.stock.entity.StockPrice;
import com.ugi.stock.repository.HoldingRiskLevelRepository;
import com.ugi.stock.repository.NotificationSettingRepository;
import com.ugi.stock.repository.StockIndicatorRepository;
import com.ugi.stock.repository.StockPriceRepository;
import com.ugi.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 每日通知的內容：沿用庫存分析（HoldingService）的停利線與除權息資料、交易計畫（PaperService）的買進候選，
 * 數字與網頁上的我的庫存、交易計畫一致。
 * <ul>
 *   <li>跌破停利線：收盤價低於移動停利線（買進以來最高收盤 − 3 × ATR）</li>
 *   <li>接近停利線：收盤價距離停利線在 N 個 ATR 以內，N 依個股風險設定（{@link RiskLevel}），
 *       大盤轉弱（0050 收盤低於季線）時再加 0.5；用 ATR 而不是固定 %，波動大的股票提醒範圍自然較寬</li>
 *   <li>即將除權息：設定的天數以內</li>
 *   <li>買進候選：交易計畫下一個交易日的買進計畫，只寫「符合條件」，不是建議</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final String MARKET_SYMBOL = "0050.TW";

    private final HoldingService holdingService;
    private final PaperService paperService;
    private final NotificationSettingRepository settingRepository;
    private final HoldingRiskLevelRepository riskLevelRepository;
    private final StockRepository stockRepository;
    private final StockPriceRepository priceRepository;
    private final StockIndicatorRepository indicatorRepository;

    public NotificationSetting settings(long userId) {
        return settingRepository.findById(userId).orElseGet(() -> NotificationSetting.defaults(userId));
    }

    /** 這位使用者各股票的風險設定；沒有設定的股票為標準 */
    public Map<String, RiskLevel> riskLevels(long userId) {
        return riskLevelRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(HoldingRiskLevel::getSymbol,
                        r -> RiskLevel.fromCode(r.getRiskLevel()).orElse(RiskLevel.STANDARD)));
    }

    /** 交易計畫下一個交易日的買進候選（所有使用者相同，每次通知只需計算一次） */
    @Transactional(readOnly = true)
    public List<EntryCandidate> entryCandidates() {
        return paperService.getAccounts().stream()
                .flatMap(account -> account.nextPlans().stream()
                        .filter(plan -> "buy".equals(plan.action()))
                        .map(plan -> new EntryCandidate(account.strategy().name(), account.exitRule().name(), plan.symbol(),
                                plan.name(), plan.planDate(), plan.closePrice(), plan.skipAbove())))
                .toList();
    }

    @Transactional(readOnly = true)
    public NotificationDigest buildDigest(long userId) {
        return buildDigest(userId, entryCandidates());
    }

    @Transactional(readOnly = true)
    public NotificationDigest buildDigest(long userId, List<EntryCandidate> entries) {
        NotificationSetting setting = settings(userId);
        HoldingsResponse holdings = holdingService.getHoldings(userId);
        LocalDate asOf = holdings.asOf();
        Market market = market(asOf);
        BigDecimal extra = market != null && market.weak() ? RiskLevel.WEAK_MARKET_EXTRA : BigDecimal.ZERO;
        Map<String, RiskLevel> levels = riskLevels(userId);

        List<ExitAlert> below = new ArrayList<>();
        List<ExitAlert> near = new ArrayList<>();
        List<ExDividend> exDividends = new ArrayList<>();
        for (Position p : holdings.positions()) {
            if (p.exitLine() != null && p.price() != null) {
                RiskLevel level = levels.getOrDefault(p.symbol(), RiskLevel.STANDARD);
                BigDecimal threshold = level.atrMultiple().add(extra);
                BigDecimal gap = p.price().subtract(p.exitLine());
                BigDecimal atrDistance = p.atr14() == null || p.atr14().signum() <= 0 ? null
                        : gap.divide(p.atr14(), 4, RoundingMode.HALF_UP);
                ExitAlert alert = new ExitAlert(p.symbol(), p.name(), p.price(), p.exitLine(), p.atr14(),
                        p.distanceToExit(), atrDistance, level.code(), level.label(), threshold);
                if (p.belowExitLine()) {
                    below.add(alert);
                } else if (atrDistance != null && atrDistance.compareTo(threshold) <= 0) {
                    near.add(alert);
                }
            }
            if (asOf != null && p.nextExDate() != null) {
                long days = ChronoUnit.DAYS.between(asOf, p.nextExDate());
                if (days >= 1 && days <= setting.getExDividendDays()) {
                    exDividends.add(new ExDividend(p.symbol(), p.name(), p.nextExDate(), p.nextExType(), p.nextCashDividend(), days));
                }
            }
        }
        // 跌破最多的、最接近停利線的、最早除權息的排在前面
        below.sort(Comparator.comparing(ExitAlert::distance, Comparator.nullsLast(Comparator.naturalOrder())));
        near.sort(Comparator.comparing(ExitAlert::atrDistance));
        exDividends.sort(Comparator.comparing(ExDividend::exDate).thenComparing(ExDividend::symbol));
        return new NotificationDigest(asOf, market, below, near, exDividends, setting.isEntryAlerts() ? entries : List.of());
    }

    /** 最新交易日的大盤狀態（設定頁顯示目前的提醒範圍） */
    @Transactional(readOnly = true)
    public Market currentMarket() {
        return market(priceRepository.findRecentTradeDates(PageRequest.of(0, 1)).stream().findFirst().orElse(null));
    }

    /** 大盤狀態：0050 在依據交易日的收盤與季線 */
    Market market(LocalDate asOf) {
        if (asOf == null) {
            return null;
        }
        Optional<StockPrice> price = priceRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(MARKET_SYMBOL, asOf);
        Optional<BigDecimal> ma60 = indicatorRepository.findFirstBySymbolAndTradeDateLessThanEqualOrderByTradeDateDesc(MARKET_SYMBOL, asOf)
                .map(StockIndicator::getMa60);
        if (price.isEmpty() || ma60.isEmpty()) {
            return null;
        }
        BigDecimal close = price.get().getClosePrice();
        String name = stockRepository.findById(MARKET_SYMBOL).map(s -> s.getName()).orElse(MARKET_SYMBOL);
        return new Market(MARKET_SYMBOL, name, close, ma60.get(), close.compareTo(ma60.get()) < 0);
    }
}
