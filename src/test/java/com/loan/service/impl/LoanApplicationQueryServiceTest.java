package com.loan.service.impl;

import com.loan.convert.LoanApplicationBasicMapper;
import com.loan.entity.LoanApplication;
import com.loan.entity.LoanProduct;
import com.loan.entity.RiskDecisionLog;
import com.loan.entity.SysUser;
import com.loan.entity.UserBankCard;
import com.loan.entity.UserIdentity;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.loanDetail.ApplicantIdentityVO;
import com.loan.entity.vo.loanDetail.LoanApplicationBasicVO;
import com.loan.entity.vo.loanDetail.ScorecardDetailVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.SysUserMapper;
import com.loan.mapper.UserBankCardMapper;
import com.loan.mapper.UserIdentityMapper;
import com.loan.service.ContractService;
import com.loan.service.LoanProductService;
import com.loan.service.RiskDecisionLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoanApplicationQueryService} 的单元测试（阶段四收尾）
 *
 * <p><b>为什么用 Mockito 而不是 MockMvc</b>：
 * 这些方法是<b>只读查询 + 展示组装</b>，业务分支全在 Service 内部
 * （申请不存在 → 404、产品为空 → 跳过字段、无档案 → 空值兜底）。
 * 用 Mockito 直接测这些分支比走 HTTP 快得多，也不需要数据库、
 * 不依赖 Docker（本机 Docker 不稳定，Testcontainers 时常跑不起来）。
 *
 * <p><b>测什么</b>：
 * <ul>
 *   <li><b>404 分支</b>：申请不存在 → 抛 BusinessException(404)</li>
 *   <li><b>空值兜底</b>：产品查不到、银行卡查不到、身份信息缺失时不能 NPE</li>
 *   <li><b>脱敏</b>：手机号/身份证/卡号出参必须是脱敏后的（这是安全要求，不只是展示）</li>
 *   <li><b>合同兜底</b>：已通过但无合同 → 触发补生成</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("申请查询 LoanApplicationQueryService")
class LoanApplicationQueryServiceTest {

    @Mock private LoanApplicationMapper loanApplicationMapper;
    @Mock private LoanProductService loanProductService;
    @Mock private RiskDecisionLogService riskDecisionLogService;
    @Mock private ContractService contractService;
    @Mock private UserBankCardMapper userBankCardMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserIdentityMapper userIdentityMapper;
    @Mock private LoanApplicationBasicMapper basicMapper;

    @InjectMocks private LoanApplicationQueryService service;

    private LoanApplication app(String id, String userId) {
        LoanApplication a = new LoanApplication();
        a.setApplicationId(id);
        a.setUserId(userId);
        a.setProductId("product_001");
        a.setApplyAmount(new BigDecimal("10000"));
        a.setApplyTerm(12);
        a.setApplicationStatus(ApplicationStatusEnum.APPROVED.getCode());
        return a;
    }

    // ==================== queryApplyProgress ====================

    @Nested
    @DisplayName("用户进度 queryApplyProgress")
    class QueryProgress {

        @Test
        @DisplayName("空列表 → 返回空（不触发合同补生成）")
        void emptyList() {
            when(loanApplicationMapper.selectByUserId("u1")).thenReturn(List.of());
            Result<List<LoanApplicationVO>> r = service.queryApplyProgress("u1");
            assertThat(r.getData()).isEmpty();
            verify(contractService, never()).generateDraftContract(anyString());
        }

        @Test
        @DisplayName("已通过但无合同 → 触发补生成，并回填 contractPath")
        void repairMissingContract() {
            LoanApplicationVO vo = new LoanApplicationVO();
            vo.setApplicationId("app1");
            vo.setApplicationStatus(ApplicationStatusEnum.APPROVED.getCode());
            vo.setContractPath(null);
            when(loanApplicationMapper.selectByUserId("u1")).thenReturn(List.of(vo));

            when(contractService.generateDraftContract("app1")).thenReturn(Result.success("ok"));
            LoanApplication latest = app("app1", "u1");
            latest.setContractPath("/contracts/app1.pdf");
            latest.setContractStatus(1);
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(latest);

            Result<List<LoanApplicationVO>> r = service.queryApplyProgress("u1");

            assertThat(r.getData()).hasSize(1);
            assertThat(r.getData().get(0).getContractPath()).isEqualTo("/contracts/app1.pdf");
            assertThat(r.getData().get(0).getContractStatus()).isEqualTo(1);
        }

