package com.loan.mapper;

import com.loan.entity.ShadowScoreLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 影子评分记录 Mapper（W4 · 主线 A）
 * 对应表：shadow_score_log
 */
public interface ShadowScoreLogMapper {

    /** 新增影子记录（状态 PENDING） */
    int insert(ShadowScoreLog record);

    /** 按状态查询（如 PENDING，供离线回填拉取） */
    List<ShadowScoreLog> selectByStatus(@Param("status") String status, @Param("limit") Integer limit);

    /** 查询最近记录（供对照查看） */
    List<ShadowScoreLog> selectRecent(@Param("limit") Integer limit);

    /** 回填影子分数（离线离线批量打分后写回） */
    int updateShadowResult(ShadowScoreLog record);

    /** 统计各状态数量（可观测性） */
    List<java.util.Map<String, Object>> countByStatus();
}
