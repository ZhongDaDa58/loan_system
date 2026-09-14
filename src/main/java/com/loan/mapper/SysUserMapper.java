package com.loan.mapper;
import com.loan.entity.SysUser;
import com.loan.entity.vo.UserItemVO;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.List;

public interface SysUserMapper {
    SysUser selectByPhone(@Param("phone") String phone);
    int insert(SysUser sysUser);
    SysUser selectById(@Param("userId") String userId);
    // 更新用户账户余额（放款时增加余额）
    int updateAccountBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);
    // 查询用户余额（校验银行卡绑定，简化为：余额字段存在即绑定）
    BigDecimal selectAccountBalance(@Param("userId") String userId);
    void updateKycStatus(@Param("userId") String userId, @Param("status") Integer status, @Param("realName") String realName);

    /**
     * 更新账号状态（冻结/解冻）
     */
    int updateUserStatus(@Param("userId") String userId, @Param("status") Integer status);

    // ========== 用户管理模块 ==========

    /**
     * 分页查询用户列表（含信用分左连）
     */
    List<UserItemVO> selectUserList(@Param("phone") String phone,
                                    @Param("name") String name,
                                    @Param("startDate") String startDate,
                                    @Param("endDate") String endDate,
                                    @Param("kycStatus") Integer kycStatus,
                                    @Param("sortBy") String sortBy,
                                    @Param("sortOrder") String sortOrder,
                                    @Param("offset") Integer offset,
                                    @Param("pageSize") Integer pageSize);

    /**
     * 统计用户总数（与 selectUserList 相同过滤条件）
     */
    Long countUserList(@Param("phone") String phone,
                       @Param("name") String name,
                       @Param("startDate") String startDate,
                       @Param("endDate") String endDate,
                       @Param("kycStatus") Integer kycStatus);

    /**
     * 修改密码
     */
    int updatePassword(@Param("userId") String userId, @Param("password") String password);
}