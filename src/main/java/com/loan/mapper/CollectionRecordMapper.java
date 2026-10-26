package com.loan.mapper;

import com.loan.entity.CollectionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 催收记录 Mapper（逾期处理功能升级 第 6 步-c）
 */
@Mapper
public interface CollectionRecordMapper {

    int insert(CollectionRecord record);

    /** 某任务的全部跟进记录（时间倒序 —— 最新的一次联系在最上面） */
    List<CollectionRecord> selectByTaskId(@Param("taskId") String taskId);

    /** 某用户的全部催收记录（跨任务，便于看"这个人被联系过几次"） */
    List<CollectionRecord> selectByUserId(@Param("userId") String userId,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    long countByUserId(@Param("userId") String userId);

    /**
     * 统计某任务下"承诺过还款"的次数 —— 用于回答"承诺几次、兑现几次"。
     */
    int countPromisedByTaskId(@Param("taskId") String taskId);
}
