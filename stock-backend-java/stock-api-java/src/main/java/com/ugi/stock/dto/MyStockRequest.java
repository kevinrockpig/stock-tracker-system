package com.ugi.stock.dto;

/**
 * POST /api/my-stocks：股票代號，不含 .TW / .TWO（例如 2330、6488、00878），上市或上櫃由官方資料判斷。
 */
public record MyStockRequest(String code) {
}
