# 贷款审批 — 历史借贷接口文档

## 概述

在贷款审批详情页（`ApplicationDetail.vue`）的「历史借贷」标签页中，展示当前申请人的历史借贷汇总统计和明细记录。

采用 **方案 A：一个接口返回全部数据**，前端通过已有的 `applicationId` 直接查询，无需额外获取 `userId`。

---

## 接口定义

| 项目 | 内容 |
|------|------|
| **用途** | 获取当前申请人的历史借贷汇总统计 + 明细记录 |
| **方法** | `GET` |
| **路径** | `/api/v1/loan/audit/{applicationId}/loan-history` |

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `applicationId` | String | 当前贷款申请的申请单号 |

---

## 响应数据 `data`

```json
{
  "summary": {
    "totalApplications": 5,
    "approvedCount": 3,
    "rejectedCount": 2,
    "totalBorrowed": 150000.00,
    "totalRepaid": 80000.00,
    "overdueCount": 0
  },
  "records": [
    {
      "applicationId": "APP202410010001",
      "productName": "优享贷",
      "amount": 50000.00,
      "term": 12,
      "status": "completed",
      "applyTime": "2024-10-01 14:20:00",
      "disbursementTime": "2024-10-02 10:15:00"
    },
    {
      "applicationId": "APP202408150002",
      "productName": "快速贷",
      "amount": 30000.00,
      "term": 6,
      "status": "repaying",
      "applyTime": "2024-08-15 09:30:00",
      "disbursementTime": "2024-08-16 11:20:00"
    },
    {
      "applicationId": "APP202406010003",
      "productName": "优享贷",
      "amount": 80000.00,
      "term": 24,
      "status": "rejected",
      "applyTime": "2024-06-01 16:45:00",
      "disbursementTime": null
    }
  ]
}
```

---

## 数据结构定义

### 汇总统计 `LoanHistorySummary`

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalApplications` | number | 历史申请总次数 |
| `approvedCount` | number | 通过次数 |
| `rejectedCount` | number | 拒绝次数 |
| `totalBorrowed` | number | 累计借款金额（元） |
| `totalRepaid` | number | 累计还款金额（元） |
| `overdueCount` | number | 逾期次数 |

### 明细记录 `LoanHistoryRecord`

| 字段 | 类型 | 说明 |
|------|------|------|
| `applicationId` | string | 申请单号 |
| `productName` | string | 产品名称 |
| `amount` | number | 借款金额（元） |
| `term` | number | 贷款期限（月） |
| `status` | string | 状态，枚举值见下表 |
| `applyTime` | string | 申请时间，格式 `YYYY-MM-DD HH:mm:ss` |
| `disbursementTime` | string \| null | 放款时间，未放款时为 `null` |

### `status` 枚举

| 值 | 中文标签 | 说明 |
|----|----------|------|
| `completed` | 已结清 | 贷款已全部还清 |
| `repaying` | 还款中 | 贷款已放款，正在还款中 |
| `rejected` | 已拒绝 | 申请被拒绝 |
| `cancelled` | 已取消 | 申请被取消 |

---

## 前端页面使用位置

| 页面 | 文件 | 标签页 |
|------|------|--------|
| 审批详情 | `src/views/ApplicationDetail.vue` | el-tab-pane name="history" |

### 前端对接说明

1. 页面已通过 `loanApi.getAuditBasic(applicationId)` 获得 `basicInfo`，其中包含 `userId`
2. 切换至「历史借贷」标签页时调用该接口
3. `summary` 数据填充顶部的统计描述（`el-descriptions`）
4. `records` 数据填充下方的明细表格（`el-table`）
5. 状态字段映射：

| 状态值 | 标签颜色 | 中文显示 |
|--------|----------|----------|
| `completed` | `success` | 已结清 |
| `repaying` | `warning` | 还款中 |
| `rejected` | `danger` | 已拒绝 |
| `cancelled` | `info` | 已取消 |

---

## 异常情况

| 情况 | HTTP 状态码 | `code` | `msg` |
|------|-------------|--------|-------|
| 请求成功 | 200 | 200 | 操作成功 |
| 申请单号不存在 | 404 | 404 | 申请记录不存在 |
| 系统异常 | 500 | 500 | 服务器内部错误 |
