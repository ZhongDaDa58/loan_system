package com.loan.entity.vo.loanDetail;

import lombok.Data;
import java.util.Date;

@Data
public class ApplicantIdentityVO {

    // ===== 用户基本信息 =====
    private String userId;              // 用户ID
    private String userName;            // 用户姓名
    private String userPhone;           // 用户手机号（脱敏）
    private String userRealName;        // 真实姓名

    // ===== 身份证信息 =====
    private String idCardNumber;        // 身份证号（脱敏）

    // ===== 认证状态 =====
    private Integer kycStatus;          // KYC状态（0-未认证，1-已认证）
    private String kycStatusDesc;       // KYC状态描述
    private Integer verifyStatus;       // 身份验证状态（0-未验证，1-已验证）
    private String verifyStatusDesc;    // 身份验证状态描述

    // ===== 认证材料 =====
    private String idCardFrontUrl;      // 身份证正面照片URL
    private String faceCaptureUrl;      // 人脸采集照片URL
    private Date verifyTime;            // 验证时间

    // ===== 注册与登录信息 =====
    private Date registerTime;          // 注册时间
    private Date lastLoginTime;         // 最后登录时间
}
