package com.loan.mapper;
import com.loan.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;

public interface SysUserMapper {
    SysUser selectByPhone(@Param("phone") String phone);
    int insert(SysUser sysUser);
    SysUser selectById(@Param("userId") String userId);
    // 更新用户账户余额（放款时增加余额）
    int updateAccountBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);
    // 查询用户余额（校验银行卡绑定，简化为：余额字段存在即绑定）
    BigDecimal selectAccountBalance(@Param("userId") String userId);
    void updateKycStatus(@Param("userId") String userId, @Param("status") Integer status, @Param("realName") String realName);
}