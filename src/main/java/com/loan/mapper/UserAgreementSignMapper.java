package com.loan.mapper;

import com.loan.entity.UserAgreementSign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface UserAgreementSignMapper {

    /**
     * 查询用户已签署的协议列表
     */
    List<UserAgreementSign> selectByUserId(@Param("userId") String userId);

    /**
     * 检查用户是否已签署指定协议
     */
    UserAgreementSign selectByUserAndAgreement(
            @Param("userId") String userId,
            @Param("agreementId") Long agreementId
    );

    /**
     * 检查用户是否已签署某类型协议的当前版本
     */
    UserAgreementSign selectCurrentVersionSign(
            @Param("userId") String userId,
            @Param("agreementType") String agreementType
    );

    /**
     * 插入签署记录
     */
    int insert(UserAgreementSign sign);

    /**
     * 撤销签署记录
     */
    int updateIsValidToNo(@Param("signId") Long signId);
}
