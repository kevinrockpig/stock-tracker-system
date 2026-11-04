package com.ugi.stock.service;

import com.ugi.stock.entity.Holding;
import com.ugi.stock.entity.HoldingSale;
import com.ugi.stock.entity.StockAction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一位使用者一檔股票的買進與賣出：每次賣出依先進先出（與券商相同）分配到買進，股數都換算成目前的基準。
 * 不另存分配結果，每次由買進與賣出紀錄即時計算，所以修改過去的紀錄後自動一致。
 * <p>
 * 先進先出的順序：沒有買進日期的視為最早買進（依建立順序），其餘依買進日期；每次賣出只從賣出日（含）以前買進的扣。
 * 庫存頁、同期比較與資產走勢都由這裡計算每筆買進在某一天的價值，最後一天的數字因此一致。
 */
final class LotLedger {

    /** 換算後的股數可能有小數（配股），差距小於這個值視為相等 */
    static final BigDecimal TOLERANCE = new BigDecimal("0.0001");
    private static final MathContext MC = MathContext.DECIMAL64;

    private final LocalDate asOf;
    private final List<StockAction> actions;
    private final List<LotState> lots;
    private final List<SaleState> sales;

    /**
     * @param holdings 這檔股票的買進（任何順序）
     * @param sales    這檔股票的賣出（任何順序）
     * @param actions  這檔股票的除權息與股本事件，依除權息日由舊到新
     * @param asOf     資料庫最新的交易日（換算股數到這天）；沒有行情時為 null
     */
    LotLedger(List<Holding> holdings, List<HoldingSale> sales, List<StockAction> actions, LocalDate asOf) {
        this.asOf = asOf;
        this.actions = actions;
        this.lots = holdings.stream()
                .sorted(Comparator.comparing((Holding h) -> h.getBuyDate() != null)
                        .thenComparing(Holding::getBuyDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(h -> h.getId() == null ? Long.MAX_VALUE : h.getId()))
                .map(this::lotState)
                .toList();
        this.sales = sales.stream()
                .sorted(Comparator.comparing(HoldingSale::getSellDate).thenComparing(s -> s.getId() == null ? Long.MAX_VALUE : s.getId()))
                .map(this::allocate)
                .toList();
    }

    // ---- 建立 ----

    private LotState lotState(Holding h) {
        // 買進日之後（到 asOf 為止）的股數變動相乘；沒有買進日期時視為已是目前基準
        BigDecimal ratio = h.getBuyDate() == null ? BigDecimal.ONE : splitRatio(h.getBuyDate());
        BigDecimal shares = BigDecimal.valueOf(h.getShares()).multiply(ratio, MC);
        BigDecimal cost = money(h.getPrice().multiply(BigDecimal.valueOf(h.getShares())).add(h.getFee()));
        return new LotState(h, ratio, shares, cost);
    }

    private SaleState allocate(HoldingSale sale) {
        BigDecimal shares = BigDecimal.valueOf(sale.getShares()).multiply(splitRatio(sale.getSellDate()), MC);
        BigDecimal amount = sale.getPrice().multiply(BigDecimal.valueOf(sale.getShares()));
        BigDecimal proceeds = amount.subtract(sale.getFee()).subtract(sale.getTax());
        SaleState state = new SaleState(sale, shares, amount, proceeds);
        BigDecimal need = shares;
        for (LotState lot : lots) {
            if (need.signum() <= 0) {
                break;
            }
            LocalDate buyDate = lot.holding.getBuyDate();
            BigDecimal available = lot.shares.subtract(lot.sold);
            if ((buyDate != null && buyDate.isAfter(sale.getSellDate())) || available.compareTo(TOLERANCE) < 0) {
                continue;
            }
            BigDecimal taken = available.min(need);
            Piece piece = new Piece(lot, state, taken,
                    lot.cost.multiply(taken, MC).divide(lot.shares, MC),
                    proceeds.multiply(taken, MC).divide(shares, MC));
            lot.pieces.add(piece);
            lot.sold = lot.sold.add(taken);
            state.pieces.add(piece);
            need = need.subtract(taken);
        }
        state.unmatched = need.compareTo(TOLERANCE) < 0 ? BigDecimal.ZERO : need;
        return state;
    }

    // 某日之後（不含）到 asOf 為止的股數變動倍數
    private BigDecimal splitRatio(LocalDate after) {
        BigDecimal ratio = BigDecimal.ONE;
        for (StockAction action : actions) {
            if (action.getSplitRatio() != null && action.getExDate().isAfter(after) && (asOf == null || !action.getExDate().isAfter(asOf))) {
                ratio = ratio.multiply(action.getSplitRatio(), MC);
            }
        }
        return ratio;
    }

    // 除息日在 (after, upTo] 之間、且不晚於 asOf 的每股現金股利合計
    private BigDecimal dividendPerShare(LocalDate after, LocalDate upTo) {
        BigDecimal total = BigDecimal.ZERO;
        for (StockAction action : actions) {
            LocalDate exDate = action.getExDate();
            if (action.getCashDividend() != null && exDate.isAfter(after) && !exDate.isAfter(upTo) && (asOf == null || !exDate.isAfter(asOf))) {
                total = total.add(action.getCashDividend());
            }
        }
        return total;
    }

    // ---- 查詢 ----

    List<LotState> lots() {
        return lots;
    }

    List<SaleState> sales() {
        return sales;
    }

    /** 有賣出超過當時持有的股數（新增或修改後應擋下） */
    SaleState firstUnmatched() {
        return sales.stream().filter(s -> s.unmatched.signum() > 0).findFirst().orElse(null);
    }

    /** 目前還持有的股數（所有賣出都扣除） */
    BigDecimal remainingShares() {
        return lots.stream().map(LotState::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 某天收盤時整檔的股數：當天以前的賣出已扣除（尚未買進的也算在內，賣出成本依整檔估算時使用） */
    BigDecimal positionSharesAt(LocalDate day) {
        return lots.stream().map(l -> l.remainingAt(day)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 這筆賣出在某天是否已發生；asOf 當天（含）以後一律視為已發生（今天賣出、行情尚未更新時，庫存頁立刻反映）
    private boolean soldBy(Piece piece, LocalDate day) {
        return !piece.sale.sale.getSellDate().isAfter(day) || (asOf != null && !day.isBefore(asOf));
    }

    /**
     * 一筆買進在某天的價值：還持有的部分依整檔「市值 − 賣出成本」按股數分攤，加上到這天為止已領的股利，
     * 以及已賣出部分拿回的金額（扣手續費與證交稅，視為現金保留）。
     *
     * @param net            整檔在這天的市值 − 賣出成本（整檔股數為 0 時不使用）
     * @param positionShares positionSharesAt(day)
     */
    BigDecimal valueAt(LotState lot, LocalDate day, BigDecimal net, BigDecimal positionShares) {
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal remaining = lot.remainingAt(day);
        if (positionShares.signum() > 0 && remaining.signum() > 0) {
            value = net.multiply(remaining.divide(positionShares, MC), MC);
        }
        BigDecimal dividends = dividendsAt(lot, day);
        return value.add(dividends == null ? BigDecimal.ZERO : dividends).add(lot.proceedsAt(day));
    }

    /** 一筆買進到某天為止領到的現金股利（賣出的部分只算到賣出日）；沒有買進日期時為 null */
    BigDecimal dividendsAt(LotState lot, LocalDate day) {
        LocalDate buyDate = lot.holding.getBuyDate();
        if (buyDate == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal kept = lot.shares;
        for (Piece piece : lot.pieces) {
            if (soldBy(piece, day)) {
                LocalDate until = piece.sale.sale.getSellDate().isBefore(day) ? piece.sale.sale.getSellDate() : day;
                total = total.add(money(dividendPerShare(buyDate, until).multiply(piece.shares, MC)));
                kept = kept.subtract(piece.shares);
            }
        }
        return total.add(money(dividendPerShare(buyDate, day).multiply(kept, MC)));
    }

    /** 目前還持有的部分領到的股利（到 asOf）；沒有買進日期時為 null */
    BigDecimal keptDividends(LotState lot) {
        LocalDate buyDate = lot.holding.getBuyDate();
        return buyDate == null || asOf == null ? null : money(dividendPerShare(buyDate, asOf).multiply(lot.remaining(), MC));
    }

    /** 一筆賣出的股數持有期間（買進日之後到賣出日）領到的股利；來自沒有買進日期的買進的部分無法計算、不計入 */
    BigDecimal dividends(SaleState sale) {
        BigDecimal total = BigDecimal.ZERO;
        for (Piece piece : sale.pieces) {
            LocalDate buyDate = piece.lot.holding.getBuyDate();
            if (buyDate != null) {
                total = total.add(money(dividendPerShare(buyDate, sale.sale.getSellDate()).multiply(piece.shares, MC)));
            }
        }
        return total;
    }

    /** 這筆賣出的股數都來自有買進日期的買進（股利完整） */
    static boolean dividendsComplete(SaleState sale) {
        return sale.pieces.stream().allMatch(p -> p.lot.holding.getBuyDate() != null);
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // ---- 狀態 ----

    /** 一筆買進 */
    final class LotState {
        final Holding holding;
        final BigDecimal splitRatio;
        final BigDecimal shares; // 換算成目前基準的股數
        final BigDecimal cost;   // 價格 × 股數 + 手續費（到分）
        final List<Piece> pieces = new ArrayList<>();
        BigDecimal sold = BigDecimal.ZERO;

        LotState(Holding holding, BigDecimal splitRatio, BigDecimal shares, BigDecimal cost) {
            this.holding = holding;
            this.splitRatio = splitRatio;
            this.shares = shares;
            this.cost = cost;
        }

        BigDecimal remaining() {
            BigDecimal left = shares.subtract(sold);
            return left.compareTo(TOLERANCE) < 0 ? BigDecimal.ZERO : left;
        }

        /** 還持有的股數占這筆的比例對應的成本 */
        BigDecimal remainingCost() {
            return cost.multiply(remaining(), MC).divide(shares, MC);
        }

        BigDecimal remainingAt(LocalDate day) {
            BigDecimal left = shares;
            for (Piece piece : pieces) {
                if (soldBy(piece, day)) {
                    left = left.subtract(piece.shares);
                }
            }
            return left.compareTo(TOLERANCE) < 0 ? BigDecimal.ZERO : left;
        }

        BigDecimal proceedsAt(LocalDate day) {
            BigDecimal total = BigDecimal.ZERO;
            for (Piece piece : pieces) {
                if (soldBy(piece, day)) {
                    total = total.add(piece.proceeds);
                }
            }
            return total;
        }
    }

    /** 一筆賣出 */
    static final class SaleState {
        final HoldingSale sale;
        final BigDecimal shares;   // 換算成目前基準的股數
        final BigDecimal amount;   // 成交金額
        final BigDecimal proceeds; // 成交金額 − 手續費 − 證交稅
        final List<Piece> pieces = new ArrayList<>();
        BigDecimal unmatched = BigDecimal.ZERO; // 找不到可扣的買進（目前基準的股數）

        SaleState(HoldingSale sale, BigDecimal shares, BigDecimal amount, BigDecimal proceeds) {
            this.sale = sale;
            this.shares = shares;
            this.amount = amount;
            this.proceeds = proceeds;
        }

        BigDecimal cost() {
            return pieces.stream().map(p -> p.cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** 當時可賣的股數（賣出當時的基準） */
        BigDecimal availableShares() {
            BigDecimal factor = shares.divide(BigDecimal.valueOf(sale.getShares()), MC);
            return shares.subtract(unmatched).divide(factor, MC).setScale(0, RoundingMode.DOWN);
        }
    }

    /** 一筆賣出從一筆買進扣掉的部分 */
    record Piece(LotState lot, SaleState sale, BigDecimal shares, BigDecimal cost, BigDecimal proceeds) {
    }
}
