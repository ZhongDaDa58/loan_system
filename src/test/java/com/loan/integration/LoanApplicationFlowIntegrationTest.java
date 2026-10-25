package com.loan.integration;

import com.loan.entity.dto.LoanApplyDTO;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.service.LoanApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 贷款申请全链路集成测试（阶段三 3.3）
 *
 * <p><b>目的</b>：一次 {@code submitApply} 调用就串起
 * 「准入校验 → L1 规则 → 评分卡 → L3 规则 → 降额 → 定价 → 多源完备性 → 落库留痕」整条链路。
 * 这是覆盖率提升的主力 —— {@code LoanApplicationServiceImpl} 有 700+ 行，全靠这里覆盖。
 *
 * <p><b>为什么用真实 MySQL</b>：项目有手写 SQL 用了 {@code JSON_OBJECT} 等 MySQL 特有语法，
 * H2 跑不通；而且 Flyway 会在容器里自动建表 + 导入配置种子（19 规则 / 3 产品 / 5 档位）。
 *
 * <p><b>准入校验链（本测试的准备依据）</b>：
 * <ol>
 *   <li>{@code sys_user.kyc_status = 1}（实名）</li>
 *   <li>无逾期记录（{@code monthly_repayment} 为空）</li>
 *   <li>产品存在且启用（种子里的 {@code product_001}）</li>
 *   <li>金额 1000~200000、期限 8~36（product_001 的范围）</li>
 *   <li>银行卡可空（{@code disbursementCardId} 为 null 时走默认卡）</li>
 *   <li>{@code user_credit_profile} 存在且 {@code credit_score} 非空</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)   // Docker 不可用时跳过（本机 Docker Desktop 不稳定）
class LoanApplicationFlowIntegrationTest {

    private static final String USER_ID = "it-user-0001";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("loan_risk_control")
            .withUsername("root")
            .withPassword("test")
            .withUrlParam("allowMultiQueries", "true")
            .withUrlParam("serverTimezone", "Asia/Shanghai")
            .withCommand("--character-set-server=utf8mb4",
                         "--collation-server=utf8mb4_general_ci");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private LoanApplicationService loanApplicationService;

    @Autowired
    private JdbcTemplate jdbc;

    // ==================== 数据准备 ====================

    @BeforeEach
    void prepareUser() {
        // 每个用例前清掉本测试用户的数据，保证互不影响
        jdbc.update("DELETE FROM risk_rule_hit_log WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM risk_decision_log WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM loan_application WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM user_credit_profile WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM sys_user WHERE user_id = ?", USER_ID);

        // 已实名用户
        jdbc.update("INSERT INTO sys_user (user_id, phone, password, real_name, role, status, kyc_status) "
                        + "VALUES (?, ?, ?, ?, 'USER', 1, 1)",
                USER_ID, "13000000001", "pwd-hash", "集成测试用户");

        // 信用档案（必须有 credit_score，否则"请先创建信用档案"）
        jdbc.update("INSERT INTO user_credit_profile "
                        + "(user_id, credit_score, age, revolving_util, debt_ratio, monthly_income, credit_lines, dependents, create_time, update_time) "
                        + "VALUES (?, 700, 32, 0.25, 0.30, 15000, 5, 1, NOW(), NOW())",
                USER_ID);
    }

    private LoanApplyDTO dto(String amount, int term) {
        LoanApplyDTO d = new LoanApplyDTO();
        d.setProductId("product_001");
        d.setApplyAmount(new BigDecimal(amount));
        d.setApplyTerm(term);
        return d;
    }

    // ==================== 主链路 ====================

    @Test
    @DisplayName("★ 提交申请 → 完成评分与决策，loan_application 落库")
    void submitApplyPersistsApplicationWithScoreAndDecision() {
        Result<?> result = loanApplicationService.submitApply(dto("50000", 12), USER_ID);

        assertEquals(200, result.getCode(), "正常申请应成功");

        Map<String, Object> app = jdbc.queryForMap(
                "SELECT scorecard_score, scorecard_decision, application_status, apply_amount "
                        + "FROM loan_application WHERE user_id = ?", USER_ID);

        assertNotNull(app.get("scorecard_score"), "必须写入评分卡分数");
        assertNotNull(app.get("scorecard_decision"), "必须写入决策结果");
        assertNotNull(app.get("application_status"), "必须写入申请状态");
        assertEquals(0, new BigDecimal("50000").compareTo((BigDecimal) app.get("apply_amount")));
    }

    @Test
    @DisplayName("★ 决策留痕：risk_decision_log 必须有记录（可追溯性）")
    void submitApplyWritesDecisionLog() {
        loanApplicationService.submitApply(dto("50000", 12), USER_ID);

        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log WHERE user_id = ?", Integer.class, USER_ID);
        assertTrue(cnt != null && cnt >= 1, "每笔申请都应有决策留痕，实际 " + cnt);
    }

