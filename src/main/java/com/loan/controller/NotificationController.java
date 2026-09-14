package com.loan.controller;

import com.loan.entity.vo.Result;
import com.loan.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "通知模块", description = "App 内消息通知查询和管理")
public class NotificationController {

    @Resource
    private NotificationService notificationService;

    @GetMapping
    @Operation(summary = "查询我的通知列表", description = "分页查询当前用户的通知，按时间倒序")
    public Result<?> listNotifications(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.getUserNotifications(userId, page, pageSize);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "查询未读通知数量")
    public Result<Long> unreadCount(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.getUnreadCount(userId);
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "标记通知已读")
    public Result<?> markAsRead(@PathVariable Long id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.markAsRead(id, userId);
    }

    @PutMapping("/read-all")
    @Operation(summary = "标记全部已读")
    public Result<?> markAllAsRead(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return notificationService.markAllAsRead(userId);
    }
}
