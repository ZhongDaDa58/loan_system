# 逾期统计 — 接口文档

## 概述

逾期统计页面（`src/views/reports/OverdueStats.vue`）需要后端提供统一的逾期数据查询接口，涵盖核心指标、趋势图、金额分布和高风险客户列表。

---

## 接口定义

| 项目 | 内容 |
|------|------|
| **用途** | 获取逾期统计数据（核心指标 + 趋势 + 分布 + 客户列表） |
| **方法** | `GET` |
| **路径** | `/api/v1/loan/overdue/stats` |

### 请求参数 (Query Params)

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `startDate` | String | 否 | — | 开始日期 `YYYY-MM-DD` |
| `endDate` | String | 否 | — | 结束日期 `YYYY-MM-DD` |
| `riskLevel` | String | 否 | — | 风险等级筛选：`high` / `medium` |

---

## 响应数据

### `data` 顶层字段

```json
{
  "summary": { ... },
  "trend": [ ... ],
  "amountDistribution": [ ... ],
  "highRiskCustomers": [ ... ]
}
```

### 1. 核心指标 `summary`

```json
{
  "currentOverdueRate": 3.8,
  "overdueAmount": 1256000.00,
  "overdueCustomers": 156,
  "avgOverdueDays": 28
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `currentOverdueRate` | number | 当前逾期率（%） |
| `overdueAmount` | number | 逾期总金额（元） |
| `overdueCustomers` | number | 逾期客户数 |
| `avgOverdueDays` | number | 平均逾期天数 |

### 2. 逾期率趋势 `trend`

按月份的逾期率序列，用于折线图。

```json
[
  { "month": "2025-01", "overdueRate": 2.8 },
  { "month": "2025-02", "overdueRate": 3.1 },
  { "month": "2025-03", "overdueRate": 2.9 }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `month` | string | 月份，格式 `YYYY-MM` |
| `overdueRate` | number | 该月逾期率（%） |

### 3. 逾期金额分布 `amountDistribution`

用于环形饼图。

```json
[
  { "range": "1万以下", "amount": 4500000.00 },
  { "range": "1-3万", "amount": 3800000.00 },
  { "range": "3-5万", "amount": 2800000.00 },
  { "range": "5万以上", "amount": 1460000.00 }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `range` | string | 金额区间标签（固定：1万以下 / 1-3万 / 3-5万 / 5万以上） |
| `amount` | number | 该区间的逾期总金额（元） |

### 4. 高风险客户列表 `highRiskCustomers`

用于 TOP 20 排名表格。

```json
[
  {
    "userName": "张三",
    "phone": "138****1234",
    "productName": "个人消费贷",
    "loanAmount": 50000.00,
    "overdueAmount": 15000.00,
    "overdueDays": 45,
    "creditScore": 580,
    "riskLevel": "high"
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `userName` | string | 客户姓名 |
| `phone` | string | 手机号（可脱敏） |
| `productName` | string | 产品名称 |
| `loanAmount` | number | 贷款金额（元） |
| `overdueAmount` | number | 逾期金额（元） |
| `overdueDays` | number | 逾期天数 |
| `creditScore` | number | 信用评分 |
| `riskLevel` | string | 风险等级：`high` / `medium` |

返回按逾期天数倒序排列，最多 20 条。

---

## 完整响应示例

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "summary": {
      "currentOverdueRate": 3.8,
      "overdueAmount": 1256000.00,
      "overdueCustomers": 156,
      "avgOverdueDays": 28
    },
    "trend": [
      { "month": "2025-01", "overdueRate": 2.8 },
      { "month": "2025-02", "overdueRate": 3.1 },
      { "month": "2025-03", "overdueRate": 2.9 },
      { "month": "2025-04", "overdueRate": 3.3 },
      { "month": "2025-05", "overdueRate": 3.5 },
      { "month": "2025-06", "overdueRate": 3.2 },
      { "month": "2025-07", "overdueRate": 3.6 },
      { "month": "2025-08", "overdueRate": 3.8 },
      { "month": "2025-09", "overdueRate": 3.5 },
      { "month": "2025-10", "overdueRate": 3.9 },
      { "month": "2025-11", "overdueRate": 4.1 },
      { "month": "2025-12", "overdueRate": 3.8 }
    ],
    "amountDistribution": [
      { "range": "1万以下", "amount": 4500000.00 },
      { "range": "1-3万", "amount": 3800000.00 },
      { "range": "3-5万", "amount": 2800000.00 },
      { "range": "5万以上", "amount": 1460000.00 }
    ],
    "highRiskCustomers": [
      {
        "userName": "张三",
        "phone": "138****1234",
        "productName": "个人消费贷",
        "loanAmount": 50000.00,
        "overdueAmount": 15000.00,
        "overdueDays": 45,
        "creditScore": 580,
        "riskLevel": "high"
      },
      {
        "userName": "李四",
        "phone": "139****5678",
        "productName": "企业经营贷",
        "loanAmount": 200000.00,
        "overdueAmount": 85000.00,
        "overdueDays": 62,
        "creditScore": 520,
        "riskLevel": "high"
      },
      {
        "userName": "王五",
        "phone": "137****9012",
        "productName": "个人消费贷",
        "loanAmount": 30000.00,
        "overdueAmount": 12000.00,
        "overdueDays": 38,
        "creditScore": 595,
        "riskLevel": "high"
      },
      {
        "userName": "赵六",
        "phone": "136****3456",
        "productName": "房屋装修贷",
        "loanAmount": 100000.00,
        "overdueAmount": 45000.00,
        "overdueDays": 55,
        "creditScore": 540,
        "riskLevel": "high"
      },
      {
        "userName": "钱七",
        "phone": "135****7890",
        "productName": "个人消费贷",
        "loanAmount": 20000.00,
        "overdueAmount": 8500.00,
        "overdueDays": 32,
        "creditScore": 610,
        "riskLevel": "medium"
      },
      {
        "userName": "孙八",
        "phone": "134****2345",
        "productName": "企业经营贷",
        "loanAmount": 150000.00,
        "overdueAmount": 62000.00,
        "overdueDays": 48,
        "creditScore": 565,
        "riskLevel": "high"
      },
      {
        "userName": "周九",
        "phone": "133****6789",
        "productName": "个人消费贷",
        "loanAmount": 25000.00,
        "overdueAmount": 9800.00,
        "overdueDays": 29,
        "creditScore": 625,
        "riskLevel": "medium"
      },
      {
        "userName": "吴十",
        "phone": "132****0123",
        "productName": "房屋装修贷",
        "loanAmount": 80000.00,
        "overdueAmount": 35000.00,
        "overdueDays": 42,
        "creditScore": 575,
        "riskLevel": "high"
      }
    ]
  }
}
```

---

## 前端对接说明

| 页面区块 | 对应 data 字段 |
|----------|---------------|
| 当前逾期率 / 逾期总金额 / 逾期客户数 / 平均逾期天数 | `summary` |
| 逾期率趋势图（折线图） | `trend` |
| 逾期金额分布（环形饼图） | `amountDistribution` |
| 高风险客户分析表格（TOP 20） | `highRiskCustomers` |

### 涉及文件

| 文件 | 说明 |
|------|------|
| `src/types/index.ts` | 新增 `OverdueStatsResponse` 及相关子类型 |
| `src/api/index.ts` | 在 `loanApi` 中新增 `getOverdueStats()` |
| `src/views/reports/OverdueStats.vue` | 替换 mock 数据为真实 API 调用 |

### 风险等级与逾期天数字段对照

| `overdueDays` 范围 | `getOverdueTagType` 返回值 | 标签颜色 |
|--------------------|--------------------------|----------|
| ≤ 30 天 | `warning` | 黄色 |
| 31-60 天 | `danger` | 红色 |
| > 60 天 | `danger` | 红色 |

| `riskLevel` 值 | 中文标签 | 标签颜色 |
|----------------|----------|----------|
| `high` | 高风险 | `danger` |
| `medium` | 中风险 | `warning` |

### 异常情况

| 情况 | HTTP 状态码 | `code` | `msg` |
|------|-------------|--------|-------|
| 请求成功 | 200 | 200 | 操作成功 |
| 参数错误 | 400 | 400 | 参数错误 |
| 系统异常 | 500 | 500 | 服务器内部错误 |
