package com.loan.test;
import com.loan.util.PasswordUtil;
public class TestPassWord {
    public static void main(String[] args) {
        System.out.println(PasswordUtil.matches("123456",PasswordUtil.encrypt("123456")));
    }
}
