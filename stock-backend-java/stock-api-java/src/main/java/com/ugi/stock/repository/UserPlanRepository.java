package com.ugi.stock.repository;

import com.ugi.stock.entity.UserPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// 主鍵即 userId：只會取得登入者自己的計畫
@Repository
public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {
}
