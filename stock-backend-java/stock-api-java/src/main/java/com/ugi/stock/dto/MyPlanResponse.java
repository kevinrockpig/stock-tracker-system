package com.ugi.stock.dto;

/**
 * GET /api/my-plan：還沒有設定時 plan 為 null。
 */
public record MyPlanResponse(MyPlan plan) {
}
