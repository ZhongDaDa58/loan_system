package com.loan.service;
import com.loan.entity.SysUser;
import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class SysUserServiceTest {

    @Autowired
    private SysUserService sysUserService;

    @Test
    @Transactional  // 测试后回滚，避免往测试库遗留脏数据
    public void testRegister() {
        // 修正：改用随机手机号，保证测试可重复执行（原先写死号码，第二次运行必然"手机号已注册"）
        String phone = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100000000));
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setPhone(phone);
        dto.setPassword("Aa123456");
        dto.setRealName("测试人");

        Result<?> result = sysUserService.register(dto);
        System.out.println("注册结果：" + result);

        assertEquals(200, result.getCode());
    }

    @Test
    public void testLogin() {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setPhone("13911110000"); // 用你数据库里已有的测试账号
        dto.setPassword("Aa123456");

        Result<?> result = sysUserService.login(dto);
        System.out.println("登录结果：" + result);
    }

    @Test
    public void testGetUserById() {
        SysUser user = sysUserService.getUserById("1234567890abcdef1234567890abcdef");
        System.out.println("查询用户：" + user);
    }
}
