package com.loan.controller;

import com.loan.entity.MockFinancialProfile;
import com.loan.entity.vo.Result;
import com.loan.service.FinancialInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 经济信息获取接口（模拟"银行/征信/社保"数据源）
 *
 * 目标：替代"用户手填收入负债"——按用户身份自动拉取模拟经济信息，回填信用档案并派生多源特征。
 * 说明：手填建档流程原样保留，本接口只是新增一条更接近真实业务的路径。
 */
@RestController
@RequestMapping("/api/v1/financial-info")
@Tag(name = "经济信息获取（模拟）", description = "模拟银行/征信侧经济画像：预览、回填档案、派生多源特征")
public class FinancialInfoController {

    @Resource
    private FinancialInfoService financialInfoService;

    @GetMapping("/preview")
    @Operation(summary = "预览经济信息", description = "按当前登录用户手机号从模拟库拉取（无记录则自动生成），返回收入/负债/资产/社保/职业与将派生的多源特征")
    public Result<?> preview(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return financialInfoService.preview(userId);
    }

    @PostMapping("/apply")
    @Operation(summary = "应用经济信息", description = "写入信用档案（自动重算特征与评分）并派生多源特征；请求体可选——App 端把\"获取后用户编辑确认\"的数据传回，为空则用模拟库原始数据")
    public Result<?> apply(HttpServletRequest request,
                           @RequestBody(required = false) com.loan.entity.dto.CreditProfileDTO dto) {
        String userId = (String) request.getAttribute("userId");
        return financialInfoService.apply(userId, dto);
    }

    @GetMapping("/list")
    @Operation(summary = "（管理端）模拟库列表")
    public Result<?> list() {
        return financialInfoService.listMock();
    }

    @PostMapping("/mock")
    @Operation(summary = "（管理端）新增/更新模拟金融信息", description = "按 matchKey（手机号）匹配，存在则更新")
    public Result<?> upsert(@RequestBody MockFinancialProfile profile) {
        return financialInfoService.upsertMock(profile);
    }

    @DeleteMapping("/mock/{id}")
    @Operation(summary = "（管理端）删除模拟金融信息")
    public Result<?> delete(@PathVariable("id") Long id) {
        return financialInfoService.deleteMock(id);
    }
}
