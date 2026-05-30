package com.loan.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class UserFaceLog {
    private Long id;
    private String userId;
    private String faceImageUrl;
    private BigDecimal similarityScore;
    private Integer isLive;
    private String result; // PASS / FAIL
    private Date createTime;
}
