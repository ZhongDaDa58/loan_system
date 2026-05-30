package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class UserSignature {
    private Long id;
    private String userId;
    private String signatureImageUrl;
    private String signatureHash;   // SHA256哈希值
    private Integer isDefault;      // 0-否, 1-是
    private Date createTime;
    private Date updateTime;
}
