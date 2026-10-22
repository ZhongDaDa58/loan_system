package com.loan.mapper;

import com.loan.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SysUserMapper} 的测试
 *
 * <p><b>⚠️ 2026-10-22 重写的原因</b>：原版这样写 ——
 * <pre>
 * // 假设数据库中存在该手机号的用户
 * SysUser user = sysUserMapper.selectByPhone("13800138000");
 * assertNotNull(user);
 * </pre>
 * 它<b>依赖开发库里恰好有这条记录</b>：
 * 开发机上能过（历史数据里正好有这个号码），
 * 但 CI 是干净库（Flyway 只建表 + 插配置种子，不含业务用户）→ 查不到 → 断言失败。
 *
 * <p>这类"依赖外部状态"的测试有三个问题：
 * <ol>
 *   <li><b>换台机器就红</b>（这次 CI 失败的直接原因之一）</li>
 *   <li><b>看不出测的是什么</b> —— 数据从哪来全靠猜</li>
 *   <li><b>不可重复</b> —— 被人删掉那条数据后，本地也红</li>
 * </ol>
 *
 * <p><b>现在的做法</b>：{@code @BeforeEach} 自己插入数据，{@code @Transactional} 让测试后自动回滚。
 * 测试自给自足，在哪跑都一样。
 */
@SpringBootTest
@Transactional   // 每个测试方法结束后回滚：自造的数据不会留在库里
class SysUserMapperTest {

    /**
     * 每个测试独立生成 —— 不用固定值的原因：
     * 开发库里可能恰好存在 1234567890abcdef... 这条记录（原测试就是"假设它有"），
     * 固定值会让 @BeforeEach 撞主键。随机值在任何库上都不会冲突。
     */
    private String phone;
    private String userId;

    @Autowired
    private SysUserMapper sysUserMapper;

    @BeforeEach
    void seedTestUser() {
        phone = "138" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000000));
        userId = java.util.UUID.randomUUID().toString().replace("-", "");

        // 自造数据，不再依赖"开发库里恰好有这条记录"
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setPhone(phone);
        user.setPassword("not-a-real-hash");   // Mapper 层测试不关心密码
        user.setRealName("测试用户");
        user.setRole("user");
        user.setStatus(1);
        user.setKycStatus(1);
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());
        assertEquals(1, sysUserMapper.insert(user), "测试数据插入失败");
    }

    @Test
    @DisplayName("按手机号查询")
    void testSelectByPhone() {
        SysUser user = sysUserMapper.selectByPhone(phone);

        assertNotNull(user, "应能按手机号查到记录");
        assertEquals(phone, user.getPhone());
        assertEquals("测试用户", user.getRealName());
    }

    @Test
    @DisplayName("按 userId 查询")
    void testSelectById() {
        SysUser user = sysUserMapper.selectById(userId);

        assertNotNull(user, "应能按 userId 查到记录");
        assertEquals(userId, user.getUserId());
        assertEquals(phone, user.getPhone());
        assertEquals("测试用户", user.getRealName());
    }

    @Test
    @DisplayName("查不存在的手机号 → 返回 null（不抛异常）")
    void testSelectByUnknownPhone() {
        assertNull(sysUserMapper.selectByPhone("19999999999"));
    }
}
