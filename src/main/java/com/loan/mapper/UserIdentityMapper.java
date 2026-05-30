package com.loan.mapper;

import com.loan.entity.UserIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserIdentityMapper {
    int insert(UserIdentity identity);
    UserIdentity selectByUserId(@Param("userId") String userId);
    int updateStatus(@Param("userId") String userId, @Param("status") Integer status);

}
