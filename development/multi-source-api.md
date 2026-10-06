# 多数据源模块 — 接口文档

> 版本：V1.1（W3：Provider 填数 + 同步落库 + 特征宽表）
> 关联文档：`multi-source-reservation-guide.md`（设计与字段预留指南）、项目根 `多源数据.md`（字段口径）
> 实现代码：`DataSourceController`、`MultiSourceController`、`DataSourceService`、`MultiSourceService`、
> `MultiSourceIngestService`（W3 新增）、`com.loan.spi`（SPI + 4 个 Provider）

---

## 概述

- **模块范围**：多数据源授权（授权留痕、可撤销） + **多源数据同步与落库**（W3 新增） + **多源特征宽表**（W3 新增） + 脱敏摘要 + 客户端事件上报。
- **鉴权**：JWT，请求头 `Authorization: Bearer <token>`；由 `LoginInterceptor` 统一覆盖 `/api/v1/**`（未登录返回 `{"code":401,...}`）。
- **返回包装**：统一 `Result<T>`（`code` / `msg` / `data`），成功 `code=200`。
- **数据源类型**（`SourceTypeEnum`）：

| code | 含义 | Provider 状态（W3） |
|---|---|---|
| `credit` | 央行征信 | ✅ **已实现模拟数据**（查询次数/负债比/历史逾期） |
| `operator` | 运营商数据 | ✅ **已实现模拟数据**（实名时长/在网时长/缴费稳定度） |
| `social` | 社保/公积金 | ⏳ 占位（清洗规则与白名单已备） |
| `device` | 设备指纹 | ⏳ 占位（计划 W4 接入） |

- **当前阶段（W3）实现边界**：
  - **征信 / 运营商 Provider 已按《多源数据.md》口径产出模拟数据**（以 `userId` 为种子的**确定性伪随机**：同一用户结果稳定）；
  - `/multi-source/sync` 完成 **Provider 拉取 → 标准化清洗 → upsert 落库**（`user_multi_source_data`）；
  - `/multi-source/features` 输出**多源特征宽表快照**（各源字段合并，供评分卡/展示）；
  - `/multi-source/profile` 的 `dataBlocks` 仍为**空结构**（W5 填充展示摘要）；
  - `/multi-source/event` 仍仅做参数校验 + 日志记录，**不落库**。
- **相关数据表**：`data_source_authorization`（授权留痕）、`user_multi_source_data`（多源标准化数据，W3 起写入）、`data_source_registry`（Provider 注册配置，W4 起消费）。

---

## 1. 发起数据源授权

```
POST /api/v1/data-source/authorize
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sourceType` | string | 是 | `credit` / `operator` / `social` / `device` |
| `scopeJson` | string | 否 | 授权范围说明（JSON 字符串） |

```json
{
  "sourceType": "credit"
}
```

**响应**（实测）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "sourceType": "credit",
    "sourceName": "央行征信",
    "authStatus": "AUTHORIZED",
    "authStatusDesc": "已授权",
    "authorized": true,
    "authorizedAt": "2026-09-15T06:49:09.000Z",
    "expiryAt": "2026-12-14T06:49:09.000Z"
  }
}
```

**行为说明**

- 首次授权：写入 `data_source_authorization`，状态 `PENDING → AUTHORIZED`；
- `authorizedAt` = 当前时间，`expiryAt` = 当前时间 + **90 天**；
- `authToken` 为**脱敏模拟凭证**：`MOCK-<sourceType>-<8位随机>`，不落任何真实敏感信息；
- **幂等**：已处于 `AUTHORIZED` 时重复调用不报错，直接返回当前状态；
- 已撤销（`REVOKED`/`EXPIRED`）后再次调用视为重新授权，刷新时间与凭证。

**错误**

| code | msg |
|---|---|
| 400 | `不支持的数据源类型：xxx` |

---

## 2. 查询各数据源授权状态

```
GET /api/v1/data-source/authorizations
```

**响应**（实测：已授权 1 个、未授权 3 个）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    {
      "sourceType": "credit", "sourceName": "央行征信",
      "authStatus": "AUTHORIZED", "authStatusDesc": "已授权", "authorized": true,
      "authorizedAt": "2026-09-15T06:49:09.000Z", "expiryAt": "2026-12-14T06:49:09.000Z"
    },
    {
      "sourceType": "operator", "sourceName": "运营商数据",
      "authStatus": "PENDING", "authStatusDesc": "待授权", "authorized": false,
      "authorizedAt": null, "expiryAt": null
    },
    {
      "sourceType": "social", "sourceName": "社保/公积金",
      "authStatus": "PENDING", "authStatusDesc": "待授权", "authorized": false,
      "authorizedAt": null, "expiryAt": null
    },
    {
      "sourceType": "device", "sourceName": "设备指纹",
      "authStatus": "PENDING", "authStatusDesc": "待授权", "authorized": false,
      "authorizedAt": null, "expiryAt": null
    }
  ]
}
```

