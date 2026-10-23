package com.loan.security;

import com.loan.entity.LoanApplication;
import com.loan.entity.SysUser;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.SysUserMapper;
import com.loan.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;   // Boot 4.0 包名变了（3.x 是 boot.test.autoconfigure.web.servlet）
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 安全访问控制的自动化回归测试（阶段四 P1）
 *
 * <p><b>为什么必须自动化</b>：阶段四 4.1 的 14 项越权验证是<b>手工用 PowerShell 打的</b> ——
 * 一旦有人改 `SecurityConfig`（加个 `permitAll`、调下 matcher 顺序），
 * <b>没有任何测试会拦</b>，漏洞会静默回归。这份测试就是那道护栏。
 *
 * <p><b>为什么用真实 JWT 而不是 {@code @WithMockUser}</b>：
 * {@code @WithMockUser} 直接往 SecurityContext 塞 Authentication，
 * <b>完全绕过 {@code JwtAuthenticationFilter}</b>。而本项目最凶的那个坑恰好就在这条链路上：
 * 库里 {@code sys_user.role} 是<b>小写</b>（user/admin），
 * 而 {@code hasRole("ADMIN")} 匹配的是 {@code ROLE_ADMIN} ——
 * 漏了 {@code toUpperCase()} 会让<b>管理员也 403</b>。
 * 用真实 token 才能真正覆盖"token → 过滤器 → UserDetails → 权限"这条链路。
 *
 * <p><b>断言策略</b>：只断言"权限结论"，不耦合业务细节 ——
 * <ul>
 *   <li>越权 → <b>必须 403</b>（严格）</li>
 *   <li>授权通过 → <b>不是 403</b>（业务层返回 200/400/404 都算通过鉴权）</li>
 * </ul>
 * 这样测试不会因为"某条业务数据不存在"而误报，也就不会被人当成"脆弱测试"删掉。
 *
 * <p><b>数据自给自足</b>：{@code @Transactional} + {@code @BeforeEach} 自造用户和申请，
 * <b>不依赖开发库里恰好有数据</b>（这个教训在 CI 上付过代价）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional   // 测试后回滚：自造的用户/申请不会留在库里
