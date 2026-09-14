# 放款统计 — 接口文档

## 概述

放款统计页面（`src/views/reports/DisbursementStats.vue`）需要后端提供统一的放款数据查询接口，涵盖核心指标、趋势图、产品占比、金额分布和期限分布。

---

## 接口定义

| 项目 | 内容 |
|------|------|
| **用途** | 获取放款统计数据（核心指标 + 趋势 + 分布） |
| **方法** | `GET` |
| **路径** | `/api/v1/loan/disbursement/stats` |

### 请求参数 (Query Params)

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `startDate` | String | 否 | — | 开始日期 `YYYY-MM-DD` |
| `endDate` | String | 否 | — | 结束日期 `YYYY-MM-DD` |
| `productId` | String | 否 | — | 产品类型筛选（可选） |

---

## 响应数据

### `data` 顶层字段

```json
{
  "summary": { ... },
  "trend": [ ... ],
  "productDistribution": [ ... ],
  "amountDistribution": [ ... ],
  "termDistribution": [ ... ]
}
```

### 1. 核心指标 `summary`

```json
{
  "totalAmount": 15680000.00,
  "totalCount": 1256,
  "avgAmount": 12484.00,
  "todayAmount": 285000.00
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalAmount` | number | 累计放款金额（元） |
| `totalCount` | number | 累计放款笔数 |
| `avgAmount` | number | 平均放款金额（元） |
| `todayAmount` | number | 今日放款金额（元） |

### 2. 放款趋势 `trend`

按月份聚合的放款金额和笔数，用于双 Y 轴柱线图。

```json
[
  { "month": "2025-01", "amount": 1200000.00, "count": 95 },
  { "month": "2025-02", "amount": 1320000.00, "count": 105 },
  { "month": "2025-03", "amount": 1010000.00, "count": 82 }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `month` | string | 月份，格式 `YYYY-MM` |
| `amount` | number | 该月放款总额（元） |
| `count` | number | 该月放款笔数 |

### 3. 产品放款占比 `productDistribution`

用于环形饼图。

```json
[
  { "productName": "个人消费贷", "amount": 6800000.00, "count": 520 },
  { "productName": "企业经营贷", "amount": 5200000.00, "count": 368 },
  { "productName": "房屋装修贷", "amount": 3680000.00, "count": 256 }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `productName` | string | 产品名称 |
| `amount` | number | 该产品放款总额（元） |
| `count` | number | 该产品放款笔数 |

### 4. 放款金额分布 `amountDistribution`

用于柱状图。

```json
[
  { "range": "0-1万", "count": 180 },
  { "range": "1-3万", "count": 320 },
  { "range": "3-5万", "count": 280 },
  { "range": "5-10万", "count": 245 },
  { "range": "10-20万", "count": 156 },
  { "range": "20-50万", "count": 65 },
  { "range": "50万以上", "count": 10 }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `range` | string | 金额区间标签（固定：0-1万/1-3万/3-5万/5-10万/10-20万/20-50万/50万以上） |
| `count` | number | 该区间的放款笔数 |

### 5. 放款期限分布 `termDistribution`

用于饼图。

```json
[
  { "term": 3, "count": 280 },
  { "term": 6, "count": 350 },
  { "term": 12, "count": 420 },
  { "term": 24, "count": 156 },
  { "term": 36, "count": 50 }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `term` | number | 期限（月） |
| `count` | number | 该期限的放款笔数 |

---

## 完整响应示例

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "summary": {
      "totalAmount": 15680000.00,
      "totalCount": 1256,
      "avgAmount": 12484.00,
      "todayAmount": 285000.00
    },
    "trend": [
      { "month": "2025-01", "amount": 1200000.00, "count": 95 },
      { "month": "2025-02", "amount": 1320000.00, "count": 105 },
      { "month": "2025-03", "amount": 1010000.00, "count": 82 },
      { "month": "2025-04", "amount": 1340000.00, "count": 108 },
      { "month": "2025-05", "amount": 900000.00, "count": 75 },
      { "month": "2025-06", "amount": 2300000.00, "count": 185 },
      { "month": "2025-07", "amount": 2100000.00, "count": 170 },
      { "month": "2025-08", "amount": 1800000.00, "count": 145 },
      { "month": "2025-09", "amount": 1950000.00, "count": 158 },
      { "month": "2025-10", "amount": 2200000.00, "count": 178 },
      { "month": "2025-11", "amount": 2400000.00, "count": 195 },
      { "month": "2025-12", "amount": 2600000.00, "count": 210 }
    ],
    "productDistribution": [
      { "productName": "个人消费贷", "amount": 6800000.00, "count": 520 },
      { "productName": "企业经营贷", "amount": 5200000.00, "count": 368 },
      { "productName": "房屋装修贷", "amount": 3680000.00, "count": 256 }
    ],
    "amountDistribution": [
      { "range": "0-1万", "count": 180 },
      { "range": "1-3万", "count": 320 },
      { "range": "3-5万", "count": 280 },
      { "range": "5-10万", "count": 245 },
      { "range": "10-20万", "count": 156 },
      { "range": "20-50万", "count": 65 },
      { "range": "50万以上", "count": 10 }
    ],
    "termDistribution": [
      { "term": 3, "count": 280 },
      { "term": 6, "count": 350 },
      { "term": 12, "count": 420 },
      { "term": 24, "count": 156 },
      { "term": 36, "count": 50 }
    ]
  }
}
```

---

## 前端对接说明

| 页面区块 | 对应 data 字段 |
|----------|---------------|
| 累计放款金额 / 笔数 / 平均金额 / 今日放款 | `summary` |
| 放款趋势图（柱 + 线） | `trend` |
| 产品放款占比（环形饼图） | `productDistribution` |
| 放款金额分布（柱状图） | `amountDistribution` |
| 放款期限分布（饼图） | `termDistribution` |

### 涉及文件

| 文件 | 说明 |
|------|------|
| `src/types/index.ts` | 新增 `DisbursementStatsResponse` 及相关子类型 |
| `src/api/index.ts` | 在 `loanApi` 中新增 `getDisbursementStats()` |
| `src/views/reports/DisbursementStats.vue` | 替换 mock 数据为真实 API 调用 |

### 异常情况

| 情况 | HTTP 状态码 | `code` | `msg` |
|------|-------------|--------|-------|
| 请求成功 | 200 | 200 | 操作成功 |
| 参数错误 | 400 | 400 | 参数错误 |
| 系统异常 | 500 | 500 | 服务器内部错误 |