**说明**：**未授权的数据源也会返回**，状态为 `PENDING` 占位（便于 App 授权中心直接渲染四项）。

授权状态取值（`AuthStatusEnum`）：`PENDING` 待授权 / `AUTHORIZED` 已授权 / `REVOKED` 已撤销 / `EXPIRED` 已过期。

---

## 3. 撤销数据源授权

```
POST /api/v1/data-source/revoke
```

**请求体**：同接口 1。

```json
{ "sourceType": "operator" }
```

**响应**

```json
{ "code": 200, "msg": "操作成功", "data": "授权已撤销" }
```

**行为说明**

- 仅将 `auth_status` 置为 `REVOKED`；
- **保留** `authorizedAt` / `expiryAt` / `authToken`，便于审计追溯（留痕不丢）。

**错误**

| code | msg |
|---|---|
| 404 | `未找到该数据源的授权记录` |

---

## 4. 多源数据脱敏摘要

```
GET /api/v1/multi-source/profile
```

**响应**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "userId": "14d7933c2e474b8eb7649746fc4541e1",
    "authorizations": [ /* 结构同接口 2 */ ],
    "dataBlocks": { "credit": {}, "operator": {}, "social": {}, "device": {} },
    "registeredSources": ["credit", "device", "operator", "social"],
    "dataVersion": "v1"
  }
}
```

| 字段 | 说明 |
|---|---|
| `authorizations` | 各源授权状态（含 `PENDING` 占位） |
| `dataBlocks` | 各源脱敏数据块；**仍为空结构**，W5 填充（届时可改为引用接口 7 的宽表） |
| `registeredSources` | 当前已注册的数据源（来自 `DataSourceRegistry`，验证 SPI 装配） |
| `dataVersion` | 数据口径版本（对齐《多源数据.md》） |

---

## 5. 客户端设备/行为事件上报

```
POST /api/v1/multi-source/event
```

**请求体**：数组

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `eventType` | string | 是 | 事件类型：`device` / `behavior` / `location` 等 |
| `eventData` | object | 否 | 事件载荷（键名见《多源数据.md》） |
| `timestamp` | long | 否 | 客户端事件时间戳（毫秒） |

```json
[
  {
    "eventType": "device",
    "eventData": { "device_fingerprint_hash": "abc123" },
    "timestamp": 1789000000000
  }
]
```

**响应**

```json
{ "code": 200, "msg": "操作成功", "data": { "accepted": 1 } }
```

**行为说明**：本期仅校验参数并记录日志（不落库）；`eventData` 仅按字段数记录，不记录明文内容。

**错误**

| code | msg |
|---|---|
| 400 | `上报事件不能为空`（空数组/空 body） |

---

## 6. 同步多源数据（W3 新增）

```
POST /api/v1/multi-source/sync?sourceType=credit
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sourceType` | string | 否 | 指定数据源；**不传则同步全部已注册源** |

**行为**：SPI 拉取（Provider）→ **标准化清洗**（字段白名单 + 数值区间截断 + 缺失保留）→ **upsert 落库** `user_multi_source_data`（同一用户同一源仅一行）。

**响应（实测：指定 credit）**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "credit": {
      "status": "SUCCESS",
      "fieldCount": 7,
      "fields": {
        "credit_query_cnt_3m": 0.0, "credit_query_cnt_6m": 1.0, "debt_to_income": 0.42,
        "hist_overdue_cnt_m1": 0.0, "hist_overdue_cnt_m2": 0.0, "hist_overdue_cnt_m3": 1.0,
        "data_version": "v1"
      },
      "fetchedAt": "2026-09-20T06:56:58.828Z"
    },
    "userId": "14d7933c2e474b8eb7649746fc4541e1"
  }
}
```

