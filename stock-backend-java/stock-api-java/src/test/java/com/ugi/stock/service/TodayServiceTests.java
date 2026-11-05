package com.ugi.stock.service;

import com.ugi.stock.dto.TodayResponse;
import com.ugi.stock.dto.TodayResponse.Candidate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 今天頁：大盤狀態與候選以 SQL 直接從資料表重算（不經過服務）比對；自選股只在登入時列入。測試在交易中執行並於結束時還原。
 */
@SpringBootTest
@Transactional
class TodayServiceTests {

    @Autowired
    private TodayService today;
    @Autowired
    private MyStockService myStocks;
    @Autowired
    private AppUserService users;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void marketMatchesSixtyDayAverage() {
        TodayResponse r = today.today(null);
        LocalDate asOf = jdbc.queryForObject("select max(trade_date) from stock_prices where symbol = '0050.TW'", LocalDate.class);
        assertThat(r.asOf()).isEqualTo(asOf);
        BigDecimal close = jdbc.queryForObject("select close_price from stock_prices where symbol = '0050.TW' and trade_date = ?", BigDecimal.class, asOf);
        // 60 日均線：直接以最近 60 個收盤平均（與資料庫中的 ma60 互相驗證）
        BigDecimal avg = jdbc.queryForObject("""
                select avg(close_price) from (select close_price from stock_prices where symbol = '0050.TW' and trade_date <= ?
                order by trade_date desc limit 60) t""", BigDecimal.class, asOf);
        assertThat(r.market().close()).isEqualByComparingTo(close);
        assertThat(r.market().ma60().doubleValue()).isCloseTo(avg.doubleValue(), org.assertj.core.data.Offset.offset(0.01));
        assertThat(r.market().canOpen()).isEqualTo(close.compareTo(avg) >= 0);
    }

    @Test
    void candidatesMatchSignalsOfLatestDay() {
        TodayResponse r = today.today(null);
        List<String> expected = jdbc.queryForList("""
                select distinct g.symbol from strategy_signals g join stocks s on s.symbol = g.symbol
                where g.trade_date = ? and g.strategy in ('high_120', 'golden_cross_trend')
                  and s.group_codes is not null and g.symbol <> '0050.TW'""", String.class, r.asOf());
        assertThat(r.candidates()).extracting(Candidate::symbol).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).isNotEmpty(); // 測試資料最新一天要有候選，否則這個測試沒有意義

        for (Candidate c : r.candidates()) {
            BigDecimal before = jdbc.queryForObject("""
                    select close_price from stock_prices where symbol = ? and trade_date <= ? order by trade_date desc offset 120 limit 1""",
                    BigDecimal.class, c.symbol(), r.asOf());
            double strength = (c.close().doubleValue() / before.doubleValue() - 1) * 100;
            assertThat(c.strength().doubleValue()).isCloseTo(strength, org.assertj.core.data.Offset.offset(0.006));
            assertThat(c.stopPrice()).isEqualByComparingTo(c.close().multiply(new BigDecimal("0.9")).setScale(2, RoundingMode.HALF_UP));
            assertThat(c.skipAbove()).isEqualByComparingTo(c.close().multiply(new BigDecimal("1.095")).setScale(2, RoundingMode.HALF_UP));
            List<String> signals = jdbc.queryForList("select strategy from strategy_signals where symbol = ? and trade_date = ? and strategy in ('high_120', 'golden_cross_trend')",
                    String.class, c.symbol(), r.asOf());
            assertThat(new HashSet<>(c.signals())).isEqualTo(new HashSet<>(signals));
            assertThat(c.mine()).isFalse();
        }
        // 依 120 日漲幅由高到低
        List<BigDecimal> strengths = r.candidates().stream().map(Candidate::strength).toList();
        assertThat(strengths).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void ownStocksOnlyWhenLoggedIn() {
        long alice = users.recordLogin("google", "test-today-alice", "today.alice@example.com", "Alice");
        LocalDate asOf = today.today(null).asOf();
        jdbc.update("insert into official_daily_quotes (market, code, name, trade_date, source) values ('TWSE', '9910', '豐泰', ?, 'test')", asOf);
        jdbc.update("insert into stocks (symbol, name, currency) values ('9910.TW', '豐泰', 'TWD')");
        jdbc.update("insert into stock_prices (symbol, trade_date, close_price) values ('9910.TW', ?, 190), ('9910.TW', ?, 200)", asOf.minusDays(1), asOf);
        jdbc.update("insert into strategy_signals (symbol, trade_date, strategy) values ('9910.TW', ?, 'high_120')", asOf);

        assertThat(today.today(null).candidates()).extracting(Candidate::symbol).doesNotContain("9910.TW");
        assertThat(today.today(alice).candidates()).extracting(Candidate::symbol).doesNotContain("9910.TW");
        myStocks.add(alice, "9910");
        Candidate mine = today.today(alice).candidates().stream().filter(c -> c.symbol().equals("9910.TW")).findFirst().orElseThrow();
        assertThat(mine.mine()).isTrue();
        assertThat(mine.strength()).isNull(); // 只有 2 天資料，算不出 120 日漲幅，排在最後
        List<Candidate> withMine = today.today(alice).candidates();
        assertThat(withMine.get(withMine.size() - 1).symbol()).isEqualTo("9910.TW");
    }
}
