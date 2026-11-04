package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.HoldingsResponse.Position;
import com.ugi.stock.dto.NotificationDigest;
import com.ugi.stock.dto.NotificationDigest.ExitAlert;
import com.ugi.stock.entity.HoldingRiskLevel;
import com.ugi.stock.entity.NotificationSetting;
import com.ugi.stock.repository.HoldingRiskLevelRepository;
import com.ugi.stock.repository.NotificationSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每日通知的內容：以觀察名單每檔都持有的使用者計算，逐項以公式與 SQL 獨立驗算分類。
 * 測試在交易中執行並於結束時還原（rollback），不會留下資料。
 */
@SpringBootTest
@Transactional
class NotificationServiceTests {

    @Autowired
    private NotificationService service;
    @Autowired
    private HoldingService holdingService;
    @Autowired
    private AppUserService users;
    @Autowired
    private NotificationSettingRepository settingRepository;
    @Autowired
    private HoldingRiskLevelRepository riskLevelRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private long user;
    private List<String> watchlist;

    @BeforeEach
    void setUp() {
        user = users.recordLogin("google", "test-notify", "notify.test@example.com", "Notify");
        watchlist = jdbc.queryForList("select symbol from stocks where group_codes is not null order by symbol", String.class);
        LocalDate asOf = jdbc.queryForObject("select max(trade_date) from stock_prices", LocalDate.class);
        // 約半年前買進：有移動停利線，且各股票距離停利線遠近不一
        LocalDate buyDate = asOf.minusDays(180);
        for (String symbol : watchlist) {
            holdingService.create(user, new HoldingRequest(symbol, buyDate, BigDecimal.TEN, 1000, "standard", null, null, null));
        }
    }

    private void riskForAll(RiskLevel level) {
        riskLevelRepository.saveAll(watchlist.stream().map(s -> new HoldingRiskLevel(user, s, level.code())).toList());
        riskLevelRepository.flush();
    }

    private static Set<String> symbols(List<ExitAlert> alerts) {
        return alerts.stream().map(ExitAlert::symbol).collect(Collectors.toSet());
    }

    // 以庫存分析的數字依公式重算：跌破 = 收盤 < 停利線；接近 = （收盤 − 停利線）÷ ATR ≤ 門檻
    private void assertMatchesFormula(NotificationDigest digest, RiskLevel level) {
        BigDecimal threshold = level.atrMultiple().add(digest.market().weak() ? RiskLevel.WEAK_MARKET_EXTRA : BigDecimal.ZERO);
        List<Position> positions = holdingService.getHoldings(user).positions();
        Set<String> below = positions.stream()
                .filter(p -> p.exitLine() != null && p.price().compareTo(p.exitLine()) < 0)
                .map(Position::symbol).collect(Collectors.toSet());
        Set<String> near = positions.stream()
                .filter(p -> p.exitLine() != null && p.atr14() != null && p.price().compareTo(p.exitLine()) >= 0)
                .filter(p -> p.price().subtract(p.exitLine()).divide(p.atr14(), 4, RoundingMode.HALF_UP).compareTo(threshold) <= 0)
                .map(Position::symbol).collect(Collectors.toSet());
        assertThat(symbols(digest.belowExit())).as("跌破停利線").isEqualTo(below);
        assertThat(symbols(digest.nearExit())).as("接近停利線").isEqualTo(near);
        assertThat(digest.nearExit()).allSatisfy(a -> {
            assertThat(a.riskLevel()).isEqualTo(level.code());
            assertThat(a.thresholdAtr()).isEqualByComparingTo(threshold);
        });
    }

    @Test
    void marketStatusMatchesSql() {
        NotificationDigest digest = service.buildDigest(user, List.of());
        var row = jdbc.queryForMap("""
                select p.close_price, i.ma60 from stock_prices p join stock_indicators i using (symbol, trade_date)
                where p.symbol = '0050.TW' and p.trade_date = (select max(trade_date) from stock_prices where symbol = '0050.TW')""");
        BigDecimal close = (BigDecimal) row.get("close_price");
        BigDecimal ma60 = (BigDecimal) row.get("ma60");
        assertThat(digest.market().close()).isEqualByComparingTo(close);
        assertThat(digest.market().ma60()).isEqualByComparingTo(ma60);
        assertThat(digest.market().weak()).isEqualTo(close.compareTo(ma60) < 0);
    }

