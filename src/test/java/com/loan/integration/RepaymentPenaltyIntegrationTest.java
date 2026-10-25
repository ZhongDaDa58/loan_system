package com.loan.integration;

import com.loan.entity.dto.RepaymentSubmitDTO;
import com.loan.entity.vo.DueItemVO;
import com.loan.entity.vo.DueItemsVO;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.service.RepaymentService;
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
 * 罚息生效后的还款链路测试（逾期处理功能升级 第 3 步-b）
 *
 * <p><b>为什么必须补这个测试</b>：第 3 步-b 改了两处 —— 展示层把罚息算进 {@code totalDue}、
 * 扣款校验要求 {@code submitAmount} 也含罚息。这两处<b>必须一致</b>，
 * 但改动时<b>完全没有测试覆盖</b>：只要有人把其中一处改回去，
 * 用户就会遇到"看到 338 元，提交 338 却报金额不一致"这种诡异问题。
 *
 * <p>本测试锁住两条（<b>两条都曾经是坏的</b>）：
 * <ol>
 *   <li>{@code queryDueItems} 的 {@code totalDue} = 当期应还 + 罚息</li>
 *   <li>{@code submitRepayment} 的金额校验基准也是"含罚息"，传不含罚息的会被拒</li>
 * </ol>
 *
 * <p><b>这个测试的价值已经被证明过一次</b>：第 3 步-b 提交时，
 * SQL 改成了 {@code mr.penalty_amount AS overduePenalty}，但 Service 里那句
 * {@code item.setOverduePenalty(BigDecimal.ZERO)} 漏改（一次字符串替换静默失败）。
 * 结果接口返回罚息恒为 0、{@code totalDue} 不含罚息 —— 而且编译通过、测试全绿，
 * 因为没有任何测试覆盖这一段。是靠启动应用调真实接口才发现的。
 * 现在这条断言就是防它再退回去。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RepaymentPenaltyIntegrationTest {

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

    private static final String USER_ID = "it-penalty-user";
    private static final String PLAN_ID = "it-penalty-plan";
    private static final String REPAYMENT_ID = "it-penalty-repayment";

    /** 当期应还（本金+利息） */
    private static final BigDecimal AMOUNT = new BigDecimal("333.00");
    /** 累计罚息 */
    private static final BigDecimal PENALTY = new BigDecimal("5.00");

    @Autowired private RepaymentService repaymentService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO repayment_plan "
                + "(plan_id, application_id, user_id, total_amount, total_principal, "
                + " total_interest, repayment_type, term_count) "
                + "VALUES (?, 'it-penalty-app', ?, 333.00, 300.00, 33.00, 'EQUAL_INSTALLMENT', 1)",
                PLAN_ID, USER_ID);

        // 一笔逾期记录（先插，再单独 UPDATE 罚息 —— 避免 INSERT 里参数绑定干扰排查）
        jdbc.update("INSERT INTO monthly_repayment "
                + "(repayment_id, plan_id, term, principal, interest, repayment_amount, "
                + " due_date, repayment_status) "
                + "VALUES (?, ?, 1, 300.00, 33.00, ?, DATE_SUB(CURDATE(), INTERVAL 20 DAY), "
                + "        'overdue')",
                REPAYMENT_ID, PLAN_ID, AMOUNT);
        jdbc.update("UPDATE monthly_repayment SET overdue_days = 20, overdue_level = 'M1', "
                + "penalty_amount = ? WHERE repayment_id = ?", PENALTY, REPAYMENT_ID);
    }

    @Test
    @Transactional
    @DisplayName("待还项展示：totalDue = 当期应还 + 罚息")
    void queryDueItemsIncludesPenalty() {
        Result<DueItemsVO> r = repaymentService.queryDueItems(USER_ID);

        DueItemsVO vo = r.getData();
        assertThat(vo.getItems()).as("应有一条待还项").hasSize(1);

        DueItemVO item = vo.getItems().get(0);
        assertThat(item.getRepaymentAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(item.getOverduePenalty())
                .as("罚息要从 penalty_amount 读出来，不能被覆盖成 0")
                .isEqualByComparingTo(PENALTY);
        assertThat(item.getTotalDue())
                .as("应还总额必须含罚息：333 + 5 = 338")
                .isEqualByComparingTo(new BigDecimal("338.00"));
    }

    @Test
    @Transactional
    @DisplayName("★ 还款校验：传不含罚息的金额会被拒（与展示层保持一致）")
    void submitRepaymentRequiresPenaltyIncluded() {
        // 只传本金利息（旧前端行为）→ 应该因为少付罚息被拒
        BusinessException e = assertThrowsBusinessException(AMOUNT);

        assertThat(e.getMessage())
                .as("不能静默放过少付罚息的请求")
                .contains("金额不一致");
    }

    private BusinessException assertThrowsBusinessException(BigDecimal submitAmount) {
        RepaymentSubmitDTO dto = new RepaymentSubmitDTO();
        dto.setRepaymentId(REPAYMENT_ID);
        dto.setRepaymentAmount(submitAmount);

        BusinessException[] holder = new BusinessException[1];
        assertThatThrownBy(() -> repaymentService.submitRepayment(dto, USER_ID))
                .as("传 %s 应被拒", submitAmount)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> holder[0] = (BusinessException) ex);
        return holder[0];
    }
}
