package com.ugi.stock.dto;

/**
 * 選股策略說明（GET /api/strategies）。
 *
 * @param code        查詢參數使用的代碼，例如 golden_cross
 * @param name        中文名稱
 * @param description 成立條件
 * @param swingOnly   波段策略：只搭配波段出場規則回測
 */
public record StrategyResponse(
        String code,
        String name,
        String description,
        boolean swingOnly
) {
}