    @Test
    void exitAlertsFollowRiskLevel() {
        // 沒有設定時為標準
        NotificationDigest standard = service.buildDigest(user, List.of());
        assertMatchesFormula(standard, RiskLevel.STANDARD);

        riskForAll(RiskLevel.AGGRESSIVE);
        NotificationDigest aggressive = service.buildDigest(user, List.of());
        assertMatchesFormula(aggressive, RiskLevel.AGGRESSIVE);

        riskForAll(RiskLevel.CONSERVATIVE);
        NotificationDigest conservative = service.buildDigest(user, List.of());
        assertMatchesFormula(conservative, RiskLevel.CONSERVATIVE);

        // 跌破與風險設定無關；保守的提醒範圍最寬
        assertThat(symbols(aggressive.belowExit())).isEqualTo(symbols(conservative.belowExit()));
        assertThat(symbols(conservative.nearExit())).containsAll(symbols(standard.nearExit()));
        assertThat(symbols(standard.nearExit())).containsAll(symbols(aggressive.nearExit()));
        // 這份資料應至少有一檔落在提醒範圍內，否則上面的比較沒有意義
        assertThat(conservative.nearExit().size() + conservative.belowExit().size()).isPositive();
    }

    @Test
    void exDividendWithinConfiguredDays() {
        NotificationSetting setting = NotificationSetting.defaults(user);
        setting.setExDividendDays(30);
        settingRepository.saveAndFlush(setting);
        NotificationDigest digest = service.buildDigest(user, List.of());
        LocalDate asOf = digest.asOf();
        // 以參考價位（每檔最新一筆）的下一個除權息日獨立查詢
        List<String> expected = jdbc.queryForList("""
                select distinct on (symbol) symbol, next_ex_date from reference_levels
                where symbol in (select symbol from stocks where group_codes is not null)
                order by symbol, basis_date desc""").stream()
                .filter(r -> r.get("next_ex_date") != null)
                .filter(r -> {
                    long days = ChronoUnit.DAYS.between(asOf, ((java.sql.Date) r.get("next_ex_date")).toLocalDate());
                    return days >= 1 && days <= 30;
                })
                .map(r -> (String) r.get("symbol")).sorted().toList();
        assertThat(digest.exDividends().stream().map(e -> e.symbol()).sorted().toList()).isEqualTo(expected);
        assertThat(digest.exDividends()).allSatisfy(e -> assertThat(e.days()).isBetween(1L, 30L));
    }

    @Test
    void entryCandidatesFollowSetting() {
        var entries = List.of(new NotificationDigest.EntryCandidate("策略", "規則", "2330.TW", "台積電", LocalDate.now(), BigDecimal.ONE, null));
        assertThat(service.buildDigest(user, entries).entries()).isEqualTo(entries); // 預設附上
        NotificationSetting setting = NotificationSetting.defaults(user);
        setting.setEntryAlerts(false);
        settingRepository.saveAndFlush(setting);
        assertThat(service.buildDigest(user, entries).entries()).isEmpty();
        // 交易計畫的買進候選只取 buy
        assertThat(service.entryCandidates()).allSatisfy(e -> assertThat(e.symbol()).isNotBlank());
    }

    @Test
    void defaultsWhenNoSettings() {
        NotificationSetting setting = service.settings(user);
        assertThat(setting.isEmailEnabled()).isFalse();
        assertThat(setting.getExDividendDays()).isEqualTo(NotificationSetting.DEFAULT_EX_DIVIDEND_DAYS);
        assertThat(setting.isEntryAlerts()).isTrue();
        assertThat(service.riskLevels(user)).isEmpty();
    }
}