        @Test
        @DisplayName("已有合同 → 不重复补生成")
        void existingContractSkipped() {
            LoanApplicationVO vo = new LoanApplicationVO();
            vo.setApplicationId("app1");
            vo.setApplicationStatus(ApplicationStatusEnum.APPROVED.getCode());
            vo.setContractPath("/contracts/app1.pdf");
            when(loanApplicationMapper.selectByUserId("u1")).thenReturn(List.of(vo));

            service.queryApplyProgress("u1");

            verify(contractService, never()).generateDraftContract(anyString());
        }

        @Test
        @DisplayName("未通过状态 → 即使无合同也不补生成（只补 approved）")
        void notApprovedSkipped() {
            LoanApplicationVO vo = new LoanApplicationVO();
            vo.setApplicationId("app1");
            vo.setApplicationStatus(ApplicationStatusEnum.PENDING.getCode());
            vo.setContractPath(null);
            when(loanApplicationMapper.selectByUserId("u1")).thenReturn(List.of(vo));

            service.queryApplyProgress("u1");

            verify(contractService, never()).generateDraftContract(anyString());
        }

        @Test
        @DisplayName("合同生成抛异常 → 不影响进度查询（吞掉）")
        void contractFailureSwallowed() {
            LoanApplicationVO vo = new LoanApplicationVO();
            vo.setApplicationId("app1");
            vo.setApplicationStatus(ApplicationStatusEnum.APPROVED.getCode());
            vo.setContractPath(null);
            when(loanApplicationMapper.selectByUserId("u1")).thenReturn(List.of(vo));
            when(contractService.generateDraftContract("app1"))
                    .thenThrow(new RuntimeException("存储不可用"));

            Result<List<LoanApplicationVO>> r = service.queryApplyProgress("u1");

            assertThat(r.getData()).hasSize(1);   // 查询本身成功
        }
    }

    // ==================== getApplicationBasic ====================

    @Nested
    @DisplayName("申请基本信息 getApplicationBasic")
    class GetBasic {

        @Test
        @DisplayName("申请不存在 → 404")
        void notFound() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(null);
            assertThatThrownBy(() -> service.getApplicationBasic("app1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("申请不存在");
        }

        @Test
        @DisplayName("产品为空 → 不填产品字段，也不 NPE")
        void productNull() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(app("app1", "u1"));
            when(basicMapper.toVo(any())).thenReturn(new LoanApplicationBasicVO());
            when(loanProductService.validateProduct("product_001")).thenReturn(null);

            Result<LoanApplicationBasicVO> r = service.getApplicationBasic("app1");

            assertThat(r.getData().getProductName()).isNull();
            assertThat(r.getData().getMonthlyPayment()).isNull();
        }

        @Test
        @DisplayName("产品存在 → 填产品名 + 等额本息月供")
        void productPresent() {
            LoanApplication a = app("app1", "u1");
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(a);
            when(basicMapper.toVo(any())).thenReturn(new LoanApplicationBasicVO());

            LoanProduct p = new LoanProduct();
            p.setProductName("极速贷");
            p.setInterestRate(new BigDecimal("0.12"));
            when(loanProductService.validateProduct("product_001")).thenReturn(p);

            Result<LoanApplicationBasicVO> r = service.getApplicationBasic("app1");

            LoanApplicationBasicVO vo = r.getData();
            assertThat(vo.getProductName()).isEqualTo("极速贷");
            assertThat(vo.getRepaymentMethod()).isEqualTo("等额本息");
            // 10000 元 / 12 期 / 年化 12% → 月供约 888.49
            assertThat(vo.getMonthlyPayment()).isNotNull();
            assertThat(vo.getMonthlyPayment()).isGreaterThan(new BigDecimal("880"));
            assertThat(vo.getMonthlyPayment()).isLessThan(new BigDecimal("900"));
        }

