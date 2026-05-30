package com.loan.controller;

import com.loan.entity.dto.RepaymentPayDTO;
import com.loan.entity.vo.Result;
import com.loan.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/payment")
@Tag(name = "模拟支付网关", description = "放款与还款处理")
public class PaymentController {

    @Resource
    private PaymentService paymentService;

    @PostMapping("/disburse")
    @Operation(summary = "模拟放款到银行卡")
    public Result<?> disburse(@RequestParam String applicationId,
                              @RequestParam Long cardId,
                              @RequestParam BigDecimal amount,
                              HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return paymentService.initiateDisbursement(applicationId, userId, cardId, amount);
    }

    @PostMapping("/repay")
    @Operation(summary = "模拟银行卡还款")
    public Result<?> repay(@RequestBody RepaymentPayDTO dto, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        // 这里的 amount 应该从还款计划中获取，简化起见由前端传入或后端查询
        return paymentService.initiateRepayment("ORDER_" + dto.getPlanId(), userId, dto.getCardId(), new BigDecimal("1000"));
    }
}