**响应（实测：不传参数 → 全部已注册源）**

```json
{
  "code": 200, "msg": "操作成功",
  "data": {
    "credit":   { "status": "SUCCESS", "fieldCount": 7, "fields": { "…": "…" } },
    "operator": { "status": "SUCCESS", "fieldCount": 4,
                  "fields": { "operator_realname_months": 85.0, "operator_tenure_months": 74.0,
                              "operator_pay_stability": 36.0, "data_version": "v1" } },
    "device":   { "status": "EMPTY", "message": "数据源未返回数据（可能未启用或 Provider 无数据）" },
    "social":   { "status": "EMPTY", "message": "数据源未返回数据（可能未启用或 Provider 无数据）" },
    "userId": "14d7933c2e474b8eb7649746fc4541e1"
  }
}
```

| status | 含义 |
|---|---|
| `SUCCESS` | 拉取并入库成功 |
| `EMPTY` | Provider 未返回数据（如占位 Provider、未启用） |
| `FAILED` | 清洗后无有效字段或异常 |

> 说明：`device` / `social` 当前返回 `EMPTY`，因为它们的 Provider 仍为占位实现（设备指纹计划 W4 接入）。

---

## 7. 多源特征宽表快照（W3 新增）

```
GET /api/v1/multi-source/features
```

**行为**：读取 `user_multi_source_data` 中该用户的各源记录，解析 JSON 并**合并为特征宽表**（键名为特征名，可直接用于评分卡）；同时返回各源的元数据。

