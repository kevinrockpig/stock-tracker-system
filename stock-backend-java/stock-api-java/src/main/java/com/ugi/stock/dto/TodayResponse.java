package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/today：今天（最新交易日收盤後）的大盤狀態與 R1 規則的候選。
 *
 * @param asOf        資料日期（最新交易日）；候選於下一個交易日開盤進場
 * @param frequency   daily：只看今天的訊號；weekly：本週（週一起）出現過的訊號，週五收盤後決定、下週第一個交易日進場
 * @param signalsFrom 訊號的起始日（daily 為 asOf，weekly 為本週一）
 * @param decisionDay 今天是否為決定日（daily 每天都是；weekly 為週五，遇到週五休市時以前一個交易日為準需自行判斷）
 */
public record TodayResponse(LocalDate asOf, String frequency, LocalDate signalsFrom, boolean decisionDay, Market market,
                            List<Candidate> candidates) {

    /**
     * @param canOpen    0050 收盤是否在 60 日均線之上（R1：之下時不開新倉）
     * @param gapPercent 收盤距 60 日均線（%）
     */
    public record Market(String symbol, BigDecimal close, BigDecimal ma60, boolean canOpen, BigDecimal gapPercent) {
    }

    /**
     * @param signals    當天出現的訊號（代碼，見 GET /api/strategies）
     * @param mine       是否來自登入者的自選（不在觀察名單）
     * @param strength   近 120 個交易日漲幅（%），候選依此排序；資料不足為 null
     * @param stopPrice  若以今天收盤價買進，回落 10% 的出場價（之後隨最高收盤上移）
     * @param skipAbove  明天開盤高於此價就不買（比今天收盤高 9.5%）
     */
    public record Candidate(String symbol, String name, List<String> groups, boolean mine, BigDecimal close, BigDecimal changePercent,
                            List<String> signals, BigDecimal strength, BigDecimal stopPrice, BigDecimal skipAbove) {
    }
}
