package com.loan.controller;

import com.loan.entity.OverdueBlacklist;
import com.loan.entity.dto.OverdueBlacklistAddDTO;
import com.loan.entity.vo.Result;
import com.loan.service.OverdueBlacklistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逾期黑名单管理（逾期处理功能升级 第 6 步）
 *
 * <p>路径在 {@code /api/v1/admin/**} 下，自动受 SecurityConfig 的 {@code hasRole("ADMIN")} 保护。
 */
@RestController
@RequestMapping("/api/v1/admin/overdue-blacklist")
@Tag(name = "逾期黑名单", description = "严重逾期用户的黑名单管理（与设备黑名单是不同维度）")
public class OverdueBlacklistController {

    @Resource
    private OverdueBlacklistService overdueBlacklistService;

    @PostMapping
    @Operation(summary = "加入逾期黑名单",
            description = "已存在生效中的记录时不会重复加入（幂等），返回 added=false")
    public Result<Map<String, Object>> add(@RequestBody OverdueBlacklistAddDTO dto) {
        boolean added = overdueBlacklistService.add(
                dto.getUserId(), dto.getReason(), dto.getOverdueLevel(),
                dto.getRelatedRepaymentId(), dto.getExpireAt(), dto.getOperatorName());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("added", added);
        data.put("message", added ? "已加入黑名单" : "该用户已在黑名单中，未重复加入");
        return Result.success(data);
    }

    @PostMapping("/{userId}/release")
    @Operation(summary = "解除逾期黑名单", description = "返回被解除的条数，0 表示本来就不在名单里")
    public Result<Map<String, Object>> release(
            @PathVariable String userId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String operatorName) {
        int released = overdueBlacklistService.release(userId, reason, operatorName);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("released", released);
        data.put("message", released > 0 ? "已解除" : "该用户不在生效中的黑名单里");
        return Result.success(data);
    }

    @GetMapping
    @Operation(summary = "分页查询生效中的黑名单")
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<OverdueBlacklist> items = overdueBlacklistService.listActive(page, size);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", overdueBlacklistService.countActive());
        data.put("page", page);
        data.put("size", size);
        data.put("items", items);
        return Result.success(data);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "查询某用户是否在黑名单",
            description = "命中时返回记录（可用于展示拦截原因），未命中时 inBlacklist=false")
    public Result<Map<String, Object>> check(@PathVariable String userId) {
        OverdueBlacklist active = overdueBlacklistService.getActive(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inBlacklist", active != null);
        data.put("record", active);
        return Result.success(data);
    }
}