**响应（实测）**

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "userId": "14d7933c2e474b8eb7649746fc4541e1",
    "featureCount": 9,
    "features": {
      "credit_query_cnt_3m": 0.0, "credit_query_cnt_6m": 1.0, "debt_to_income": 0.42,
      "hist_overdue_cnt_m1": 0.0, "hist_overdue_cnt_m2": 0.0, "hist_overdue_cnt_m3": 1.0,
      "operator_realname_months": 85.0, "operator_tenure_months": 74.0, "operator_pay_stability": 36.0
    },
    "sources": {
      "credit":   { "fetchStatus": "SUCCESS", "fetchedAt": "2026-09-20T06:56:59.000Z", "dataVersion": "v1" },
      "operator": { "fetchStatus": "SUCCESS", "fetchedAt": "2026-09-20T06:56:59.000Z", "dataVersion": "v1" }
    }
  }
}
```

> 该接口即"多源特征宽表"的读取入口；W4 的实时特征服务将基于同一数据来源构建。
> 尚未同步任何数据时返回 `featureCount=0` 与提示信息。

---

## 8. 批量同步（W4 主线 B）

```
POST /api/v1/multi-source/sync/batch?sourceType=device&limit=200
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sourceType` | string | 否 | 指定数据源；为空则处理全部已注册源 |
| `limit` | int | 否 | 单次最多处理的用户数（默认 200） |

**行为**：范围来自**授权留痕表**——只同步 `auth_status=AUTHORIZED` 的用户（合规优先）；每个用户走"拉取 → 清洗 → 落库"。

**响应结构**

```json
{
  "code": 200, "msg": "操作成功",
  "data": {
    "mode": "BATCH",
    "sources": ["device"],
    "limit": 200,
    "users": 3, "success": 3, "failed": 0,
    "perSource": [ { "sourceType": "device", "users": 3, "success": 3, "failed": 0 } ],
    "elapsedMs": 152,
    "startedAt": "…", "finishedAt": "…",
    "circuit": [ { "sourceType": "device", "enabled": true, "circuitStatus": "CLOSED", "failureCount": 0 } ]
  }
}
```

## 9. 增量同步（W4 主线 B）

```
POST /api/v1/multi-source/sync/incremental?sourceType=device&staleHours=24&limit=200
```

**行为**：只同步"已授权 **且** 多源数据缺失或已过期（`fetched_at` 早于 `now - staleHours`）"的用户——避免全量重复拉取。

**响应结构**：同接口 8，`mode=INCREMENTAL`。

## 10. 同步统计（W4 主线 B）

```
GET /api/v1/multi-source/sync/stats
```

**行为**：返回**最近一次**批量/增量同步的统计（含各源明细、耗时与当时各源的熔断状态），用于可观测与评审展示。

> 定时同步：`@Scheduled(cron = "${multisource.sync.cron:0 30 2 * * ?}")`（每日 02:30，避开既有逾期检测 02:00）；
> **默认关闭**，需显式开启 `multisource.sync.enabled=true`；过期阈值由 `multisource.sync.stale-hours`（默认 24）控制。

## 11. 数据源注册表（W4 主线 B）

```
GET /api/v1/multi-source/registry
```

**行为**：返回各数据源的**开关、优先级、熔断状态、可用性**，以及 `healthCheck()` 结果；配置存于 `data_source_registry` 表（启动时自动补默认记录，幂等）。

**响应结构**

```json
{
  "code": 200, "msg": "操作成功",
  "data": {
    "sources": [
      { "sourceType": "credit",  "provider": "MockCreditProvider",  "enabled": true,
        "priority": 100, "circuitStatus": "CLOSED", "failureCount": 0, "available": true },
      { "sourceType": "device",  "provider": "MockDeviceProvider",  "enabled": true,
        "priority": 100, "circuitStatus": "CLOSED", "failureCount": 0, "available": true },
      { "sourceType": "operator","provider": "MockOperatorProvider","enabled": true, "…": "…" },
      { "sourceType": "social",  "provider": "MockSocialProvider",  "enabled": true, "…": "…" }
    ],
    "health": [ { "sourceType": "credit", "circuitStatus": "CLOSED", "healthy": true } ]
  }
}
```

**熔断规则（B3）**：同一数据源**连续失败 3 次**即熔断（`OPEN`，跳过取数），**冷却 60 秒**后进入 `HALF_OPEN` 试探，成功则恢复 `CLOSED`；状态同步写入 `circuit_status` 便于观测。

---

## 错误码汇总

| code | 场景 |
|---|---|
| 200 | 成功 |
| 400 | 数据源类型不受支持；上报事件为空；请求参数校验失败 |
| 401 | 未登录 / 令牌无效或过期（拦截器统一返回） |
| 403 | 账号不存在或已禁用（拦截器统一返回） |
| 404 | 撤销时未找到该数据源的授权记录 |

---

## 与后续版本的衔接

| 阶段 | 变化 | 状态 |
|---|---|---|
| W2 | 表结构 + SPI + 5 个端点骨架（Provider 返回空） | ✅ 已完成 |
| **W3** | **征信/运营商 Provider 填数；`/sync` 清洗落库；`/features` 特征宽表；`mvnw test` 全绿 + 冒烟通过** | ✅ **已完成** |
| W4 | 设备指纹 Provider 接入；`DataSourceRegistry` 读取 `data_source_registry` 实现统一开关/熔断/优先级；实时特征服务 | 待办 |
| W5 | `dataBlocks` 填充脱敏摘要；Web 审批详情页与 App 信用档案页展示多源画像 | 待办 |
| W6 | 多源特征 → 评分卡 v4 全链路联调（接口保持向后兼容） | 待办 |

> W3 附带说明：清洗环节将数值统一为 `double` 输出（如 `0.0`），如需保持整型可在 `MultiSourceIngestServiceImpl.clean()` 中按字段类型转换。

---

**文档结束**
