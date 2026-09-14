# 信用档案模块 — 接口文档

## 概述

用户只需填写一次个人信息，系统自动完成特征转换和评分。后续申请贷款时无需重复填写，直接选产品 + 金额 + 期限即可。

---

## 接口列表

### 1. 创建信用档案

```
POST /api/v1/credit-profile
```

#### 请求体

```json
{
  "name": "张三",
  "idCard": "110101199001011234",
  "phone": "13800138000",
  "email": "zhangsan@example.com",
  "birthDate": "1990-01-01",
  "gender": "男",
  "education": "本科",
  "occupation": "工程师",
  "familyInfo": {
    "maritalStatus": "已婚",
    "spouseInfo": {
      "name": "李四",
      "birthDate": "1992-05-10",
      "occupation": "教师",
      "monthlyIncome": 8000.00
    },
    "childrenList": [
      { "name": "张小一", "birthDate": "2020-03-15", "relationship": "儿子" }
    ],
    "parentsInfo": [
      { "name": "张父", "birthDate": "1960-01-01", "relationship": "父亲", "isDependent": true }
    ],
    "otherDependents": [
      { "name": "张小二", "relationship": "弟弟", "isFinancialDependent": true }
    ]
  },
  "financialInfo": {
    "incomeSources": [
      { "type": "工资", "monthlyAmount": 15000.00, "description": "主业收入" }
    ],
    "bankStatements": [
      {
        "bankName": "中国银行",
        "accountNumber": "6217****1234",
        "monthlyBalances": [
          { "month": "2026-01", "averageBalance": 50000.00, "totalIncome": 20000.00, "totalExpense": 15000.00 }
        ]
      }
    ],
    "assetInfo": [
      { "assetType": "房产", "description": "自住房", "estimatedValue": 2000000.00, "acquisitionDate": "2020-06-01" }
    ],
    "liabilityInfo": [
      { "liabilityType": "房贷", "creditor": "建设银行", "totalAmount": 1000000.00, "monthlyPayment": 5500.00, "dueDate": "2040-06-01" }
    ]
  },
  "employmentInfo": {
    "companyName": "某某科技有限公司",
    "position": "高级工程师",
    "workDurationYears": 5,
    "industryType": "互联网",
    "monthlySalary": 15000.00,
    "additionalIncome": 3000.00,
    "employmentType": "全职"
  }
}
```

#### 响应

```json
{
  "code": 200,
  "msg": "信用档案创建成功，信用分：680",
  "data": null
}
```

| 情况 | code | msg |
|------|------|-----|
| 成功 | 200 | 信用档案创建成功，信用分：{score} |
| 已存在 | 400 | 您已创建信用档案，如需修改请使用更新接口 |
| 参数校验失败 | 400 | 姓名不能为空 / 身份证号不能为空 / ... |

---

### 2. 更新信用档案

```
PUT /api/v1/credit-profile
```

#### 请求体

与创建接口完全一致，传需要修改的字段即可。

#### 响应

```json
{
  "code": 200,
  "msg": "信用档案更新成功，信用分：690",
  "data": null
}
```

| 情况 | code | msg |
|------|------|-----|
| 成功 | 200 | 信用档案更新成功，信用分：{score} |
| 未建档 | 400 | 请先创建信用档案 |

---

### 3. 查询信用档案

```
GET /api/v1/credit-profile
```

#### 响应

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "id": 1,
    "userId": "abc123",
    "name": "张三",
    "idCard": "110101199001011234",
    "phone": "138****0000",
    "email": "zhangsan@example.com",
    "birthDate": "1990-01-01T00:00:00",
    "gender": "男",
    "education": "本科",
    "occupation": "工程师",
    "familyInfo": "{...}",        ← JSON 字符串
    "financialInfo": "{...}",      ← JSON 字符串
    "employmentInfo": "{...}",     ← JSON 字符串
    "age": 36,
    "debtRatio": 0.3667,
    "monthlyIncome": 18000.00,
    "creditLines": 3,
    "dependents": 2,
    "revolvingUtil": 0.1500,
    "creditScore": 680,
    "createTime": "2026-06-14T10:00:00",
    "updateTime": "2026-06-14T10:00:00"
  }
}
```

| 情况 | code | msg |
|------|------|-----|
| 成功 | 200 | (含 data) |
| 未建档 | 404 | 请先创建信用档案 |

---

### 4. 删除信用档案

```
DELETE /api/v1/credit-profile
```

#### 响应

```json
{
  "code": 200,
  "msg": "信用档案已删除",
  "data": null
}
```

---

### 5. 重新评分

基于现有档案数据重新计算信用分（不修改个人信息）。

```
POST /api/v1/credit-profile/rescore
```

#### 响应

```json
{
  "code": 200,
  "msg": "重新评分完成，当前信用分：690",
  "data": null
}
```

| 情况 | code | msg |
|------|------|-----|
| 成功 | 200 | 重新评分完成，当前信用分：{score} |
| 未建档 | 404 | 请先创建信用档案 |

---

## 数据流

```
用户填写信息
      │
      ▼
特征转换（FeatureTransformationService）
      │
      ├─ 年龄 / 负债率 / 月收入
      ├─ 信用额度数量 / 家属人数 / 资产率
      │
      ▼
评分卡计算（ScorecardService）
      │
      ▼
存入 user_credit_profile 表
      │
      ▼
申请贷款时直接读取档案 → 走审批流程
```

---

## 数据库表

```sql
CREATE TABLE user_credit_profile (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(64)     NOT NULL UNIQUE       COMMENT '用户ID（唯一）',

    -- 个人信息
    name            VARCHAR(64),
    id_card         VARCHAR(32),
    phone           VARCHAR(20),
    email           VARCHAR(128),
    birth_date      DATE,
    gender          VARCHAR(4),
    education       VARCHAR(32),
    occupation      VARCHAR(64),

    -- JSON 详细数据
    family_info     JSON            COMMENT '家庭信息',
    financial_info  JSON            COMMENT '财务信息',
    employment_info JSON            COMMENT '职业信息',

    -- 评分特征（由系统计算）
    age             INT,
    debt_ratio      DECIMAL(10,4),
    monthly_income  DECIMAL(12,2),
    credit_lines    INT,
    dependents      INT,
    revolving_util  DECIMAL(10,4),

    -- 评分结果
    credit_score    INT             COMMENT '最近一次信用评分',

    create_time     DATETIME        NOT NULL,
    update_time     DATETIME        NOT NULL,

    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信用档案';
```

---

## 与贷款申请的衔接

| 步骤 | 接口 | 说明 |
|------|------|------|
| 1. 首次建档 | `POST /api/v1/credit-profile` | 填个人信息 → 自动评分 |
| 2. 申请贷款（简化） | `POST /api/v1/loan/application/submit` | 只需 `productId` + `applyAmount` + `applyTerm`，系统从档案读评分数据 |
| 3. 申请贷款（完整） | `POST /api/v1/loan/application/submit-with-basic-info` | 填完整信息，同时**自动更新档案** |
| 4. 更新档案 | `PUT /api/v1/credit-profile` | 信息变更时修改 → 自动重新评分 |

---

> **文档版本**: 2026-06-14  
> **后端文件**: `UserCreditProfileController` + `UserCreditProfileService` + `UserCreditProfileMapper`
