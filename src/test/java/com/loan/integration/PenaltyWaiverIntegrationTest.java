package com.loan.integration;

import com.loan.entity.PenaltyWaiver;
import com.loan.exception.BusinessException;
import com.loan.service.PenaltyWaiverService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 罚息减免审批测试（逾期处理功能升级 第 6 步-d）
 *
 * <p>锁住七件事：
 * <ol>
 *   <li>申请阶段<b>不动罚息</b>（客户这段时间仍能正常还款）</li>
 *   <li>审批通过才真正扣减罚息</li>
 *   <li><b>审批人不能是申请人</b>（自己批自己等于没有审批）</li>
 *   <li>不能重复审批（第二次返回"已被处理"）</li>
 *   <li>同一期次只能有一条待审批申请（否则两条都通过会减两次）</li>
 *   <li>减免金额不能超过当前罚息</li>
 *   <li>驳回/撤回不影响罚息</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PenaltyWaiverIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("loan_risk_control")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("allowMultiQueries", "true")
            .withUrlParam("serverTimezone", "Asia/Shanghai")
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_general_ci");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }

    private static final String USER_ID = "it-pw-user";
    private static final String PLAN_ID = "it-pw-plan";
    private static final String REPAYMENT_ID = "it-pw-repayment";

    /** 期次上的罚息：100 元 */
    private static final BigDecimal PENALTY = new BigDecimal("100.00");

    @Autowired private PenaltyWaiverService penaltyWaiverService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM penalty_waiver");
        jdbc.update("DELETE FROM monthly_repayment");
        jdbc.update("DELETE FROM repayment_plan");

        jdbc.update("INSERT INTO repayment_plan "
                + "(plan_id, application_id, user_id, total_amount, total_principal, "
                + " total_interest, repayment_type, term_count) "
                + "VALUES (?, 'it-pw-app', ?, 500.00, 450.00, 50.00, 'EQUAL_INSTALLMENT', 1)",
                PLAN_ID, USER_ID);

        jdbc.update("INSERT INTO monthly_repayment "
                + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                + " due_date, repayment_status, overdue_days, overdue_level, penalty_amount) "
                + "VALUES (?, ?, 1, 450.00, 50.00, 500.00, DATE_SUB(CURDATE(), INTERVAL 40 DAY), "
                + "        'overdue', 40, 'M2', ?)",
                REPAYMENT_ID, PLAN_ID, PENALTY);
    }

    private BigDecimal currentPenalty() {
        return jdbc.queryForObject(
                "SELECT penalty_amount FROM monthly_repayment WHERE repayment_id = ?",
                BigDecimal.class, REPAYMENT_ID);
    }

    @Test
    @Transactional
    @DisplayName("★ 申请阶段不动罚息（客户这段时间仍能正常还款）")
    void applyDoesNotChangePenalty() {
        PenaltyWaiver w = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("30.00"), "银行到账延迟导致逾期", "collector-01");

        assertThat(w.getWaiverId()).startsWith("PW-");
        assertThat(w.getStatus()).isEqualTo("pending");
        assertThat(w.getPenaltySnapshot())
                .as("快照记录申请那一刻的罚息，便于事后核对")
                .isEqualByComparingTo(PENALTY);
        assertThat(w.getUserId()).as("要能按客户查全量").isEqualTo(USER_ID);
        assertThat(currentPenalty())
                .as("申请阶段绝不能动罚息")
                .isEqualByComparingTo(PENALTY);
    }

    @Test
    @Transactional
    @DisplayName("★ 审批通过才真正扣减罚息")
    void approveReducesPenalty() {
        PenaltyWaiver w = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("30.00"), "协商减免", "collector-01");

        PenaltyWaiver approved = penaltyWaiverService.approve(w.getWaiverId(), "manager-01", "同意");

        assertThat(approved.getStatus()).isEqualTo("approved");
        assertThat(approved.getApprover()).isEqualTo("manager-01");
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(currentPenalty())
                .as("100 - 30 = 70")
                .isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    @Transactional
    @DisplayName("★ 审批人不能与申请人相同")
    void cannotApproveOwnApplication() {
        PenaltyWaiver w = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("30.00"), "协商减免", "collector-01");

        assertThatThrownBy(() -> penaltyWaiverService.approve(
                w.getWaiverId(), "collector-01", "自己批自己"))
                .as("自己申请自己批等于没有审批")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能与申请人相同");

        assertThat(currentPenalty()).as("被拒的审批不能动罚息").isEqualByComparingTo(PENALTY);
    }

    @Test
    @Transactional
    @DisplayName("不能重复审批（第二次提示已被处理）")
    void cannotApproveTwice() {
        PenaltyWaiver w = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("30.00"), "协商减免", "collector-01");

        penaltyWaiverService.approve(w.getWaiverId(), "manager-01", "同意");

        assertThatThrownBy(() -> penaltyWaiverService.approve(
                w.getWaiverId(), "manager-02", "再批一次"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能重复处理");

        assertThat(currentPenalty())
                .as("只该减一次：100 - 30 = 70")
                .isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    @Transactional
    @DisplayName("★ 同一期次只能有一条待审批（两条都通过会减两次）")
    void onlyOnePendingPerRepayment() {
        penaltyWaiverService.apply(REPAYMENT_ID, new BigDecimal("10.00"), "第一次", "collector-01");

        assertThatThrownBy(() -> penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("20.00"), "第二次", "collector-02"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待审批");
    }

    @Test
    @Transactional
    @DisplayName("减免金额不能超过当前罚息")
    void cannotExceedPenalty() {
        assertThatThrownBy(() -> penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("150.00"), "想全免", "collector-01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过当前罚息");
    }

    @Test
    @Transactional
    @DisplayName("驳回与撤回都不动罚息")
    void rejectAndCancelKeepPenalty() {
        PenaltyWaiver w1 = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("30.00"), "驳回测试", "collector-01");
        PenaltyWaiver rejected = penaltyWaiverService.reject(w1.getWaiverId(), "manager-01", "证据不足");
        assertThat(rejected.getStatus()).isEqualTo("rejected");
        assertThat(currentPenalty()).isEqualByComparingTo(PENALTY);

        // 驳回后可以重新申请
        PenaltyWaiver w2 = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("20.00"), "撤回测试", "collector-01");
        assertThat(penaltyWaiverService.cancel(w2.getWaiverId(), "collector-01", "客户自己还了")).isTrue();
        assertThat(currentPenalty()).as("撤回也不动罚息").isEqualByComparingTo(PENALTY);

        // 别人提交的撤不了
        PenaltyWaiver w3 = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("20.00"), "越权撤回测试", "collector-01");
        assertThat(penaltyWaiverService.cancel(w3.getWaiverId(), "collector-02", "别人的"))
                .as("只能撤自己提交的")
                .isFalse();
    }

    @Test
    @DisplayName("审批时罚息已不足申请金额 -> 拒绝并整单回滚（状态回到 pending）")
    void approveFailsWhenPenaltyShrunk() {
        // ⚠️ 这个用例**不能加 @Transactional**：
        //    要验证"扣减失败时状态能回滚"，就必须让 Service 的事务真正生效。
        //    如果测试自己也开事务，Service 的事务会加入外层，异常只把外层标记成
        //    rollback-only，断言时看到的是"已 approved"（因为它其实还没被回滚）。
        PenaltyWaiver w = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("80.00"), "大额减免", "collector-01");

        // 模拟这期间罚息因为别的原因变少了（比如另一笔减免/系统修正）
        jdbc.update("UPDATE monthly_repayment SET penalty_amount = 50.00 WHERE repayment_id = ?",
                REPAYMENT_ID);

        assertThatThrownBy(() -> penaltyWaiverService.approve(w.getWaiverId(), "manager-01", "批"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("余额已不足");

        assertThat(penaltyWaiverService.getByWaiverId(w.getWaiverId()).getStatus())
                .as("扣减失败必须回滚状态，不能出现「已通过但罚息没减」")
                .isEqualTo("pending");
        assertThat(currentPenalty()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    @Transactional
    @DisplayName("已还清的期次不能申请减免（罚息已结算）")
    void rejectsPaidRepayment() {
        jdbc.update("UPDATE monthly_repayment SET repayment_status = 'paid' WHERE repayment_id = ?",
                REPAYMENT_ID);
        assertThatThrownBy(() -> penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("10.00"), "已还清", "collector-01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已还清");
    }

    @Test
    @Transactional
    @DisplayName("没有罚息不能申请减免")
    void rejectsNoPenalty() {
        // ⚠️ 注意这里必须在**第一次** selectById 之前改数据。
        //    MyBatis 一级缓存是 SqlSession 级的：同一事务里先用 jdbc.update 改、
        //    再让 MyBatis 查同一个 id，如果它之前已经查过一次，拿到的还是旧值。
        //    所以这两个场景拆成两个独立用例，各自只查一次。
        jdbc.update("UPDATE monthly_repayment SET penalty_amount = 0 WHERE repayment_id = ?",
                REPAYMENT_ID);
        assertThatThrownBy(() -> penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("10.00"), "没罚息", "collector-01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有罚息");
    }

    @Test
    @Transactional
    @DisplayName("金额与原因必填")
    void validatesAmountAndReason() {
        assertThatThrownBy(() -> penaltyWaiverService.apply(
                REPAYMENT_ID, BigDecimal.ZERO, "零元", "collector-01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("大于 0");

        assertThatThrownBy(() -> penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("10.00"), "  ", "collector-01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原因");
    }

    @Test
    @Transactional
    @DisplayName("列表：待审批的排在最前")
    void listPutsPendingFirst() {
        PenaltyWaiver first = penaltyWaiverService.apply(
                REPAYMENT_ID, new BigDecimal("10.00"), "第一条", "collector-01");
        penaltyWaiverService.reject(first.getWaiverId(), "manager-01", "先驳了");

        // 再造一条待审批的（前一条已驳回，所以可以再申请）
        penaltyWaiverService.apply(REPAYMENT_ID, new BigDecimal("20.00"), "第二条", "collector-01");

        var page = penaltyWaiverService.list(null, null, 1, 20);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getStatus())
                .as("待审批的应该排第一个")
                .isEqualTo("pending");
        assertThat(penaltyWaiverService.count("pending", null)).isEqualTo(1);
        assertThat(penaltyWaiverService.count(null, USER_ID)).isEqualTo(2);
    }
}
