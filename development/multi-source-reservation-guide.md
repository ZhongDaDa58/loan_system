# 后端多源数据字段与接口预留指南（第 2 周）

> 目的：在不影响现有功能的前提下，为"评分卡多数据源融合升级"预留数据字段与接口落点。
> 配套：`../development/` 契约文档、12 周计划 W2 任务；`model_config.json` v3.0 为评分卡基线。
> 红线：**本文件列出的所有改动不得修改现有表语义、现有接口签名与评分卡特征口径。**

---

## 1. 现状盘点（改动前必须知道的）

| 对象 | 现状 | 是否可动 |
|---|---|---|
| `model_config.json` v3.0 | 6 特征：`revolving_util/age/debt_ratio/monthly_income/credit_lines/dependents`；阈值 auto 650 / manual 600 | **不可动**（决定审批三档决策） |
| `ScorecardController` `/api/v1/scorecard` 4 个端点 | 入参 `UserProfileDTO`/`UserBasicInfoDTO` | **不可动**；v4 新增端点而非改现有 |
| `user_credit_profile` 表 | Mapper 三处 SQL 均为**显式列白名单**；`update` 全列覆盖 | 可**加可空列**，但**不建议**（见 §3 结论） |
| `loan_application` / `monthly_repayment` / `repayment_plan` 等主流程表 | 贷款申请→评分→审批→还款主链路 | **不可动** |
| `UserCreditProfile`/`UserCreditScore`/`RiskDecisionLog` | 信用档案、信用分、决策留痕 | 作为**扩展落点**（宽表化、批量同步、版本留痕） |
| 鉴权 | JWT + `LoginInterceptor` 写入 `request.getAttribute("userId")` | 新端点自动受保护 |
| 返回/命名 | `Result<T>` 包装；`@RequestMapping("/api/v1/...")`；表列 snake_case、实体 camelCase、`map-underscore-to-camel-case=true` | 新代码遵循同样约定 |

## 2. 为什么"独立成表 + 独立端点"最安全

现有 Mapper 的 insert/update/select 全部是**显式列名**（无 `SELECT *`、无 `SET 全表字段`）：

- 新表、新列 → 旧代码完全不知道 → **零影响**；
- 若往 `user_credit_profile` 加列：旧 `insert` 不写该列 → 走 DB 默认值；旧 `update` 不 SET 该列 → 原值保留；旧 `select` 不查该列 → 实体字段为 null。看似安全，但**新增列一旦要写入，就必须同步改三处 Mapper SQL + `copyDtoToEntity` + 新 DTO 字段**，改动面扩散且易漏。
- **结论**：多源数据一律进**新表**；`user_credit_profile` 保持不动。信用档案的"多源摘要展示"后续由新表 join/独立接口提供。

## 3. 新增数据表（DDL，MySQL 库 `loan_risk_control`）

### 3.1 `data_source_authorization` — 多源授权留痕表（对应合规要求：授权留痕、脱敏）

```sql
CREATE TABLE data_source_authorization (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        VARCHAR(64)  NOT NULL COMMENT '用户ID，关联 sys_user.user_id',
  source_type    VARCHAR(32)  NOT NULL COMMENT '数据源类型：credit/operator/social/device',
  auth_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING=待授权 AUTHORIZED=已授权 REVOKED=已撤销 EXPIRED=已过期',
  auth_token     VARCHAR(128) NULL COMMENT '模拟授权凭证（脱敏/加密存储，不落明文敏感信息）',
  scope_json     TEXT         NULL COMMENT '授权范围（JSON）',
  authorized_at  DATETIME     NULL COMMENT '授权时间',
  expiry_at      DATETIME     NULL COMMENT '授权有效期至',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_source (user_id, source_type),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多数据源授权留痕表';
```

### 3.2 `user_multi_source_data` — 多源标准化数据表（Provider 拉取结果落库；特征宽表化留待 W5）

```sql
CREATE TABLE user_multi_source_data (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        VARCHAR(64) NOT NULL COMMENT '用户ID',
  source_type    VARCHAR(32) NOT NULL COMMENT 'credit/operator/social/device',
  data_json      TEXT        NULL COMMENT '标准化数据载荷（JSON），字段口径见《多源数据.md》',
  data_version   VARCHAR(16) NULL COMMENT '数据口径版本（对齐模型/特征版本）',
  fetch_status   VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
                 COMMENT 'SUCCESS/FAILED/STALE=数据过期',
  fetched_at     DATETIME    NULL COMMENT '最近拉取时间',
  create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user_source (user_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多源标准化数据表';
```

### 3.3 `data_source_registry` — 数据源 Provider 注册与开关表（统一开关/熔断/监控）

```sql
CREATE TABLE data_source_registry (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type    VARCHAR(32) NOT NULL COMMENT 'credit/operator/social/device',
  provider_name  VARCHAR(64) NOT NULL COMMENT 'Provider 实现类名或 Bean 名',
  enabled        TINYINT     NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  priority       INT         NOT NULL DEFAULT 100 COMMENT '优先级（同源多 Provider 时生效）',
  circuit_status VARCHAR(16) NOT NULL DEFAULT 'CLOSED' COMMENT 'CLOSED/OPEN/HALF_OPEN（熔断状态）',
  config_json    TEXT        NULL COMMENT '扩展配置（JSON）',
  last_health_at DATETIME    NULL COMMENT '最近健康检查时间',
  create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_source (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源 Provider 注册表';
```

