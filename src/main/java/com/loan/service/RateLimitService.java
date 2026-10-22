package com.loan.service;

import com.loan.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 接口限流（阶段四 4.4）
 *
 * <p><b>解决什么问题</b>：登录接口原来没有任何频率限制 —— 攻击者可以无限次尝试密码，
 * 结合本项目"4-8 位密码"的策略，暴力破解成本很低。
 *
 * <p><b>为什么不用 Bucket4j</b>：Bucket4j 需要额外依赖 + 额外配置（分布式时还要 Redis 支持）。
 * 本项目已经有 Redis，用 <b>Lua 脚本 + INCR + EXPIRE</b> 就能实现固定窗口限流，
 * 逻辑透明、零新依赖。代价是没有滑动窗口的平滑性 —— 边界处（如 59 秒和 61 秒各打满）可能放过 2 倍流量。
 * 对"防暴力破解"这个目标，固定窗口完全够用。
 *
 * <p><b>为什么用 Lua</b>：{@code INCR} 和 {@code EXPIRE} 是两条命令，非原子。
 * 如果在 INCR 之后、EXPIRE 之前进程崩溃，这个 key 就永不过期 → 该用户/IP 被永久限流。
 * Lua 脚本在 Redis 内单线程执行，保证原子。
 *
 * <p><b>Redis 不可用时的策略</b>：<b>放行</b>（fail-open）。理由与幂等相关 ——
 * 限流是"防滥用"，不是"业务必需"。Redis 挂了就把所有登录都拒掉，等于自己制造故障。
 * 此时退化为"无保护"，但至少服务可用（这一取舍记录在阶段四文档，可改为 fail-close）。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /**
     * 原子操作：INCR 计数；首次（count==1）设置过期时间；返回当前计数。
     */
    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(LUA, Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 检查并计数；超限抛 429。
     *
     * @param scene      场景（login / adminLogin / apply …），隔离不同接口的配额
     * @param identifier 限流维度（手机号 / 用户 ID / IP）
     * @param limit      窗口内允许的最大次数
     * @param window     窗口长度
     * @throws BusinessException 429 —— 超过配额
     */
    public void check(String scene, String identifier, int limit, Duration window) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        String key = "rl:" + scene + ":" + identifier;
        long count;
        try {
            Long result = stringRedisTemplate.execute(
                    SCRIPT, List.of(key), String.valueOf(window.toSeconds()));
            count = result == null ? 0L : result;
        } catch (Exception e) {
            // Redis 不可用 → 放行（fail-open），不让限流组件成为可用性单点
            log.warn("⚠️ 限流检查失败（Redis 不可用），本次放行: scene={}, reason={}", scene, e.getMessage());
            return;
        }

        if (count > limit) {
            log.warn("🚫 触发限流: scene={}, identifier={}, count={}/{}", scene, identifier, count, limit);
            throw new BusinessException(429, "操作过于频繁，请稍后再试");
        }
    }

    // ==================== 预置策略 ====================

    /**
     * 登录：同一手机号 5 分钟内最多 5 次。
     *
     * <p>为什么按手机号而不是 IP：用户可能在 NAT/校园网后共用出口 IP，
     * 按 IP 会误伤；按手机号能精确定位"某个账号在被暴力尝试"。
     *
     * <p>5 次/5 分钟的依据：正常用户输错密码不会超过 5 次（4-8 位密码也能记住），
     * 而攻击者 5 分钟内最多试 5 个密码，破解 8 位密码的概率可忽略。
     */
    public void checkLogin(String phone) {
        check("login", phone, 5, Duration.ofMinutes(5));
    }

    /** 管理员登录：更严 —— 同一手机号 10 分钟内最多 3 次（管理员账号是高价值目标） */
    public void checkAdminLogin(String phone) {
        check("adminLogin", phone, 3, Duration.ofMinutes(10));
    }

    /** 提交申请：同一用户 1 分钟内最多 3 次（防止脚本刷单 + 配合幂等） */
    public void checkApply(String userId) {
        check("apply", userId, 3, Duration.ofMinutes(1));
    }

    /** 验证码/短信类操作：同一手机号 1 分钟内最多 1 次、1 小时最多 5 次 */
    public void checkSms(String phone) {
        check("sms:1m", phone, 1, Duration.ofMinutes(1));
        check("sms:1h", phone, 5, Duration.ofHours(1));
    }
}
