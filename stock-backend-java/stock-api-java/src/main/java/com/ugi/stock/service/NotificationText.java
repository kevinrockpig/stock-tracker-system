package com.ugi.stock.service;

import com.ugi.stock.dto.NotificationDigest;
import com.ugi.stock.dto.NotificationDigest.EntryCandidate;
import com.ugi.stock.dto.NotificationDigest.ExDividend;
import com.ugi.stock.dto.NotificationDigest.ExitAlert;
import com.ugi.stock.dto.NotificationDigest.Market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 每日通知的文字（Email 主旨與內文，純文字）。只描述「符合條件」的事實，不寫買賣建議。
 */
public final class NotificationText {

    static final String DISCLAIMER = "以上依每日收盤資料計算，僅供參考，不構成投資建議；買賣請自行判斷。";

    private NotificationText() {
    }

    public static String subject(NotificationDigest d) {
        String day = d.asOf() == null ? "" : " " + d.asOf().getMonthValue() + "/" + d.asOf().getDayOfMonth();
        List<String> parts = new ArrayList<>();
        if (!d.belowExit().isEmpty()) {
            parts.add("跌破停利 " + d.belowExit().size());
        }
        if (!d.nearExit().isEmpty()) {
            parts.add("接近停利 " + d.nearExit().size());
        }
        if (!d.exDividends().isEmpty()) {
            parts.add("除權息 " + d.exDividends().size());
        }
        if (!d.entries().isEmpty()) {
            parts.add("買進候選 " + d.entries().size());
        }
        return "股票提醒" + day + "：" + (parts.isEmpty() ? "今日無提醒" : String.join("、", parts));
    }

    /**
     * @param siteUrl 網站網址（管理通知的連結）；尚未部署時為空白，不附連結
     */
    public static String body(NotificationDigest d, String siteUrl) {
        StringBuilder b = new StringBuilder();
        if (d.asOf() == null) {
            b.append("資料庫還沒有行情資料。\n");
        } else {
            b.append(date(d.asOf())).append(" 收盤後的提醒\n\n");
        }
        Market m = d.market();
        if (m != null) {
            b.append("大盤：").append(m.name()).append(" 收盤 ").append(price(m.close()))
                    .append("，季線 ").append(price(m.ma60()));
            b.append(m.weak()
                    ? "，收盤低於季線（大盤轉弱：「接近停利線」的提醒範圍各加 " + plain(RiskLevel.WEAK_MARKET_EXTRA) + " 個 ATR）"
                    : "，收盤在季線之上");
            b.append("\n");
        }

        if (!d.belowExit().isEmpty()) {
            section(b, "跌破移動停利線", d.belowExit().size());
            for (ExitAlert a : d.belowExit()) {
                b.append("- ").append(stock(a.symbol(), a.name())).append("：收盤 ").append(price(a.price()))
                        .append("，停利線 ").append(price(a.exitLine())).append("（低於 ").append(percent(a.distance().negate())).append("）\n");
            }
        }
        if (!d.nearExit().isEmpty()) {
            section(b, "接近移動停利線", d.nearExit().size());
            for (ExitAlert a : d.nearExit()) {
                b.append("- ").append(stock(a.symbol(), a.name())).append("：收盤 ").append(price(a.price()))
                        .append("，停利線 ").append(price(a.exitLine())).append("，相距 ").append(percent(a.distance()))
                        .append("（").append(oneDecimal(a.atrDistance())).append(" 個 ATR；").append(a.riskLabel())
                        .append("，").append(plain(a.thresholdAtr())).append(" 個 ATR 內提醒）\n");
            }
        }
        if (!d.exDividends().isEmpty()) {
            section(b, "即將除權息", d.exDividends().size());
            for (ExDividend e : d.exDividends()) {
                b.append("- ").append(stock(e.symbol(), e.name())).append("：").append(e.exDate().getMonthValue()).append("/")
                        .append(e.exDate().getDayOfMonth()).append(" 除").append(e.exType() == null ? "權息" : e.exType())
                        .append(e.cashDividend() == null ? "（股利未公告）" : "，現金股利 " + plain(e.cashDividend()))
                        .append("（").append(e.days()).append(" 天後）\n");
            }
        }
        if (!d.entries().isEmpty()) {
            section(b, "交易計畫的買進候選（符合條件，非投資建議）", d.entries().size());
            for (EntryCandidate e : d.entries()) {
                b.append("- ").append(stock(e.symbol(), e.name())).append("：").append(e.strategy()).append("＋").append(e.exitRule())
                        .append("，收盤 ").append(price(e.closePrice()));
                if (e.skipAbove() != null) {
                    b.append("，開盤高於 ").append(price(e.skipAbove())).append(" 時計畫放棄（接近漲停）");
                }
                b.append("\n");
            }
        }
        if (d.alertCount() == 0) {
            b.append("\n今天沒有需要留意的項目。\n");
        }

        b.append("\n---\n");
        b.append("移動停利線 = 買進以來最高收盤價 − 3 × ATR(14)（有填買進日期的庫存才會計算）。\n");
        b.append(DISCLAIMER).append("\n");
        if (siteUrl != null && !siteUrl.isBlank()) {
            b.append("通知設定與個股風險設定：").append(siteUrl.replaceAll("/+$", "")).append("/holdings\n");
        }
        return b.toString();
    }

    private static void section(StringBuilder b, String title, int count) {
        b.append("\n■ ").append(title).append("（").append(count).append("）\n");
    }

    // 2330.TW → 2330 台積電
    static String stock(String symbol, String name) {
        String code = symbol.contains(".") ? symbol.substring(0, symbol.indexOf('.')) : symbol;
        return name == null || name.isBlank() ? code : code + " " + name;
    }

    static String date(LocalDate day) {
        return day + "（" + day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.TRADITIONAL_CHINESE).replace("週", "") + "）";
    }

    static String price(BigDecimal value) {
        return value == null ? "—" : new DecimalFormat("#,##0.00").format(value.setScale(2, RoundingMode.HALF_UP));
    }

    static String percent(BigDecimal ratio) {
        return ratio == null ? "—" : ratio.movePointRight(2).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    static String oneDecimal(BigDecimal value) {
        return value == null ? "—" : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    // 去掉多餘的 0：1.5000 → 1.5、0.866 → 0.866
    static String plain(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal rounded = value.setScale(Math.min(value.scale(), 3), RoundingMode.HALF_UP).stripTrailingZeros();
        return (rounded.scale() < 0 ? rounded.setScale(0) : rounded).toPlainString();
    }
}
