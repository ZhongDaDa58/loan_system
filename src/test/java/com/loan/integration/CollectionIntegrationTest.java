package com.loan.integration;

import com.loan.entity.CollectionRecord;
import com.loan.entity.CollectionTask;
import com.loan.exception.BusinessException;
import com.loan.service.CollectionRecordService;
import com.loan.service.CollectionTaskService;
import com.loan.service.impl.CollectionTaskGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 催收任务与记录测试（逾期处理功能升级 第 6 步-c）
 *
 * <p>锁住六件事：
 * <ol>
 *   <li><b>M1 前 7 天不建任务</b> —— 刚逾期一两天自动提醒就够了，这时候打电话会让客户反感</li>
 *   <li>M2/M3 建任务，优先级按等级：M3=1 / M2=2 / M1=3</li>
 *   <li>生成是幂等的：重复跑不会堆出一堆重复任务</li>
 *   <li>承诺还款会同步到任务上（催收员一眼看到"答应哪天还"）</li>
 *   <li>关闭必须给原因；关闭后不能再加记录</li>
 *   <li>客户还款后，该期次的任务自动关闭</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CollectionIntegrationTest {

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

    private static final String USER_ID = "it-ct-user";
    private static final String PLAN_ID = "it-ct-plan";

    @Autowired private CollectionTaskGenerator generator;
    @Autowired private CollectionTaskService taskService;
    @Autowired private CollectionRecordService recordService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.loan.service.RepaymentService repaymentService;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM collection_record");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM monthly_repayment");
        jdbc.update("DELETE FROM repayment_plan");

        jdbc.update("INSERT INTO repayment_plan "
                + "(plan_id, application_id, user_id, total_amount, total_principal, "
                + " total_interest, repayment_type, term_count) "
                + "VALUES (?, 'it-ct-app', ?, 400.00, 350.00, 50.00, 'EQUAL_INSTALLMENT', 4)",
                PLAN_ID, USER_ID);

        // M1 才 3 天 —— 不该建任务
        insertOverdue("ct-m1-early", 3, "M1", 1);
        // M1 已 10 天 —— 该建，优先级 3
        insertOverdue("ct-m1-late", 10, "M1", 2);
        // M2 40 天 —— 该建，优先级 2
        insertOverdue("ct-m2", 40, "M2", 3);
        // M3 70 天 —— 该建，优先级 1
        insertOverdue("ct-m3", 70, "M3", 4);
    }

    private void insertOverdue(String id, int daysAgo, String level, int term) {
        jdbc.update("INSERT INTO monthly_repayment "
                + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                + " due_date, repayment_status, overdue_days, overdue_level) "
                + "VALUES (?, ?, ?, 100.00, 10.00, 110.00, "
                + "        DATE_SUB(CURDATE(), INTERVAL ? DAY), 'overdue', ?, ?)",
                id, PLAN_ID, term, daysAgo, daysAgo, level);
    }

    private CollectionTask taskOf(String repaymentId) {
        return jdbc.query("SELECT task_id FROM collection_task WHERE repayment_id = ?",
                        (rs, i) -> rs.getString("task_id"), repaymentId)
                .stream().findFirst()
                .map(taskService::getByTaskId)
                .orElse(null);
    }

    @Test
    @DisplayName("生成规则：M1 前 7 天不建；M2/M3 建，优先级 M3=1 / M2=2 / M1=3")
    void generationRules() {
        int created = generator.generate();

        assertThat(created)
                .as("4 笔逾期里只有 3 笔该建任务（M1 才 3 天那笔不建）")
                .isEqualTo(3);

        assertThat(taskOf("ct-m1-early"))
                .as("M1 才 3 天，自动提醒就够了，不该打扰客户")
                .isNull();

        assertThat(taskOf("ct-m1-late").getPriority()).as("M1 优先级 3（低）").isEqualTo(3);
        assertThat(taskOf("ct-m2").getPriority()).as("M2 优先级 2（中）").isEqualTo(2);
        assertThat(taskOf("ct-m3").getPriority()).as("M3 优先级 1（高）").isEqualTo(1);

        assertThat(taskOf("ct-m3").getStatus()).isEqualTo("pending");
        assertThat(taskOf("ct-m3").getOverdueDays()).isEqualTo(70);
    }

    @Test
    @DisplayName("生成幂等：重复跑不会堆出重复任务")
    void generationIsIdempotent() {
        generator.generate();
        int second = generator.generate();

        assertThat(second).as("第二次不该新建任何任务").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM collection_task", Integer.class))
                .as("总共还是 3 个任务")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("认领后负责人和状态都更新")
    void claimTask() {
        generator.generate();
        String taskId = taskOf("ct-m3").getTaskId();

        assertThat(taskService.claim(taskId, "collector-01")).isTrue();

        CollectionTask after = taskService.getByTaskId(taskId);
        assertThat(after.getAssignedTo()).isEqualTo("collector-01");
        assertThat(after.getStatus()).isEqualTo("in_progress");
        assertThat(after.getLastFollowAt()).as("认领也算一次跟进动作").isNotNull();
    }

    @Test
    @DisplayName("★ 承诺还款会同步到任务上（催收员一眼看到答应哪天还）")
    void promiseSyncsToTask() {
        generator.generate();
        String taskId = taskOf("ct-m2").getTaskId();
        LocalDate promise = LocalDate.now().plusDays(5);

        CollectionRecord record = recordService.add(
                taskId, "phone", "promised", promise, "客户说发工资就还", "collector-01");

        assertThat(record.getRecordId()).isNotBlank();
        assertThat(record.getUserId()).as("记录要冗余 userId，便于按用户查").isEqualTo(USER_ID);

        CollectionTask after = taskService.getByTaskId(taskId);
        assertThat(after.getStatus()).isEqualTo("promised");
        assertThat(after.getPromisedPayDate()).as("承诺日要同步到任务").isNotNull();
        assertThat(recordService.countPromised(taskId)).isEqualTo(1);
    }

    @Test
    @DisplayName("result=promised 但没填承诺日 → 拒绝（承诺不写日期等于没承诺）")
    void promiseRequiresDate() {
        generator.generate();
        String taskId = taskOf("ct-m2").getTaskId();

        assertThatThrownBy(() -> recordService.add(
                taskId, "phone", "promised", null, "客户答应还", "collector-01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("承诺还款日期");
    }

    @Test
    @DisplayName("非法 method / result 被拒（取值约束在 Service 层）")
    void validatesMethodAndResult() {
        generator.generate();
        String taskId = taskOf("ct-m2").getTaskId();

        assertThatThrownBy(() -> recordService.add(
                taskId, "telepathy", "connected", null, null, "op"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("联系方式");

        assertThatThrownBy(() -> recordService.add(
                taskId, "phone", "maybe_later", null, null, "op"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("跟进结果");
    }

    @Test
    @DisplayName("关闭必须给原因；关闭后不能再加记录")
    void closeRequiresReason() {
        generator.generate();
        String taskId = taskOf("ct-m3").getTaskId();

        assertThatThrownBy(() -> taskService.close(taskId, "  "))
                .as("没有原因的关闭等于把问题藏起来")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原因");

        assertThat(taskService.close(taskId, "客户已结清")).isTrue();
        assertThat(taskService.getByTaskId(taskId).getStatus()).isEqualTo("closed");

        // 已关闭的任务不能再加记录
        assertThatThrownBy(() -> recordService.add(
                taskId, "phone", "connected", null, "再打一次", "op"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已关闭");

        // 重复关闭返回 false 而不是抛异常
        assertThat(taskService.close(taskId, "再关一次")).isFalse();
    }

    @Test
    @DisplayName("★ 客户还款后，该期次的任务自动关闭")
    void closeByRepayment() {
        generator.generate();
        String taskId = taskOf("ct-m3").getTaskId();
        assertThat(taskService.getByTaskId(taskId).getStatus()).isEqualTo("pending");

        int closed = taskService.closeByRepayment("ct-m3", "客户已还款");

        assertThat(closed).isEqualTo(1);
        CollectionTask after = taskService.getByTaskId(taskId);
        assertThat(after.getStatus()).isEqualTo("closed");
        assertThat(after.getCloseReason()).isEqualTo("客户已还款");

        // 再关一次：已经关了，返回 0
        assertThat(taskService.closeByRepayment("ct-m3", "重复")).isZero();
    }

    @Test
    @DisplayName("今日待办：只返回 next_follow_date <= 今天的任务")
    void dueFollowFilter() {
        generator.generate();

        List<CollectionTask> due = taskService.list(null, null, true, 1, 50);
        assertThat(due).as("生成时首次跟进日就是今天，所以 3 个都该在待办里").hasSize(3);

        // 把一个任务的下次跟进日推到 3 天后
        String taskId = taskOf("ct-m2").getTaskId();
        taskService.updateFollow(taskId, null, null, LocalDate.now().plusDays(3), null);

        assertThat(taskService.list(null, null, true, 1, 50))
                .as("推到 3 天后就不在今天待办里了")
                .hasSize(2);
    }

    @Test
    @DisplayName("updateFollow 不能直接把状态设成 closed（必须走 close 接口填原因）")
    void cannotCloseViaUpdate() {
        generator.generate();
        String taskId = taskOf("ct-m2").getTaskId();

        assertThatThrownBy(() -> taskService.updateFollow(taskId, null, "closed", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("关闭请用关闭接口");
    }

    // ==================== 还款链路联动（端到端）====================

    /**
     * 客户真的还了钱 → 该期的催收任务自动关闭。
     *
     * <p>这条必须用<b>完整的还款链路</b>测，不能用 {@code closeByRepayment} 单独测 ——
     * 后者已经有一条用例了，但它证明不了"submitRepayment 里那行调用还在"。
     * 如果哪天有人把 RepaymentServiceImpl 里那段 try-catch 删掉，只有这个测试会红。
     */
    @Test
    @DisplayName("★ 端到端：submitRepayment 成功后，该期催收任务自动关闭")
    void repaymentClosesTaskEndToEnd() {
        // 任务先建出来
        generator.generate();
        String taskId = taskOf("ct-m2").getTaskId();
        assertThat(taskService.getByTaskId(taskId).getStatus()).isEqualTo("pending");

        // 造还款所需的账户与银行卡：余额要够 110 元
        jdbc.update("DELETE FROM user_bank_card WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM sys_user WHERE user_id = ?", USER_ID);
        jdbc.update("INSERT INTO sys_user (user_id, phone, password, real_name, role, status, "
                + " kyc_status, account_balance) VALUES (?, '13900008001', 'pwd', '催收测试', "
                + " 'USER', 1, 1, 1000.00)", USER_ID);

        jdbc.update("DELETE FROM mock_bank_card_pool WHERE id = 9901");
        jdbc.update("INSERT INTO mock_bank_card_pool (id, card_number, bank_code, bank_name, "
                + " card_type, cardholder_name, id_card, phone, balance, status, daily_limit) "
                + "VALUES (9901, '622200009901', 'ICBC', '工商银行', 'DEBIT', '催收测试', "
                + " '110101199001010000', '13900008001', 1000.00, 'ACTIVE', 50000.00)");

        jdbc.update("INSERT INTO user_bank_card (user_id, pool_card_id, card_number, alias_name, "
                + " is_default, bind_status) VALUES (?, 9901, '622200009901', '测试卡', 1, 'ACTIVE')",
                USER_ID);

        // 还款：金额 110（这期 repayment_amount）
        com.loan.entity.dto.RepaymentSubmitDTO dto = new com.loan.entity.dto.RepaymentSubmitDTO();
        dto.setRepaymentId("ct-m2");
        dto.setRepaymentAmount(new java.math.BigDecimal("110.00"));

        var result = repaymentService.submitRepayment(dto, USER_ID);

        assertThat(result.getCode()).as("还款应成功").isEqualTo(200);
        assertThat(taskService.getByTaskId(taskId).getStatus())
                .as("还款成功后，该期催收任务必须自动关闭（否则催收员会继续打给已经还了钱的人）")
                .isEqualTo("closed");
        assertThat(taskService.getByTaskId(taskId).getCloseReason()).contains("还款");
    }
}