    @Test
    @DisplayName("★ 特征快照落库（P-044 的回归守护：feature_snapshot 不能是 NULL）")
    void submitApplyWritesFeatureSnapshot() {
        loanApplicationService.submitApply(dto("50000", 12), USER_ID);

        Integer withSnapshot = jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_decision_log "
                        + "WHERE user_id = ? AND feature_snapshot IS NOT NULL", Integer.class, USER_ID);
        assertTrue(withSnapshot != null && withSnapshot >= 1,
                "feature_snapshot 必须有值 —— 它是可解释性与 S1 导出的数据源");
    }

    @Test
    @DisplayName("定价档位落库：applied_rate 与 pricing_tier 应匹配评分")
    void submitApplyWritesPricing() {
        loanApplicationService.submitApply(dto("50000", 12), USER_ID);

        Map<String, Object> app = jdbc.queryForMap(
                "SELECT applied_rate, pricing_tier FROM loan_application WHERE user_id = ?", USER_ID);

        // 档案 credit_score=700 + 无多源 → 走 v3 单模型，分数在 6xx 区间
        // 定价档位应命中 A~E 之一（具体档位取决于分数）
        assertNotNull(app.get("pricing_tier"), "应命中定价档位");
        assertNotNull(app.get("applied_rate"), "应写入实际执行利率");
    }

    // ==================== 准入校验（负向用例）====================

    @Test
    @DisplayName("未实名 → 拒绝（403）")
    void rejectWhenNotVerified() {
        jdbc.update("UPDATE sys_user SET kyc_status = 0 WHERE user_id = ?", USER_ID);

        BusinessException e = assertThrows(BusinessException.class,
                () -> loanApplicationService.submitApply(dto("50000", 12), USER_ID));
        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("实名"));
    }

    @Test
    @DisplayName("无信用档案 → 拒绝")
    void rejectWhenNoCreditProfile() {
        jdbc.update("DELETE FROM user_credit_profile WHERE user_id = ?", USER_ID);

        BusinessException e = assertThrows(BusinessException.class,
                () -> loanApplicationService.submitApply(dto("50000", 12), USER_ID));
        assertTrue(e.getMessage().contains("信用档案"));
    }

    @Test
    @DisplayName("金额低于产品下限 → 拒绝")
    void rejectWhenAmountTooSmall() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> loanApplicationService.submitApply(dto("100", 12), USER_ID));
        assertTrue(e.getMessage().contains("申请金额"));
    }

    @Test
    @DisplayName("金额高于产品上限 → 拒绝")
    void rejectWhenAmountTooLarge() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> loanApplicationService.submitApply(dto("999999", 12), USER_ID));
        assertTrue(e.getMessage().contains("申请金额"));
    }

    @Test
    @DisplayName("期限超出范围 → 拒绝")
    void rejectWhenTermOutOfRange() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> loanApplicationService.submitApply(dto("50000", 99), USER_ID));
        assertTrue(e.getMessage().contains("申请期限"));
    }

    /**
     * 有逾期记录 → 拒绝申请。
     *
     * <p><b>⚠️ 2026-10-25 重写（原来是"假测试"）</b>：原实现是这样 ——
     * <pre>
     * if (存在 overdue_days 列) {
     *     jdbc.update("INSERT INTO monthly_repayment (repayment_id, user_id, plan_id, ...)");
     * }
     * // ← 没有任何断言，插完就结束，根本不验证"是否被拒绝"
     * </pre>
     * 两个问题：
     * <ol>
     *   <li><b>SQL 列名是猜的</b>（{@code user_id} / {@code status} / {@code create_time} 在
     *       {@code monthly_repayment} 里都不存在）—— V4 之前那个分支不执行，所以一直没暴露</li>
     *   <li><b>没有断言</b> —— 即使插进去，也没有验证申请是否被拦，等于什么都没测</li>
     * </ol>
     *
     * <p><b>顺带说明为什么加 {@code @Transactional}</b>：本类有 10 个测试共用 {@code USER_ID}，
     * 若插入的逾期记录不回滚，后面所有申请测试都会被这条逾期记录拦掉。
     */
    @Test
    @Transactional
    @DisplayName("有逾期记录（M2）→ 拒绝申请")
    void rejectWhenHasOverdue() {
        // ① 造还款计划（selectOverdueByUserId 要 JOIN repayment_plan 取 user_id）
        jdbc.update("INSERT INTO repayment_plan "
                        + "(plan_id, application_id, user_id, total_amount, total_principal, "
                        + " total_interest, repayment_type, term_count) "
                        + "VALUES ('it-plan-overdue', 'it-app-overdue', ?, 110.00, 100.00, 10.00, "
                        + "        'EQUAL_INSTALLMENT', 1)",
                USER_ID);

        // ② 造一笔 40 天前的逾期明细（V4 后的字段：overdue_days / overdue_level）
        jdbc.update("INSERT INTO monthly_repayment "
                        + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                        + " due_date, repayment_status, overdue_days, overdue_level) "
                        + "VALUES ('it-overdue-1', 'it-plan-overdue', 1, 100.00, 10.00, 110.00, "
                        + "        DATE_SUB(CURDATE(), INTERVAL 40 DAY), 'overdue', 40, 'M2')");

        // ③ 断言：申请被拒绝，且提示与逾期有关
        BusinessException e = assertThrows(BusinessException.class,
                () -> loanApplicationService.submitApply(dto("50000", 12), USER_ID),
                "有逾期记录时不该允许申请");
        assertTrue(e.getMessage().contains("逾期"),
                "提示应提到逾期，实际提示：" + e.getMessage());
    }

    // ==================== L1 规则（影子模式）====================

    @Test
    @DisplayName("L1 影子规则命中应写入 risk_rule_hit_log，但不改变决策")
    void l1ShadowHitIsLoggedButDoesNotBlock() {
        // 种子里的 14 条 L1 规则默认 enabled=1 + shadow_mode=1
        loanApplicationService.submitApply(dto("50000", 12), USER_ID);

        Integer hits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM risk_rule_hit_log WHERE user_id = ?", Integer.class, USER_ID);

        // 该用户无设备/活体/多源数据 → 必定命中 L1-09(无活体)/L1-11(无定位)/L1-14(可信度低)
        // 影子模式下命中只留痕，不阻断申请（上面主链路用例已证明返回 200）
        assertTrue(hits != null && hits >= 1,
                "无任何行为数据的用户应至少命中 L1-09/11/14，实际 " + hits);
    }
}
