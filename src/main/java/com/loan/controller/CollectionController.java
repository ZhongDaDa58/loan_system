package com.loan.controller;

import com.loan.entity.CollectionRecord;
import com.loan.entity.CollectionTask;
import com.loan.entity.vo.Result;
import com.loan.service.CollectionRecordService;
import com.loan.service.CollectionTaskService;
import com.loan.service.impl.CollectionTaskGenerator;
import com.loan.service.impl.PromisedPayViolationChecker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 催收工作台（逾期处理功能升级 第 6 步-c）
 *
 * <p>路径在 {@code /api/v1/admin/**} 下，自动受 SecurityConfig 的 {@code hasRole("ADMIN")} 保护。
 */
@RestController
@RequestMapping("/api/v1/admin/collection")
@Tag(name = "催收工作台", description = "催收任务的查询、认领、跟进留痕、关闭")
public class CollectionController {

    @Resource
    private CollectionTaskService collectionTaskService;

    @Resource
    private CollectionRecordService collectionRecordService;

    @Resource
    private CollectionTaskGenerator collectionTaskGenerator;

    @Resource
    private PromisedPayViolationChecker promisedPayViolationChecker;

    @GetMapping("/tasks")
    @Operation(summary = "分页查询催收任务",
            description = "dueFollowOnly=true 时只返回今天该跟进的（催收员的\"今日待办\"）。"
                    + "排序：优先级高的、逾期久的在前")
    public Result<Map<String, Object>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(defaultValue = "false") boolean dueFollowOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<CollectionTask> items =
                collectionTaskService.list(status, assignedTo, dueFollowOnly, page, size);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", collectionTaskService.count(status, assignedTo, dueFollowOnly));
        data.put("page", page);
        data.put("size", size);
        data.put("items", items);
        return Result.success(data);
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查看任务详情（含全部跟进记录与承诺次数）")
    public Result<Map<String, Object>> taskDetail(@PathVariable String taskId) {
        CollectionTask task = collectionTaskService.getByTaskId(taskId);
        if (task == null) {
            return Result.error(404, "催收任务不存在");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("records", collectionRecordService.listByTaskId(taskId));
        data.put("promisedCount", collectionRecordService.countPromised(taskId));
        return Result.success(data);
    }

    @PostMapping("/tasks/{taskId}/claim")
    @Operation(summary = "认领任务", description = "把负责人设成自己，状态转 in_progress")
    public Result<Map<String, Object>> claim(@PathVariable String taskId,
                                             @RequestParam String operator) {
        boolean ok = collectionTaskService.claim(taskId, operator);
        return Result.success(resultOf(ok, "已认领", "任务不存在或已关闭"));
    }

    @PostMapping("/tasks/{taskId}/follow")
    @Operation(summary = "更新跟进信息", description = "设下次跟进日 / 记客户承诺还款日 / 改状态")
    public Result<Map<String, Object>> follow(
            @PathVariable String taskId,
            @RequestParam(required = false) String assignedTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nextFollowDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate promisedPayDate) {

        boolean ok = collectionTaskService.updateFollow(
                taskId, assignedTo, status, nextFollowDate, promisedPayDate);
        return Result.success(resultOf(ok, "已更新", "任务不存在或已关闭"));
    }

    @PostMapping("/tasks/{taskId}/close")
    @Operation(summary = "关闭任务", description = "必须填写原因")
    public Result<Map<String, Object>> close(@PathVariable String taskId,
                                             @RequestParam String reason) {
        boolean ok = collectionTaskService.close(taskId, reason);
        return Result.success(resultOf(ok, "已关闭", "任务不存在或本来就已经关闭"));
    }

    @PostMapping("/tasks/{taskId}/records")
    @Operation(summary = "添加催收记录",
            description = "记一次联系。result=promised 时 promisedPayDate 必填，"
                    + "并会同步到任务上")
    public Result<CollectionRecord> addRecord(
            @PathVariable String taskId,
            @RequestParam String method,
            @RequestParam String result,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate promisedPayDate,
            @RequestParam(required = false) String remark,
            @RequestParam(required = false) String operator) {

        CollectionRecord record = collectionRecordService.add(
                taskId, method, result, promisedPayDate, remark, operator);
        return Result.success(record);
    }

    @GetMapping("/users/{userId}/records")
    @Operation(summary = "查某用户的全部催收记录（跨任务）",
            description = "用于回答\"这个人被联系过几次\"")
    public Result<Map<String, Object>> userRecords(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", collectionRecordService.countByUserId(userId));
        data.put("page", page);
        data.put("size", size);
        data.put("items", collectionRecordService.listByUserId(userId, page, size));
        return Result.success(data);
    }

    @PostMapping("/generate")
    @Operation(summary = "手动触发催收任务生成",
            description = "正常由每天 3:30 的定时任务执行；这个接口用于测试和补数据。"
                    + "返回新建的任务数（已有 open 任务的不重复建）")
    public Result<Map<String, Object>> generate() {
        int created = collectionTaskGenerator.generate();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("message", "新建 " + created + " 个催收任务");
        return Result.success(data);
    }

    @PostMapping("/check-violations")
    @Operation(summary = "手动触发承诺违约检查",
            description = "正常由每天 3:35 的定时任务执行。"
                    + "把「承诺日已过且仍未还款」的任务标记违约、提升优先级、拉回今日待办")
    public Result<Map<String, Object>> checkViolations() {
        int violated = promisedPayViolationChecker.check();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("violated", violated);
        data.put("message", "标记 " + violated + " 条承诺违约");
        return Result.success(data);
    }

    private static Map<String, Object> resultOf(boolean ok, String okMsg, String failMsg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", ok);
        m.put("message", ok ? okMsg : failMsg);
        return m;
    }
}
