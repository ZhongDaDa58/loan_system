package com.loan.controller;

import com.loan.entity.vo.LoanOverviewVO;
import com.loan.entity.vo.Result;
import com.loan.service.LoanOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan")
@Tag(name = "贷款概况模块", description = "用户贷款总览：汇总信息 + 各笔贷款详情")
public class LoanOverviewController {

    @Resource
    private LoanOverviewService loanOverviewService;

    @GetMapping("/overview")
    @Operation(summary = "查询贷款概况", description = "获取当前登录用户的贷款汇总信息和各笔贷款明细")
    public Result<LoanOverviewVO> getLoanOverview(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return loanOverviewService.getLoanOverview(userId);
    }
}
