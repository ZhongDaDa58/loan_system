# 消息通知模块 — 接口文档

## 概述

App 内通知系统，支持逾期提醒、还款预警、系统通知三类消息。用户登录后通过 REST 接口获取和管理通知。

### 通知类型

| 类型 | 说明 | 触发方式 |
|------|------|---------|
| `overdue` | 还款逾期提醒 | 每天 02:00 定时任务自动检测触发 |
| `repayment_reminder` | 还款提前预警 | 每天 09:00 定时任务自动检测触发 |
| `system` | 系统通知 | 预留，暂未使用 |

### 定时任务

```
02:00  → 逾期检测 → 标记逾期 → 发通知（type=overdue）
09:00  → 还款预警 → 未来3天到期 → 发通知（type=repayment_reminder，每笔仅1次）
```

### 手动触发（测试用）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/loan/overdue/check` | POST | 立即执行逾期检测 |
| `/api/v1/loan/overdue/send-reminders` | POST | 立即发送还款提醒 |

---

## 接口列表

### 1. 查询通知列表

分页查询当前登录用户的通知，按时间倒序。

```
GET /api/v1/notifications?page=1&pageSize=20
```

#### 请求参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `page` | Integer | 否 | 1 | 页码 |
| `pageSize` | Integer | 否 | 20 | 每页条数 |

#### 响应 `data`

```json
{
  "list": [
    {
      "id": 1,
      "userId": "abc123",
      "title": "还款逾期提醒",
      "content": "您第 3 期还款（4283.33 元）已逾期，请尽快还款以免影响信用。",
      "type": "overdue",
      "relatedId": "RP20250301001",
      "isRead": false,
      "createTime": "2026-06-12T02:00:00"
    },
    {
      "id": 2,
      "userId": "abc123",
      "title": "还款提醒",
      "content": "您有一笔 5000.00 元的还款将于 06月15日 到期，请确保账户余额充足。",
      "type": "repayment_reminder",
      "relatedId": "RP20250615001",
      "isRead": true,
      "createTime": "2026-06-12T09:00:00"
    }
  ],
  "total": 2,
  "page": 1,
  "pageSize": 20
}
```

#### 前端渲染对照

| 字段 | 展示位置 |
|------|---------|
| `title` | 通知列表项标题 |
| `content` | 通知列表项正文 |
| `type` | 可用来显示不同图标（⚠️逾期 / 📅还款 / ℹ️系统） |
| `createTime` | 通知时间 |
| `isRead` | 未读加粗/蓝点标记，已读灰字 |
| `relatedId` | 点击跳转业务页面（如逾期追到还款详情） |

---

### 2. 查询未读数量

用于 App 首页/底部导航栏红点提示。

```
GET /api/v1/notifications/unread-count
```

#### 响应

```json
{
  "code": 200,
  "data": 3
}
```

---

### 3. 标记单条已读

```
PUT /api/v1/notifications/{id}/read
```

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 通知 ID |

#### 响应

```json
{ "code": 200, "msg": "操作成功", "data": null }
```

---

### 4. 标记全部已读

```
PUT /api/v1/notifications/read-all
```

#### 响应

```json
{ "code": 200, "msg": "操作成功", "data": null }
```

---

## 数据库表

```sql
CREATE TABLE notification (
    id          BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(64)     NOT NULL                COMMENT '接收用户 ID',
    title       VARCHAR(128)    NOT NULL                COMMENT '通知标题',
    content     TEXT                                    COMMENT '通知正文',
    type        VARCHAR(32)     NOT NULL DEFAULT 'system' COMMENT '类型',
    related_id  VARCHAR(64)                             COMMENT '关联业务 ID',
    is_read     TINYINT(1)      DEFAULT 0               COMMENT '0-未读 1-已读',
    create_time DATETIME        NOT NULL                COMMENT '创建时间',
    INDEX idx_user_time (user_id, create_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统通知';
```

---

## App 端对接示例

### 首页红点（Vue / 小程序）

```javascript
// 启动时获取未读数
const { data: unreadCount } = await axios.get('/api/v1/notifications/unread-count')
if (unreadCount > 0) showBadge(unreadCount)
```

### 消息中心列表

```javascript
const res = await axios.get('/api/v1/notifications', {
  params: { page: 1, pageSize: 20 }
})
res.data.list.forEach(item => {
  // 根据 type 显示不同图标
  item.icon = item.type === 'overdue' ? '⚠️' : '📅'
})
```

### 点击通知

```javascript
async function onClick(item) {
  // 标记已读
  await axios.put(`/api/v1/notifications/${item.id}/read`)
  item.isRead = true  // 本地更新状态

  // 跳转业务页面
  if (item.type === 'overdue' || item.type === 'repayment_reminder') {
    navigateTo('/repayment/detail', { id: item.relatedId })
  }
}
```

### 全部已读

```javascript
async function markAllRead() {
  await axios.put('/api/v1/notifications/read-all')
  list.forEach(item => item.isRead = true)
  unreadCount = 0
}
```

---

## 接口一览

| # | 接口 | 方法 | 用途 |
|---|------|------|------|
| 1 | `/api/v1/notifications` | GET | 分页查通知列表 |
| 2 | `/api/v1/notifications/unread-count` | GET | 未读数量 |
| 3 | `/api/v1/notifications/{id}/read` | PUT | 标记单条已读 |
| 4 | `/api/v1/notifications/read-all` | PUT | 标记全部已读 |
| — | `/api/v1/loan/overdue/check` | POST | 手动触发逾期检测 |
| — | `/api/v1/loan/overdue/send-reminders` | POST | 手动触发还款提醒 |

---

> **文档版本**: 2026-06-12  
> **后端文件**: `NotificationController` + `NotificationService` + `RepaymentReminderService` + `OverdueCheckService`
