package com.loan.integration;

import com.loan.entity.CollectionTask;
import com.loan.service.impl.PromisedPayViolationChecker;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 承诺违约检查测试（逾期处理功能升级 第 6 步-c 收尾）
 *
 * <p>锁住五件事：
 * <ol>
 *   <li>承诺日过了还没还 → 标记违约 + 优先级提升一级 + 拉回今日待办</li>
 *   <li>承诺日还没到 → 不处理</li>
 *   <li><b>钱已经还了 → 不算违约</b>（即使任务还开着、承诺日已过）</li>
 *   <li>幂等：跑多次也只标记一次（否则优先级会每天降一格，很快全变最高）</li>
 *   <li>优先级已是 1（最高）时不再往下降</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PromisedPayViolationIntegrationTest {

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

    private static final String USER_ID = "it-viol-user";
    private static final String PLAN_ID = "it-viol-plan";

    @Autowired private PromisedPayViolationChecker checker;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM collection_record");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM monthly_repayment");
        jdbc.update("DELETE FROM repayment_plan");

        jdbc.update("INSERT INTO repayment_plan "
                + "(plan_id, application_id, user_id, total_amount, total_principal, "
                + " total_interest, repayment_type, term_count) "
                + "VALUES (?, 'it-viol-app', ?, 400.00, 350.00, 50.00, 'EQUAL_INSTALLMENT', 4)",
                PLAN_ID, USER_ID);
    }

    /** 造一期逾期 + 一个已承诺还款的任务 */
    private void seedPromise(String repaymentId, int promiseDaysFromNow,
                            int priority, String repaymentStatus) {
        jdbc.update("INSERT INTO monthly_repayment "
                + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                + " due_date, repayment_status, overdue_days, overdue_level) "
                + "VALUES (?, ?, 1, 100.00, 10.00, 110.00, "
                + "        DATE_SUB(CURDATE(), INTERVAL 40 DAY), ?, 40, 'M2')",
                repaymentId, PLAN_ID, repaymentStatus);

        jdbc.update("INSERT INTO collection_task "
                + "(task_id, user_id, repayment_id, plan_id, overdue_level, overdue_days, "
                + " priority, status, promised_pay_date, promise_violated, next_follow_date) "
                + "VALUES (?, ?, ?, ?, 'M2', 40, ?, 'promised', "
                + "        DATE_ADD(CURDATE(), INTERVAL ? DAY), 0, CURDATE())",
                "CT-" + repaymentId, USER_ID, repaymentId, PLAN_ID, priority, promiseDaysFromNow);
    }

    private CollectionTask taskOf(String repaymentId) {
        return jdbc.queryForObject(
                "SELECT task_id, priority, status, promise_violated, next_follow_date "
                        + "FROM collection_task WHERE repayment_id = ?",
                (rs, i) -> {
                    CollectionTask t = new CollectionTask();
                    t.setTaskId(rs.getString("task_id"));
                    t.setPriority(rs.getInt("priority"));
                    t.setStatus(rs.getString("status"));
                    t.setPromiseViolated(rs.getInt("promise_violated"));
                    t.setNextFollowDate(rs.getDate("next_follow_date"));
                    return t;
                }, repaymentId);
    }

    @Test
    @DisplayName("★ 承诺日过了还没还 → 标记违约 + 优先级提升 + 拉回今日待办")
    void marksViolation() {
        seedPromise("viol-1", -1, 2, "overdue");   // 昨天承诺到期，优先级 2

        int violated = checker.check();

        assertThat(violated).isEqualTo(1);
        CollectionTask t = taskOf("viol-1");
        assertThat(t.getPromiseViolated()).as("要标出来，让催收员知道这个人说话不算数").isEqualTo(1);
        assertThat(t.getPriority()).as("优先级 2 -> 1（违约的案子该往前排）").isEqualTo(1);
        assertThat(t.getStatus()).as("承诺已失效，回到跟进中").isEqualTo("in_progress");
    }

    @Test
    @DisplayName("承诺日还没到 → 不处理")
    void ignoresFuturePromise() {
        seedPromise("viol-2", 3, 2, "overdue");    // 三天后才到期

        assertThat(checker.check()).isZero();
        assertThat(taskOf("viol-2").getPromiseViolated()).isZero();
    }

    @Test
    @DisplayName("★ 钱已经还了 → 不算违约（即使任务还开着、承诺日已过）")
    void ignoresPaidRepayment() {
        seedPromise("viol-3", -1, 2, "paid");      // 承诺过期了，但期次已还清

        assertThat(checker.check())
                .as("光看 promised_pay_date 会误判成违约，必须 JOIN 还款明细")
                .isZero();
        assertThat(taskOf("viol-3").getPromiseViolated()).isZero();
    }

    @Test
    @DisplayName("幂等：跑多次只标记一次（否则优先级每天降一格，很快全变最高）")
    void isIdempotent() {
        seedPromise("viol-4", -1, 3, "overdue");   // 优先级 3 -> 违约后 2

        assertThat(checker.check()).isEqualTo(1);
        assertThat(taskOf("viol-4").getPriority()).isEqualTo(2);

        assertThat(checker.check()).as("第二次不该再标记").isZero();
        assertThat(taskOf("viol-4").getPriority())
                .as("优先级不能被重复降低")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("优先级已是 1（最高）时不再往下降")
    void priorityDoesNotGoBelowOne() {
        seedPromise("viol-5", -1, 1, "overdue");

        assertThat(checker.check()).isEqualTo(1);
        assertThat(taskOf("viol-5").getPriority())
                .as("1 已经最高，不能变成 0 或负数")
                .isEqualTo(1);
        assertThat(taskOf("viol-5").getPromiseViolated()).isEqualTo(1);
    }

    @Test
    @DisplayName("已关闭的任务不参与检查")
    void ignoresClosedTask() {
        seedPromise("viol-6", -1, 2, "overdue");
        jdbc.update("UPDATE collection_task SET status = 'closed', closed_at = NOW() "
                + "WHERE repayment_id = 'viol-6'");

        assertThat(checker.check()).isZero();
        assertThat(taskOf("viol-6").getPromiseViolated()).isZero();
    }

    @Test
    @DisplayName("没有任何承诺到期时正常返回 0，不报错")
    void handlesEmpty() {
        assertThat(checker.check()).isZero();
    }
}
