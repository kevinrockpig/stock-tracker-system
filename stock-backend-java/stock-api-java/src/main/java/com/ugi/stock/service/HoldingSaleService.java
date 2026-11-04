package com.ugi.stock.service;

import com.ugi.stock.dto.HoldingsResponse.Sale;
import com.ugi.stock.dto.SaleRequest;
import com.ugi.stock.entity.Holding;
import com.ugi.stock.entity.HoldingSale;
import com.ugi.stock.entity.Stock;
import com.ugi.stock.repository.HoldingRepository;
import com.ugi.stock.repository.HoldingSaleRepository;
import com.ugi.stock.repository.StockRepository;
import com.ugi.stock.service.LotLedger.SaleState;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import static com.ugi.stock.service.HoldingService.badRequest;

/**
 * 賣出紀錄的新增 / 修改 / 刪除。所有方法都以登入者的 userId 過濾，取不到（包括別人的）一律回 404。
 * 寫入前依先進先出重新分配這檔的所有賣出，任何一筆超過當時持有的股數就擋下（交易還原）。
 */
@Service
@RequiredArgsConstructor
public class HoldingSaleService {

    public static final int MAX_SALES = 1000;

    private final EntityManager entityManager;
    private final HoldingSaleRepository saleRepository;
    private final HoldingRepository holdingRepository;
    private final StockRepository stockRepository;
    private final HoldingService holdingService;

    @Transactional
    public Sale create(long userId, SaleRequest request) {
        if (saleRepository.countByUserId(userId) >= MAX_SALES) {
            throw badRequest("賣出紀錄最多 " + MAX_SALES + " 筆");
        }
        HoldingSale sale = new HoldingSale(userId);
        apply(userId, sale, request);
        List<HoldingSale> sales = new ArrayList<>(saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId));
        sales.add(sale);
        check(userId, sale, sales, List.of(sale.getSymbol()));
        saleRepository.saveAndFlush(sale);
        return describe(userId, sale);
    }

    @Transactional
    public Sale update(long userId, long id, SaleRequest request) {
        HoldingSale sale = find(userId, id);
        String oldSymbol = sale.getSymbol();
        // 先讀出全部紀錄（之後只在記憶體中檢查，不會先寫入資料庫）
        List<HoldingSale> sales = saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId);
        apply(userId, sale, request);
        try {
            holdingService.withoutAutoFlush(() -> check(userId, sale, sales, new TreeSet<>(List.of(oldSymbol, sale.getSymbol()))));
        } catch (ResponseStatusException e) {
            entityManager.refresh(sale); // 還原這筆的修改
            throw e;
        }
        sale.setUpdatedAt(Instant.now());
        saleRepository.flush();
        return describe(userId, sale);
    }

    // 刪除賣出只會讓可賣的股數變多，不需檢查
    @Transactional
    public void delete(long userId, long id) {
        saleRepository.delete(find(userId, id));
    }

    private HoldingSale find(long userId, long id) {
        return saleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這筆賣出"));
    }

    // 依先進先出重新分配受影響的股票；這筆或其他賣出超過當時持有的股數時擋下
    private void check(long userId, HoldingSale changed, List<HoldingSale> sales, Collection<String> symbols) {
        List<Holding> holdings = holdingRepository.findByUserIdOrderByIdAsc(userId);
        LocalDate asOf = holdingService.latestTradeDate();
        for (String symbol : symbols) {
            SaleState unmatched = holdingService.ledger(symbol, holdings, sales, asOf).firstUnmatched();
            if (unmatched == null) {
                continue;
            }
            HoldingSale bad = unmatched.sale;
            if (bad == changed) {
                throw badRequest("賣出 " + bad.getShares() + " 股超過 " + bad.getSellDate() + " 當時可賣的 " + unmatched.availableShares()
                        + " 股（只計當天以前買進的，並扣除之前的賣出）");
            }
            throw badRequest("這筆賣出會讓 " + bad.getSellDate() + " 賣出的 " + bad.getShares() + " 股超過當時持有的股數（"
                    + unmatched.availableShares() + " 股；先進先出會先扣較早的賣出），請改日期或股數");
        }
    }

    // 寫入後的這一筆（含先進先出的分配與損益）
    private Sale describe(long userId, HoldingSale saved) {
        LotLedger ledger = holdingService.ledger(saved.getSymbol(), holdingRepository.findByUserIdOrderByIdAsc(userId),
                saleRepository.findByUserIdOrderBySellDateAscIdAsc(userId), holdingService.latestTradeDate());
        SaleState state = ledger.sales().stream().filter(s -> s.sale.getId().equals(saved.getId())).findFirst().orElseThrow();
        String name = stockRepository.findById(saved.getSymbol()).map(Stock::getName).orElse(saved.getSymbol());
        return HoldingService.sale(ledger, state, name);
    }

    // 檢查輸入並寫入欄位；手續費與證交稅依方式計算後存下金額
    private void apply(long userId, HoldingSale sale, SaleRequest request) {
        if (request == null) {
            throw badRequest("缺少賣出內容");
        }
        String symbol = HoldingService.symbol(request.symbol());
        if (!holdingRepository.existsByUserIdAndSymbol(userId, symbol)) {
            throw badRequest("沒有 " + symbol + " 的買進紀錄");
        }
        LocalDate sellDate = request.sellDate();
        if (sellDate == null) {
            throw badRequest("請填寫賣出日期");
        }
        if (sellDate.isAfter(LocalDate.now(HoldingService.TAIPEI))) {
            throw badRequest("賣出日期不能晚於今天");
        }
        BigDecimal price = HoldingService.checkPrice(request.price());
        int shares = HoldingService.checkShares(request.shares());
        BigDecimal amount = price.multiply(BigDecimal.valueOf(shares));
        HoldingService.Fee fee = HoldingService.fee(request.feeMode(), request.feeDiscount(), request.minFee(), request.fee(), amount);

        String taxMode = Optional.ofNullable(request.taxMode()).map(String::trim).orElse("");
        BigDecimal tax = switch (taxMode) {
            case "standard" -> TradingCosts.tax(amount, symbol);
            case "actual" -> {
                HoldingService.checkMoney(request.tax(), "請填寫實際證交稅（0 以上、最多 2 位小數）");
                yield request.tax();
            }
            default -> throw badRequest("證交稅方式必須是 standard（依稅率計算）或 actual（實際金額）");
        };

        sale.setSymbol(symbol);
        sale.setSellDate(sellDate);
        sale.setPrice(price);
        sale.setShares(shares);
        sale.setFeeMode(fee.mode());
        sale.setFeeDiscount(fee.discount());
        sale.setMinFee(fee.minFee());
        sale.setFee(fee.amount());
        sale.setTaxMode(taxMode);
        sale.setTax(tax.setScale(2, RoundingMode.HALF_UP));
    }
}
