package com.loan.controller;

import com.loan.entity.dto.DataSourceAuthorizeDTO;
import com.loan.entity.vo.Result;
import com.loan.service.DataSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 多数据源授权模块（授权留痕、可撤销）
 * 说明：本期为预留骨架，授权流程为模拟实现（凭证脱敏、不落明文敏感信息）。
 */
@RestController
@RequestMapping("/api/v1/data-source")
@Tag(name = "多数据源授权模块", description = "征信/运营商/社保/设备指纹的数据源授权、状态查询与撤销")
public class DataSourceController {

    @Resource
    private DataSourceService dataSourceService;

    @PostMapping("/authorize")
    @Operation(summary = "发起数据源授权", description = "对指定数据源发起授权（模拟），写入授权留痕并返回脱敏状态")
    public Result<?> authorize(@Valid @RequestBody DataSourceAuthorizeDTO dto,
                               HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return dataSourceService.authorize(userId, dto);
    }

    @GetMapping("/authorizations")
    @Operation(summary = "查询数据源授权状态", description = "查询当前用户各数据源的授权状态（未授权源返回 PENDING 占位）")
    public Result<?> listAuthorizations(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return dataSourceService.listAuthorizations(userId);
    }

    @PostMapping("/revoke")
    @Operation(summary = "撤销数据源授权", description = "撤销当前用户对指定数据源的授权")
    public Result<?> revoke(@Valid @RequestBody DataSourceAuthorizeDTO dto,
                            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return dataSourceService.revoke(userId, dto);
    }
}