@DisplayName("安全访问控制（RBAC + 资源归属）")
class SecurityAccessControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private LoanApplicationMapper loanApplicationMapper;
    @Autowired private JwtUtil jwtUtil;

    /** 普通用户 A（申请 appAId 的 owner） */
    private String userAId;
    private String userAToken;
    /** 普通用户 B（申请 appBId 的 owner） */
    private String userBId;
    private String userBToken;
    /** 管理员 */
    private String adminId;
    private String adminToken;

    private String appAId;
    private String appBId;

    @BeforeEach
    void seed() {
        userAId = newId();
        userBId = newId();
        adminId = newId();

        insertUser(userAId, "普通用户A", "user");
        insertUser(userBId, "普通用户B", "user");
        insertUser(adminId, "管理员", "admin");

        // ⚠️ role 传小写：库里就是这个形态，让 JwtAuthenticationFilter + DbUserDetailsService
        //    走真实的"小写 → ROLE_大写"转换链路
        userAToken = jwtUtil.generateToken(userAId, "user");
        userBToken = jwtUtil.generateToken(userBId, "user");
        adminToken = jwtUtil.generateToken(adminId, "admin");

        appAId = insertApplication(userAId);
        appBId = insertApplication(userBId);
    }

    // ==================== 工具 ====================

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomPhone() {
        return "138" + String.format("%08d", ThreadLocalRandom.current().nextInt(100000000));
    }

    private void insertUser(String userId, String realName, String role) {
        SysUser u = new SysUser();
        u.setUserId(userId);
        u.setPhone(randomPhone());
        u.setPassword("not-a-real-hash");   // 本测试只走 JWT，不走密码校验
        u.setRealName(realName);
        u.setRole(role);                    // 小写，与生产库一致
        u.setStatus(1);                     // DbUserDetailsService 要求 status=1
        u.setKycStatus(1);
        u.setCreateTime(new Date());
        u.setUpdateTime(new Date());
        sysUserMapper.insert(u);
    }

    private String insertApplication(String userId) {
        LoanApplication a = new LoanApplication();
        a.setApplicationId(newId());
        a.setUserId(userId);
        a.setProductId("product_001");
        a.setApplyAmount(new BigDecimal("10000"));
        a.setApplyTerm(12);
        a.setCreditScore(600);              // NOT NULL
        a.setScorecardScore(600);
        a.setScorecardDecision("MANUAL_REVIEW");
        a.setApplicationStatus("pending");
        a.setApplyTime(new Date());
        loanApplicationMapper.insert(a);
        return a.getApplicationId();
    }

    /**
     * 期望「权限拒绝」—— <b>以响应体里的 {@code code} 为准</b>。
     *
     * <p><b>⚠️ 为什么不直接断言 HTTP 403</b>：项目里两种拒绝方式的 HTTP 状态码<b>不一致</b>：
     * <ul>
     *   <li><b>Spring Security 层</b>（越权访问管理端）→ 真 {@code HTTP 403}（accessDeniedHandler）</li>
     *   <li><b>业务层</b>（{@code throw new BusinessException(403)} 做归属校验）→
     *       <b>{@code HTTP 200} + body {@code {code:403}}</b> ——
     *       因为 {@code GlobalExceptionHandler.handleBusinessException} 没有 {@code @ResponseStatus}</li>
     * </ul>
     * 两种情况的 body 里都有 {@code code=403}，所以统一按 body 断言。
     * 这个不一致由 {@code httpStatusInconsistency()} 单独固化，避免被悄悄改掉。
     */
    private void expectForbidden(String token, String path) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn();
        int httpStatus = r.getResponse().getStatus();
        int bizCode = bodyCode(r);

        // 权限被拒绝有三种表现形态（项目历史原因，见 httpStatusInconsistency()）：
        //   ① Spring Security 层 → HTTP 403 + JSON body{code:403}
        //   ② 业务层 BusinessException(403) → HTTP 200 + body{code:403}
        //   ③ ResponseEntity.status(403).build() → HTTP 403 + 空 body
        // 这里只关心"结论是拒绝"，不锁定具体形态 —— 形态由 httpStatusInconsistency() 单独固化。
        assertThat(httpStatus == 403 || bizCode == 403)
                .as("越权访问 %s 应被拒绝（HTTP %d, body.code=%d）", path, httpStatus, bizCode)
                .isTrue();
    }

    /** 期望"通过鉴权"（业务层返回什么不管，只要不是 401/403 / body.code 不是 401/403） */
    private void expectAllowed(String token, String path) throws Exception {
        MvcResult r = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn();
        int status = r.getResponse().getStatus();
        int bizCode = bodyCode(r);

        assertThat(status)
                .as("授权用户访问 %s 不应被 HTTP 拦截（实际 %d）", path, status)
                .isNotIn(401, 403);
        assertThat(bizCode)
                .as("授权用户访问 %s 不该得到 401/403 的业务结论（实际 body.code=%d）", path, bizCode)
                .isNotIn(401, 403);
    }

    /** 读响应体里的业务 code；解析失败返回 0（不是 200/403，能被断言抓到） */
    private int bodyCode(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if (body == null || body.isBlank()) {
            return 0;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body).path("code").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 未登录 ====================

    @Nested
    @DisplayName("未登录访问")
    class Anonymous {

        @Test
        @DisplayName("受保护接口 → 401，且响应是 JSON（不是 HTML/302）")
        void protectedApiReturns401Json() throws Exception {
            MvcResult r = mockMvc.perform(get("/api/v1/risk/rules")).andReturn();

            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getContentType())
                    .as("401 必须是 JSON —— App 与前端只解析 {code,msg,data}")
                    .contains(MediaType.APPLICATION_JSON_VALUE);
        }

        @Test
        @DisplayName("/actuator/health 公开（容器 healthcheck 要用）")
        void actuatorHealthIsPublic() throws Exception {
            MvcResult r = mockMvc.perform(get("/actuator/health")).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("产品列表公开（申请页需要浏览）")
        void productsArePublic() throws Exception {
            expectAllowed("", "/api/v1/products");   // 空 token 也不该 401
        }
    }

    // ==================== 普通用户越权 ====================

    @Nested
    @DisplayName("普通用户调用管理端 —— 全部 403")
    class UserDeniedOnAdmin {

        @Test
        @DisplayName("风控规则：读（改造前是 200，能看规则等于知道风控逻辑）")
        void riskRulesRead() throws Exception {
            expectForbidden(userAToken, "/api/v1/risk/rules");
        }

        @Test
        @DisplayName("风控规则：写（改造前是 200，能改规则等于能改风控）")
        void riskRulesWrite() throws Exception {
            MvcResult r = mockMvc.perform(post("/api/v1/risk/rules")
                            .header("Authorization", "Bearer " + userAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("风控决策日志（含收入、负债率等敏感特征）")
        void riskLog() throws Exception {
            expectForbidden(userAToken, "/api/v1/risk/log/application/" + appAId);
        }

        @Test
        @DisplayName("审核待办列表（改造前是 200，普通用户能看到全部申请）")
        void auditPendingList() throws Exception {
            expectForbidden(userAToken, "/api/v1/loan/audit/pending-list");
        }

        @Test
        @DisplayName("审核已放款列表")
        void auditPassedList() throws Exception {
            expectForbidden(userAToken, "/api/v1/loan/audit/passed-list");
        }

        @Test
        @DisplayName("审核详情（申请人信息 / 评分卡 / 信用档案）")
        void auditDetails() throws Exception {
            expectForbidden(userAToken, "/api/v1/loan/audit/" + appAId + "/basic");
            expectForbidden(userAToken, "/api/v1/loan/audit/" + appAId + "/applicant");
            expectForbidden(userAToken, "/api/v1/loan/audit/" + appAId + "/scorecard");
            expectForbidden(userAToken, "/api/v1/loan/audit/" + appAId + "/credit-profile");
        }

        @Test
        @DisplayName("评分卡 v2 详情（含全量特征与 A/B/C 档归因）")
        void scorecardV2() throws Exception {
            expectForbidden(userAToken, "/api/v1/scorecard/v2/approval-summary?applicationId=" + appAId);
        }

        @Test
        @DisplayName("管理端用户操作（封禁/解封账号）")
        void adminToggleStatus() throws Exception {
            MvcResult r = mockMvc.perform(put("/api/v1/admin/users/" + userAId + "/toggle-status")
                            .header("Authorization", "Bearer " + userAToken))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("actuator 指标（暴露内部运行数据）")
        void actuatorMetrics() throws Exception {
            expectForbidden(userAToken, "/actuator/prometheus");
        }
    }

    // ==================== 管理员正常 ====================

    @Nested
    @DisplayName("管理员访问 —— 不被误伤（防止改鉴权时把管理员也锁在外面）")
    class AdminAllowed {

        @Test
        @DisplayName("风控规则可读")
        void riskRulesRead() throws Exception {
            expectAllowed(adminToken, "/api/v1/risk/rules");
        }

        @Test
        @DisplayName("审核待办列表可读")
        void auditPendingList() throws Exception {
            expectAllowed(adminToken, "/api/v1/loan/audit/pending-list");
        }

        @Test
        @DisplayName("审核详情可读")
        void auditDetail() throws Exception {
            expectAllowed(adminToken, "/api/v1/loan/audit/" + appBId + "/basic");
        }

        @Test
        @DisplayName("actuator 指标可读")
        void actuatorMetrics() throws Exception {
            expectAllowed(adminToken, "/actuator/prometheus");
        }

        @Test
        @DisplayName("可查任意用户的申请（客服/纠纷处理需要）")
        void canReadAnyApplication() throws Exception {
            expectAllowed(adminToken, "/api/v1/contract/status/" + appBId);
        }
    }

    // ==================== 资源归属（RBAC 之外的第二个维度）====================

    @Nested
    @DisplayName("资源归属：用户只能查自己的")
    class Ownership {

        @Test
        @DisplayName("合同状态：查自己的 → 不被拦")
        void ownContractStatus() throws Exception {
            expectAllowed(userAToken, "/api/v1/contract/status/" + appAId);
        }

        @Test
        @DisplayName("合同状态：查别人的 → 403（改造前这里能直接看别人的合同）")
        void othersContractStatus() throws Exception {
            expectForbidden(userAToken, "/api/v1/contract/status/" + appBId);
        }

        @Test
        @DisplayName("合同 PDF 下载：查别人的 → 403（改造前能直接下到别人的合同文件）")
        void othersContractFile() throws Exception {
            expectForbidden(userAToken, "/api/v1/contract/file/" + appBId);
        }

        @Test
        @DisplayName("还款计划详情：查自己的 → 不被拦")
        void ownRepaymentPlan() throws Exception {
            expectAllowed(userAToken, "/api/v1/loan/repayment/plan/" + appAId);
        }

        @Test
        @DisplayName("还款计划详情：查别人的 → 403")
        void othersRepaymentPlan() throws Exception {
            expectForbidden(userAToken, "/api/v1/loan/repayment/plan/" + appBId);
        }

        @Test
        @DisplayName("双向验证：B 也查不到 A 的（不是只对 A 特殊）")
        void reverseDirection() throws Exception {
            expectForbidden(userBToken, "/api/v1/contract/status/" + appAId);
            expectForbidden(userBToken, "/api/v1/loan/repayment/plan/" + appAId);
        }

        @Test
        @DisplayName("盖章是平台行为：普通用户对自己的合同也不能触发")
        void userCannotStamp() throws Exception {
            MvcResult r = mockMvc.perform(post("/api/v1/contract/stamp/" + appAId)
                            .header("Authorization", "Bearer " + userAToken))
                    .andReturn();
            assertThat(bodyCode(r))
                    .as("普通用户不该能盖章，即使是自己的合同（body.code=%d）", bodyCode(r))
                    .isEqualTo(403);
        }
    }

    // ==================== 已知不一致（固化，防止被悄悄改掉）====================

    @Nested
    @DisplayName("★ 已知问题：两种拒绝的 HTTP 状态码不一致")
    class HttpStatusInconsistency {

        @Test
        @DisplayName("Spring Security 层拒绝 → 真 HTTP 403")
        void securityLayerReturnsReal403() throws Exception {
            MvcResult r = mockMvc.perform(get("/api/v1/risk/rules")
                    .header("Authorization", "Bearer " + userAToken)).andReturn();

            assertThat(r.getResponse().getStatus())
                    .as("accessDeniedHandler 写的是 res.setStatus(403)")
                    .isEqualTo(403);
            assertThat(bodyCode(r)).isEqualTo(403);
        }

        @Test
        @DisplayName("★ 业务层拒绝（归属校验）→ HTTP 200 + body{code:403}，语义上不理想")
        void businessLayerReturnsHttp200() throws Exception {
            MvcResult r = mockMvc.perform(get("/api/v1/contract/status/" + appBId)
                    .header("Authorization", "Bearer " + userAToken)).andReturn();

            // GlobalExceptionHandler.handleBusinessException 没有 @ResponseStatus → HTTP 200
            assertThat(r.getResponse().getStatus())
                    .as("这是已知问题：权限拒绝却返回 HTTP 200，APM/网关按状态码统计会漏判")
                    .isEqualTo(200);
            assertThat(bodyCode(r))
                    .as("业务结论仍是 403，前端只看 code 字段的话不受影响")
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("对照：合同文件下载用的是 ResponseEntity.status(403)，是真 403")
        void contractFileReturnsReal403() throws Exception {
            MvcResult r = mockMvc.perform(get("/api/v1/contract/file/" + appBId)
                    .header("Authorization", "Bearer " + userAToken)).andReturn();

            assertThat(r.getResponse().getStatus())
                    .as("同一个 Controller 里两种写法，状态码不一样")
                    .isEqualTo(403);
        }
    }

    // ==================== 失效 token ====================

    @Nested
    @DisplayName("无效凭证")
    class InvalidCredentials {

        @Test
        @DisplayName("伪造 token → 401")
        void forgedToken() throws Exception {
            MvcResult r = mockMvc.perform(get("/api/v1/risk/rules")
                            .header("Authorization", "Bearer not.a.real.token"))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("Authorization 头格式错误（缺 Bearer 前缀）→ 401")
        void malformedHeader() throws Exception {
            MvcResult r = mockMvc.perform(get("/api/v1/risk/rules")
                            .header("Authorization", userAToken))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("用户被封禁（status != 1）→ 401，即使 token 还在有效期内")
        void bannedUser() throws Exception {
            sysUserMapper.updateUserStatus(userBId, 0);

            MvcResult r = mockMvc.perform(get("/api/v1/contract/status/" + appBId)
                            .header("Authorization", "Bearer " + userBToken))
                    .andReturn();
            assertThat(r.getResponse().getStatus())
                    .as("被封禁的用户不该还能用旧 token 访问")
                    .isEqualTo(401);
        }
    }
}
