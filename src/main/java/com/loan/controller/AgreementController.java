package com.loan.controller;

import com.loan.entity.dto.AgreementSignDTO;
import com.loan.entity.vo.Result;
import com.loan.service.AgreementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agreement")
@Tag(name = "用户协议模块", description = "协议查看、签署、历史记录")
public class AgreementController {

    @Resource
    private AgreementService agreementService;

    /**
     * 获取协议列表（含签署状态）
     */
    @GetMapping("/list")
    @Operation(summary = "获取协议列表", description = "返回所有需要签署的协议及当前用户的签署状态")
    public Result<?> getAgreementList(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return agreementService.getAgreementList(userId);
    }

    /**
     * 查看协议详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "查看协议详情", description = "查看指定协议的完整内容")
    public Result<?> getAgreementDetail(@PathVariable Long id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return agreementService.getAgreementDetail(id, userId);
    }

    /**
     * 签署协议
     */
    @PostMapping("/sign")
    @Operation(summary = "签署协议", description = "用户签署指定协议，需传入签名图片URL")
    public Result<?> signAgreement(@Valid @RequestBody AgreementSignDTO signDTO,
                                   HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String ipAddress = getClientIp(request);
        return agreementService.signAgreement(
                userId,
                signDTO.getAgreementId(),
                signDTO.getSignatureImageUrl(),
                ipAddress,
                signDTO.getDeviceInfo()
        );
    }

    /**
     * 检查是否已完成所有协议签署
     */
    @GetMapping("/check-all-signed")
    @Operation(summary = "检查签署状态", description = "检查用户是否已签署所有必需协议")
    public Result<?> checkAllSigned(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return agreementService.checkAllAgreementsSigned(userId);
    }

    /**
     * 获取我的签署历史
     */
    @GetMapping("/my-history")
    @Operation(summary = "签署历史", description = "获取当前用户的所有协议签署记录")
    public Result<?> getMyHistory(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return agreementService.getMySignHistory(userId);
    }

    /**
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
