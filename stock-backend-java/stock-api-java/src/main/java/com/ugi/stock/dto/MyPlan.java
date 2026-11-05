package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 我的計畫（GET / PUT /api/my-plan 的內容）。
 *
 * @param version      core（核心為主，0050 70%）/ balanced（均衡 50%）/ strategy（策略為主 30%）
 * @param frequency    daily（每天收盤後看）/ weekly（每週最後一個交易日收盤後看）
 * @param capital      本金（元）
 * @param maxLoss      可承受的最大虧損（0.35 為 -35%）
 * @param investAmount 實際投入股市的金額（其餘保留現金），不超過本金
 * @param updatedAt    儲存時間（PUT 時忽略）
 */
public record MyPlan(String version, String frequency, BigDecimal capital, BigDecimal maxLoss, BigDecimal investAmount, Instant updatedAt) {
}
