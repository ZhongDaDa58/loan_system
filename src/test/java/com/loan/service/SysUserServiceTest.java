package com.loan.service;
import com.loan.entity.SysUser;
import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class SysUserServiceTest {

    @Autowired
    private SysUserService sysUserService;

    @Test
    public void testRegister() {
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setPhone("13911110000");
        dto.setPassword("Aa123456");
        dto.setRealName("测试人");

        Result<?> result = sysUserService.register(dto);
        System.out.println("注册结果：" + result);
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
