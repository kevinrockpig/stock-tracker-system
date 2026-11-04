package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingHistoryResponse;
import com.ugi.stock.dto.HoldingHistoryResponse.Point;
import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.HoldingsResponse;
import com.ugi.stock.dto.HoldingsResponse.Lot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 庫存資產走勢：最後一天必須等於庫存頁的同期比較；中間的日子以 SQL 直接從資料表重算比對。
 * 測試在交易中執行並於結束時還原（rollback），不會留下資料。
 */
@SpringBootTest
@Transactional
class HoldingHistoryServiceTests {

    @Autowired
    private HoldingHistoryService history;
    @Autowired
    private HoldingService holdings;
    @Autowired
    private AppUserService users;
    @Autowired
    private JdbcTemplate jdbc;

    private long alice;

    @BeforeEach
    void setUp() {
        alice = users.recordLogin("google", "test-history", "history.test@example.com", "History");
    }

    private Lot buy(String symbol, LocalDate date, String price, int shares) {
        return holdings.create(alice, new HoldingRequest(symbol, date, new BigDecimal(price), shares, "standard", null, null, null));
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

    private LocalDate entry(LocalDate buyDate) {
        return jdbc.queryForObject("select min(trade_date) from stock_prices where symbol = '0050.TW' and trade_date >= ?", LocalDate.class, buyDate);
    }

    private static Point at(HoldingHistoryResponse response, LocalDate day) {
        return response.points().stream().filter(p -> p.date().equals(day)).findFirst().orElseThrow();
    }

    @Test
    void lastDayEqualsBenchmarkAndMiddleDaysMatchSql() {
        LocalDate dateA = LocalDate.of(2025, 1, 11); // 星期六：以下一個交易日起算
        LocalDate dateB = LocalDate.of(2025, 6, 10); // 0050 於 2025-06-18 1 拆 4 之前
        LocalDate dateD = LocalDate.of(2026, 3, 2);
        Lot a = buy("2330.TW", dateA, "1000", 1000);
        Lot b = buy("0050.TW", dateB, "188.65", 1000);
        buy("2330.TW", null, "900", 500);                     // 沒有日期：不納入，但與庫存頁相同，計入整檔的賣出成本
        Lot d = holdings.create(alice, new HoldingRequest("2330.TW", dateD, new BigDecimal("1500"), 100, "discount",
                new BigDecimal("0.6"), new BigDecimal("1"), null)); // 整檔最近一筆：賣出手續費依這筆的折數估算
        buy("2884.TW", LocalDate.of(2015, 1, 5), "20", 1000); // 早於 0050 的資料起點：不納入

        HoldingHistoryResponse response = history.getHistory(alice);
        HoldingsResponse.Benchmark bench = holdings.getHoldings(alice).benchmark();
        LocalDate asOf = response.asOf();

        // 納入的筆數與最後一天：與庫存頁的同期比較完全相同
        assertThat(response.lots()).isEqualTo(3).isEqualTo(bench.lots());
        assertThat(response.excludedLots()).isEqualTo(2).isEqualTo(bench.excludedLots());
        Point last = response.points().get(response.points().size() - 1);
        assertThat(last.date()).isEqualTo(asOf);
        assertThat(last.invested()).isEqualByComparingTo(bench.invested());
        assertThat(last.holdingsValue()).isEqualByComparingTo(bench.holdingsValue());
        assertThat(last.etfValue()).isEqualByComparingTo(bench.etfValue());

        // 日期：從第一筆的進場日開始、由舊到新不重複、都是資料庫中的交易日
        LocalDate entryA = entry(dateA);
        assertThat(entryA).isEqualTo(LocalDate.of(2025, 1, 13));
        List<LocalDate> dates = response.points().stream().map(Point::date).toList();
        assertThat(dates.get(0)).isEqualTo(entryA);
        assertThat(dates).isSorted().doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("select count(distinct trade_date) from stock_prices where trade_date between ? and ? and symbol in ('0050.TW', '2330.TW')",
                Integer.class, entryA, asOf)).isEqualTo(dates.size());

        // 投入成本：買進那天起計入（階梯）
        LocalDate entryB = entry(dateB);
        LocalDate entryD = entry(dateD);
        LocalDate beforeB = dates.get(dates.indexOf(entryB) - 1);
        assertThat(at(response, beforeB).invested()).isEqualByComparingTo(a.cost());
        assertThat(at(response, entryB).invested()).isEqualByComparingTo(a.cost().add(b.cost()));
        assertThat(at(response, entryD).invested()).isEqualByComparingTo(a.cost().add(b.cost()).add(d.cost()));

        // 中間的一天以 SQL 重算（A、B 已買進，D 還沒）
        LocalDate day = dates.stream().filter(x -> !x.isBefore(LocalDate.of(2025, 9, 1))).findFirst().orElseThrow();
        double c2330 = close("2330.TW", day);
        double c0050 = close("0050.TW", day);
        double mv2330 = 1600 * c2330; // 整檔：1,000 + 500 + 100 股
        double sell2330 = Math.max(Math.floor(mv2330 * 0.001425 * 0.6), 1) + Math.floor(mv2330 * 0.003);
        double mv0050 = 4000 * c0050; // 分割後 4,000 股
        double sell0050 = Math.max(Math.floor(mv0050 * 0.001425), 20) + Math.floor(mv0050 * 0.001);
        double holdingsValue = (mv2330 - sell2330) * 1000 / 1600 + dividends("2330.TW", dateA, day) * 1000
                + (mv0050 - sell0050) + dividends("0050.TW", dateB, day) * 4000;
        double etfValue = 0;
        for (Object[] lot : new Object[][]{{entryA, a.cost().doubleValue()}, {entryB, b.cost().doubleValue()}}) {
            LocalDate entryDate = (LocalDate) lot[0];
            etfValue += (double) lot[1] / close("0050.TW", entryDate) * (c0050 + dividends("0050.TW", entryDate, day));
        }
        Point middle = at(response, day);
        assertThat(middle.invested()).isEqualByComparingTo(a.cost().add(b.cost()));
        assertThat(middle.holdingsValue().doubleValue()).isCloseTo(holdingsValue, within(1.5));
        assertThat(middle.etfValue().doubleValue()).isCloseTo(etfValue, within(0.05));
    }

