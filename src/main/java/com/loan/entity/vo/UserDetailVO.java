package com.loan.entity.vo;

import lombok.Data;

@Data
public class UserDetailVO {
    private String userId;
    private String phone;
    private String name;
    private String nickname;
    private String gender;
    private Integer age;
    private String registerTime;
    private String lastLoginTime;
    private String verifyStatus;
    private Integer creditScore;
}
