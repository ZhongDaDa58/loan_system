package com.loan.mapper;

import com.loan.entity.Notification;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface NotificationMapper {

    int insert(Notification notification);

    /**
     * 分页查询用户通知（按时间倒序）
     */
    List<Notification> selectByUserId(@Param("userId") String userId,
                                       @Param("offset") Integer offset,
                                       @Param("limit") Integer limit);

    /**
     * 统计用户通知总数
     */
    Long countByUserId(@Param("userId") String userId);

    /**
     * 查询用户未读通知数
     */
    Long countUnreadByUserId(@Param("userId") String userId);

    /**
     * 标记单条通知已读
     */
    int markAsRead(@Param("id") Long id, @Param("userId") String userId);

    /**
     * 标记用户全部通知已读
     */
    int markAllAsRead(@Param("userId") String userId);

    /**
     * 插入并返回自增 ID
     */
    int insertWithBackId(Notification notification);

    /**
     * 查询指定类型 + 关联 ID 的通知数量（用于重复检查）
     */
    int countByTypeAndRelatedId(@Param("type") String type, @Param("relatedId") String relatedId);
}
