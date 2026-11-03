package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.HoldingsResponse;
import com.ugi.stock.dto.HoldingsResponse.Lot;
import com.ugi.stock.dto.HoldingsResponse.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 庫存的新增 / 修改 / 刪除與分析。分析數字以 SQL 直接從資料表重算（與服務的 JPA 計算分開），逐項比對。
 * 測試在交易中執行並於結束時還原（rollback），不會留下資料。
 */
@SpringBootTest
@Transactional
class HoldingServiceTests {

    @Autowired
    private HoldingService service;
    @Autowired
    private AppUserService users;
    @Autowired
    private JdbcTemplate jdbc;

    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        alice = users.recordLogin("google", "test-alice", "alice.test@example.com", "Alice");
        bob = users.recordLogin("google", "test-bob", "bob.test@example.com", "Bob");
    }

    private static HoldingRequest standard(String symbol, LocalDate date, String price, int shares) {
        return new HoldingRequest(symbol, date, new BigDecimal(price), shares, "standard", null, null, null);
    }

    private double q(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? 0 : value.doubleValue();
    }

    // ---- 手續費 ----

    @Test
    void feeModes() {
        // 2,300,000 × 0.1425% = 3,277.5 → 捨去 3,277
        assertThat(service.create(alice, standard("2330.TW", null, "1000", 2300)).fee()).isEqualByComparingTo("3277");
        // 1,000,000 × 0.1425% × 0.28 = 399
        assertThat(service.create(alice, new HoldingRequest("2330.TW", null, new BigDecimal("1000"), 1000, "discount",
                new BigDecimal("0.28"), null, null)).fee()).isEqualByComparingTo("399");
        // 零股 500 元 × 0.1425% = 0.71 → 0，低於最低手續費 → 20；最低手續費設 1 → 1
        assertThat(service.create(alice, standard("2330.TW", null, "50", 10)).fee()).isEqualByComparingTo("20");
        assertThat(service.create(alice, new HoldingRequest("2330.TW", null, new BigDecimal("50"), 10, "standard",
                null, new BigDecimal("1"), null)).fee()).isEqualByComparingTo("1");
        // 實際金額照填
        Lot actual = service.create(alice, new HoldingRequest("2330.tw", null, new BigDecimal("1000"), 100, "actual",
                null, null, new BigDecimal("50")));
        assertThat(actual.fee()).isEqualByComparingTo("50");
        assertThat(actual.symbol()).isEqualTo("2330.TW"); // 代號轉大寫
        assertThat(actual.cost()).isEqualByComparingTo("100050");
    }

    @Test
    void invalidInputRejected() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Taipei")).plusDays(1);
        LocalDate listed = jdbc.queryForObject("select listed_date from stocks where symbol = '6669.TW'", LocalDate.class);
        HoldingRequest[] bad = {
                null,
                standard("", null, "10", 1),
                standard("9999.TW", null, "10", 1),
                standard("2330.TW", null, "0", 1),
                standard("2330.TW", null, "10.12345", 1),
                standard("2330.TW", null, "10", 0),
                standard("2330.TW", tomorrow, "10", 1),
                standard("6669.TW", listed.minusDays(1), "10", 1),
                new HoldingRequest("2330.TW", null, BigDecimal.TEN, 1, "free", null, null, null),
                new HoldingRequest("2330.TW", null, BigDecimal.TEN, 1, "actual", null, null, null),
                new HoldingRequest("2330.TW", null, BigDecimal.TEN, 1, "actual", null, null, new BigDecimal("-1")),
                new HoldingRequest("2330.TW", null, BigDecimal.TEN, 1, "discount", null, null, null),
                new HoldingRequest("2330.TW", null, BigDecimal.TEN, 1, "discount", new BigDecimal("1.2"), null, null),
                new HoldingRequest("2330.TW", null, BigDecimal.TEN, 1, "standard", null, new BigDecimal("-5"), null),
        };
        for (HoldingRequest request : bad) {
            assertThatThrownBy(() -> service.create(alice, request))
                    .as(String.valueOf(request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(service.getHoldings(alice).count()).isZero();
        // 上市日當天可以
        assertThat(service.create(alice, standard("6669.TW", listed, "10", 1)).buyDate()).isEqualTo(listed);
    }

    // ---- 只能存取自己的資料 ----

    @Test
    void usersCannotSeeOrChangeOthersHoldings() {
        Lot lot = service.create(alice, standard("2330.TW", null, "1000", 1000));

        assertThat(service.getHoldings(bob).positions()).isEmpty();
        assertThatThrownBy(() -> service.update(bob, lot.id(), standard("2330.TW", null, "1", 1)))
                .isInstanceOf(ResponseStatusException.class).extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
        assertThatThrownBy(() -> service.delete(bob, lot.id()))
                .isInstanceOf(ResponseStatusException.class).extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);

        // 本人可以修改、刪除
        Lot updated = service.update(alice, lot.id(), standard("2330.TW", null, "1100", 1000));
        assertThat(updated.price()).isEqualByComparingTo("1100");
        assertThat(service.getHoldings(alice).positions().get(0).lots()).hasSize(1);
        service.delete(alice, lot.id());
        assertThat(service.getHoldings(alice).positions()).isEmpty();
        assertThatThrownBy(() -> service.delete(alice, lot.id())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void limitPerUser() {
        for (int i = 0; i < HoldingService.MAX_HOLDINGS; i++) {
            service.create(alice, standard("2330.TW", null, "100", 1));
        }
        assertThatThrownBy(() -> service.create(alice, standard("2330.TW", null, "100", 1)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("最多");
        service.create(bob, standard("2330.TW", null, "100", 1)); // 別人不受影響
    }

    // ---- 分析：與 SQL 重算比對 ----

    @Test
    void analysisMatchesIndependentSql() {
        LocalDate asOf = jdbc.queryForObject("select max(trade_date) from stock_prices", LocalDate.class);
        // A：2884 玉山金 2024-01-02 買進，之後有配股（股數變動）與現金股利
        LocalDate dateA = LocalDate.of(2024, 1, 2);
        Lot a = service.create(alice, standard("2884.TW", dateA, "25.5", 1000));
        // B：0050 2026-03-02 買進，折數 0.6、最低 1 元
        LocalDate dateB = LocalDate.of(2026, 3, 2);
        Lot b = service.create(alice, new HoldingRequest("0050.TW", dateB, new BigDecimal("70"), 500, "discount",
                new BigDecimal("0.6"), new BigDecimal("1"), null));
        // C：2884 再買一筆，沒有買進日期（視為目前基準，不計股利）
        Lot c = service.create(alice, new HoldingRequest("2884.TW", null, new BigDecimal("30"), 2000, "actual", null, null, new BigDecimal("60")));

        assertThat(b.fee()).isEqualByComparingTo("29"); // 35,000 × 0.1425% × 0.6 = 29.925 → 29

        HoldingsResponse response = service.getHoldings(alice);
        assertThat(response.asOf()).isEqualTo(asOf);
        assertThat(response.count()).isEqualTo(3);

        // A 的換算：買進日之後的股數變動相乘、現金股利加總
        double ratioA = q("select coalesce(exp(sum(ln(split_ratio))), 1) from stock_actions where symbol = '2884.TW' and ex_date > ? and ex_date <= ? and split_ratio is not null", dateA, asOf);
        double divA = q("select coalesce(sum(cash_dividend), 0) from stock_actions where symbol = '2884.TW' and ex_date > ? and ex_date <= ?", dateA, asOf);
        Position p2884 = response.positions().stream().filter(p -> p.symbol().equals("2884.TW")).findFirst().orElseThrow();
        Lot lotA = p2884.lots().stream().filter(l -> l.id() == a.id()).findFirst().orElseThrow();
        assertThat(ratioA).isGreaterThan(1.0); // 期間內確實有配股
        assertThat(lotA.splitRatio().doubleValue()).isCloseTo(ratioA, within(1e-9));
        assertThat(lotA.currentShares().doubleValue()).isCloseTo(1000 * ratioA, within(1e-4));
        assertThat(lotA.dividends().doubleValue()).isCloseTo(divA * 1000 * ratioA, within(0.01));
        Lot lotC = p2884.lots().stream().filter(l -> l.id() == c.id()).findFirst().orElseThrow();
        assertThat(lotC.splitRatio()).isEqualByComparingTo("1");
        assertThat(lotC.dividends()).isNull();
        assertThat(p2884.dividendsComplete()).isFalse();

        // 2884 整檔
        double close2884 = q("select close_price from stock_prices where symbol = '2884.TW' and trade_date = ?", asOf);
        double shares2884 = 1000 * ratioA + 2000;
        double cost2884 = 25.5 * 1000 + 36 + 30 * 2000 + 60; // A 的手續費 25,500 × 0.1425% = 36.3 → 36
        double mv2884 = shares2884 * close2884;
        // 賣出成本依最近一筆（C：實際金額 → 公定費率、最低 20）
        double sell2884 = Math.max(Math.floor(mv2884 * 0.001425), 20) + Math.floor(mv2884 * 0.003);
        assertThat(p2884.shares().doubleValue()).isCloseTo(shares2884, within(1e-3));
        assertThat(p2884.cost().doubleValue()).isCloseTo(cost2884, within(0.005));
        assertThat(p2884.price().doubleValue()).isCloseTo(close2884, within(1e-9));
        assertThat(p2884.marketValue().doubleValue()).isCloseTo(mv2884, within(0.01));
        assertThat(p2884.sellCost().doubleValue()).isCloseTo(sell2884, within(1.0001)); // 捨去到元，可能差 1 元的浮點邊界
        assertThat(p2884.unrealizedPnl().doubleValue()).isCloseTo(mv2884 - sell2884 - cost2884, within(1.02));
        assertThat(p2884.avgPrice().doubleValue()).isCloseTo((25.5 * 1000 + 30 * 2000) / shares2884, within(1e-4));
        assertThat(p2884.costPerShare().doubleValue()).isCloseTo(cost2884 / shares2884, within(1e-4));

        // 移動停利線：最早買進日以來的最高收盤 − 3 × 最新 ATR
        double high = q("select max(close_price) from stock_prices where symbol = '2884.TW' and trade_date between ? and ?", dateA, asOf);
        double atr = q("select atr14 from stock_indicators where symbol = '2884.TW' and trade_date = ?", asOf);
        double ma60 = q("select ma60 from stock_indicators where symbol = '2884.TW' and trade_date = ?", asOf);
        assertThat(p2884.trailingFrom()).isEqualTo(dateA);
        assertThat(p2884.historyComplete()).isTrue();
        assertThat(p2884.highestClose().doubleValue()).isCloseTo(high, within(1e-9));
        assertThat(p2884.exitLine().doubleValue()).isCloseTo(high - 3 * atr, within(1e-4));
        assertThat(p2884.distanceToExit().doubleValue()).isCloseTo((close2884 - (high - 3 * atr)) / close2884, within(1e-6));
        assertThat(p2884.belowExitLine()).isEqualTo(close2884 < high - 3 * atr);
        assertThat(p2884.belowMa60()).isEqualTo(close2884 < ma60);

        // 訊號：最新交易日的 strategy_signals
        Integer signals = jdbc.queryForObject("select count(*) from strategy_signals where symbol = '2884.TW' and trade_date = ?", Integer.class, asOf);
        assertThat(p2884.signals()).hasSize(signals);

        // 0050（B）
        Position p0050 = response.positions().stream().filter(p -> p.symbol().equals("0050.TW")).findFirst().orElseThrow();
        double close0050 = q("select close_price from stock_prices where symbol = '0050.TW' and trade_date = ?", asOf);
        double mv0050 = 500 * close0050;
        double sell0050 = Math.max(Math.floor(mv0050 * 0.001425 * 0.6), 1) + Math.floor(mv0050 * 0.001); // ETF 稅 0.1%
        double div0050 = q("select coalesce(sum(cash_dividend), 0) from stock_actions where symbol = '0050.TW' and ex_date > ? and ex_date <= ?", dateB, asOf) * 500;
        assertThat(p0050.sellCost().doubleValue()).isCloseTo(sell0050, within(1.0001));
        assertThat(p0050.dividends().doubleValue()).isCloseTo(div0050, within(0.01));
        assertThat(p0050.dividendsComplete()).isTrue();

        // 權重與合計
        double total = mv2884 + mv0050;
        assertThat(p2884.weight().doubleValue()).isCloseTo(mv2884 / total, within(1e-5));
        assertThat(response.summary().marketValue().doubleValue()).isCloseTo(total, within(0.02));
        assertThat(response.summary().cost().doubleValue()).isCloseTo(cost2884 + 35029, within(0.01));
        assertThat(response.summary().dividends().doubleValue()).isCloseTo(divA * 1000 * ratioA + div0050, within(0.02));
        assertThat(response.positions().get(0).marketValue()).isGreaterThanOrEqualTo(response.positions().get(1).marketValue());

        // 同期比較：A、B 納入，C（沒有日期）排除
        HoldingsResponse.Benchmark bench = response.benchmark();
        assertThat(bench.lots()).isEqualTo(2);
        assertThat(bench.excludedLots()).isEqualTo(1);
        double etfValue = 0;
        for (Object[] lot : new Object[][]{{dateA, 25.5 * 1000 + 36}, {dateB, 35029.0}}) {
            LocalDate entryDate = jdbc.queryForObject("select min(trade_date) from stock_prices where symbol = '0050.TW' and trade_date >= ?", LocalDate.class, lot[0]);
            double entryClose = q("select close_price from stock_prices where symbol = '0050.TW' and trade_date = ?", entryDate);
            double divs = q("select coalesce(sum(cash_dividend), 0) from stock_actions where symbol = '0050.TW' and ex_date > ? and ex_date <= ?", entryDate, asOf);
            etfValue += (double) lot[1] / entryClose * (close0050 + divs);
        }
        double share = 1000 * ratioA / shares2884;
        double holdingsValue = (mv2884 - sell2884) * share + divA * 1000 * ratioA + (mv0050 - sell0050) + div0050;
        double invested = 25.5 * 1000 + 36 + 35029;
        assertThat(bench.invested().doubleValue()).isCloseTo(invested, within(0.01));
        assertThat(bench.etfValue().doubleValue()).isCloseTo(etfValue, within(0.05));
        assertThat(bench.holdingsValue().doubleValue()).isCloseTo(holdingsValue, within(1.5));
        assertThat(bench.etfReturn().doubleValue()).isCloseTo(etfValue / invested - 1, within(1e-5));
    }

    @Test
    void lotBoughtBeforeSplitIsConverted() {
        // 0050 於 2025-06-18 1 拆 4（Yahoo 沒有提供這筆事件，由 Python 的 corrections.py 補上）。
        // 分割前以實際報價 188.65 買 1 張 → 現在是 4,000 股；資料庫的價格已換算（2025-06-10 收盤 47.1625 = 188.65 ÷ 4）
        LocalDate buyDate = LocalDate.of(2025, 6, 10);
        Lot created = service.create(alice, standard("0050.TW", buyDate, "188.65", 1000));
        Lot lot = service.getHoldings(alice).positions().get(0).lots().get(0);
        assertThat(lot.id()).isEqualTo(created.id());
        assertThat(lot.splitRatio()).as("stock_actions 缺少 0050 的分割紀錄（請執行 Python 每日更新）").isEqualByComparingTo("4");
        assertThat(lot.currentShares()).isEqualByComparingTo("4000");
        double closeOnBuyDate = q("select close_price from stock_prices where symbol = '0050.TW' and trade_date = ?", buyDate);
        assertThat(lot.currentShares().doubleValue() * closeOnBuyDate).isCloseTo(188.65 * 1000, within(0.01));
        double divs = q("select coalesce(sum(cash_dividend), 0) from stock_actions where symbol = '0050.TW' and ex_date > ? and cash_dividend is not null", buyDate);
        assertThat(lot.dividends().doubleValue()).isCloseTo(divs * 4000, within(0.01));
    }

    @Test
    void undatedPositionHasNoTrailingLine() {
        service.create(alice, standard("2330.TW", null, "500", 1000));
        Position p = service.getHoldings(alice).positions().get(0);
        assertThat(p.trailingFrom()).isNull();
        assertThat(p.exitLine()).isNull();
        assertThat(p.historyComplete()).isTrue(); // 沒有買進日期：不是「早於資料起點」
        assertThat(p.dividendsComplete()).isFalse();
    }

    @Test
    void emptyHoldings() {
        HoldingsResponse response = service.getHoldings(alice);
        assertThat(response.positions()).isEmpty();
        assertThat(response.summary().cost()).isEqualByComparingTo("0");
        assertThat(response.summary().unrealizedReturn()).isNull();
        assertThat(response.benchmark().lots()).isZero();
        assertThat(response.feeRules().feeRate()).isEqualByComparingTo("0.001425");
        assertThat(response.maxCount()).isEqualTo(200);
    }

    @SuppressWarnings("unused")
    private static BigDecimal floor(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.FLOOR);
    }
}