> 同步为三张表各新增一个 entity（`com.loan.entity` 下 `DataSourceAuthorization` / `UserMultiSourceData` / `DataSourceRegistryConfig`，Lombok `@Data`，字段命名与列一一对应）+ 对应 Mapper XML（先只写 `insertByUserId` / `selectByUserId` / `updateStatus` 等最小方法）。**每张表只做独立读写，不 join 现有主流程表。**

## 4. 新增 Java 骨架（新建包，不动现有包结构）

### 4.1 SPI 接口：`com.loan.spi.DataSourceProvider`（W3 起由 Mock Provider 实现）

```java
package com.loan.spi;

import java.util.Map;

public interface DataSourceProvider {
    String sourceType();                 // credit / operator / social / device
    Map<String, Object> fetch(String userId);  // 返回标准化字段（键名见《多源数据.md》）
    boolean isEnabled();                 // 开关（联动 data_source_registry.enabled）
    boolean healthCheck();               // 熔断健康检查
}
```

配套：
- `com.loan.spi.MultiSourceData`（简单数据载体：`sourceType` + `Map<String,Object> fields` + `fetchedAt`）；
- `com.loan.spi.DataSourceRegistry`（`@Component`，启动时扫描注册所有 `DataSourceProvider` Bean，提供 `getProvider(sourceType)`，统一执行开关与熔断检查）——这是 W4"统一接入层"的雏形，W2 只搭空壳。

### 4.2 Mock Provider 占位（W3 实现，本期只建类不做逻辑）

`com.loan.spi.provider` 下建 `MockCreditProvider` / `MockOperatorProvider` / `MockSocialProvider` / `MockDeviceProvider` 四个空实现，`@Component` 注册，返回空 `Map` 即可——目的是让注册表链路先跑通。

## 5. 新增接口（新端点，路径不与现有冲突）

新增两个 Controller（遵循 `Result<T>` 包装 + JWT 自动鉴权），**不改动任何现有 Controller**：

### 5.1 `DataSourceAuthorizationController` — `/api/v1/data-source`

| 方法 | 路径 | 说明 | 本期行为 |
|---|---|---|---|
| POST | `/api/v1/data-source/authorize` | 发起某源授权（模拟：body `{sourceType}`） | 写 `data_source_authorization`（PENDING→AUTHORIZED），返回脱敏授权状态 |
| GET | `/api/v1/data-source/authorizations` | 查当前用户各源授权状态 | 按 `user_id` 查表返回 |
| POST | `/api/v1/data-source/revoke` | 撤销授权 | 状态置 REVOKED |

### 5.2 `MultiSourceController` — `/api/v1/multi-source`

| 方法 | 路径 | 说明 | 本期行为 |
|---|---|---|---|
| GET | `/api/v1/multi-source/profile` | 多源脱敏摘要（W5 供审批详情/App 信用档案展示） | 本期先返回各源授权状态 + 空数据块，**结构先行** |
| POST | `/api/v1/multi-source/event` | App 端设备/行为事件上报预留（W3 埋点用） | 本期仅做参数校验 + 记录日志，不落库（或落一张事件日志表后再定） |

> 端点冲突自查：现有 `/api/v1/credit-score`、`/api/v1/credit-profile`、`/api/v1/scorecard` 等均与新路径无重叠；新端点加入后旧接口请求路径不变。

## 6. 字段预留规范（新增内容必须遵守）

1. **列名** snake_case，实体属性 camelCase（现有 `map-underscore-to-camel-case=true`）；
2. 外键统一用 `String userId` / `String applicationId`（雪花 ID），不新建自增关联列；
3. 时间列统一 `create_time` / `update_time`，`DATETIME` + 默认 `CURRENT_TIMESTAMP`；
4. 复杂结构用 `TEXT` 存 JSON（先例：`user_credit_profile.family_info` 等），**敏感字段脱敏后入库、不落明文**；
5. 新增列一律**可空/带默认值**，禁止 NOT NULL 无默认；
6. Mapper XML 保持**显式列名**习惯，新增列须同步出现在该表自己的 insert/update/select 三处；
7. 状态类字段用枚举常量字符串（先例：`ApplicationStatusEnum`），不散落硬编码。

## 7. 第 2 周执行清单（建议顺序）

- [ ] ① 执行 §3 三张表 DDL（`loan_risk_control` 库）；
- [ ] ② 新增三个 entity + 三个 Mapper XML（最小读写方法）；
- [ ] ③ 建 `com.loan.spi` 包：`DataSourceProvider` + `MultiSourceData` + `DataSourceRegistry` 空壳；
- [ ] ④ 建四个 Mock Provider 空实现并注册；
- [ ] ⑤ 实现两个新 Controller 的 6 个预留端点骨架；
- [ ] ⑥ 更新 `development/` 下契约文档（本指南转为正式 API 文档）；
- [ ] ⑦ 回归验证（见 §8）。

## 8. 回归验证（防止影响现有功能）

1. **现有测试**：`./mvnw.cmd test` 全绿（Windows 下用 `mvnw.cmd`）；
2. **手工冒烟**（可借助 Swagger：`/swagger-ui.html`）：
   - 老链路：建档 `POST /api/v1/credit-profile` → 评分 `POST /api/v1/scorecard/calculate` → 贷款申请 → 决策三档结果与改动前一致；
   - 新链路：`POST /api/v1/data-source/authorize` → `GET /api/v1/multi-source/profile` 能鉴权通过并返回预留结构；
3. **确认项**：`model_config.json` 未变、`ScorecardController` 4 个端点签名未变、`user_credit_profile` 表结构未变、主流程表未变。

---

**文档结束**
