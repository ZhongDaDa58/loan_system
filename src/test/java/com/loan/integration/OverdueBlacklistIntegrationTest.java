package com.loan.integration;

import com.loan.entity.OverdueBlacklist;
import com.loan.service.OverdueBlacklistService;
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

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逾期黑名单测试（逾期处理功能升级 第 6 步）
 *
 * <p>锁住四件事：
 * <ol>
 *   <li>加入后立即生效，重复加入是幂等的（不会堆出多条）</li>
 *   <li>解除后失效</li>
 *   <li><b>过期后自动失效，但 status 仍然是 active</b> —— 这是刻意设计：
 *       status 只反映"是否人工解除"，过期靠时间判断（见 V5 迁移注释 ③）</li>
 *   <li>原因必填（否则审核员看到拦截提示也不知道为什么）</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OverdueBlacklistIntegrationTest {

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

    private static final String USER_ID = "it-bl-user";

    @Autowired private OverdueBlacklistService overdueBlacklistService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM overdue_blacklist");
    }

    private static Date daysFromNow(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTime();
    }

    @Test
    @Transactional
    @DisplayName("加入后立即生效；重复加入幂等，不会堆出多条")
    void addIsIdempotent() {
        assertThat(overdueBlacklistService.isBlacklisted(USER_ID)).isFalse();

        boolean first = overdueBlacklistService.add(USER_ID, "M3 逾期 75 天",
                "M3", "rp-001", daysFromNow(180), "admin");
        assertThat(first).as("首次加入应返回 true").isTrue();
        assertThat(overdueBlacklistService.isBlacklisted(USER_ID)).isTrue();

        // 再加一次：不该重复
        boolean second = overdueBlacklistService.add(USER_ID, "重复加一次",
                "M3", "rp-002", daysFromNow(180), "admin");
        assertThat(second).as("已有生效记录时返回 false").isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM overdue_blacklist WHERE user_id = ?",
                Integer.class, USER_ID))
                .as("只应该有一条记录")
                .isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("解除后失效；重复解除返回 0（不报错）")
    void releaseWorks() {
        overdueBlacklistService.add(USER_ID, "测试", "M2", null, null, "admin");
        assertThat(overdueBlacklistService.isBlacklisted(USER_ID)).isTrue();

        int released = overdueBlacklistService.release(USER_ID, "已结清并满观察期", "admin");
        assertThat(released).isEqualTo(1);
        assertThat(overdueBlacklistService.isBlacklisted(USER_ID)).isFalse();

        // 再解一次：本来就不在名单里，返回 0 而不是抛异常
        assertThat(overdueBlacklistService.release(USER_ID, "再解一次", "admin")).isZero();
    }

    @Test
    @Transactional
    @DisplayName("★ 过期后自动失效，但 status 仍是 active（刻意设计：status 只表示人工解除）")
    void expiredRecordsAreNotActive() {
        // 到期时间设为昨天
        overdueBlacklistService.add(USER_ID, "已过期的记录", "M1", null, daysFromNow(-1), "admin");

        assertThat(overdueBlacklistService.isBlacklisted(USER_ID))
                .as("过期后不应再拦截")
                .isFalse();
        assertThat(overdueBlacklistService.getActive(USER_ID))
                .as("查不到生效中的记录")
                .isNull();

        // status 没被改成 released —— 这是设计而不是 bug
        assertThat(jdbc.queryForObject(
                "SELECT status FROM overdue_blacklist WHERE user_id = ?",
                String.class, USER_ID))
                .as("过期是时间判断，不改 status（否则会有一个隐性依赖的每日任务）")
                .isEqualTo("active");
    }

    @Test
    @Transactional
    @DisplayName("过期后可以被重新加入（不算重复）")
    void canReAddAfterExpire() {
        overdueBlacklistService.add(USER_ID, "第一次", "M2", null, daysFromNow(-1), "admin");
        assertThat(overdueBlacklistService.isBlacklisted(USER_ID)).isFalse();

        boolean again = overdueBlacklistService.add(USER_ID, "再次严重逾期",
                "M3", null, daysFromNow(90), "admin");
        assertThat(again).as("旧记录已过期，应能重新加入").isTrue();
        assertThat(overdueBlacklistService.isBlacklisted(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("原因必填 —— 否则审核员看到拦截提示也不知道为什么")
    void reasonRequired() {
        assertThatThrownBy(() -> overdueBlacklistService.add(USER_ID, "  ", "M3", null, null, "admin"))
                .isInstanceOf(com.loan.exception.BusinessException.class)
                .hasMessageContaining("原因");

        assertThatThrownBy(() -> overdueBlacklistService.add("", "有原因但没用户", "M3", null, null, "admin"))
                .isInstanceOf(com.loan.exception.BusinessException.class)
                .hasMessageContaining("用户 ID");
    }

    @Test
    @Transactional
    @DisplayName("分页查询生效中的名单，size 超上限会被截断（防止拉爆内存）")
    void listActiveWithPaging() {
        for (int i = 0; i < 3; i++) {
            overdueBlacklistService.add("it-bl-u" + i, "测试" + i, "M3", null, null, "admin");
        }
        // 造一条过期的，不该出现在列表里
        overdueBlacklistService.add("it-bl-expired", "过期了", "M1", null, daysFromNow(-1), "admin");

        List<OverdueBlacklist> page = overdueBlacklistService.listActive(1, 2);
        assertThat(page).as("每页 2 条").hasSize(2);
        assertThat(overdueBlacklistService.countActive())
                .as("只算生效中的 3 条")
                .isEqualTo(3);

        // size 传超大也不炸
        assertThat(overdueBlacklistService.listActive(1, 100000)).hasSize(3);
    }
}
