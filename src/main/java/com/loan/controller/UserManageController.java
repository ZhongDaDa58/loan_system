package com.loan.controller;

import com.loan.entity.vo.*;
import com.loan.service.UserManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "用户管理模块", description = "用户列表、用户详情及相关子信息查询")
public class UserManageController {

    @Resource
    private UserManageService userManageService;

    @GetMapping
    @Operation(summary = "用户列表分页查询", description = "支持手机号/姓名模糊搜索、注册日期范围筛选、认证状态过滤、排序")
    public Result<PageResult<UserItemVO>> listUsers(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String verifyStatus,
            @RequestParam(defaultValue = "1") @Parameter(description = "页码，从1开始") Integer page,
            @RequestParam(defaultValue = "20") @Parameter(description = "每页条数") Integer pageSize,
            @RequestParam(required = false) @Parameter(description = "排序字段：registerTime / creditScore") String sortBy,
            @RequestParam(required = false) @Parameter(description = "排序方向：asc / desc") String sortOrder) {
        return userManageService.listUsers(phone, name, startDate, endDate,
                verifyStatus, page, pageSize, sortBy, sortOrder);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "获取用户详情", description = "用户详情页「基本信息」标签页数据")
    public Result<UserDetailVO> getUserDetail(@PathVariable String userId) {
        return userManageService.getUserDetail(userId);
    }

    @GetMapping("/{userId}/verification")
    @Operation(summary = "获取用户实名认证信息", description = "用户详情页「实名认证」标签页数据")
    public Result<UserVerificationVO> getUserVerification(@PathVariable String userId) {
        return userManageService.getUserVerification(userId);
    }

    @GetMapping("/{userId}/bankcards")
    @Operation(summary = "获取用户银行卡列表", description = "用户详情页「银行卡」标签页数据")
    public Result<List<UserBankCardVO>> getUserBankCards(@PathVariable String userId) {
        return userManageService.getUserBankCards(userId);
    }

    @GetMapping("/{userId}/credit-history")
    @Operation(summary = "获取用户信用分历史", description = "用户详情页「信用分历史」标签页数据，按时间正序返回近12个月记录")
    public Result<List<CreditHistoryPointVO>> getCreditHistory(@PathVariable String userId) {
        return userManageService.getCreditHistory(userId);
    }

    @GetMapping("/{userId}/loans")
    @Operation(summary = "获取用户贷款申请历史", description = "用户详情页「贷款历史」标签页数据")
    public Result<List<UserLoanRecordVO>> getUserLoans(@PathVariable String userId) {
        return userManageService.getUserLoans(userId);
    }

    @GetMapping("/{userId}/repayments")
    @Operation(summary = "获取用户还款记录", description = "用户详情页「还款记录」标签页数据")
    public Result<List<RepaymentRecordVO>> getUserRepayments(@PathVariable String userId) {
        return userManageService.getUserRepayments(userId);
    }
}
