# 立即还款模块 — 接口文档

## 概述

立即还款页面展示用户所有待还款项，逾期项置顶排序，每项可单独发起还款。

---

## 接口列表

### 1. 查询待还款项

获取当前登录用户所有待还款的还款项，包含逾期和正常到期。

```
GET /api/v1/loan/repayment/due-items
```

#### 响应

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "totalDue": 2,
    "overdueCount": 1,
    "items": [
      {
        "repaymentId": "RP20250601001",
        "applicationId": "L20250601001",
        "productName": "大额贷",
        "term": 3,
        "dueDate": "2026-06-10",
        "repaymentAmount": 5200.00,
        "principal": 4200.00,
        "interest": 1000.00,
        "status": "overdue",
        "statusDesc": "逾期",
        "overdueDays": 5,
        "overduePenalty": 130.00,
        "totalDue": 5330.00
      },
      {
        "repaymentId": "RP20250701002",
        "applicationId": "L20250601002",
        "productName": "极速贷",
        "term": 4,
        "dueDate": "2026-07-15",
        "repaymentAmount": 4283.33,
        "principal": 3500.00,
        "interest": 783.33,
        "status": "pending",
        "statusDesc": "待还款",
        "overdueDays": 0,
        "overduePenalty": 0,
        "totalDue": 4283.33
      }
    ]
  }
}
```

#### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| totalDue | Integer | 待还款总笔数 |
| overdueCount | Integer | 逾期笔数 |
| items[].repaymentId | String | 还款项ID（用于提交还款） |
| items[].applicationId | String | 贷款申请编号 |
| items[].productName | String | 产品名称 |
| items[].term | Integer | 当前期数 |
| items[].dueDate | String | 到期日 |
| items[].repaymentAmount | Double | 应还金额（本息） |
| items[].principal | Double | 本金部分 |
| items[].interest | Double | 利息部分 |
| items[].status | String | 状态：`pending` 待还款 / `overdue` 逾期 |
| items[].statusDesc | String | 状态描述 |
| items[].overdueDays | Integer | 逾期天数（未逾期为0） |
| items[].overduePenalty | Double | 逾期罚息 |
| items[].totalDue | Double | 实际应还总额（含罚息） |

#### 排序规则

1. 逾期项排前面，按 `overdueDays` 降序（逾期最久的在最上面）
2. 正常待还项排后面，按 `dueDate` 升序（最近到期的在前）

---

### 2. 提交还款

还款时需选择放款银行卡，调用此接口完成还款。

```
POST /api/v1/loan/repayment/pay
```

#### 请求体

```json
{
  "repaymentId": "RP20250601001",
  "disbursementCardId": 1,
  "amount": 5330.00
}
```

#### 响应

```json
{
  "code": 200,
  "msg": "还款成功",
  "data": null
}
```

| 情况 | code | msg |
|------|------|-----|
| 成功 | 200 | 还款成功 |
| 余额不足 | 400 | 银行卡余额不足 |
| 参数错误 | 400 | 还款项ID不能为空 |
| 已还款 | 400 | 该还款项已结清 |

---

> **文档版本**: 2026-06-15
> **后端文件**: `RepaymentController` + `RepaymentService`
