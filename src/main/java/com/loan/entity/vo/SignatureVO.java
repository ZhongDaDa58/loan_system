// SignatureVO.java
package com.loan.entity.vo;

import lombok.Data;
import java.util.Date;

@Data
public class SignatureVO {
    private Long id;
    private String signatureImageUrl;
    private Integer isDefault;
    private Date createTime;
}
