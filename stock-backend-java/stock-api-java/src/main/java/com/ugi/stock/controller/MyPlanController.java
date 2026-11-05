package com.ugi.stock.controller;

import com.ugi.stock.dto.MyPlan;
import com.ugi.stock.dto.MyPlanResponse;
import com.ugi.stock.security.AppUserPrincipal;
import com.ugi.stock.service.MyPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的計畫（需要登入，見 SecurityConfig）。未登入時前端把計畫存在瀏覽器，登入後再存到這裡。
 */
@RestController
@RequestMapping("/api/my-plan")
@RequiredArgsConstructor
public class MyPlanController {

    private final MyPlanService planService;

    @GetMapping
    public MyPlanResponse get(@AuthenticationPrincipal AppUserPrincipal user) {
        return planService.get(user.getUserId());
    }

    @PutMapping
    public MyPlan save(@AuthenticationPrincipal AppUserPrincipal user, @RequestBody MyPlan plan) {
        return planService.save(user.getUserId(), plan);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUserPrincipal user) {
        planService.delete(user.getUserId());
    }
}
