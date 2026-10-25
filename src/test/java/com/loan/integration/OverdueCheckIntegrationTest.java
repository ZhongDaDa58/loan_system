package com.loan.integration;

import com.loan.entity.MonthlyRepayment;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.NotificationMapper;
import com.loan.service.impl.OverdueCheckService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逾期检测与分级的集成测试（逾期处理功能升级 第 2 步）
 *
 * <p><b>为什么必须有这个测试</b>：这一步改的是<b>整个逾期体系的地基断点</b> ——
 * 从"只扫 unpaid"改成"扫 unpaid + overdue 并回算天数"。
 * 如果只手工验证一次，将来谁把 SQL 改回 {@code selectByStatus('unpaid')}，
 * 逾期天数又会静默停止更新，而<b>没有任何东西会报错</b>。
 *
 * <p><b>核心断言</b>：
 * <ol>
 *   <li><b>分级正确</b>：5 / 40 / 70 天 → M1 / M2 / M3</li>
 *   <li><b>逾期后持续跟踪</b>：已经是 overdue 的记录，再跑一次仍然会更新天数
 *       （这正是原实现的断点：改成 overdue 后就再也扫不到）</li>
 *   <li><b>当天到期不算逾期</b>：避免"今天还没过完就催"</li>
 *   <li><b>通知不重复</b>：同等级只发一次，不会每天轰炸</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OverdueCheckIntegrationTest {

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

    private static final String USER_ID = "it-overdue-user";
    private static final String PLAN_ID = "it-overdue-plan";

    @Autowired private OverdueCheckService overdueCheckService;
    @Autowired private MonthlyRepaymentMapper monthlyRepaymentMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // 还款计划（selectOverdueByUserId / 通知取 userId 都要 JOIN 它）
        jdbc.update("INSERT INTO repayment_plan "
                + "(plan_id, application_id, user_id, total_amount, total_principal, "
                + " total_interest, repayment_type, term_count) "
                + "VALUES (?, 'it-overdue-app', ?, 999.00, 900.00, 99.00, 'EQUAL_INSTALLMENT', 3)",
                PLAN_ID, USER_ID);

        // 三笔不同逾期天数的明细：5 天 / 40 天 / 70 天
        insertRepayment("it-ovd-5",  5,  "unpaid");
        insertRepayment("it-ovd-40", 40, "unpaid");
        insertRepayment("it-ovd-70", 70, "unpaid");
        // 当天到期（D+0）—— 不该被判逾期
        insertRepayment("it-ovd-0",  0,  "unpaid");
    }

    private void insertRepayment(String id, int daysAgo, String status) {
        jdbc.update("INSERT INTO monthly_repayment "
                        + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                        + " due_date, repayment_status) "
                        + "VALUES (?, ?, 1, 300.00, 33.00, 333.00, DATE_SUB(CURDATE(), INTERVAL ? DAY), ?)",
                id, PLAN_ID, daysAgo, status);
    }

    private MonthlyRepayment reload(String id) {
        return monthlyRepaymentMapper.selectById(id);
    }

    @Test
    @Transactional
    @DisplayName("按天数分级：5→M1 / 40→M2 / 70→M3")
    void levelsByDays() {
        overdueCheckService.manualCheckOverdue();

        MonthlyRepayment m1 = reload("it-ovd-5");
        MonthlyRepayment m2 = reload("it-ovd-40");
        MonthlyRepayment m3 = reload("it-ovd-70");

        assertThat(m1.getRepaymentStatus()).isEqualTo("overdue");
        assertThat(m1.getOverdueDays()).isEqualTo(5);
        assertThat(m1.getOverdueLevel()).isEqualTo("M1");

        assertThat(m2.getOverdueDays()).isEqualTo(40);
        assertThat(m2.getOverdueLevel()).isEqualTo("M2");

        assertThat(m3.getOverdueDays()).isEqualTo(70);
        assertThat(m3.getOverdueLevel()).isEqualTo("M3");
    }

    @Test
    @Transactional
    @DisplayName("★ 断点修复：已经是 overdue 的记录，再跑仍会更新天数（原实现扫不到）")
    void keepsTrackingAfterMarkedOverdue() {
        overdueCheckService.manualCheckOverdue();
        assertThat(reload("it-ovd-40").getOverdueDays()).isEqualTo(40);

        // 把到期日再往前挪 10 天 —— 模拟"时间又过了 10 天"
        jdbc.update("UPDATE monthly_repayment SET due_date = DATE_SUB(due_date, INTERVAL 10 DAY) "
                + "WHERE repayment_id = 'it-ovd-40'");

        // 关键：此时它是 overdue 状态，原实现（只扫 unpaid）这里会"扫不到"→ 天数停住不动
        overdueCheckService.manualCheckOverdue();

        assertThat(reload("it-ovd-40").getOverdueDays())
                .as("已经是 overdue 也必须继续跟踪天数")
                .isEqualTo(50);
        assertThat(reload("it-ovd-40").getOverdueLevel())
                .as("50 天应升级到 M2")
                .isEqualTo("M2");
    }

    @Test
    @Transactional
    @DisplayName("等级跃迁：M2 → M3")
    void levelUpgrade() {
        overdueCheckService.manualCheckOverdue();
        assertThat(reload("it-ovd-40").getOverdueLevel()).isEqualTo("M2");

        // 逾期天数推进到 65 天 → M3
        jdbc.update("UPDATE monthly_repayment SET due_date = DATE_SUB(CURDATE(), INTERVAL 65 DAY) "
                + "WHERE repayment_id = 'it-ovd-40'");
        overdueCheckService.manualCheckOverdue();

        MonthlyRepayment r = reload("it-ovd-40");
        assertThat(r.getOverdueDays()).isEqualTo(65);
        assertThat(r.getOverdueLevel()).isEqualTo("M3");
        assertThat(r.getOverdueLevelChangedAt())
                .as("等级跃迁要记录时间（用于只在跃迁时通知）")
                .isNotNull();
    }

    @Test
    @Transactional
    @DisplayName("当天到期（D+0）不算逾期")
    void dueTodayIsNotOverdue() {
        overdueCheckService.manualCheckOverdue();

        MonthlyRepayment r = reload("it-ovd-0");
        assertThat(r.getRepaymentStatus())
                .as("到期当天还没过完，不该判逾期")
                .isEqualTo("unpaid");
        assertThat(r.getOverdueLevel()).isNull();
    }

    @Test
    @Transactional
    @DisplayName("通知按等级去重：连跑两次不会重复推送")
    void notificationDeduplicatedPerLevel() {
        overdueCheckService.manualCheckOverdue();

        int m1Count = notificationMapper.countByTypeAndRelatedId("overdue_m1", "it-ovd-5");
        int m2Count = notificationMapper.countByTypeAndRelatedId("overdue_m2", "it-ovd-40");
        int m3Count = notificationMapper.countByTypeAndRelatedId("overdue_m3", "it-ovd-70");

        assertThat(m1Count).isEqualTo(1);
        assertThat(m2Count).isEqualTo(1);
        assertThat(m3Count).isEqualTo(1);

        // 再跑一次：天数不变、等级不变 → 不该再发通知
        overdueCheckService.manualCheckOverdue();

        assertThat(notificationMapper.countByTypeAndRelatedId("overdue_m1", "it-ovd-5"))
                .as("同等级只发一次，不能每天轰炸")
                .isEqualTo(1);
        assertThat(notificationMapper.countByTypeAndRelatedId("overdue_m2", "it-ovd-40"))
                .isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("幂等：同一天连跑两次，天数与等级一致")
    void idempotentOnSameDay() {
        overdueCheckService.manualCheckOverdue();
        List<MonthlyRepayment> first = List.of(
                reload("it-ovd-5"), reload("it-ovd-40"), reload("it-ovd-70"));

        overdueCheckService.manualCheckOverdue();
        List<MonthlyRepayment> second = List.of(
                reload("it-ovd-5"), reload("it-ovd-40"), reload("it-ovd-70"));

        for (int i = 0; i < first.size(); i++) {
            assertThat(second.get(i).getOverdueDays())
                    .as("第 %d 笔天数应一致", i + 1)
                    .isEqualTo(first.get(i).getOverdueDays());
            assertThat(second.get(i).getOverdueLevel())
                    .isEqualTo(first.get(i).getOverdueLevel());
        }
    }

    @Test
    @Transactional
    @DisplayName("已还款的记录不受影响")
    void paidRecordsUntouched() {
        jdbc.update("UPDATE monthly_repayment SET repayment_status = 'paid', repayment_time = NOW() "
                + "WHERE repayment_id = 'it-ovd-5'");

        overdueCheckService.manualCheckOverdue();

        MonthlyRepayment r = reload("it-ovd-5");
        assertThat(r.getRepaymentStatus()).as("已还款不能被改回 overdue").isEqualTo("paid");
        assertThat(r.getOverdueLevel()).isNull();
    }
}
