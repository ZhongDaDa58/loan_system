package com.loan.service;

import com.loan.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;

/**
 * 幂等控制（阶段四 4.3）
 *
 * <p><b>解决什么问题</b>：{@code loan_application} 原来没有幂等约束、{@code submitApply}
 * 也不做重复检查 → 用户连点两次提交会生成两笔申请，各走一遍评分、各生成一份合同。
 * 放款/还款接口同理，风险更高（<b>重复放款 = 资金损失</b>）。
 *
 * <p><b>三层防护</b>：
 * <ol>
 *   <li>客户端：请求头 {@code Idempotency-Key}（前端生成 UUID，重试时复用同一个）</li>
 *   <li><b>服务端（本类）</b>：Redis {@code SETNX idem:{scene}:{userId}:{key}}，TTL 5 分钟</li>
 *   <li>数据库：唯一索引 {@code uk_user_idem(user_id, idempotency_key)} 兜底</li>
 * </ol>
 *
 * <p><b>为什么从 RequestContextHolder 取 header 而不是加方法参数</b>：
 * {@code submitApply(LoanApplyDTO, String)} 的签名被 34 个 Controller 依赖，
 * 加参数会造成大范围改动。用 {@code RequestContextHolder} 可以在不改签名的前提下拿到 header。
 *
 * <p><b>向后兼容</b>：没有带 {@code Idempotency-Key} 的请求（老版 App）直接放行 ——
 * 不能因为加了幂等就让老客户端全部失败。这类请求仍有数据库唯一索引之外的风险，
 * 但至少不会破坏现有功能。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 幂等窗口：5 分钟。同一 key 在这段时间内只允许成功一次 */
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final String HEADER = "Idempotency-Key";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试占用幂等键。
     *
     * @param scene  业务场景（apply / disbursement / repayment / audit），用于隔离不同接口的 key
     * @param userId 当前用户
     * @return 本次请求对应的幂等键；<b>客户端未提供时返回 {@code null}</b>（调用方据此决定是否落库）
     * @throws BusinessException 409 —— 同一个 key 在 TTL 内已经处理过
     */
    public String acquire(String scene, String userId) {
        String key = currentKey();
        if (key == null) {
            // 老客户端没带 key：放行，不阻断
            return null;
        }

        String redisKey = "idem:" + scene + ":" + userId + ":" + key;
        Boolean ok;
        try {
            ok = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, "1", TTL);
        } catch (Exception e) {
            // Redis 不可用时不阻断业务（幂等是"防重复"，不是"必须成功"）
            // 此时退化为只靠数据库唯一索引兜底
            log.warn("⚠️ 幂等检查失败（Redis 不可用），本次放行: scene={}, reason={}", scene, e.getMessage());
            return key;
        }

        if (Boolean.FALSE.equals(ok)) {
            throw new BusinessException(409, "请求正在处理中，请勿重复提交");
        }
        log.info("🔒 幂等键已占用: scene={}, key={}", scene, key);

        // ★ 防"幂等键泄漏"：Redis 不参与数据库事务 —— 如果业务逻辑抛异常导致事务回滚，
        //   Redis 里的键仍然存在，用户会被锁在 5 分钟窗口外无法重试
        //   （例如"卡状态异常"失败后换张卡重试 → 莫名 409）。
        //   这里注册事务同步：**事务回滚时自动释放**，事务提交则保留（真正防重复）。
        //
        //   注意：仅在存在活动事务时注册。无事务的方法（如 submitApply 无 @Transactional）
        //   不会触发 —— 这是已知限制，见阶段四文档 §9.5。
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                release(scene, userId, key);
                            }
                        }
                    });
        }
        return key;
    }

    /** 业务失败时释放幂等键，允许用户重试（成功则保留到 TTL 结束） */
    public void release(String scene, String userId, String key) {
        if (key == null) {
            return;
        }
        try {
            stringRedisTemplate.delete("idem:" + scene + ":" + userId + ":" + key);
            log.info("🔓 幂等键已释放: scene={}, key={}", scene, key);
        } catch (Exception e) {
            log.warn("⚠️ 释放幂等键失败（不影响业务，TTL 到期自动失效）: {}", e.getMessage());
        }
    }

    /** 从当前请求头读取 Idempotency-Key；非 Web 上下文（定时任务/单测）返回 null */
    private String currentKey() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest req = attrs.getRequest();
            String k = req.getHeader(HEADER);
            return (k == null || k.isBlank()) ? null : k.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
