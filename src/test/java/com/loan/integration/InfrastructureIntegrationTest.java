package com.loan.integration;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试基础设施验证（阶段三 3.3）
 *
 * <p><b>目的</b>：先证明"管道通了"—— Spring 上下文能加载、Testcontainers 能起 MySQL、
 * Flyway 能自动建表并导入种子。业务场景的测试再逐个往上加。
 *
 * <p><b>为什么用 Testcontainers 而不是 H2</b>：
 * 本项目 31 个手写 SQL Mapper 大量使用 MySQL 特有语法
 * （{@code JSON_OBJECT}、{@code JSON_EXTRACT}、{@code ON DUPLICATE KEY UPDATE}、
 * {@code LIMIT x,y}、{@code information_schema} 查询），H2 跑不通。
 *
 * <p><b>前置条件</b>：本机 Docker 必须运行（Testcontainers 通过 Docker API 起容器）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)   // Docker 不可用时跳过（本机 Docker Desktop 不稳定）
class InfrastructureIntegrationTest {

    /**
     * 每个测试类共享一个 MySQL 容器；容器用完会自动销毁。
     * 字符集必须显式指定 —— 否则中文会乱码（本项目历史踩过的坑）。
     */
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("loan_risk_control")
            .withUsername("root")
            .withPassword("test")
            // 项目里有用分号分隔的多语句（如初始化脚本），需要 allowMultiQueries
            .withUrlParam("allowMultiQueries", "true")
            .withUrlParam("serverTimezone", "Asia/Shanghai")
            .withCommand("--character-set-server=utf8mb4",
                         "--collation-server=utf8mb4_general_ci");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        // ⚠️ 不要手工拼接 "&xxx=yyy"：getJdbcUrl() 返回的 URL 不带 "?"，
        //    用 & 拼接会把参数当成数据库名的一部分（Unknown database 'xxx&allowMultiQueries=true'）。
        //    参数请用 withUrlParam(...) 指定，它会体现在 getJdbcUrl() 里。
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Spring 上下文加载成功，DataSource 可用")
    void contextLoads() {
        assertNotNull(jdbc);
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        assertEquals(1, one);
    }

    @Test
    @DisplayName("Flyway 自动建表：31 张业务表 + flyway_schema_history")
    void flywayCreatesAllTables() {
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE table_schema = DATABASE()",
                Integer.class);
        assertNotNull(tableCount);
        assertTrue(tableCount >= 32,
                "应至少有 31 张业务表 + flyway_schema_history，实际 " + tableCount);

        Integer history = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class);
        assertTrue(history != null && history >= 2, "应有 V1 与 R__ 两条成功记录");
    }

    @Test
    @DisplayName("Flyway 种子数据：规则 19 / 产品 3 / 定价档位 5")
    void flywaySeedsConfigData() {
        assertEquals(19, count("risk_rule"), "风控规则应有 19 条");
        assertEquals(3, count("loan_product"), "贷款产品应有 3 个");
        assertEquals(5, count("loan_pricing_tier"), "定价档位应有 5 档");
    }

    @Test
    @DisplayName("★ 中文未乱码（字符集坑的回归守护）")
    void chineseIsNotGarbled() {
        String name = jdbc.queryForObject(
                "SELECT name FROM risk_rule WHERE rule_code = 'L1-01'", String.class);
        assertEquals("模拟器/非真机", name,
                "中文必须正常 —— 本机 mysql 默认 gbk 曾导致乱码（P-001）");
    }

    @Test
    @DisplayName("业务表为空（种子只含配置，不含业务数据）")
    void businessTablesShouldBeEmpty() {
        assertEquals(0, count("loan_application"), "迁移脚本不应导入业务数据");
        assertEquals(0, count("sys_user"), "用户数据应由测试自行准备");
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
