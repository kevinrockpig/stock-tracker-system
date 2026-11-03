package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增或修改一筆庫存（POST /api/holdings、PUT /api/holdings/{id}）。
 *
 * @param symbol      股票代號（必須在觀察名單內）
 * @param buyDate     買進日期，選填（不可晚於今天、不可早於上市日）
 * @param price       每股成交價（買進當時）
 * @param shares      股數（買進當時；1 張 = 1,000 股）
 * @param feeMode     actual 實際金額 / standard 公定費率 / discount 公定費率 × 折數
 * @param feeDiscount discount 時必填：折數（2.8 折為 0.28）
 * @param minFee      standard / discount 時的最低手續費，未填為 20 元
 * @param fee         actual 時必填：實際手續費（元）；其他方式由伺服器計算
 */
public record HoldingRequest(
        String symbol,
        LocalDate buyDate,
        BigDecimal price,
        Integer shares,
        String feeMode,
        BigDecimal feeDiscount,
        BigDecimal minFee,
        BigDecimal fee
) {
}
