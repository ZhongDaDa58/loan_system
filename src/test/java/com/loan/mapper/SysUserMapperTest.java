package com.loan.mapper;

import com.loan.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


import static org.junit.jupiter.api.Assertions.*;


@SpringBootTest
class SysUserMapperTest {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    void testSelectByPhone() {
        // 假设数据库中存在该手机号的用户
        SysUser user = sysUserMapper.selectByPhone("13800138000");
        assertNotNull( user);
        assertEquals("13800138000", user.getPhone());
    }

    @Test
    void testSelectById() {
        // 假设数据库中存在该ID的用户
        SysUser user = sysUserMapper.selectById("1234567890abcdef1234567890abcdef");
        assertNotNull( user);
        System.out.println("User details: " + user); // 需要 SysUser 实现 toString() 方法
        // 或者分别打印各字段
        System.out.println("User ID: " + user.getUserId());
        System.out.println("Phone: " + user.getPhone());
        System.out.println("Real Name: " + user.getRealName());

        assertEquals("13800138000", user.getUserId());
    }
}
