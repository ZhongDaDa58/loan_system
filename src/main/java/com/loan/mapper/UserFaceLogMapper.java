package com.loan.mapper;

import com.loan.entity.UserFaceLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserFaceLogMapper {
    int insert(UserFaceLog faceLog);

    /**
     * 查询用户最近一次人脸/活体记录（第一层「信息校验先行」活体判定用）
     *
     * <p>阶段 C1 新增：此前本 Mapper 只有 insert，活体结果写入后无人可读，
     * 导致 L1-08/L1-09 无法生效（见测试问题 G1）。
     */
    UserFaceLog selectLatestByUserId(String userId);
}
