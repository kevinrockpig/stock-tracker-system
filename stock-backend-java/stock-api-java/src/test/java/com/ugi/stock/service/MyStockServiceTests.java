package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.MyStocksResponse;
import com.ugi.stock.dto.MyStocksResponse.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 我的自選：代號檢查（官方每日資料）、市場別、等待更新 / 已有資料、每人與全站上限、只能動自己的、庫存可加入自選中已有資料的股票。
 * 官方資料與非觀察名單的股票由測試自行寫入，測試在交易中執行並於結束時還原。
 */
@SpringBootTest
@Transactional
class MyStockServiceTests {

    @Autowired
    private MyStockService myStocks;
    @Autowired
    private HoldingService holdings;
    @Autowired
    private AppUserService users;
    @Autowired
    private JdbcTemplate jdbc;

    private long alice;
    private long bob;
    private LocalDate latest;

    @BeforeEach
    void setUp() {
        alice = users.recordLogin("google", "test-my-alice", "my.alice@example.com", "Alice");
        bob = users.recordLogin("google", "test-my-bob", "my.bob@example.com", "Bob");
        latest = jdbc.queryForObject("select max(trade_date) from stock_prices", LocalDate.class);
        quote("TPEX", "6488", "環球晶", latest);
        quote("TWSE", "00878", "國泰永續高股息", latest);
        quote("TWSE", "2330", "台積電", latest);
        quote("TWSE", "9910", "豐泰", latest.minusDays(3)); // 最近 7 天內出現過
        quote("TWSE", "1234", "已下市", latest.minusDays(30)); // 太久沒出現
        // 非觀察名單、已有資料的股票（之前有人加入過）
        jdbc.update("insert into stocks (symbol, name, currency) values ('9910.TW', '豐泰', 'TWD')");
        jdbc.update("insert into stock_prices (symbol, trade_date, close_price) values ('9910.TW', ?, 180), ('9910.TW', ?, 200)",
                latest.minusDays(1), latest);
    }

    private void quote(String market, String code, String name, LocalDate day) {
        jdbc.update("insert into official_daily_quotes (market, code, name, trade_date, close_price, source) values (?, ?, ?, ?, 100, 'test')",
                market, code, name, day);
    }

    @Test
    void addPendingAndReadyStocks() {
        Item pending = myStocks.add(alice, " 6488 ");
        assertThat(pending.symbol()).isEqualTo("6488.TWO"); // 上櫃
        assertThat(pending.name()).isEqualTo("環球晶");
        assertThat(pending.ready()).isFalse();
        assertThat(pending.quote()).isNull();

        Item etf = myStocks.add(alice, "00878");
        assertThat(etf.symbol()).isEqualTo("00878.TW");

        Item ready = myStocks.add(alice, "9910");
        assertThat(ready.ready()).isTrue();
        assertThat(ready.watchlist()).isFalse();
        assertThat(ready.quote().closePrice()).isEqualByComparingTo("200");
        assertThat(ready.quote().changePercent()).isEqualByComparingTo("11.11");

        Item watch = myStocks.add(alice, "2330");
        assertThat(watch.watchlist()).isTrue();
        assertThat(watch.ready()).isTrue();

        MyStocksResponse list = myStocks.list(alice);
        assertThat(list.stocks()).extracting(Item::symbol).containsExactly("6488.TWO", "00878.TW", "9910.TW", "2330.TW");
        assertThat(list.limit()).isEqualTo(MyStockService.MAX_PER_USER);
        assertThat(myStocks.list(bob).stocks()).isEmpty();
    }

    @Test
    void rejectsBadCodes() {
        assertThatThrownBy(() -> myStocks.add(alice, "2330.TW")).hasMessageContaining("4～6 碼");
        assertThatThrownBy(() -> myStocks.add(alice, "23")).hasMessageContaining("4～6 碼");
        assertThatThrownBy(() -> myStocks.add(alice, null)).hasMessageContaining("4～6 碼");
        assertThatThrownBy(() -> myStocks.add(alice, "5555")).hasMessageContaining("查不到代號 5555");
        assertThatThrownBy(() -> myStocks.add(alice, "1234")).hasMessageContaining("查不到代號 1234");
        myStocks.add(alice, "6488");
        assertThatThrownBy(() -> myStocks.add(alice, "6488")).hasMessageContaining("已經在我的自選中");
        myStocks.add(bob, "6488"); // 別人加過的同一檔可以再加
    }

    @Test
    void perUserLimit() {
        for (int i = 0; i < MyStockService.MAX_PER_USER; i++) {
            jdbc.update("insert into user_stocks (user_id, symbol) values (?, ?)", alice, String.format("Y%03d.TW", i));
        }
        assertThatThrownBy(() -> myStocks.add(alice, "6488")).hasMessageContaining("最多 " + MyStockService.MAX_PER_USER + " 檔");
    }

    @Test
    void totalLimitOnlyForNewStocksOutsideWatchlist() {
        // 全站已有 MAX_TOTAL 檔非觀察名單的股票（含 bob 的 6488）
        jdbc.update("insert into user_stocks (user_id, symbol) values (?, '6488.TWO')", bob);
        int others = (int) (MyStockService.MAX_TOTAL - jdbc.queryForObject(
                "select count(distinct u.symbol) from user_stocks u left join stocks s on s.symbol = u.symbol where s.group_codes is null", Long.class));
        for (int i = 0; i < others; i++) {
            long user = users.recordLogin("google", "test-my-cap-" + i / 25, "my.cap" + i / 25 + "@example.com", "Cap");
            jdbc.update("insert into user_stocks (user_id, symbol) values (?, ?)", user, String.format("Z%03d.TW", i)); // 假代號，不會與觀察名單重複
        }
        assertThatThrownBy(() -> myStocks.add(alice, "00878")).hasMessageContaining("全站自行加入的股票已達 " + MyStockService.MAX_TOTAL);
        assertThat(myStocks.add(alice, "6488").symbol()).isEqualTo("6488.TWO"); // 已有人加入的不增加負擔
        assertThat(myStocks.add(alice, "2330").watchlist()).isTrue(); // 觀察名單本來就每天更新
    }

    @Test
    void removeOnlyOwn() {
        myStocks.add(alice, "6488");
        assertThatThrownBy(() -> myStocks.remove(bob, "6488.TWO"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        myStocks.remove(alice, "6488.TWO");
        assertThat(myStocks.list(alice).stocks()).isEmpty();
        assertThatThrownBy(() -> myStocks.remove(alice, "6488.TWO")).hasMessageContaining("404");
    }

    @Test
    void holdingsAllowReadyStocksInOwnList() {
        HoldingRequest buy = new HoldingRequest("9910.TW", latest, new BigDecimal("200"), 1000, "standard", null, null, null);
        assertThatThrownBy(() -> holdings.create(alice, buy)).hasMessageContaining("不在觀察名單或我的自選內");
        myStocks.add(alice, "9910");
        assertThat(holdings.create(alice, buy).symbol()).isEqualTo("9910.TW");
        assertThatThrownBy(() -> holdings.create(bob, buy)).hasMessageContaining("不在觀察名單或我的自選內"); // 別人的自選不算

        // 還沒有資料（等待每日更新）的不能加入庫存
        myStocks.add(alice, "6488");
        HoldingRequest pending = new HoldingRequest("6488.TWO", null, new BigDecimal("300"), 1000, "standard", null, null, null);
        assertThatThrownBy(() -> holdings.create(alice, pending)).hasMessageContaining("等下一次每日更新");
    }
}
