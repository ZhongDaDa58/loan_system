package com.loan.mapper;

import com.loan.entity.OverdueBlacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 逾期黑名单 Mapper（逾期处理功能升级 第 6 步）
 *
 * <p><b>注意"生效"的定义</b>：{@code status='active'} 只是必要条件，
 * 还必须没到期。所以凡是查"是否在黑名单里"的方法，都带上了
 * {@code expire_at IS NULL OR expire_at > NOW()} 这个条件。
 */
@Mapper
public interface OverdueBlacklistMapper {

    /**
     * 插入一条黑名单记录。
     *
     * @return 影响行数；<b>返回 0 表示该用户已有生效中的记录</b>（靠 SQL 里的 NOT EXISTS 保证）
     */
    int insertIfAbsent(OverdueBlacklist record);

    /**
     * 是否处于生效中的黑名单（未解除 + 未过期）。
     *
     * @return 命中条数，>0 表示命中
     */
    int countActive(@Param("userId") String userId);

    /**
     * 查用户的生效中记录（用于给审核员展示"为什么被拦"）。
     *
     * @return 生效中的记录；无则 null
     */
    OverdueBlacklist selectActiveByUserId(@Param("userId") String userId);

    /**
     * 解除该用户所有生效中的黑名单（置为 released）。
     *
     * @return 影响行数
     */
    int releaseActive(@Param("userId") String userId,
                      @Param("reason") String reason,
                      @Param("operatorName") String operatorName);

    /**
     * 分页查生效中的黑名单（催收/审核工作台用）。
     */
    List<OverdueBlacklist> selectActivePage(@Param("offset") int offset,
                                            @Param("limit") int limit);

    /** 生效中的总数（分页用） */
    long countActiveAll();
}
