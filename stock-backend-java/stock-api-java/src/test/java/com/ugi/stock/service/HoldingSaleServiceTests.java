package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingHistoryResponse;
import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.HoldingsResponse;
import com.ugi.stock.dto.HoldingsResponse.Lot;
import com.ugi.stock.dto.HoldingsResponse.Position;
import com.ugi.stock.dto.HoldingsResponse.Sale;
import com.ugi.stock.dto.HoldingsResponse.SaleLot;
import com.ugi.stock.dto.SaleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 賣出紀錄：先進先出分配、已實現損益、持股只算剩下的、同期比較與走勢把賣出拿回的金額當現金保留、各種擋下的情況。
 * 數字以 SQL 直接從資料表與公式重算（與服務的計算分開）比對。測試在交易中執行並於結束時還原。
 */
@SpringBootTest
@Transactional
class HoldingSaleServiceTests {

    @Autowired
    private HoldingSaleService sales;
    @Autowired
    private HoldingService holdings;
    @Autowired
    private HoldingHistoryService history;
    @Autowired
    private AppUserService users;
    @Autowired
    private JdbcTemplate jdbc;

    private long alice;
    private long bob;
    // 2330：A（2025-01-13）1,000 股、U（沒有日期）500 股、B（2025-06-02）1,000 股，依此順序建立
    private Lot a;
    private Lot u;
    private Lot b;

    private static final LocalDate DATE_A = LocalDate.of(2025, 1, 13);
    private static final LocalDate DATE_B = LocalDate.of(2025, 6, 2);
    private static final LocalDate SELL_1 = LocalDate.of(2025, 9, 1);

    @BeforeEach
    void setUp() {
        alice = users.recordLogin("google", "test-sale-alice", "sale.alice@example.com", "Alice");
        bob = users.recordLogin("google", "test-sale-bob", "sale.bob@example.com", "Bob");
        a = holdings.create(alice, buy("2330.TW", DATE_A, "1000", 1000));
        u = holdings.create(alice, buy("2330.TW", null, "900", 500));
        b = holdings.create(alice, new HoldingRequest("2330.TW", DATE_B, new BigDecimal("1050"), 1000, "actual", null, null, new BigDecimal("100")));
    }

    private static HoldingRequest buy(String symbol, LocalDate date, String price, int shares) {
        return new HoldingRequest(symbol, date, new BigDecimal(price), shares, "standard", null, null, null);
    }

    private static SaleRequest sell(String symbol, LocalDate date, String price, int shares) {
        return new SaleRequest(symbol, date, new BigDecimal(price), shares, "standard", null, null, null, "standard", null);
    }

    private double q(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? 0 : value.doubleValue();
    }

    private double close(String symbol, LocalDate day) {
        return q("select close_price from stock_prices where symbol = ? and trade_date = (select max(trade_date) from stock_prices where symbol = ? and trade_date <= ?)",
                symbol, symbol, day);
    }

    private double dividends(String symbol, LocalDate after, LocalDate upTo) {
        return q("select coalesce(sum(cash_dividend), 0) from stock_actions where symbol = ? and ex_date > ? and ex_date <= ?", symbol, after, upTo);
    }

    private static double fee(double amount) {
        return Math.max(Math.floor(amount * 0.001425), 20);
    }

    private static Position position(HoldingsResponse response, String symbol) {
        return response.positions().stream().filter(p -> p.symbol().equals(symbol)).findFirst().orElse(null);
    }

