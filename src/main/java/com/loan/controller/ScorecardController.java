
package com.loan.controller;

import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.ScorecardResultVO;
import com.loan.service.DataValidationService;
import com.loan.service.FeatureTransformationService;
import com.loan.service.ScorecardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scorecard")
@Tag(name = "评分卡模块", description = "基于机器学习的贷款评分卡系统")
public class ScorecardController {

    @Resource
    private ScorecardService scorecardService;

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private DataValidationService dataValidationService;

    @PostMapping("/calculate")
    @Operation(summary = "计算信用评分", description = "根据用户画像数据计算信用评分和审批决策")
    public Result<ScorecardResultVO> calculateScore(@Valid @RequestBody UserProfileDTO userProfile) {
        ScorecardResultVO result = scorecardService.calculateScore(userProfile);
        return Result.success(result);
    }

    @PostMapping("/score-only")
    @Operation(summary = "仅计算分数", description = "只返回信用分数，不包含详细决策信息")
    public Result<Integer> calculateScoreOnly(@Valid @RequestBody UserProfileDTO userProfile) {
        Integer score = scorecardService.calculateScoreOnly(userProfile);
        return Result.success(score);
    }

    @PostMapping("/calculate-with-basic-info")
    @Operation(summary = "通过基础信息计算评分", description = "接收用户基础信息，自动转换为特征并计算评分")
    public Result<ScorecardResultVO> calculateScoreWithBasicInfo(@Valid @RequestBody UserBasicInfoDTO request) {
        var validationErrors = dataValidationService.validateInputData(request);
        if (!validationErrors.isEmpty()) {
            return Result.error(400, "数据验证失败: " + String.join(", ", validationErrors));
        }

        var transformationResult = featureTransformationService.transformToUserProfile(request);
        if (!transformationResult.getIsValid()) {
            return Result.error(400, transformationResult.getErrorMessage());
        }

        ScorecardResultVO result = scorecardService.calculateScore(transformationResult.getUserProfile());
        return Result.success(result);
    }

    @PostMapping("/transform-features")
    @Operation(summary = "特征转换预览", description = "将用户基础信息转换为评分卡特征，不执行评分")
    public Result<Object> transformFeatures(@Valid @RequestBody UserBasicInfoDTO request) {
        var validationErrors = dataValidationService.validateInputData(request);
        if (!validationErrors.isEmpty()) {
            return Result.error(400, "数据验证失败: " + String.join(", ", validationErrors));
        }

        var transformationResult = featureTransformationService.transformToUserProfile(request);
        if (!transformationResult.getIsValid()) {
            return Result.error(400, transformationResult.getErrorMessage());
        }

        var consistencyWarnings = dataValidationService.validateLogicalConsistency(request);
        var businessRuleViolations = dataValidationService.validateBusinessRules(request);

        var response = new java.util.HashMap<String, Object>();
        response.put("userProfile", transformationResult.getUserProfile());
        response.put("transformationDetails", transformationResult.getTransformationDetails());
        response.put("consistencyWarnings", consistencyWarnings);
        response.put("businessRuleViolations", businessRuleViolations);

        return Result.success(response);
    }
}
