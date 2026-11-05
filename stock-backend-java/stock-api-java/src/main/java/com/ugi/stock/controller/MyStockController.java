package com.ugi.stock.controller;

import com.ugi.stock.dto.MyStockRequest;
import com.ugi.stock.dto.MyStocksResponse;
import com.ugi.stock.security.AppUserPrincipal;
import com.ugi.stock.service.MyStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的自選（需要登入，見 SecurityConfig）：加入觀察名單以外的股票，下一次每日更新後就有 K 線、指標與參考價位，也可以加入庫存。
 */
@RestController
@RequestMapping("/api/my-stocks")
@RequiredArgsConstructor
public class MyStockController {

    private final MyStockService myStockService;

    @GetMapping
    public MyStocksResponse list(@AuthenticationPrincipal AppUserPrincipal user) {
        return myStockService.list(user.getUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MyStocksResponse.Item add(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody MyStockRequest request) {
        return myStockService.add(user.getUserId(), request.code());
    }

    // symbol 含市場別，例如 6488.TWO
    @DeleteMapping("/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable String symbol) {
        myStockService.remove(user.getUserId(), symbol);
    }
}
