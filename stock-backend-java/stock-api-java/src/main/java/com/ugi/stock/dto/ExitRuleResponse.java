package com.ugi.stock.dto;

/**
 * 回測出場規則說明。
 *
 * @param code        查詢參數使用的代碼，例如 trailing_atr
 * @param name        中文名稱
 * @param description 規則說明
 * @param horizon     分組：short 短線 / swing 波段
 */
public record ExitRuleResponse(
        String code,
        String name,
        String description,
        String horizon
) {
}
