# 还款信息 — RepaymentPlan 扩展字段文档

## 概述

还款信息页面（`src/views/Repayment.vue`）顶部的四个统计卡片需要从还款计划列表中直接聚合计算，当前 `RepaymentPlan` 缺少已还金额、待还金额和逾期金额字段。

---

## 接口

### 获取所有还款计划列表

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/loan/repayment/plans/all` |

现有接口不变，仅在 `RepaymentPlan` 中新增三个字段。

### 原响应

```typescript
interface RepaymentPlan {
  applicationId: string
  createTime: string
  hasOverdue: boolean
  overdueCount: number
  planId: string
  repaymentType: string
  repaymentTypeDesc: string
  termCount: number
  totalAmount: number
  totalInterest: number
  totalPrincipal: number
  userId: string
  userName: string
  userPhone: string
}
```

### 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `paidAmount` | number | 已还金额（元），默认 0 |
| `unpaidAmount` | number | 待还金额（元），默认 0 |
| `overdueAmount` | number | 逾期金额（元），默认 0 |

### 扩展后的响应数据

```typescript
interface RepaymentPlan {
  applicationId: string      // 申请单号
  createTime: string         // 创建时间
  hasOverdue: boolean        // 是否有逾期
  overdueCount: number       // 逾期次数
  planId: string             // 还款计划ID
  repaymentType: string      // 还款类型代码
  repaymentTypeDesc: string  // 还款类型描述
  termCount: number          // 总期数
  totalAmount: number        // 总金额（本金+利息）
  totalInterest: number      // 总利息
  totalPrincipal: number     // 总本金
  userId: string             // 用户ID
  userName: string           // 用户姓名
  userPhone: string          // 用户手机号
  paidAmount: number         // 已还金额（元）
  unpaidAmount: number       // 待还金额（元）
  overdueAmount: number      // 逾期金额（元）
}
```

### 完整响应示例

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    {
      "applicationId": "LA20250101001",
      "userName": "张三",
      "userPhone": "138****1234",
      "totalAmount": 50000.00,
      "totalPrincipal": 48000.00,
      "totalInterest": 2000.00,
      "termCount": 12,
      "repaymentType": "equal_principal_interest",
      "repaymentTypeDesc": "等额本息",
      "hasOverdue": false,
      "overdueCount": 0,
      "paidAmount": 25000.00,
      "unpaidAmount": 25000.00,
      "overdueAmount": 0.00,
      "createTime": "2025-01-01 10:00:00",
      "planId": "PLAN20250101001",
      "userId": "U123456"
    },
    {
      "applicationId": "LA20250201002",
      "userName": "李四",
      "userPhone": "139****5678",
      "totalAmount": 100000.00,
      "totalPrincipal": 95000.00,
      "totalInterest": 5000.00,
      "termCount": 24,
      "repaymentType": "equal_principal_interest",
      "repaymentTypeDesc": "等额本息",
      "hasOverdue": true,
      "overdueCount": 2,
      "paidAmount": 30000.00,
      "unpaidAmount": 70000.00,
      "overdueAmount": 6000.00,
      "createTime": "2025-02-01 14:30:00",
      "planId": "PLAN20250201002",
      "userId": "U123457"
    }
  ]
}
```

---

## 前端四个统计卡片的数据来源

| 卡片 | 计算公式 |
|------|----------|
| **总应还金额** | `sum(data.paidAmount + data.unpaidAmount)` 或直接 `sum(data.totalAmount)` |
| **已还金额** | `sum(data.paidAmount)` |
| **待还金额** | `sum(data.unpaidAmount)` |
| **逾期金额** | `sum(data.overdueAmount)` |

---

## 涉及文件

| 文件 | 改动 |
|------|------|
| `src/types/index.ts` | `RepaymentPlan` 接口新增 `paidAmount`、`unpaidAmount`、`overdueAmount` 字段 |
| `src/views/Repayment.vue` | 用 API 数据替换四个 card 的计算逻辑 |

### 异常情况

| 情况 | HTTP 状态码 | `code` | `msg` |
|------|-------------|--------|-------|
| 请求成功 | 200 | 200 | 操作成功 |
| 系统异常 | 500 | 500 | 服务器内部错误 |
