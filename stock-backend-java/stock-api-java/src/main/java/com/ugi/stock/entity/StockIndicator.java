package com.ugi.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 技術指標（stock_indicators 表，與 stock_prices 一對一）。
 * 由 Python 擷取服務計算並寫入，Java 只讀取，因此標記為 @Immutable。
 * MACD、RSI、KD 在暖身期為 null；MA 在資料筆數不足時為 null。
 */
@Entity
@Immutable
@Table(name = "stock_indicators")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockIndicator {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "ma5", precision = 12, scale = 4)
    private BigDecimal ma5;

    @Column(name = "ma10", precision = 12, scale = 4)
    private BigDecimal ma10;

    @Column(name = "ma20", precision = 12, scale = 4)
    private BigDecimal ma20;

    @Column(name = "ma60", precision = 12, scale = 4)
    private BigDecimal ma60;

    // 台股名稱：DIF = EMA12 − EMA26
    @Column(name = "macd_dif", precision = 12, scale = 4)
    private BigDecimal macdDif;

    // 台股名稱：MACD（DIF 的 9 日 EMA，訊號線）
    @Column(name = "macd_signal", precision = 12, scale = 4)
    private BigDecimal macdSignal;

    // 台股名稱：OSC = DIF − MACD（柱狀體）
    @Column(name = "macd_osc", precision = 12, scale = 4)
    private BigDecimal macdOsc;

    @Column(name = "rsi6", precision = 12, scale = 4)
    private BigDecimal rsi6;

    @Column(name = "rsi12", precision = 12, scale = 4)
    private BigDecimal rsi12;

    @Column(name = "kd_k", precision = 12, scale = 4)
    private BigDecimal kdK;

    @Column(name = "kd_d", precision = 12, scale = 4)
    private BigDecimal kdD;

    // 平均真實區間（14 日），回測移動停利與參考價位的 ATR 停損使用
    @Column(name = "atr14", precision = 12, scale = 4)
    private BigDecimal atr14;
}
