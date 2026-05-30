package com.loan.service;
import com.loan.entity.dto.RepaymentSubmitDTO;
import com.loan.entity.vo.RepaymentPlanListVO;
import com.loan.entity.vo.RepaymentPlanVO;
import com.loan.entity.vo.Result;

import java.util.List;

public interface RepaymentService {
    // 查询还款计划列表（不含含每月明细）
    Result<List<RepaymentPlanVO>> queryRepaymentPlan(String userId);
    // 查询还款计划（含每月明细）
    Result<RepaymentPlanVO> queryRepaymentPlanDetail(String userId);

    // 提交还款
    Result<?> submitRepayment(RepaymentSubmitDTO repaymentDTO, String userId);
    // 查询所有还款计划（审核员用）
    Result<List<RepaymentPlanListVO>> queryAllRepaymentPlans();
}