        @Test
        @DisplayName("银行卡存在 → 卡号必须脱敏（安全要求）")
        void bankCardMasked() {
            LoanApplication a = app("app1", "u1");
            a.setDisbursementCardId(99L);
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(a);
            when(basicMapper.toVo(any())).thenReturn(new LoanApplicationBasicVO());
            when(loanProductService.validateProduct("product_001")).thenReturn(null);

            UserBankCard card = new UserBankCard();
            card.setCardNumber("6222021234567890123");
            card.setAliasName("工商银行");
            when(userBankCardMapper.selectById(99L)).thenReturn(card);

            Result<LoanApplicationBasicVO> r = service.getApplicationBasic("app1");

            assertThat(r.getData().getCardBankName()).isEqualTo("工商银行");
            assertThat(r.getData().getCardNumber()).isEqualTo("6222 **** **** 0123");
            assertThat(r.getData().getCardNumber()).doesNotContain("1234567890123".substring(0, 6));
        }

        @Test
        @DisplayName("银行卡查不到 → 不填卡字段，也不 NPE")
        void bankCardNull() {
            LoanApplication a = app("app1", "u1");
            a.setDisbursementCardId(99L);
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(a);
            when(basicMapper.toVo(any())).thenReturn(new LoanApplicationBasicVO());
            when(loanProductService.validateProduct("product_001")).thenReturn(null);
            when(userBankCardMapper.selectById(99L)).thenReturn(null);

            Result<LoanApplicationBasicVO> r = service.getApplicationBasic("app1");

            assertThat(r.getData().getCardNumber()).isNull();
        }
    }

    // ==================== getApplicantIdentity ====================

    @Nested
    @DisplayName("申请人身份 getApplicantIdentity")
    class GetIdentity {

        @Test
        @DisplayName("申请不存在 → 404")
        void appNotFound() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(null);
            assertThatThrownBy(() -> service.getApplicantIdentity("app1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("申请不存在");
        }

        @Test
        @DisplayName("用户不存在 → 404")
        void userNotFound() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(app("app1", "u1"));
            when(sysUserMapper.selectById("u1")).thenReturn(null);
            assertThatThrownBy(() -> service.getApplicantIdentity("app1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户不存在");
        }

        @Test
        @DisplayName("身份信息完整 → 手机号/身份证必须脱敏")
        void fullIdentityMasked() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(app("app1", "u1"));

            SysUser user = new SysUser();
            user.setUserId("u1");
            user.setPhone("13900009001");
            user.setRealName("张三");
            user.setKycStatus(1);
            when(sysUserMapper.selectById("u1")).thenReturn(user);

            UserIdentity id = new UserIdentity();
            id.setIdCardNumber("350102199001011234");
            id.setVerifyStatus(1);
            when(userIdentityMapper.selectByUserId("u1")).thenReturn(id);

            Result<ApplicantIdentityVO> r = service.getApplicantIdentity("app1");

            ApplicantIdentityVO vo = r.getData();
            assertThat(vo.getUserPhone()).isEqualTo("139****9001");
            assertThat(vo.getIdCardNumber()).isEqualTo("350102********1234");
            assertThat(vo.getKycStatusDesc()).isEqualTo("已认证");
            assertThat(vo.getVerifyStatusDesc()).isEqualTo("已验证");
        }

        @Test
        @DisplayName("无身份记录 → verifyStatus=0 / 「未验证」，不 NPE")
        void identityNull() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(app("app1", "u1"));

            SysUser user = new SysUser();
            user.setUserId("u1");
            user.setPhone("13900009001");
            user.setKycStatus(0);
            when(sysUserMapper.selectById("u1")).thenReturn(user);
            when(userIdentityMapper.selectByUserId("u1")).thenReturn(null);

            Result<ApplicantIdentityVO> r = service.getApplicantIdentity("app1");

