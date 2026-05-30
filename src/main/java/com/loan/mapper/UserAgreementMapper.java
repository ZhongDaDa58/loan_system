package com.loan.mapper;

import com.loan.entity.UserAgreement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface UserAgreementMapper {

    /**
     * 查询所有当前生效的协议
     */
    List<UserAgreement> selectCurrentAgreements();

    /**
     * 根据协议类型查询当前版本
     */
    UserAgreement selectCurrentByType(@Param("agreementType") String agreementType);

    /**
     * 根据协议ID查询
     */
    UserAgreement selectById(@Param("agreementId") Long agreementId);

    /**
     * 插入新协议版本
     */
    int insert(UserAgreement agreement);

    /**
     * 取消某个类型的所有当前版本（用于发布新版本时）
     */
    int updateIsCurrentToNo(@Param("agreementType") String agreementType);
}
