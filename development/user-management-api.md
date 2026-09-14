# 用户管理模块 — 后端接口文档

## 概述

用户管理模块包含 **用户列表** 和 **用户详情** 两个页面，共 **7 个 GET 接口**。所有接口统一返回 `Result<T>` 包装：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": { ... }
}
```

---

## 后端实现文件

| 层级 | 文件 | 说明 |
|------|------|------|
| **Controller** | `controller/UserManageController.java` | 7 个接口入口，`@RequestMapping("/api/v1/users")` |
| **Service** | `service/UserManageService.java` | 接口定义 |
| **ServiceImpl** | `service/impl/UserManageServiceImpl.java` | 业务逻辑实现（当前为 TODO 桩） |
| **VO** | `entity/vo/UserItemVO.java` | 用户列表项 |
| **VO** | `entity/vo/UserDetailVO.java` | 用户详情 |
| **VO** | `entity/vo/UserVerificationVO.java` | 实名认证信息 |
| **VO** | `entity/vo/UserBankCardVO.java` | 银行卡信息 |
| **VO** | `entity/vo/CreditHistoryPointVO.java` | 信用分历史点 |
| **VO** | `entity/vo/UserLoanRecordVO.java` | 贷款申请记录 |
| **VO** | `entity/vo/RepaymentRecordVO.java` | 还款记录 |
| **VO** | `entity/vo/PageResult.java` | 通用分页包装 |
| **Enum** | `entity/enums/VerifyStatusEnum.java` | 认证状态枚举 |

---

## 接口详情

### 1. 用户列表分页查询

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/users` |
| **用途** | 用户列表页的搜索、筛选、分页展示 |

#### 请求参数 (Query Params)

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `phone` | String | 否 | — | 手机号模糊搜索 |
| `name` | String | 否 | — | 姓名模糊搜索 |
| `startDate` | String | 否 | — | 注册开始日期 `YYYY-MM-DD` |
| `endDate` | String | 否 | — | 注册结束日期 `YYYY-MM-DD` |
| `verifyStatus` | String | 否 | — | 认证状态过滤：`verified` / `unverified` / `verifying` |
| `page` | Integer | 否 | `1` | 页码，从 1 开始 |
| `pageSize` | Integer | 否 | `20` | 每页条数 |
| `sortBy` | String | 否 | — | 排序字段：`registerTime` / `creditScore` |
| `sortOrder` | String | 否 | — | 排序方向：`asc` / `desc` |

#### 响应数据 `data`

```json
{
  "list": [
    {
      "userId": "U123456",
      "phone": "138****0000",
      "name": "张三",
      "nickname": "小张",
      "verifyStatus": "verified",
      "creditScore": 680,
      "registerTime": "2024-01-15 10:30:00",
      "lastLoginTime": "2025-03-01 08:12:00"
    }
  ],
  "total": 1,
  "page": 1,
  "pageSize": 20
}
```

#### 对应后端

```
UserManageController#listUsers()
UserManageServiceImpl#listUsers()
```

---

### 2. 用户详情

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/users/{userId}` |
| **用途** | 用户详情页「基本信息」标签页 |

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | String | 用户 ID |

#### 响应数据 `data`

```json
{
  "userId": "U123456",
  "phone": "138****0000",
  "name": "张三",
  "nickname": "小张",
  "gender": "男",
  "age": 28,
  "registerTime": "2024-01-15 10:30:00",
  "lastLoginTime": "2025-03-01 08:12:00",
  "verifyStatus": "verified",
  "creditScore": 680
}
```

#### 对应后端

```
UserManageController#getUserDetail()
UserManageServiceImpl#getUserDetail()
```

---

### 3. 用户实名认证信息

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/users/{userId}/verification` |
| **用途** | 用户详情页「实名认证」标签页 |

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | String | 用户 ID |

#### 响应数据 `data`

```json
{
  "realName": "张三",
  "idCard": "110101199001011234",
  "verifyTime": "2024-01-20 14:30:00",
  "verifyMethod": "人脸识别+身份证",
  "idCardFront": "https://xxx.com/idcard/front.jpg",
  "idCardBack": "https://xxx.com/idcard/back.jpg",
  "facePhoto": "https://xxx.com/face/photo.jpg"
}
```

#### 对应后端

```
UserManageController#getUserVerification()
UserManageServiceImpl#getUserVerification()
```

---

