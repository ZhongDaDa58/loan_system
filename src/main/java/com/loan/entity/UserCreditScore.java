package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class UserCreditScore {
    private Long id;
    private String userId;      // 用户ID
    private Integer creditScore; // 信用分

    /**
     * 上次逾期扣分对应的最高等级（M1/M2/M3）—— <b>用户维度的幂等标记</b>。
     *
     * <p>为什么在用户上而不是期次上：分期贷款逾期 10 期本质是**一次违约**，
     * 按期次扣会扣 10 次、直接打到底分，失去区分度（详见 V7 迁移注释）。
     *
     * <p>{@code null} 表示从未因逾期扣过分。
     */
    private String lastOverdueDeductLevel;

    private Date createTime;
    private Date updateTime;
}
