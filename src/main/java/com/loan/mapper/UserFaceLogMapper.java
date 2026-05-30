package com.loan.mapper;

import com.loan.entity.UserFaceLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserFaceLogMapper {
    int insert(UserFaceLog faceLog);
}
