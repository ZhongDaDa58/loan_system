package com.loan.controller;

import com.loan.entity.dto.BindCardDTO;
import com.loan.entity.vo.Result;
import com.loan.service.MockBankCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bank-card")
@Tag(name = "模拟银行卡管理", description = "绑卡、解绑、查询")
public class BankCardController {

    @Resource
    private MockBankCardService cardService;

    @PostMapping("/bind")
    @Operation(summary = "绑定模拟银行卡", description = "四要素校验：姓名、身份证、卡号、手机号")
    public Result<?> bindCard(@Valid @RequestBody BindCardDTO dto, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String cardId = cardService.bindCard(userId, dto);
        return Result.success("绑卡成功，卡片ID: " + cardId);
    }

    @GetMapping("/list")
    @Operation(summary = "查询我的银行卡")
    public Result<?> listCards(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return Result.success(cardService.getUserCards(userId));
    }

    @PostMapping("/set-default/{cardId}")
    @Operation(summary = "设置默认银行卡")
    public Result<?> setDefault(@PathVariable Long cardId, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        cardService.setDefaultCard(userId, cardId);
        return Result.success("设置成功");
    }
}
