package com.loan.service;

import com.loan.entity.Notification;
import com.loan.entity.vo.Result;

import java.util.List;

public interface NotificationService {

    /**
     * 发送通知
     */
    void sendNotification(String userId, String title, String content,
                          String type, String relatedId);

    /**
     * 分页查询用户通知
     */
    Result<?> getUserNotifications(String userId, Integer page, Integer pageSize);

    /**
     * 查询未读通知数
     */
    Result<Long> getUnreadCount(String userId);

    /**
     * 标记单条已读
     */
    Result<?> markAsRead(Long id, String userId);

    /**
     * 标记全部已读
     */
    Result<?> markAllAsRead(String userId);
}