### 4. 用户银行卡列表

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/users/{userId}/bankcards` |
| **用途** | 用户详情页「银行卡」标签页 |

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | String | 用户 ID |

#### 响应数据 `data`

```json
[
  {
    "bankName": "中国建设银行",
    "cardNumber": "6222 **** **** 0123",
    "cardType": "debit",
    "isDefault": true,
    "bindTime": "2024-01-20 14:35:00",
    "status": "active"
  }
]
```

#### 对应后端

```
UserManageController#getUserBankCards()
UserManageServiceImpl#getUserBankCards()
```

---

### 5. 用户信用分历史

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/users/{userId}/credit-history` |
| **用途** | 用户详情页「信用分历史」标签页，ECharts 折线图数据 |

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | String | 用户 ID |

#### 响应数据 `data`

```json
[
  { "date": "2024-03", "score": 650 },
  { "date": "2024-06", "score": 670 },
  { "date": "2024-09", "score": 680 },
  { "date": "2024-12", "score": 690 }
]
```

#### 对应后端

```
UserManageController#getCreditHistory()
UserManageServiceImpl#getCreditHistory()
```

---

### 6. 用户贷款申请历史

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/users/{userId}/loans` |
| **用途** | 用户详情页「贷款历史」标签页 |

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | String | 用户 ID |

#### 响应数据 `data`

```json
[
  {
    "applicationId": "LA20250101001",
    "productName": "消费贷",
    "applyAmount": 50000.00,
    "applyTerm": 12,
    "status": "approved",
    "applyTime": "2025-01-01 10:00:00"
  }
]
```

> `status` 可选值：`pending`（待审核） / `approved`（审核通过） / `rejected`（拒绝） / `disbursed`（已放款）

#### 对应后端

```
UserManageController#getUserLoans()
UserManageServiceImpl#getUserLoans()
```

---

### 7. 用户还款记录

| 项目 | 内容 |
|------|------|
| **方法** | `GET` |
| **路径** | `/api/v1/users/{userId}/repayments` |
| **用途** | 用户详情页「还款记录」标签页 |

#### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `userId` | String | 用户 ID |

#### 响应数据 `data`

```json
[
  {
    "repaymentId": "RP20250201001",
    "loanId": "LA20250101001",
    "period": 1,
    "amount": 4283.33,
    "dueDate": "2025-02-01",
    "repayDate": "2025-02-01",
    "status": "paid"
  },
  {
    "repaymentId": "RP20250301001",
    "loanId": "LA20250101001",
    "period": 2,
    "amount": 4283.33,
    "dueDate": "2025-03-01",
    "repayDate": null,
    "status": "pending"
  }
]
```

> `status` 可选值：`paid`（已还款） / `pending`（待还款） / `overdue`（逾期）

#### 对应后端

```
UserManageController#getUserRepayments()
UserManageServiceImpl#getUserRepayments()
```

---

## 接口总览

| # | 接口 | 方法 | 路径 | 后端方法 |
|---|------|------|------|----------|
| 1 | 用户列表分页查询 | `GET` | `/api/v1/users` | `listUsers()` |
| 2 | 用户详情 | `GET` | `/api/v1/users/{userId}` | `getUserDetail()` |
| 3 | 实名认证信息 | `GET` | `/api/v1/users/{userId}/verification` | `getUserVerification()` |
| 4 | 银行卡列表 | `GET` | `/api/v1/users/{userId}/bankcards` | `getUserBankCards()` |
| 5 | 信用分历史 | `GET` | `/api/v1/users/{userId}/credit-history` | `getCreditHistory()` |
| 6 | 贷款申请历史 | `GET` | `/api/v1/users/{userId}/loans` | `getUserLoans()` |
| 7 | 还款记录 | `GET` | `/api/v1/users/{userId}/repayments` | `getUserRepayments()` |

---

## 待办事项

`UserManageServiceImpl` 中所有方法当前返回空数据桩（`// TODO`），需要补充以下 Mapper 查询逻辑：

| 接口 | 涉及数据表 |
|------|-----------|
| 用户列表 | `sys_user` + `user_identity` + `user_credit_score` |
| 用户详情 | `sys_user` + `user_identity` + `user_credit_score` |
| 实名认证 | `user_identity` + `user_face_log` |
| 银行卡 | `user_bank_card` + `mock_bank_card_pool` |
| 信用分历史 | `user_credit_score` |
| 贷款历史 | `loan_application` + `loan_product` |
| 还款记录 | `monthly_repayment` + `repayment_plan` |
