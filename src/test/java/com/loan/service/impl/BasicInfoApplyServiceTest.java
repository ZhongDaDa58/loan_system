package com.loan.service.impl;

import com.loan.entity.LoanApplication;
import com.loan.entity.LoanProduct;
import com.loan.entity.RiskDecisionLog;
import com.loan.entity.dto.FeatureTransformationResultDTO;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.scorecard.ProductThresholdResolver;
import com.loan.service.CreditScoreSyncService;
import com.loan.service.DataValidationService;
import com.loan.service.FeatureTransformationService;
import com.loan.service.RiskDecisionLogService;
import com.loan.service.ScorecardRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BasicInfoApplyService} 的单元测试（阶段四收尾）
 *
 * <p><b>为什么值得测</b>：覆盖率只有 3%（75 行），但它是**另一条申请提交链路** ——
 * 用户填完整表单直接申请（不走信用档案）。这条链路之前完全没有测试，
 * 而它写的是 `loan_application` 主表。
 *
 * <p><b>这条链路与 submitApply 的关键差异</b>：
 * <ul>
 *   <li>有<b>数据校验</b>和<b>特征转换</b>两道前置闸（失败要抛 400）</li>
 *   <li><b>没有</b> L1/L3 规则评估（只按分数阈值决策，是已知的功能差异）</li>
 *   <li>会<b>回写信用档案</b>（下次申请可复用）</li>
 * </ul>
 *
 * <p><b>测什么</b>：
 * <ul>
 *   <li>两道前置闸失败 → 400，且<b>不能建单</b></li>
 *   <li>三种决策 → 对应三种状态码 + 只有 AUTO_PASS 生成合同</li>
 *   <li>insert 影响行数不是 1 → 500</li>
 *   <li>阈值和模型版本要写进决策日志（可追溯性）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("完整信息链路提交 BasicInfoApplyService")
class BasicInfoApplyServiceTest {

    @Mock private ApplicationEligibilityChecker eligibilityChecker;
    @Mock private DataValidationService dataValidationService;
    @Mock private FeatureTransformationService featureTransformationService;
    @Mock private ScorecardRoutingService scorecardRoutingService;
    @Mock private ProductThresholdResolver productThresholdResolver;
    @Mock private LoanApplicationMapper loanApplicationMapper;
    @Mock private RiskDecisionLogService riskDecisionLogService;
    @Mock private CreditScoreSyncService creditScoreSyncService;
    @Mock private CreditProfileAutoSaveService creditProfileAutoSaveService;
    @Mock private ContractDraftService contractDraftService;

    @InjectMocks private BasicInfoApplyService service;

    private UserBasicInfoDTO dto;
    private UserProfileDTO profile;
    private LoanProduct product;
    private ProductThresholdResolver.ResolvedThresholds thresholds;

