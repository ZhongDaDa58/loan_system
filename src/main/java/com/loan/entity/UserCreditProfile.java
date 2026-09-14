package com.loan.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class UserCreditProfile {
    private Long id;
    private String userId;

    // 个人信息
    private String name;
    private String idCard;
    private String phone;
    private String email;
    private Date birthDate;
    private String gender;
    private String education;
    private String occupation;

    // 家庭信息（JSON）
    private String familyInfo;

    // 财务信息（JSON）
    private String financialInfo;

    // 职业信息（JSON）
    private String employmentInfo;

    // 评分特征（由 FeatureTransformationService 计算得出）
    private Integer age;
    private BigDecimal debtRatio;
    private BigDecimal monthlyIncome;
    private Integer creditLines;
    private Integer dependents;
    private BigDecimal revolvingUtil;

    // 最近一次评分结果
    private Integer creditScore;

    private Date createTime;
    private Date updateTime;
}
