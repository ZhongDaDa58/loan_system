package com.loan.service;

import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SysUserService} 的测试
 *
 * <p><b>⚠️ 2026-10-22 修正 testLogin</b>：原版这样写 ——
 * <pre>
 * dto.setPhone("13911110000"); // 用你数据库里已有的测试账号
 * Result&lt;?&gt; result = sysUserService.login(dto);
 * System.out.println("登录结果：" + result);   // 而且没有断言
 * </pre>
 * 两个问题：
 * <ol>
 *   <li><b>依赖开发库里恰好有这个账号</b> → CI 干净库上直接抛「账号不存在」</li>
 *   <li><b>没有断言</b> —— 只 print，所以就算登录失败测试也是"绿的"（假通过）</li>
 * </ol>
 *
 * <p><b>现在</b>：先注册再登录（同一事务内，测试后回滚），并补上断言。
 */
@SpringBootTest
public class SysUserServiceTest {

    @Autowired
    private SysUserService sysUserService;

    /** 随机手机号：保证测试可重复执行（写死号码第二次必然「手机号已注册」） */
    private static String randomPhone() {
        return "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100000000));
    }

    @Test
    @Transactional   // 测试后回滚，避免往库里遗留脏数据
    @DisplayName("注册成功")
    public void testRegister() {
        String phone = randomPhone();

        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setPhone(phone);
        dto.setPassword("Aa123456");
        dto.setRealName("测试人");

        Result<?> result = sysUserService.register(dto);

        assertEquals(200, result.getCode());
    }

    @Test
    @Transactional
    @DisplayName("注册后可登录（自给自足，不依赖外部已有账号）")
    public void testLogin() {
        String phone = randomPhone();
        String password = "Aa123456";

        // ① 先注册 —— 不再假设"数据库里刚好有 13911110000"
        UserRegisterDTO reg = new UserRegisterDTO();
        reg.setPhone(phone);
        reg.setPassword(password);
        reg.setRealName("测试人");
        assertEquals(200, sysUserService.register(reg).getCode(), "前置注册应成功");

        // ② 再登录
        UserLoginDTO login = new UserLoginDTO();
        login.setPhone(phone);
        login.setPassword(password);

        Result<?> result = sysUserService.login(login);

        assertEquals(200, result.getCode());
    }

    @Test
    @Transactional
    @DisplayName("密码错误 → 登录失败（不能只是 print，必须有断言）")
    public void testLoginWithWrongPassword() {
        String phone = randomPhone();

        UserRegisterDTO reg = new UserRegisterDTO();
        reg.setPhone(phone);
        reg.setPassword("Aa123456");
        reg.setRealName("测试人");
        assertEquals(200, sysUserService.register(reg).getCode());

        UserLoginDTO login = new UserLoginDTO();
        login.setPhone(phone);
        login.setPassword("Wrong123456");

        // 密码错误时 login 是**抛异常**（不是返回非 200 的 Result），所以用 assertThrows
        assertThrows(com.loan.exception.BusinessException.class,
                () -> sysUserService.login(login),
                "密码错误应抛业务异常");
    }
}
