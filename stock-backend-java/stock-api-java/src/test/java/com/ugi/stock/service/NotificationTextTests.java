package com.ugi.stock.service;

import com.ugi.stock.dto.NotificationDigest;
import com.ugi.stock.dto.NotificationDigest.EntryCandidate;
import com.ugi.stock.dto.NotificationDigest.ExDividend;
import com.ugi.stock.dto.NotificationDigest.ExitAlert;
import com.ugi.stock.dto.NotificationDigest.Market;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTextTests {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4); // 星期三

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static NotificationDigest full() {
        Market weak = new Market("0050.TW", "元大台灣50", d("100.60"), d("106.2345"), true);
        ExitAlert below = new ExitAlert("2330.TW", "台積電", d("2295"), d("2310.5"), d("40"), d("-0.0068"), d("-0.3875"),
                "standard", "標準", d("1.5"));
        ExitAlert near = new ExitAlert("2454.TW", "聯發科", d("3675"), d("3520"), d("190"), d("0.0422"), d("0.8158"),
                "conservative", "保守", d("2.0"));
        ExDividend ex = new ExDividend("0056.TW", "元大高股息", LocalDate.of(2026, 11, 6), "息", d("0.866"), 2);
        EntryCandidate entry = new EntryCandidate("創 120 日新高", "移動停利", "2317.TW", "鴻海", DAY, d("230.5"), d("251.5"));
        return new NotificationDigest(DAY, weak, List.of(below), List.of(near), List.of(ex), List.of(entry));
    }

    @Test
    void subjectCountsEachSection() {
        assertThat(NotificationText.subject(full())).isEqualTo("股票提醒 11/4：跌破停利 1、接近停利 1、除權息 1、買進候選 1");
        NotificationDigest empty = new NotificationDigest(DAY, null, List.of(), List.of(), List.of(), List.of());
        assertThat(NotificationText.subject(empty)).isEqualTo("股票提醒 11/4：今日無提醒");
    }

    @Test
    void bodyListsFactsWithoutAdvice() {
        String body = NotificationText.body(full(), "https://stock.example.test/");
        assertThat(body)
                .startsWith("2026-11-04（三） 收盤後的提醒")
                .contains("大盤：元大台灣50 收盤 100.60，季線 106.23，收盤低於季線（大盤轉弱：「接近停利線」的提醒範圍各加 0.5 個 ATR）")
                .contains("■ 跌破移動停利線（1）\n- 2330 台積電：收盤 2,295.00，停利線 2,310.50（低於 0.7%）")
                .contains("■ 接近移動停利線（1）\n- 2454 聯發科：收盤 3,675.00，停利線 3,520.00，相距 4.2%（0.8 個 ATR；保守，2 個 ATR 內提醒）")
                .contains("■ 即將除權息（1）\n- 0056 元大高股息：11/6 除息，現金股利 0.866（2 天後）")
                .contains("■ 交易計畫的買進候選（符合條件，非投資建議）（1）\n- 2317 鴻海：創 120 日新高＋移動停利，收盤 230.50，開盤高於 251.50 時計畫放棄（接近漲停）")
                .contains(NotificationText.DISCLAIMER)
                .contains("通知設定與個股風險設定：https://stock.example.test/holdings")
                .doesNotContain("今天沒有需要留意的項目")
                .doesNotContainIgnoringCase("建議買")
                .doesNotContain("應該");
    }

    @Test
    void emptyDayStillSaysSo() {
        Market strong = new Market("0050.TW", "元大台灣50", d("110"), d("106"), false);
        String body = NotificationText.body(new NotificationDigest(DAY, strong, List.of(), List.of(), List.of(), List.of()), "");
        assertThat(body).contains("收盤在季線之上").contains("今天沒有需要留意的項目。").doesNotContain("■").doesNotContain("通知設定與個股風險設定");
    }
}
