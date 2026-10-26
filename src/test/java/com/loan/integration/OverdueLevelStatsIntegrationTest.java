package com.loan.integration;

import com.loan.entity.vo.stats.OverdueLevelStatsVO;
import com.loan.service.OverdueStatsService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 逾期分级看板测试（逾期处理功能升级 第 6 步）
 *
 * <p>锁住四件事：
 * <ol>
 *   <li>分级分布：M1/M2/M3 的笔数、金额正确</li>
 *   <li><b>金额含罚息</b>（逾期客户实际欠的是本金利息 + 罚息）</li>
 *   <li>某个等级没有记录时，<b>返回体里仍然有这一档</b>（count=0）—— 否则前端柱状图会缺柱子</li>
 *   <li>非逾期记录（paid）不参与统计</li>
 * </ol>
 *
 * <p>注意：这个接口是<b>全表统计</b>（不加 userId 过滤），所以测试数据的精确控制
 * 依赖 Testcontainers 的容器隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OverdueLevelStatsIntegrationTest {

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

    private static final String USER_ID = "it-lvstats-user";
    private static final String PLAN_ID = "it-lvstats-plan";

    @Autowired private OverdueStatsService overdueStatsService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM monthly_repayment");
        jdbc.update("DELETE FROM repayment_plan");

        jdbc.update("INSERT INTO repayment_plan "
                + "(plan_id, application_id, user_id, total_amount, total_principal, "
                + " total_interest, repayment_type, term_count) "
                + "VALUES (?, 'it-lvstats-app', ?, 900.00, 800.00, 100.00, 'EQUAL_INSTALLMENT', 3)",
                PLAN_ID, USER_ID);

        // 三档各一笔：金额 100/200/300，罚息 10/20/30 -> 分布金额应为 110/220/330
        insertOverdue("it-lv-m1", 5, "M1", "100.00", "10.00");
        insertOverdue("it-lv-m2", 40, "M2", "200.00", "20.00");
        insertOverdue("it-lv-m3", 70, "M3", "300.00", "30.00");

        // 已还清 —— 不该出现在任何统计里
        jdbc.update("INSERT INTO monthly_repayment "
                + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                + " due_date, repayment_status, overdue_days, overdue_level) "
                + "VALUES ('it-lv-paid', ?, 4, 300.00, 33.00, 333.00, "
                + "        DATE_SUB(CURDATE(), INTERVAL 90 DAY), 'paid', 90, 'M3')",
                PLAN_ID);
    }

    private void insertOverdue(String id, int daysAgo, String level,
                              String amount, String penalty) {
        // overdue_level_changed_at 设为昨天，便于迁徙事件测试
        jdbc.update("INSERT INTO monthly_repayment "
                + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                + " due_date, repayment_status, overdue_days, overdue_level, "
                + " penalty_amount, overdue_level_changed_at) "
                + "VALUES (?, ?, 1, 300.00, 33.00, ?, DATE_SUB(CURDATE(), INTERVAL ? DAY), "
                + "        'overdue', ?, ?, ?, DATE_SUB(NOW(), INTERVAL 1 DAY))",
                id, PLAN_ID, new BigDecimal(amount), daysAgo, daysAgo, level,
                new BigDecimal(penalty));
    }

    private Map<String, OverdueLevelStatsVO.LevelBucket> distribution() {
        List<OverdueLevelStatsVO.LevelBucket> list =
                overdueStatsService.getLevelStats(null, null).getData().getDistribution();
        return list.stream().collect(Collectors.toMap(
                OverdueLevelStatsVO.LevelBucket::getLevel, Function.identity()));
    }

    @Test
    @DisplayName("分级分布：三档笔数、金额（含罚息）、占比都正确")
    void distributionByLevel() {
        OverdueLevelStatsVO vo = overdueStatsService.getLevelStats(null, null).getData();

        assertThat(vo.getTotalCount())
                .as("只算 overdue，paid 那笔不算")
                .isEqualTo(3);
        assertThat(vo.getTotalAmount())
                .as("110 + 220 + 330")
                .isEqualByComparingTo(new BigDecimal("660.00"));

        Map<String, OverdueLevelStatsVO.LevelBucket> d = distribution();
        assertThat(d).as("三档齐全").containsOnlyKeys("M1", "M2", "M3");

        assertThat(d.get("M1").getCount()).isEqualTo(1);
        assertThat(d.get("M1").getAmount())
                .as("金额必须含罚息：100 + 10")
                .isEqualByComparingTo(new BigDecimal("110.00"));
        assertThat(d.get("M2").getAmount()).isEqualByComparingTo(new BigDecimal("220.00"));
        assertThat(d.get("M3").getAmount()).isEqualByComparingTo(new BigDecimal("330.00"));

        // 每档 1/3 -> 33.3%
        assertThat(d.get("M1").getCountRatio())
                .isEqualByComparingTo(new BigDecimal("33.3"));
        assertThat(d.get("M1").getLabel()).contains("1-30天");
    }

    @Test
    @DisplayName("某个等级没有记录时仍然返回这一档（count=0），否则前端柱状图会缺柱子")
    void emptyLevelsStillPresent() {
        jdbc.update("DELETE FROM monthly_repayment WHERE repayment_id IN ('it-lv-m1','it-lv-m2')");

        Map<String, OverdueLevelStatsVO.LevelBucket> d = distribution();

        assertThat(d).as("即使只剩 M3，也要返回三档").containsOnlyKeys("M1", "M2", "M3");
        assertThat(d.get("M1").getCount()).isZero();
        assertThat(d.get("M1").getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.get("M3").getCount()).isEqualTo(1);
        assertThat(d.get("M3").getCountRatio())
                .as("只有 1 笔 M3 时占比 100%")
                .isEqualByComparingTo(new BigDecimal("100.0"));
    }

    @Test
    @DisplayName("迁徙：期内升级事件数正确，率是近似值（分母=当前存量+期内升走量）")
    void migrationCounts() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        OverdueLevelStatsVO vo = overdueStatsService
                .getLevelStats(yesterday.toString(), LocalDate.now().toString())
                .getData();

        OverdueLevelStatsVO.Migration m = vo.getMigration();

        // 造数据时三笔的 changed_at 都是"昨天" -> 期内升到 M2 的 1 笔、升到 M3 的 1 笔
        assertThat(m.getM1ToM2Count()).as("期内升到 M2 的笔数").isEqualTo(1);
        assertThat(m.getM2ToM3Count()).as("期内升到 M3 的笔数").isEqualTo(1);

        // M1->M2 率 = 1 / (当前 M1 存量 1 + 期内升走 1) = 50.0%
        assertThat(m.getM1ToM2Rate()).isEqualByComparingTo(new BigDecimal("50.0"));
        // M2->M3 率 = 1 / (当前 M2 存量 1 + 期内升走 1) = 50.0%
        assertThat(m.getM2ToM3Rate()).isEqualByComparingTo(new BigDecimal("50.0"));

        assertThat(m.getCaliberNote())
                .as("必须把「这是近似值」直接告诉调用方，避免被当成精确值")
                .contains("近似");
    }

    @Test
    @DisplayName("统计区间之外的升级事件不计入迁徙")
    void upgradeOutsidePeriodIgnored() {
        // 区间取很久以前 -> 昨天发生的升级不该计入
        OverdueLevelStatsVO vo = overdueStatsService
                .getLevelStats(LocalDate.now().minusDays(30).toString(),
                        LocalDate.now().minusDays(20).toString())
                .getData();

        assertThat(vo.getMigration().getM1ToM2Count()).isZero();
        assertThat(vo.getMigration().getM2ToM3Count()).isZero();
        assertThat(vo.getMigration().getM1ToM2Rate())
                .as("没有升级事件时率为 0，不能除零炸掉")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("没有任何逾期时返回三档全 0，不报错")
    void noOverdueAtAll() {
        jdbc.update("DELETE FROM monthly_repayment");

        OverdueLevelStatsVO vo = overdueStatsService.getLevelStats(null, null).getData();

        assertThat(vo.getTotalCount()).isZero();
        assertThat(vo.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(vo.getDistribution()).hasSize(3);
        assertThat(distribution().values()).allSatisfy(b -> {
            assertThat(b.getCount()).isZero();
            assertThat(b.getCountRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }
}
