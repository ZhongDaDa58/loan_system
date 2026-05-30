package com.loan.entity;

import lombok.Data;

import java.util.Date;

@Data
public class SysUser {
    private String userId;
    private String phone;
    private String password;
    private String realName;
    private String role;
    private Integer status;
    private Date createTime;
    private Date updateTime;
    private Integer kycStatus;
}