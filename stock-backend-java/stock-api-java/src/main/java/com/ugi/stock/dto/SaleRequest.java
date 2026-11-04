package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增或修改一筆賣出（POST /api/holdings/sales、PUT /api/holdings/sales/{id}）。
 *
 * @param symbol      股票代號（必須有這檔的買進紀錄）
 * @param sellDate    賣出日期，必填（不可晚於今天）
 * @param price       每股成交價
 * @param shares      股數（賣出當時）
 * @param feeMode     手續費方式，同 HoldingRequest
 * @param feeDiscount discount 時必填：折數
 * @param minFee      standard / discount 時的最低手續費，未填為 20 元
 * @param fee         actual 時必填：實際手續費（元）
 * @param taxMode     standard 依法定稅率計算（股票 0.3%、ETF 0.1%）/ actual 實際金額
 * @param tax         actual 時必填：實際證交稅（元）
 */
public record SaleRequest(
        String symbol,
        LocalDate sellDate,
        BigDecimal price,
        Integer shares,
        String feeMode,
        BigDecimal feeDiscount,
        BigDecimal minFee,
        BigDecimal fee,
        String taxMode,
        BigDecimal tax
) {
}