    @BeforeEach
    void setUp() {
        dto = new UserBasicInfoDTO();
        dto.setProductId("product_001");
        dto.setApplyAmount(new BigDecimal("10000"));
        dto.setApplyTerm(12);
        dto.setDisbursementCardId(99L);

        profile = new UserProfileDTO();
        profile.setAge(35);

        product = new LoanProduct();
        product.setProductId("product_001");

        thresholds = new ProductThresholdResolver.ResolvedThresholds(700, 600, "product_config");

        // 默认：准入通过
        when(eligibilityChecker.checkBasics(anyString(), anyString(), any(), any(), any()))
                .thenReturn(new ApplicationEligibilityChecker.BasicEligibility(
                        product, new BigDecimal("10000"), 12, 99L));
        // 默认：校验通过、转换成功
        when(dataValidationService.validateInputData(any())).thenReturn(List.of());
        when(dataValidationService.validateLogicalConsistency(any())).thenReturn(List.of());
        when(featureTransformationService.transformToUserProfile(any()))
                .thenReturn(FeatureTransformationResultDTO.success(profile, java.util.Map.of()));
        // 默认：阈值
        when(productThresholdResolver.resolve(any())).thenReturn(thresholds);
        // 默认：建单成功
        when(loanApplicationMapper.insert(any())).thenReturn(1);
        // 默认：决策日志
        when(riskDecisionLogService.buildLogFromTransformation(
                anyString(), anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new RiskDecisionLog());
    }

    /** 配置评分结果 */
    private void givenScore(int score) {
        when(scorecardRoutingService.score(anyString(), any()))
                .thenReturn(new ScorecardRoutingService.ScoreOutcome(
                        score, "v4", "v4.0", 0, 6, "multi_source"));
    }

    // ==================== 两道前置闸 ====================

    @Nested
    @DisplayName("前置闸：数据校验与特征转换")
    class PreChecks {

        @Test
        @DisplayName("数据校验失败 → 400，且不建单")
        void validationFails() {
            when(dataValidationService.validateInputData(any()))
                    .thenReturn(List.of("身份证格式错误", "月收入不能为负"));

            assertThatThrownBy(() -> service.submit(dto, "u1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("数据验证失败")
                    .hasMessageContaining("身份证格式错误");

            verify(loanApplicationMapper, never()).insert(any());
        }

        @Test
        @DisplayName("特征转换失败 → 400，且不建单")
        void transformationFails() {
            when(featureTransformationService.transformToUserProfile(any()))
                    .thenReturn(FeatureTransformationResultDTO.failure("缺少月收入"));

            assertThatThrownBy(() -> service.submit(dto, "u1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("特征转换失败")
                    .hasMessageContaining("缺少月收入");

            verify(loanApplicationMapper, never()).insert(any());
        }
    }

    // ==================== 三种决策分支 ====================

    @Nested
    @DisplayName("决策分支（只按分数阈值，无 L1/L3 规则）")
    class DecisionBranches {

        @Test
        @DisplayName("高分 → AUTO_PASS：状态 APPROVED + 生成合同 + 消息「自动通过」")
        void autoPass() {
            givenScore(750);

            Result<?> r = service.submit(dto, "u1");

            ArgumentCaptor<LoanApplication> cap = ArgumentCaptor.forClass(LoanApplication.class);
            verify(loanApplicationMapper).insert(cap.capture());
            LoanApplication app = cap.getValue();

            assertThat(app.getApplicationStatus()).isEqualTo(ApplicationStatusEnum.APPROVED.getCode());
            assertThat(app.getScorecardDecision()).isEqualTo("AUTO_APPROVE");
            assertThat(r.getData().toString()).contains("自动通过");

            verify(contractDraftService).generateQuietly(app.getApplicationId());
        }

        @Test
        @DisplayName("中分 → MANUAL_REVIEW：状态 PENDING + 不生成合同 + 消息「等待人工审核」")
        void manualReview() {
            givenScore(650);

            Result<?> r = service.submit(dto, "u1");

            ArgumentCaptor<LoanApplication> cap = ArgumentCaptor.forClass(LoanApplication.class);
            verify(loanApplicationMapper).insert(cap.capture());
            LoanApplication app = cap.getValue();

            assertThat(app.getApplicationStatus()).isEqualTo(ApplicationStatusEnum.PENDING.getCode());
            assertThat(app.getScorecardDecision()).isEqualTo("MANUAL_REVIEW");
            assertThat(r.getData().toString()).contains("等待人工审核");

            verify(contractDraftService, never()).generateQuietly(anyString());
        }

        @Test
        @DisplayName("低分 → AUTO_REJECT：状态 REJECTED + 不生成合同 + 消息「已被拒绝」")
        void autoReject() {
            givenScore(500);

            Result<?> r = service.submit(dto, "u1");

            ArgumentCaptor<LoanApplication> cap = ArgumentCaptor.forClass(LoanApplication.class);
            verify(loanApplicationMapper).insert(cap.capture());
            LoanApplication app = cap.getValue();

            assertThat(app.getApplicationStatus()).isEqualTo(ApplicationStatusEnum.REJECTED.getCode());
            assertThat(app.getScorecardDecision()).isEqualTo("REJECT");
            assertThat(r.getData().toString()).contains("已被拒绝");

            verify(contractDraftService, never()).generateQuietly(anyString());
        }

        @Test
        @DisplayName("阈值边界：正好等于 autoPassScore(700) → AUTO_PASS")
        void thresholdBoundary() {
            givenScore(700);
            service.submit(dto, "u1");

            ArgumentCaptor<LoanApplication> cap = ArgumentCaptor.forClass(LoanApplication.class);
            verify(loanApplicationMapper).insert(cap.capture());
            assertThat(cap.getValue().getScorecardDecision()).isEqualTo("AUTO_APPROVE");
        }

        @Test
        @DisplayName("阈值边界：正好等于 manualReviewScore(600) → MANUAL_REVIEW（不是 REJECT）")
        void manualBoundary() {
            givenScore(600);
            service.submit(dto, "u1");

            ArgumentCaptor<LoanApplication> cap = ArgumentCaptor.forClass(LoanApplication.class);
            verify(loanApplicationMapper).insert(cap.capture());
            assertThat(cap.getValue().getScorecardDecision()).isEqualTo("MANUAL_REVIEW");
        }
    }

    // ==================== 落库失败 ====================

    @Test
    @DisplayName("insert 影响行数不是 1 → 500「申请提交失败」")
    void insertFails() {
        givenScore(750);
        when(loanApplicationMapper.insert(any())).thenReturn(0);

        assertThatThrownBy(() -> service.submit(dto, "u1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("申请提交失败");
    }

    // ==================== 建单字段 ====================

    @Nested
    @DisplayName("建单字段组装")
    class EntityFields {

        @Test
        @DisplayName("金额/期限/银行卡/分数都来自准入校验结果与评分结果")
        void basicFields() {
            givenScore(720);
            service.submit(dto, "u1");

            ArgumentCaptor<LoanApplication> cap = ArgumentCaptor.forClass(LoanApplication.class);
            verify(loanApplicationMapper).insert(cap.capture());
            LoanApplication app = cap.getValue();

            assertThat(app.getApplicationId()).isNotBlank();
            assertThat(app.getUserId()).isEqualTo("u1");
            assertThat(app.getProductId()).isEqualTo("product_001");
            assertThat(app.getApplyAmount()).isEqualByComparingTo("10000");
            assertThat(app.getApplyTerm()).isEqualTo(12);
            assertThat(app.getDisbursementCardId()).isEqualTo(99L);
            assertThat(app.getScorecardScore()).isEqualTo(720);
            assertThat(app.getCreditScore()).isEqualTo(720);   // 这条链路没有档案分，直接用本次评分
            assertThat(app.getApplyTime()).isNotNull();
        }
    }

    // ==================== 副作用 ====================

    @Nested
    @DisplayName("副作用：留痕 / 同步 / 档案回写")
    class SideEffects {

        @Test
        @DisplayName("阈值与模型版本写进决策日志（可追溯）")
        void riskLogFields() {
            givenScore(750);
            RiskDecisionLog log = new RiskDecisionLog();
            when(riskDecisionLogService.buildLogFromTransformation(
                    anyString(), anyString(), any(), anyString(), any(), any(), any()))
                    .thenReturn(log);

            service.submit(dto, "u1");

            assertThat(log.getAppliedAutoPass()).isEqualTo(700);
            assertThat(log.getAppliedManualReview()).isEqualTo(600);
            assertThat(log.getThresholdSource()).isEqualTo("product_config");
            assertThat(log.getModelVersion()).isEqualTo("v4");
            assertThat(log.getProductId()).isEqualTo("product_001");
            verify(riskDecisionLogService).logDecision(log);
        }

        @Test
        @DisplayName("信用分同步被调用")
        void creditScoreSync() {
            givenScore(750);
            service.submit(dto, "u1");
            verify(creditScoreSyncService).syncScorecardToCreditScore(eq("u1"), any(), anyString());
        }

        @Test
        @DisplayName("信用档案回写被调用（下次申请可复用）")
        void profileAutoSave() {
            givenScore(750);
            service.submit(dto, "u1");
            verify(creditProfileAutoSaveService).saveOrUpdate(eq("u1"), eq(dto), eq(profile), anyInt());
        }
    }
}