    @Test
    void noJumpAcrossSplit() {
        // 分割前以實際報價買 1 張：資料庫的價格已換算，每天的價值 = 4,000 股 × 當天收盤 − 賣出成本，分割當天不會跳動
        LocalDate buyDate = LocalDate.of(2025, 6, 10);
        Lot lot = buy("0050.TW", buyDate, "188.65", 1000);
        HoldingHistoryResponse response = history.getHistory(alice);
        assertThat(response.points().get(0).date()).isEqualTo(buyDate);
        List<Point> june = response.points().stream().filter(p -> p.date().isBefore(LocalDate.of(2025, 7, 1))).toList();
        // 0050 分割期間 6/11～6/17 停止交易：只有 0050 時走勢從 6/10 直接接到 6/18
        assertThat(june).extracting(Point::date).startsWith(buyDate, LocalDate.of(2025, 6, 18));
        for (Point p : june) {
            double mv = 4000 * close("0050.TW", p.date());
            double expected = mv - Math.max(Math.floor(mv * 0.001425), 20) - Math.floor(mv * 0.001);
            assertThat(p.holdingsValue().doubleValue()).as(p.date().toString()).isCloseTo(expected, within(0.01));
            assertThat(p.invested()).isEqualByComparingTo(lot.cost());
        }
        // 買進當天收盤買 0050：0050 的價值等於投入成本
        assertThat(june.get(0).etfValue()).isEqualByComparingTo(lot.cost());
    }

    @Test
    void followsEditsWithoutSnapshots() {
        Lot lot = buy("2330.TW", null, "1000", 1000);
        HoldingHistoryResponse undated = history.getHistory(alice);
        assertThat(undated.points()).isEmpty();
        assertThat(undated.lots()).isZero();
        assertThat(undated.excludedLots()).isEqualTo(1);

        // 補填過去的買進日期：立刻有從那天起的走勢
        holdings.update(alice, lot.id(), new HoldingRequest("2330.TW", LocalDate.of(2026, 1, 5), new BigDecimal("1000"), 1000,
                "standard", null, null, null));
        HoldingHistoryResponse dated = history.getHistory(alice);
        assertThat(dated.lots()).isEqualTo(1);
        assertThat(dated.points().get(0).date()).isEqualTo(entry(LocalDate.of(2026, 1, 5)));

        holdings.delete(alice, lot.id());
        assertThat(history.getHistory(alice).points()).isEmpty();
    }

    @Test
    void emptyHoldings() {
        HoldingHistoryResponse response = history.getHistory(alice);
        assertThat(response.points()).isEmpty();
        assertThat(response.lots()).isZero();
        assertThat(response.excludedLots()).isZero();
        assertThat(response.etfSymbol()).isEqualTo("0050.TW");
    }
}
