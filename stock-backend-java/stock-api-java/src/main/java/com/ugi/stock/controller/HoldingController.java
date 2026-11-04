package com.ugi.stock.controller;

import com.ugi.stock.dto.HoldingHistoryResponse;
import com.ugi.stock.dto.HoldingRequest;
import com.ugi.stock.dto.HoldingsResponse;
import com.ugi.stock.dto.SaleRequest;
import com.ugi.stock.security.AppUserPrincipal;
import com.ugi.stock.service.HoldingHistoryService;
import com.ugi.stock.service.HoldingSaleService;
import com.ugi.stock.service.HoldingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登入者自己的庫存（需要登入，見 SecurityConfig）。使用者一律取自登入狀態，不接受前端傳來的使用者識別；
 * 不允許跨網域呼叫（沒有 @CrossOrigin），前端經由同一個來源呼叫。
 */
@RestController
@RequestMapping("/api/holdings")
@RequiredArgsConstructor
public class HoldingController {

    private final HoldingService holdingService;
    private final HoldingHistoryService historyService;
    private final HoldingSaleService saleService;

    @GetMapping
    public HoldingsResponse getHoldings(@AuthenticationPrincipal AppUserPrincipal user) {
        return holdingService.getHoldings(user.getUserId());
    }

    // 資產走勢：每日的投入成本、庫存價值與同期 0050 的價值
    @GetMapping("/history")
    public HoldingHistoryResponse getHistory(@AuthenticationPrincipal AppUserPrincipal user) {
        return historyService.getHistory(user.getUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldingsResponse.Lot create(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody HoldingRequest request) {
        return holdingService.create(user.getUserId(), request);
    }

    @PutMapping("/{id}")
    public HoldingsResponse.Lot update(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long id,
                                       @RequestBody HoldingRequest request) {
        return holdingService.update(user.getUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long id) {
        holdingService.delete(user.getUserId(), id);
    }

    // 賣出紀錄：從哪幾筆買進扣（先進先出）與已實現損益在 GET /api/holdings 的 realized 中
    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldingsResponse.Sale createSale(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody SaleRequest request) {
        return saleService.create(user.getUserId(), request);
    }

    @PutMapping("/sales/{id}")
    public HoldingsResponse.Sale updateSale(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long id,
                                            @RequestBody SaleRequest request) {
        return saleService.update(user.getUserId(), id, request);
    }

    @DeleteMapping("/sales/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSale(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable long id) {
        saleService.delete(user.getUserId(), id);
    }
}
