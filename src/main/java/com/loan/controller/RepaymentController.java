package com.loan.controller;

import com.loan.entity.dto.RepaymentSubmitDTO;
import com.loan.entity.vo.RepaymentPlanListVO;
import com.loan.entity.vo.RepaymentPlanVO;
import com.loan.entity.vo.Result;
import com.loan.service.RepaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/v1/loan/repayment")
@Tag(name = "还款管理模块", description = "查询还款计划、提交还款")
public class RepaymentController {

    @Resource
    private RepaymentService repaymentService;

    /**
     * 查询还款计划（需登录）
     */
    @GetMapping("/plan")
    @Operation(summary = "查询还款计划列表（贷款用户使用）", description = "返回用户的还款计划列表")
    public Result<List<RepaymentPlanVO>> queryRepaymentPlan(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return repaymentService.queryRepaymentPlan(userId);
    }

    /**
     * 查询还款计划（含每月明细）（使用applicationid）
     */
    @GetMapping("/plan/{applicationId}")
    @Operation(summary = "查询还款计划（含每月明细）")
    public Result<RepaymentPlanVO> queryRepaymentPlanDetail(HttpServletRequest request, @PathVariable String applicationId) {

        return repaymentService.queryRepaymentPlanDetail(applicationId);
    }

    /**
     * 提交还款（需登录）
     */
    @PostMapping("/submit")
    @Operation(summary = "提交还款", description = "扣减余额并更新还款状态")
    public Result<?> submitRepayment(@Valid @RequestBody RepaymentSubmitDTO repaymentDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return repaymentService.submitRepayment(repaymentDTO, userId);
    }

    /**
     * 查询所有还款计划列表（管理端使用，含用户信息和逾期状态）
     */
    @GetMapping("/plans/all")
    @Operation(summary = "查询所有还款计划列表（审批员使用）", description = "返回所有用户的还款计划列表，包含用户姓名、手机号和逾期状态")
    public Result<List<RepaymentPlanListVO>> queryAllRepaymentPlans() {
        return repaymentService.queryAllRepaymentPlans();
    }
}