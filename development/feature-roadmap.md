# 逾期处理 — 功能现状与构建计划

## 一、已有功能

| 功能 | 说明 | 位置 |
|------|------|------|
| **自动检测逾期** | 每日凌晨 2:00 定时任务，将 `unpaid` 且已过 `due_date` 的还款标记为 `overdue` | `OverdueCheckService` + `@Scheduled(cron = "0 0 2 * * ?")` |
| **手动触发检查** | `POST /api/v1/loan/overdue/check` 可手动触发 | `OverdueController` |
| **逾期 App 内通知** | 检测到逾期时自动创建 App 内消息提醒用户 | `OverdueCheckService` → `NotificationService` |
| **阻止新增贷款** | 有逾期记录的用户提交新贷款申请时返回 400："您有 N 笔逾期未还款记录，请先结清欠款后再申请" | `LoanApplicationServiceImpl`（两处） |
| **允许正常还款** | 逾期记录仍可通过还款流程结清，付清后状态变回 `paid` | `RepaymentServiceImpl` |
| **逾期统计展示** | 管理端可查按期限聚合的逾期计数、还款计划列表中显示逾期笔数 | `AuditHistoryController` + `RepaymentPlanListVO` |
| **历史借贷汇总** | 审批详情页展示该用户的历史逾期次数 | `AuditServiceImpl.getLoanHistory()` |

### 通知模块接口

| 接口 | 说明 |
|------|------|
| `GET  /api/v1/notifications` | 分页查询当前用户通知 |
| `GET  /api/v1/notifications/unread-count` | 未读通知数量 |
| `PUT  /api/v1/notifications/{id}/read` | 标记单条已读 |
| `PUT  /api/v1/notifications/read-all` | 标记全部已读 |

---

## 二、待构建功能

### 🔴 高优先级

| # | 功能 | 缺失说明 | 收益 |
|---|------|---------|------|
| 1 | **逾期罚息计算** | 无滞纳金/罚息逻辑，逾期无额外成本 | 减少逾期、增加收入 |
| 2 | **逾期信用分扣减** | 逾期不影响信用分，风控闭环断裂 | 增强威慑、完善风控 |
| 3 | **逾期分级处理（M1/M2/M3）** | 只有逾期/非逾期二分，无分级策略 | 精细化催收 |

### 🟡 中优先级

| # | 功能 | 缺失说明 | 收益 |
|---|------|---------|------|
| 4 | ~~还款提前提醒~~ | ✅ 已完成 | 降低逾期率 |
| 5 | **逾期报表看板** | 无逾期趋势图、逾期率统计 | 运营监控 |
| 6 | **黑名单/账户冻结** | 长期逾期无账户限制措施 | 风险控制 |

---

## 三、构建计划

### 阶段一：逾期惩罚闭环（待启动）

```
目标：逾期用户承担实际成本（罚息 + 扣分）
```

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 1.1 | **罚息计算**：`penalty_amount = overdue_days × amount × daily_penalty_rate`；`MonthlyRepayment` 加 `penaltyAmount`、`overdueDays` 字段 | `OverdueCheckService`、`MonthlyRepayment.java`、`MonthlyRepaymentMapper.xml` |
| 1.2 | **自动扣信用分**：逾期 ≥30 天每次扣 50 分；逾期 ≥60 天再扣 50 分 | `CreditScoreService`、`OverdueCheckService` |

### 阶段二：分级催收（待启动）

```
目标：按逾期天数分级处理，差异化策略
```

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 2.1 | **逾期等级字段**：`overdueStage`（M1=1-30d, M2=31-60d, M3=61d+） | `MonthlyRepayment`、`OverdueCheckService` |
| 2.2 | **分级催收逻辑**：M1 通知 → M2 多次通知 → M3 冻结账户 | `OverdueCheckService` + `SysUserService` |
| 2.3 | **还款提前提醒**：到期前 3 天 / 1 天发送通知 | `NotificationService` + 新定时任务 `@Scheduled` |

### 阶段三：监控与报表（待启动）

```
目标：逾期数据可视化、运营决策支撑
```

| 步骤 | 内容 | 涉及文件 |
|------|------|---------|
| 3.1 | **逾期看板接口**：逾期率、M1/M2/M3 分布、趋势图数据 | 新 `OverdueDashboardController` |
| 3.2 | **黑名单机制**：逾期 ≥90 天自动冻结账户，限制登录和申请 | `SysUserService` + 定时任务 |

---

## 四、通知模块数据库

```sql
CREATE TABLE notification (
    id          BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(64)     NOT NULL                COMMENT '接收用户 ID',
    title       VARCHAR(128)    NOT NULL                COMMENT '通知标题',
    content     TEXT                                    COMMENT '通知正文',
    type        VARCHAR(32)     NOT NULL DEFAULT 'system' COMMENT '类型：overdue/repayment_reminder/system',
    related_id  VARCHAR(64)                             COMMENT '关联业务 ID',
    is_read     TINYINT(1)      DEFAULT 0               COMMENT '0-未读 1-已读',
    create_time DATETIME        NOT NULL                COMMENT '创建时间',
    INDEX idx_user_time (user_id, create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统通知';
```

---

> **文档版本**: 2026-06-12  
> **项目**: Loan System (Spring Boot 4 + Java 17)
