package com.ugi.stock.service;

import com.ugi.stock.dto.MyPlan;
import com.ugi.stock.dto.MyPlanResponse;
import com.ugi.stock.entity.UserPlan;
import com.ugi.stock.repository.UserPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static com.ugi.stock.service.HoldingService.badRequest;

/**
 * 我的計畫：首次設定的結果，每位使用者一筆。所有方法都以登入者的 userId 操作。
 * 建議怎麼算在前端（plan.js）；這裡只檢查並保存使用者確認後的設定。
 */
@Service
@RequiredArgsConstructor
public class MyPlanService {

    public static final BigDecimal MIN_CAPITAL = new BigDecimal("10000");
    public static final BigDecimal MAX_CAPITAL = new BigDecimal("10000000000");
    public static final BigDecimal MIN_LOSS = new BigDecimal("0.05");
    public static final BigDecimal MAX_LOSS = new BigDecimal("0.80");

    private final UserPlanRepository planRepository;

    @Transactional(readOnly = true)
    public MyPlanResponse get(long userId) {
        return new MyPlanResponse(planRepository.findById(userId).map(MyPlanService::toDto).orElse(null));
    }

    @Transactional
    public MyPlan save(long userId, MyPlan request) {
        if (request == null) {
            throw badRequest("缺少計畫內容");
        }
        if (request.version() == null || !PlanRules.VERSIONS.containsKey(request.version())) {
            throw badRequest("版本必須是 core（核心為主）、balanced（均衡）或 strategy（策略為主）");
        }
        if (request.frequency() == null || !PlanRules.FREQUENCIES.contains(request.frequency())) {
            throw badRequest("操作頻率必須是 daily（每天）或 weekly（每週）");
        }
        BigDecimal capital = request.capital();
        if (capital == null || capital.compareTo(MIN_CAPITAL) < 0 || capital.compareTo(MAX_CAPITAL) > 0 || capital.stripTrailingZeros().scale() > 0) {
            throw badRequest("本金請填 1 萬到 100 億之間的整數金額（元）");
        }
        BigDecimal maxLoss = request.maxLoss();
        if (maxLoss == null || maxLoss.compareTo(MIN_LOSS) < 0 || maxLoss.compareTo(MAX_LOSS) > 0 || maxLoss.stripTrailingZeros().scale() > 4) {
            throw badRequest("可承受的最大虧損請在 5% 到 80% 之間");
        }
        BigDecimal invest = request.investAmount();
        if (invest == null || invest.signum() < 0 || invest.compareTo(capital) > 0 || invest.stripTrailingZeros().scale() > 0) {
            throw badRequest("投入金額請填 0 到本金之間的整數金額（元）");
        }
        UserPlan plan = planRepository.findById(userId).orElseGet(() -> new UserPlan(userId));
        plan.setVersion(request.version());
        plan.setFrequency(request.frequency());
        plan.setCapital(capital);
        plan.setMaxLoss(maxLoss);
        plan.setInvestAmount(invest);
        plan.setUpdatedAt(Instant.now());
        return toDto(planRepository.saveAndFlush(plan));
    }

    @Transactional
    public void delete(long userId) {
        planRepository.deleteById(userId);
    }

    private static MyPlan toDto(UserPlan p) {
        return new MyPlan(p.getVersion(), p.getFrequency(), p.getCapital(), p.getMaxLoss(), p.getInvestAmount(), p.getUpdatedAt());
    }
}
