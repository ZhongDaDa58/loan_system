package com.loan.service.impl;

import com.loan.entity.Notification;
import com.loan.entity.vo.PageResult;
import com.loan.entity.vo.Result;
import com.loan.mapper.NotificationMapper;
import com.loan.service.NotificationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationServiceImpl implements NotificationService {

    @Resource
    private NotificationMapper notificationMapper;

    @Override
    public void sendNotification(String userId, String title, String content,
                                  String type, String relatedId) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setType(type);
        notification.setRelatedId(relatedId);
        notification.setIsRead(false);
        notification.setCreateTime(new Date());
        notificationMapper.insert(notification);
    }

    @Override
    public Result<?> getUserNotifications(String userId, Integer page, Integer pageSize) {
        Integer offset = (page - 1) * pageSize;
        List<Notification> list = notificationMapper.selectByUserId(userId, offset, pageSize);
        Long total = notificationMapper.countByUserId(userId);

        PageResult<Notification> pageResult = new PageResult<>();
        pageResult.setList(list);
        pageResult.setTotal(total);
        pageResult.setPage(page);
        pageResult.setPageSize(pageSize);
        return Result.success(pageResult);
    }

    @Override
    public Result<Long> getUnreadCount(String userId) {
        Long count = notificationMapper.countUnreadByUserId(userId);
        return Result.success(count);
    }

    @Override
    public Result<?> markAsRead(Long id, String userId) {
        int rows = notificationMapper.markAsRead(id, userId);
        return Result.success();
    }

    @Override
    public Result<?> markAllAsRead(String userId) {
        int rows = notificationMapper.markAllAsRead(userId);
        return Result.success();
    }
}
