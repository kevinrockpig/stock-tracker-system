package com.ugi.stock.dto;

/**
 * 觀察名單的組別（GET /api/groups）。
 *
 * @param code   組別代碼，也可作為 GET /api/backtests 的 scope
 * @param name   中文名稱
 * @param stocks 組內股票數（同一檔可同時屬於多組）
 */
public record StockGroupResponse(
        String code,
        String name,
        int stocks
) {
}
