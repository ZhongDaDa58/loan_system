package com.loan.test;

import lombok.extern.slf4j.Slf4j;
import com.loan.util.PasswordUtil;
@Slf4j
public class TestPassWord {
    public static void main(String[] args) {
        log.info("password match = {}", PasswordUtil.matches("123456", PasswordUtil.encrypt("123456")));
    }
}
