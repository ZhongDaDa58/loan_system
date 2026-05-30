package com.loan.mapper;

import com.loan.entity.UserSignature;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSignatureMapper {

    /**
     * 查询用户默认签名
     */
    UserSignature selectDefaultByUserId(@Param("userId") String userId);

    /**
     * 查询用户所有签名
     */
    java.util.List<UserSignature> selectByUserId(@Param("userId") String userId);

    /**
     * 插入签名
     */
    int insert(UserSignature signature);

    /**
     * 更新默认签名标识
     */
    int updateDefaultFlag(@Param("userId") String userId, @Param("isDefault") Integer isDefault);

    /**
     * 删除签名
     */
    int deleteById(@Param("id") Long id);

    int cancelDefaultSignature(@Param("userId") String userId);

}