            assertThat(r.getData().getVerifyStatus()).isEqualTo(0);
            assertThat(r.getData().getVerifyStatusDesc()).isEqualTo("未验证");
        }
    }

    // ==================== getScorecardDetail ====================

    @Nested
    @DisplayName("评分卡详情 getScorecardDetail")
    class GetScorecardDetail {

        @Test
        @DisplayName("申请不存在 → 404")
        void appNotFound() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(null);
            assertThatThrownBy(() -> service.getScorecardDetail("app1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("申请不存在");
        }

        @Test
        @DisplayName("无决策日志 → 404「评分卡详情数据不存在」")
        void logNotFound() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(app("app1", "u1"));
            when(riskDecisionLogService.getByApplicationId("app1")).thenReturn(null);
            assertThatThrownBy(() -> service.getScorecardDetail("app1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("评分卡详情数据不存在");
        }

        @Test
        @DisplayName("featureDetails 为 null → 404")
        void featureDetailsNull() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(app("app1", "u1"));
            RiskDecisionLog log = new RiskDecisionLog();
            log.setFeatureDetails(null);
            when(riskDecisionLogService.getByApplicationId("app1")).thenReturn(log);

            assertThatThrownBy(() -> service.getScorecardDetail("app1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("评分卡详情数据不存在");
        }

        @Test
        @DisplayName("完整数据 → 6 个特征全部组装，等级/描述正确")
        void fullDetail() {
            LoanApplication a = app("app1", "u1");
            a.setScorecardScore(720);
            a.setScorecardDecision("AUTO_APPROVE");
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(a);

            RiskDecisionLog log = new RiskDecisionLog();
            // 模拟 Jackson 反序列化后的形态：数值可能是 int/double/string
            log.setFeatureDetails("{\"revolvingUtil\":0.35,\"age\":35,"
                    + "\"debtRatio\":0.4,\"monthlyIncome\":8000,"
                    + "\"creditLines\":6,\"dependents\":2}");
            when(riskDecisionLogService.getByApplicationId("app1")).thenReturn(log);

            Result<ScorecardDetailVO> r = service.getScorecardDetail("app1");

            ScorecardDetailVO vo = r.getData();
            assertThat(vo.getTotalScore()).isEqualTo(720);
            assertThat(vo.getDecision()).isEqualTo("AUTO_APPROVE");
            assertThat(vo.getDecisionDesc()).isEqualTo("自动通过");
            assertThat(vo.getScoreLevel()).isEqualTo("优秀");
            assertThat(vo.getBaseScore()).isEqualTo(791);

            assertThat(vo.getRevolvingUtil().getLevel()).isEqualTo("中");     // 0.35 ∈ [0.2, 0.5)
            assertThat(vo.getAge().getLevel()).isEqualTo("中年");             // 35
            assertThat(vo.getDebtRatio().getLevel()).isEqualTo("中");         // 0.4 ∈ [0.3, 0.6)
            assertThat(vo.getMonthlyIncome().getLevel()).isEqualTo("中");     // 8000 ∈ [5000, 10000)
            assertThat(vo.getCreditLines().getLevel()).isEqualTo("中");       // 6 ∈ [5, 10)
            assertThat(vo.getDependents().getLevel()).isEqualTo("中");        // 2
        }

        @Test
        @DisplayName("特征缺失 → 等级为「未知」、描述为「数据缺失」，不 NPE")
        void missingFeatures() {
            LoanApplication a = app("app1", "u1");
            a.setScorecardScore(500);
            a.setScorecardDecision("REJECT");
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(a);

            RiskDecisionLog log = new RiskDecisionLog();
            log.setFeatureDetails("{}");   // 全空
            when(riskDecisionLogService.getByApplicationId("app1")).thenReturn(log);

            Result<ScorecardDetailVO> r = service.getScorecardDetail("app1");

            ScorecardDetailVO vo = r.getData();
            assertThat(vo.getAge().getLevel()).isEqualTo("未知");
            assertThat(vo.getAge().getDescription()).isEqualTo("数据缺失");
            assertThat(vo.getRevolvingUtil().getLevel()).isEqualTo("未知");
            assertThat(vo.getScoreLevel()).isEqualTo("较差");
        }

        @Test
        @DisplayName("featureDetails 是非法 JSON → 500「解析失败」")
        void invalidJson() {
            when(loanApplicationMapper.selectByApplicationId("app1")).thenReturn(app("app1", "u1"));
            RiskDecisionLog log = new RiskDecisionLog();
            log.setFeatureDetails("not-a-json{{{");
            when(riskDecisionLogService.getByApplicationId("app1")).thenReturn(log);

            assertThatThrownBy(() -> service.getScorecardDetail("app1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("解析失败");
        }
    }
}
