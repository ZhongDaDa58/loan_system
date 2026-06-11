package com.loan.controller;

import com.loan.entity.vo.AuditHistoryVO;
import com.loan.entity.vo.OverdueTermCountVO;
import com.loan.entity.vo.Result;
import com.loan.mapper.AuditRecordMapper;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.MonthlyRepaymentMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import com.loan.entity.vo.DateMetricVO;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "审批历史与统计", description = "审批统计、逾期统计与审核员历史记录查询")
public class AuditHistoryController {

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private AuditRecordMapper auditRecordMapper;

    @GetMapping("/pending-count")
    @Operation(summary = "待审批申请个数")
    public Result<Integer> getPendingCount() {
        int cnt = loanApplicationMapper.countByStatus("pending");
        return Result.success(cnt);
    }

    @GetMapping("/audited-count")
    @Operation(summary = "已审核贷款数量（按日期范围，返回列表）", description = "可选参数: startDate=YYYY-MM-DD, endDate=YYYY-MM-DD；若不提供默认返回最近7天")
    public Result<List<DateMetricVO>> getAuditedCount(@RequestParam(required = false) String startDate,
                                                      @RequestParam(required = false) String endDate) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate end = (endDate != null && !endDate.isEmpty()) ? LocalDate.parse(endDate, fmt) : LocalDate.now();
        LocalDate start = (startDate != null && !startDate.isEmpty()) ? LocalDate.parse(startDate, fmt) : end.minusDays(6);

        List<DateMetricVO> raw = loanApplicationMapper.selectAuditedCountGroupByDate(start.toString(), end.toString());
        Map<String, DateMetricVO> map = new HashMap<>();
        if (raw != null) {
            for (DateMetricVO dm : raw) {
                map.put(dm.getDate(), dm);
            }
        }
        List<DateMetricVO> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            String key = d.format(fmt);
            if (map.containsKey(key)) {
                result.add(map.get(key));
            } else {
                DateMetricVO zero = new DateMetricVO();
                zero.setDate(key);
                zero.setValue(java.math.BigDecimal.ZERO);
                result.add(zero);
            }
        }
        return Result.success(result);
    }

    @GetMapping("/daily-pass-rate")
    @Operation(summary = "贷款通过率（按日期范围，返回列表）", description = "可选参数: startDate=YYYY-MM-DD, endDate=YYYY-MM-DD；若不提供默认返回最近7天")
    public Result<List<DateMetricVO>> getDailyPassRate(@RequestParam(required = false) String startDate,
                                                      @RequestParam(required = false) String endDate) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        LocalDate end = (endDate != null && !endDate.isEmpty()) ? LocalDate.parse(endDate, fmt) : LocalDate.now();
        LocalDate start = (startDate != null && !startDate.isEmpty()) ? LocalDate.parse(startDate, fmt) : end.minusDays(6);

        List<DateMetricVO> auditedRaw = loanApplicationMapper.selectAuditedCountGroupByDate(start.toString(), end.toString());
        List<DateMetricVO> approvedRaw = loanApplicationMapper.selectApprovedCountGroupByDate(start.toString(), end.toString());

        Map<String, java.math.BigDecimal> auditedMap = new HashMap<>();
        if (auditedRaw != null) {
            for (DateMetricVO dm : auditedRaw) {
                auditedMap.put(dm.getDate(), dm.getValue());
            }
        }
        Map<String, java.math.BigDecimal> approvedMap = new HashMap<>();
        if (approvedRaw != null) {
            for (DateMetricVO dm : approvedRaw) {
                approvedMap.put(dm.getDate(), dm.getValue());
            }
        }

        List<DateMetricVO> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            String key = d.format(fmt);
            java.math.BigDecimal audited = auditedMap.getOrDefault(key, java.math.BigDecimal.ZERO);
            java.math.BigDecimal approved = approvedMap.getOrDefault(key, java.math.BigDecimal.ZERO);
            java.math.BigDecimal rate;
            if (audited.compareTo(java.math.BigDecimal.ZERO) == 0) {
                rate = java.math.BigDecimal.ZERO;
            } else {
                rate = approved.divide(audited, 4, java.math.RoundingMode.HALF_UP);
            }
            DateMetricVO dm = new DateMetricVO();
            dm.setDate(key);
            dm.setValue(rate);
            result.add(dm);
        }
        return Result.success(result);
    }

    @GetMapping("/overdue-count")
    @Operation(summary = "所有逾期贷款的个数（按期数统计）")
    public Result<List<OverdueTermCountVO>> getOverdueCountByTerm() {
        List<OverdueTermCountVO> list = monthlyRepaymentMapper.selectOverdueCountGroupedByTerm();
        return Result.success(list);
    }

    @GetMapping("/history")
    @Operation(summary = "审核员历史审批记录（仅返回当前登录的审核员自己的记录）")
    public Result<List<AuditHistoryVO>> getAuditHistory(HttpServletRequest request,
                                                        @RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        Object uid = request.getAttribute("userId");
        if (uid == null) {
            return Result.error(403, "无权限：请先登录");
        }
        String auditorId = uid.toString();
        int offset = (page - 1) * size;
        List<AuditHistoryVO> list = auditRecordMapper.selectByAuditorHistory(auditorId, startDate, endDate, offset, size);
        return Result.success(list);
    }
}

