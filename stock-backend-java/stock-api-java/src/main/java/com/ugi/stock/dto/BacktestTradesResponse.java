package com.ugi.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 某檔股票在某策略、某出場規則下的歷史回測交易（GET /api/stocks/{symbol}/trades），依進場日由舊到新。
 */
public record BacktestTradesResponse(
        String symbol,
        String name,
        StrategyResponse strategy,
        ExitRuleResponse exitRule,
        List<Trade> trades
) {

    /**
     * 單筆交易。仍持有中時 exitDate、exitPrice、exitReason、netReturn 為 null。
     *
     * @param signalDate  訊號日（收盤後出現訊號）
     * @param entryDate   進場日（訊號日的下一個交易日，以開盤價買進）
     * @param exitReason  hold：持有期滿 / trailing：跌破移動停利線 / max_hold：移動停利達最長天數
     * @param dividends   持有期間領到的每股現金股利
     * @param holdingDays 持有的交易日數（含進場日與出場日）
     * @param netReturn   扣成本、含股利的報酬率（0.05 = 5%）
     */
    public record Trade(
            LocalDate signalDate,
            LocalDate entryDate,
            BigDecimal entryPrice,
            LocalDate exitDate,
            BigDecimal exitPrice,
            String exitReason,
            BigDecimal dividends,
            int holdingDays,
            BigDecimal netReturn
    ) {
    }
}
