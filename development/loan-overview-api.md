# 贷款概况模块 — 接口文档

## 概述

贷款概况页面直观展示用户所有贷款的汇总信息和各笔贷款的详细状态，帮助用户快速了解贷款现状。

---

## 接口列表

### 1. 查询贷款概况

获取用户的贷款汇总信息和各笔贷款明细。

```
GET /api/v1/loan/overview
```

#### 响应

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "summary": {
      "totalBorrowed": 120000.00,
      "pendingRepayment": 35000.00,
      "overdueCount": 1,
      "activeLoanCount": 2,
      "totalLoanCount": 3
    },
    "loanList": [
      {
        "applicationId": "L20250601001",
        "productName": "极速贷",
        "applyAmount": 50000.00,
        "applyTerm": 12,
        "applyTime": "2026-06-01T10:00:00",
        "status": "repaying",
        "statusDesc": "还款中",
        "remainingAmount": 35000.00,
        "remainingTerm": 8,
        "nextRepaymentDate": "2026-07-15",
        "nextRepaymentAmount": 4283.33,
        "overdueDays": 0,
        "overdueAmount": 0,
        "interestRate": 0.05
      },
      {
        "applicationId": "L20260501002",
        "productName": "大额贷",
        "applyAmount": 100000.00,
        "applyTerm": 24,
        "applyTime": "2026-05-01T10:00:00",
        "status": "repaying",
        "statusDesc": "还款中",
        "remainingAmount": 82000.00,
        "remainingTerm": 20,
        "nextRepaymentDate": "2026-07-10",
        "nextRepaymentAmount": 5200.00,
        "overdueDays": 3,
        "overdueAmount": 520.00,
        "interestRate": 0.04
      },
      {
        "applicationId": "L20260401003",
        "productName": "极速贷",
        "applyAmount": 30000.00,
        "applyTerm": 6,
        "applyTime": "2026-04-01T10:00:00",
        "status": "cleared",
        "statusDesc": "已结清",
        "remainingAmount": 0,
        "remainingTerm": 0,
        "nextRepaymentDate": null,
        "nextRepaymentAmount": 0,
        "overdueDays": 0,
        "overdueAmount": 0,
        "interestRate": 0.05
      }
    ]
  }
}
```

#### 状态码说明

| code | msg | 说明 |
|------|-----|------|
| 200 | 操作成功 | 正常返回 |
| 401 | 未登录 | Token 失效或未登录 |
| 404 | 暂无贷款记录 | 用户无任何贷款 |

#### 字段说明 - summary

| 字段 | 类型 | 说明 |
|------|------|------|
| totalBorrowed | Double | 累计借款总额 |
| pendingRepayment | Double | 待还总额 |
| overdueCount | Integer | 逾期笔数 |
| activeLoanCount | Integer | 进行中贷款笔数 |
| totalLoanCount | Integer | 总贷款笔数（含已结清） |

#### 字段说明 - loanList[]

| 字段 | 类型 | 说明 |
|------|------|------|
| applicationId | String | 贷款申请编号 |
| productName | String | 产品名称 |
| applyAmount | Double | 申请金额 |
| applyTerm | Integer | 期限（月） |
| applyTime | String | 申请时间 |
| status | String | 状态编码：`repaying` 还款中 / `cleared` 已结清 / `overdue` 逾期 / `pending` 审核中 |
| statusDesc | String | 状态描述 |
| remainingAmount | Double | 剩余待还本金 |
| remainingTerm | Integer | 剩余期数 |
| nextRepaymentDate | String | 下一期还款日 |
| nextRepaymentAmount | Double | 下一期应还金额 |
| overdueDays | Integer | 逾期天数 |
| overdueAmount | Double | 逾期金额（含罚息） |
| interestRate | Double | 年化利率 |

---

## 页面设计参考

```
┌──────────────────────────────────────┐
│  ← 贷款概况                          │
├──────────────────────────────────────┤
│  贷款汇总                            │
│  ┌──────┬──────┬──────┬──────┐      │
│  │累计借款│待还总额│逾期笔数│进行中  │      │
│  │¥120000│¥35000│  1   │  2   │      │
│  └──────┴──────┴──────┴──────┘      │
├──────────────────────────────────────┤
│  贷款明细                            │
│                                      │
│  ┌─ 极速贷 ──────────────────────┐  │
│  │ 状态: 还款中     进度: ████░░ 4/12 │
│  │ 申请金额: ¥50,000              │  │
│  │ 剩余待还: ¥35,000              │  │
│  │ 下期还款: ¥4,283.33 (07-15)    │  │
│  │ 年化利率: 5.00%                │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ 大额贷 ──── 逾期3天 ─────────┐  │
│  │ 状态: 逾期⚠️     进度: ████░░ 4/24│
│  │ 申请金额: ¥100,000             │  │
│  │ 逾期金额: ¥520.00              │  │
│  │ 下期还款: ¥5,200.00 (07-10)    │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌─ 极速贷 ──────────────────────┐  │
│  │ 状态: 已结清✅                  │  │
│  │ 申请金额: ¥30,000              │  │
│  │ 已结清: 2026-04-01 → 2026-10-01│  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
```

---

> **文档版本**: 2026-06-15
> **后端文件**: `LoanOverviewController` + `LoanOverviewService`