    private static String message(Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value() + " " + e.getReason();
        }
        return "沒有擋下";
    }

    @Test
    void firstInFirstOutLikeTheBroker() {
        // 1,200 股：沒有日期的 U（視為最早）500 股，再從 A 扣 700 股；B 在賣出日之後才不會被扣（這裡 B 早於賣出日，但先進先出輪不到）
        Sale sale = sales.create(alice, sell("2330.TW", SELL_1, "1200", 1200));
        assertThat(sale.lots()).extracting(SaleLot::holdingId).containsExactly(u.id(), a.id());
        assertThat(sale.lots()).extracting(l -> l.shares().intValue()).containsExactly(500, 700);

        double amount = 1200.0 * 1200;
        double fee = fee(amount);
        double tax = Math.floor(amount * 0.003);
        assertThat(sale.fee().doubleValue()).isEqualTo(fee);
        assertThat(sale.tax().doubleValue()).isEqualTo(tax);
        double cost = u.cost().doubleValue() + a.cost().doubleValue() * 0.7;
        double proceeds = amount - fee - tax;
        assertThat(sale.proceeds().doubleValue()).isCloseTo(proceeds, within(0.001));
        assertThat(sale.cost().doubleValue()).isCloseTo(cost, within(0.01));
        assertThat(sale.realizedPnl().doubleValue()).isCloseTo(proceeds - cost, within(0.01));
        // 有一部分來自沒有日期的買進：那部分的股利無法計算，只計 A 的 700 股
        assertThat(sale.dividendsComplete()).isFalse();
        assertThat(sale.dividends().doubleValue()).isCloseTo(dividends("2330.TW", DATE_A, SELL_1) * 700, within(0.01));

        // 持股只算剩下的：A 300 股 + B 1,000 股
        HoldingsResponse response = holdings.getHoldings(alice);
        Position p = position(response, "2330.TW");
        assertThat(p.shares()).isEqualByComparingTo("1300");
        assertThat(p.cost().doubleValue()).isCloseTo(a.cost().doubleValue() * 0.3 + b.cost().doubleValue(), within(0.01));
        assertThat(p.trailingFrom()).isEqualTo(DATE_A); // A 還剩 300 股
        assertThat(p.dividendsComplete()).isTrue();      // U 已全部賣出，剩下的都有日期
        assertThat(p.lots()).extracting(Lot::id).containsExactly(u.id(), a.id(), b.id()); // 先進先出的順序
        assertThat(p.lots()).extracting(l -> l.remainingShares().intValue()).containsExactly(0, 300, 1000);
        assertThat(p.realizedPnl()).isEqualByComparingTo(sale.realizedPnl());
        assertThat(p.overallPnl()).isEqualByComparingTo(p.totalPnl().add(p.realizedPnl()).add(p.realizedDividends()));
        assertThat(response.summary().cost()).isEqualByComparingTo(p.cost());

        HoldingsResponse.Realized realized = response.realized();
        assertThat(realized.count()).isEqualTo(1);
        assertThat(realized.sales()).hasSize(1);
        assertThat(realized.realizedPnl()).isEqualByComparingTo(sale.realizedPnl());
        assertThat(realized.dividendsComplete()).isFalse();
        assertThat(realized.overallPnl()).isEqualByComparingTo(response.summary().totalPnl().add(realized.realizedPnl()).add(realized.dividends()));
        assertThat(realized.closedPositions()).isEmpty();

        // 再賣 300 股：A 剩下的 300 股；持有期間的股利依 SQL 重算；A 賣完後停利線改從 B 起算
        LocalDate sell2 = LocalDate.of(2026, 3, 2);
        Sale second = sales.create(alice, sell("2330.TW", sell2, "1500", 300));
        assertThat(second.lots()).extracting(SaleLot::holdingId).containsExactly(a.id());
        assertThat(second.dividends().doubleValue()).isCloseTo(dividends("2330.TW", DATE_A, sell2) * 300, within(0.01));
        assertThat(second.dividendsComplete()).isTrue();
        assertThat(position(holdings.getHoldings(alice), "2330.TW").trailingFrom()).isEqualTo(DATE_B);
    }

    @Test
    void onlyLotsBoughtBeforeTheSaleCount() {
        // 2025-03-03 只有 U 500 + A 1,000 股（B 在 6 月才買）
        assertThat(message(() -> sales.create(alice, sell("2330.TW", LocalDate.of(2025, 3, 3), "1000", 1600))))
                .isEqualTo("400 賣出 1600 股超過 2025-03-03 當時可賣的 1500 股（只計當天以前買進的，並扣除之前的賣出）");
        assertThat(sales.create(alice, sell("2330.TW", LocalDate.of(2025, 3, 3), "1000", 1500)).lots()).hasSize(2);
        // 之後再賣只剩 B
        assertThat(message(() -> sales.create(alice, sell("2330.TW", SELL_1, "1000", 1001)))).contains("當時可賣的 1000 股");
    }

    @Test
    void earlierSaleReallocatesLaterOnes() {
        Sale later = sales.create(alice, sell("2330.TW", SELL_1, "1200", 1200));
        // 補登一筆更早的賣出：先進先出會先分配給它，之後那筆改從 A、B 扣
        sales.create(alice, sell("2330.TW", LocalDate.of(2025, 8, 1), "1100", 1000));
        Sale reallocated = holdings.getHoldings(alice).realized().sales().stream().filter(s -> s.id() == later.id()).findFirst().orElseThrow();
        assertThat(reallocated.lots()).extracting(SaleLot::holdingId).containsExactly(a.id(), b.id());
        assertThat(reallocated.lots()).extracting(l -> l.shares().intValue()).containsExactly(500, 700);

        // 再補一筆會讓 9/1 那筆不夠賣：擋下並說明是哪一筆
        assertThat(message(() -> sales.create(alice, sell("2330.TW", LocalDate.of(2025, 7, 1), "1100", 400))))
                .isEqualTo("400 這筆賣出會讓 2025-09-01 賣出的 1200 股超過當時持有的股數（1100 股；先進先出會先扣較早的賣出），請改日期或股數");
        assertThat(holdings.getHoldings(alice).realized().count()).isEqualTo(2); // 被擋下的沒有寫入
    }

    @Test
    void editingBuysCannotBreakSales() {
        sales.create(alice, sell("2330.TW", SELL_1, "1200", 2400));
        // 減少 B 的股數、把 B 改到賣出日之後、刪除 A：都會讓賣出超過買進
        assertThat(message(() -> holdings.update(alice, b.id(), new HoldingRequest("2330.TW", DATE_B, new BigDecimal("1050"), 800, "actual", null, null, new BigDecimal("100")))))
                .isEqualTo("400 修改後，2025-09-01 賣出的 2400 股會超過當時持有的股數（2300 股），請先修改或刪除那筆賣出");
        assertThat(message(() -> holdings.update(alice, b.id(), new HoldingRequest("2330.TW", LocalDate.of(2025, 10, 1), new BigDecimal("1050"), 1000, "actual", null, null, new BigDecimal("100")))))
                .startsWith("400 修改後，2025-09-01 賣出的 2400 股");
        assertThat(message(() -> holdings.delete(alice, a.id()))).startsWith("400 刪除這筆後，2025-09-01 賣出的 2400 股");
        // 改價格不影響股數：可以
        holdings.update(alice, b.id(), new HoldingRequest("2330.TW", DATE_B, new BigDecimal("1060"), 1000, "actual", null, null, new BigDecimal("100")));
        assertThat(position(holdings.getHoldings(alice), "2330.TW").shares()).isEqualByComparingTo("100");
    }

    @Test
    void splitAndClosedPosition() {
        // 0050 於 2025-06-18 1 拆 4：分割前買 1 張 = 現在 4,000 股
        Lot etf = holdings.create(alice, buy("0050.TW", LocalDate.of(2025, 6, 10), "188.65", 1000));
        // 分割前賣 500 股（= 現在 2,000 股），分割後賣 2,000 股：全部賣完
        Sale before = sales.create(alice, sell("0050.TW", LocalDate.of(2025, 6, 10), "188.65", 500));
        assertThat(before.lots().get(0).shares()).isEqualByComparingTo("2000");
        assertThat(before.cost()).isEqualByComparingTo(etf.cost().divide(new BigDecimal("2")));
        assertThat(message(() -> sales.create(alice, sell("0050.TW", LocalDate.of(2025, 7, 1), "50", 2001)))).contains("當時可賣的 2000 股");
        Sale after = sales.create(alice, sell("0050.TW", LocalDate.of(2025, 7, 1), "50", 2000));
        double etfTax = Math.floor(50.0 * 2000 * 0.001); // ETF 0.1%
        assertThat(after.tax().doubleValue()).isEqualTo(etfTax);
        assertThat(after.dividends().doubleValue()).isCloseTo(dividends("0050.TW", LocalDate.of(2025, 6, 10), LocalDate.of(2025, 7, 1)) * 2000, within(0.01));

        HoldingsResponse response = holdings.getHoldings(alice);
        assertThat(position(response, "0050.TW")).as("全部賣完：不在持股中").isNull();
        HoldingsResponse.ClosedPosition closed = response.realized().closedPositions().get(0);
        assertThat(closed.symbol()).isEqualTo("0050.TW");
        assertThat(closed.realizedPnl()).isEqualByComparingTo(before.realizedPnl().add(after.realizedPnl()));
        assertThat(closed.lots().get(0).remainingShares()).isEqualByComparingTo("0");
        assertThat(closed.lots().get(0).soldShares()).isEqualByComparingTo("4000");
    }

    @Test
    void benchmarkAndHistoryKeepSaleProceedsAsCash() {
        Sale sale = sales.create(alice, sell("2330.TW", SELL_1, "1200", 1200));
        HoldingsResponse response = holdings.getHoldings(alice);
        HoldingsResponse.Benchmark bench = response.benchmark();
        HoldingHistoryResponse hist = history.getHistory(alice);
        LocalDate asOf = response.asOf();

        // 納入 A、B（U 沒有日期）；投入成本含已賣出的部分
        assertThat(bench.lots()).isEqualTo(2);
        assertThat(bench.invested()).isEqualByComparingTo(a.cost().add(b.cost()));
        // 庫存價值 = 剩下的 A 300 + B 1,000 股的（市值 − 賣出成本）+ 股利 + A 賣出的 700 股拿回的金額
        double c = close("2330.TW", asOf);
        double mv = 1300 * c;
        double net = mv - fee(mv) - Math.floor(mv * 0.003); // 最近一筆 B 為「實際金額」：以公定費率估算
        double divA = dividends("2330.TW", DATE_A, SELL_1) * 700 + dividends("2330.TW", DATE_A, asOf) * 300;
        double divB = dividends("2330.TW", DATE_B, asOf) * 1000;
        double expected = net + divA + divB + sale.proceeds().doubleValue() * 700 / 1200;
        assertThat(bench.holdingsValue().doubleValue()).isCloseTo(expected, within(1.5));

        // 走勢最後一天等於同期比較
        HoldingHistoryResponse.Point last = hist.points().get(hist.points().size() - 1);
        assertThat(last.invested()).isEqualByComparingTo(bench.invested());
        assertThat(last.holdingsValue()).isEqualByComparingTo(bench.holdingsValue());
        assertThat(last.etfValue()).isEqualByComparingTo(bench.etfValue());

        // 賣出前一天與賣出那天：持股 2,500 股 → 1,300 股，賣出的 1,200 股換成現金
        List<LocalDate> dates = hist.points().stream().map(HoldingHistoryResponse.Point::date).toList();
        LocalDate dayBefore = dates.get(dates.indexOf(SELL_1) - 1);
        for (LocalDate day : List.of(dayBefore, SELL_1)) {
            boolean sold = !day.isBefore(SELL_1);
            double shares = sold ? 1300 : 2500;
            double m = shares * close("2330.TW", day);
            double n = m - fee(m) - Math.floor(m * 0.003);
            double value = n * (sold ? 1300.0 : 2000.0) / shares // A、B（U 沒有納入，但與庫存頁相同計入整檔股數）
                    + (sold ? dividends("2330.TW", DATE_A, SELL_1) * 700 + sale.proceeds().doubleValue() * 700 / 1200 : 0)
                    + dividends("2330.TW", DATE_A, day) * (sold ? 300 : 1000) + dividends("2330.TW", DATE_B, day) * 1000;
            HoldingHistoryResponse.Point point = hist.points().get(dates.indexOf(day));
            assertThat(point.holdingsValue().doubleValue()).as(day.toString()).isCloseTo(value, within(1.5));
            assertThat(point.invested()).isEqualByComparingTo(a.cost().add(b.cost()));
        }
    }

    @Test
    void inputChecksAndOwnership() {
        assertThat(message(() -> sales.create(alice, sell("2454.TW", SELL_1, "1000", 100)))).isEqualTo("400 沒有 2454.TW 的買進紀錄");
        assertThat(message(() -> sales.create(alice, sell("2330.TW", null, "1000", 100)))).isEqualTo("400 請填寫賣出日期");
        assertThat(message(() -> sales.create(alice, sell("2330.TW", LocalDate.now(ZoneId.of("Asia/Taipei")).plusDays(1), "1000", 100))))
                .isEqualTo("400 賣出日期不能晚於今天");
        assertThat(message(() -> sales.create(alice, new SaleRequest("2330.TW", SELL_1, new BigDecimal("1000"), 100, "standard", null, null, null, "auto", null))))
                .startsWith("400 證交稅方式必須是");
        assertThat(message(() -> sales.create(alice, new SaleRequest("2330.TW", SELL_1, new BigDecimal("1000"), 100, "standard", null, null, null, "actual", null))))
                .startsWith("400 請填寫實際證交稅");

        // 實際金額的手續費與證交稅照填；折數依公式
        Sale actual = sales.create(alice, new SaleRequest("2330.tw", SELL_1, new BigDecimal("1000"), 100, "actual", null, null,
                new BigDecimal("50"), "actual", new BigDecimal("299")));
        assertThat(actual.symbol()).isEqualTo("2330.TW");
        assertThat(actual.fee()).isEqualByComparingTo("50");
        assertThat(actual.tax()).isEqualByComparingTo("299");
        assertThat(actual.proceeds()).isEqualByComparingTo("99651");
        Sale discount = sales.create(alice, new SaleRequest("2330.TW", SELL_1, new BigDecimal("1000"), 1000, "discount",
                new BigDecimal("0.28"), null, null, "standard", null));
        assertThat(discount.fee()).isEqualByComparingTo("399"); // 1,000,000 × 0.1425% × 0.28 = 399

        // 別人的賣出：看不到、改不了、刪不了
        assertThat(holdings.getHoldings(bob).realized().count()).isZero();
        assertThat(message(() -> sales.update(bob, actual.id(), sell("2330.TW", SELL_1, "1000", 100)))).startsWith("404");
        assertThat(message(() -> sales.delete(bob, actual.id()))).startsWith("404");

        // 修改與刪除
        Sale edited = sales.update(alice, actual.id(), sell("2330.TW", SELL_1, "1100", 200));
        assertThat(edited.amount()).isEqualByComparingTo("220000");
        sales.delete(alice, actual.id());
        assertThat(holdings.getHoldings(alice).realized().sales()).extracting(Sale::id).containsExactly(discount.id());
    }
}
