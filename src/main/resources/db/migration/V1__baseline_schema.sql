
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_record` (
  `audit_id` varchar(32) NOT NULL COMMENT '审核记录ID（UUID）',
  `application_id` varchar(32) NOT NULL COMMENT '关联申请ID（loan_application.application_id）',
  `auditor_id` varchar(32) NOT NULL COMMENT '审核员ID（关联sys_user.user_id）',
  `audit_result` varchar(20) NOT NULL COMMENT '审核结果（approved=通过，rejected=拒绝，supplement=需补充材料）',
  `audit_opinion` varchar(512) DEFAULT NULL COMMENT '审核意见',
  `audit_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `final_label` tinyint(1) DEFAULT NULL COMMENT 'Y标签（S1）：1=欺诈 / 0=正常 / NULL=未标注',
  `fraud_tag` varchar(64) DEFAULT NULL COMMENT '欺诈类型标签（如 资料伪造/团伙欺诈/中介代办）',
  `reject_reason` varchar(255) DEFAULT NULL COMMENT '结构化拒绝原因（与 audit_opinion 自由文本并存）',
  PRIMARY KEY (`audit_id`),
  KEY `idx_application_id` (`application_id`),
  KEY `idx_auditor_id` (`auditor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contract_sign_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `contract_no` varchar(50) NOT NULL COMMENT '合同编号',
  `action` varchar(20) NOT NULL COMMENT '动作: GENERATED, VIEWED, SIGNED',
  `action_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '动作发生时间',
  `ip_address` varchar(50) DEFAULT NULL COMMENT '操作IP地址',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注(如: 模拟区块高度)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合同签署过程日志';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `data_source_authorization` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL COMMENT '鐢ㄦ埛ID锛屽叧鑱?sys_user.user_id',
  `source_type` varchar(32) NOT NULL COMMENT '鏁版嵁婧愮被鍨嬶細credit/operator/social/device',
  `auth_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=寰呮巿鏉?AUTHORIZED=宸叉巿鏉?REVOKED=宸叉挙閿?EXPIRED=宸茶繃鏈',
  `auth_token` varchar(128) DEFAULT NULL COMMENT '妯℃嫙鎺堟潈鍑?瘉锛堣劚鏁?鍔犲瘑瀛樺偍锛屼笉钀芥槑鏂囨晱鎰熶俊鎭?級',
  `scope_json` text COMMENT '鎺堟潈鑼冨洿锛圝SON锛',
  `authorized_at` datetime DEFAULT NULL COMMENT '鎺堟潈鏃堕棿',
  `expiry_at` datetime DEFAULT NULL COMMENT '鎺堟潈鏈夋晥鏈熻嚦',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_source` (`user_id`,`source_type`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='澶氭暟鎹?簮鎺堟潈鐣欑棔琛';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `data_source_registry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_type` varchar(32) NOT NULL COMMENT 'credit/operator/social/device',
  `provider_name` varchar(64) NOT NULL COMMENT 'Provider 瀹炵幇绫诲悕鎴?Bean 鍚',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1=鍚?敤 0=鍋滅敤',
  `priority` int NOT NULL DEFAULT '100' COMMENT '浼樺厛绾э紙鍚屾簮澶?Provider 鏃剁敓鏁堬級',
  `circuit_status` varchar(16) NOT NULL DEFAULT 'CLOSED' COMMENT 'CLOSED/OPEN/HALF_OPEN锛堢啍鏂?姸鎬侊級',
  `config_json` text COMMENT '鎵╁睍閰嶇疆锛圝SON锛',
  `last_health_at` datetime DEFAULT NULL COMMENT '鏈?繎鍋ュ悍妫?煡鏃堕棿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source` (`source_type`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='鏁版嵁婧?Provider 娉ㄥ唽琛';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `loan_application` (
  `application_id` varchar(32) NOT NULL COMMENT '申请ID（IdUtil生成）',
  `user_id` varchar(32) NOT NULL COMMENT '申请人ID（关联sys_user.user_id）',
  `product_id` varchar(32) NOT NULL COMMENT '产品ID（关联loan_product.product_id）',
  `apply_amount` decimal(10,2) NOT NULL COMMENT '申请金额',
  `apply_term` int NOT NULL COMMENT '申请期限（月）',
  `credit_score` int NOT NULL COMMENT '申请人信用分',
  `application_status` varchar(20) NOT NULL COMMENT '申请状态（pending=待审核，approved=审核通过，rejected=拒绝，supplement=需补充材料，issued=已放款）',
  `apply_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `disbursement_card_id` bigint DEFAULT NULL COMMENT '放款银行卡ID（关联user_bank_card表的id）',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `scorecard_score` int DEFAULT NULL COMMENT '评分卡计算分数',
  `scorecard_decision` varchar(50) DEFAULT NULL COMMENT '评分卡决策结果(AUTO_APPROVE/MANUAL_REVIEW/REJECT)',
  `contract_path` varchar(255) DEFAULT NULL COMMENT '合同PDF路径',
  `contract_hash` varchar(64) DEFAULT NULL COMMENT '合同防篡改哈希值',
  `contract_status` tinyint(1) DEFAULT '0' COMMENT '合同状态: 0-未生成, 1-待签署, 2-已签署(待盖章), 3-已生效',
  `sign_time` datetime DEFAULT NULL COMMENT '用户签署时间',
  `stamp_time` datetime DEFAULT NULL COMMENT '平台盖章时间',
  `approved_amount` decimal(10,2) DEFAULT NULL COMMENT '批准金额（P-025 规则降额后的最终放款额；NULL=未降额，按 apply_amount）',
  `applied_rate` decimal(6,4) DEFAULT NULL COMMENT '实际执行年化利率（精细化定价；NULL=未定价，回退产品利率）',
  `pricing_tier` varchar(32) DEFAULT NULL COMMENT '命中的定价档位标签（A/B/C…）',
  PRIMARY KEY (`application_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_disbursement_card` (`disbursement_card_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='贷款申请表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `loan_contract` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contract_no` varchar(50) NOT NULL COMMENT '合同编号(如: LOAN-20260508-001)',
  `loan_application_id` bigint NOT NULL COMMENT '关联的贷款申请ID',
  `user_id` varchar(64) NOT NULL COMMENT '借款人ID',
  `borrower_name` varchar(50) NOT NULL COMMENT '借款人姓名',
  `id_card_number` varchar(64) NOT NULL COMMENT '借款人身份证',
  `loan_amount` decimal(12,2) NOT NULL COMMENT '贷款金额',
  `annual_rate` decimal(5,2) NOT NULL COMMENT '年化利率(%)',
  `term_months` int NOT NULL COMMENT '借款期限(月)',
  `repayment_method` varchar(20) NOT NULL COMMENT '还款方式(EQUAL_PRINCIPAL_AND_INTEREST等)',
  `file_path` varchar(255) NOT NULL COMMENT 'PDF文件存储路径',
  `hash_value` varchar(64) NOT NULL COMMENT '合同SHA-256哈希值(防篡改)',
  `sign_status` tinyint(1) DEFAULT '0' COMMENT '签署状态: 0-待签署, 1-已签署',
  `sign_time` datetime DEFAULT NULL COMMENT '签署时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `contract_no` (`contract_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_contract_no` (`contract_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='电子借款合同表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `loan_issue` (
  `issue_id` varchar(32) NOT NULL COMMENT '放款记录ID（UUID）',
  `application_id` varchar(32) NOT NULL COMMENT '关联申请ID',
  `user_id` varchar(32) NOT NULL COMMENT '关联用户ID',
  `issue_amount` decimal(10,2) NOT NULL COMMENT '放款金额',
  `issue_status` varchar(20) NOT NULL COMMENT '放款状态（success=成功，fail=失败）',
  `issue_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `disbursement_card_id` bigint DEFAULT NULL COMMENT '实际放款银行卡ID（关联user_bank_card表的id）',
  PRIMARY KEY (`issue_id`),
  KEY `idx_application_id` (`application_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_issue_disbursement_card` (`disbursement_card_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='放款记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `loan_pricing_tier` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` varchar(32) DEFAULT NULL COMMENT '产品ID；NULL=全局档位',
  `min_score` int NOT NULL COMMENT '评分下限（含）',
  `max_score` int NOT NULL COMMENT '评分上限（含）',
  `annual_rate` decimal(6,4) NOT NULL COMMENT '年化利率（0.0960 = 9.60%）',
  `tier_label` varchar(32) DEFAULT NULL COMMENT '档位标签（A/B/C…）',
  `remark` varchar(255) DEFAULT NULL COMMENT '说明',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_score` (`min_score`,`max_score`),
  KEY `idx_product` (`product_id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='精细化定价档位（按评分区间定利率）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `loan_product` (
  `product_id` varchar(32) NOT NULL COMMENT '产品ID（UUID）',
  `product_name` varchar(64) NOT NULL COMMENT '产品名称（如：个人信用贷）',
  `min_amount` decimal(10,2) NOT NULL COMMENT '最小申请金额',
  `max_amount` decimal(10,2) NOT NULL COMMENT '最大申请金额',
  `min_term` int NOT NULL COMMENT '最小期限（月）',
  `max_term` int NOT NULL COMMENT '最大期限（月）',
  `interest_rate` decimal(5,2) NOT NULL COMMENT '年利率（如：8.5=8.5%）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-下架，1-上架',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `auto_pass_score` int DEFAULT NULL COMMENT '自动通过分数线',
  `manual_review_score` int DEFAULT NULL COMMENT '人工审核分数线',
  `target_bad_rate` decimal(5,4) DEFAULT NULL COMMENT '鐩?爣鍧忚处鐜囷紙濡?0.0300=3%锛夛紝闃堝?鏍囧畾杈撳叆',
  `target_pass_rate` decimal(5,4) DEFAULT NULL COMMENT '鏈?綆鐩?爣閫氳繃鐜囷紙绾︽潫涓嬬晫锛屽彲绌猴級',
  `threshold_source` varchar(16) DEFAULT NULL COMMENT '闃堝?鏉ユ簮锛歁ANUAL/CALIBRATED/DEFAULT',
  `threshold_updated_at` datetime DEFAULT NULL COMMENT '闃堝?鏈?繎鍙樻洿鏃堕棿',
  PRIMARY KEY (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='贷款产品表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mock_bank_card_pool` (
  `id` int NOT NULL AUTO_INCREMENT,
  `card_number` varchar(19) NOT NULL,
  `bank_code` varchar(20) NOT NULL,
  `bank_name` varchar(50) NOT NULL,
  `card_type` varchar(10) DEFAULT 'DEBIT',
  `cardholder_name` varchar(50) NOT NULL,
  `id_card` varchar(18) NOT NULL,
  `phone` varchar(11) NOT NULL,
  `balance` decimal(12,2) DEFAULT '100000.00',
  `status` varchar(20) DEFAULT 'ACTIVE',
  `daily_limit` decimal(12,2) DEFAULT '50000.00',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `card_number` (`card_number`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mock_financial_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `match_key` varchar(64) NOT NULL COMMENT '匹配键：手机号（唯一）',
  `real_name` varchar(50) DEFAULT NULL COMMENT '姓名',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `monthly_income` decimal(12,2) DEFAULT NULL COMMENT '月收入（流水推算）',
  `income_source` varchar(32) DEFAULT NULL COMMENT '收入来源 SALARY/BUSINESS/INVESTMENT/FREELANCE',
  `bank_flow_months` int DEFAULT NULL COMMENT '可核实流水月数',
  `credit_card_limit` decimal(12,2) DEFAULT NULL COMMENT '信用卡总额度',
  `credit_card_used` decimal(12,2) DEFAULT NULL COMMENT '信用卡已用额度',
  `existing_loan_count` int DEFAULT NULL COMMENT '在贷笔数',
  `monthly_debt_payment` decimal(12,2) DEFAULT NULL COMMENT '月还款额合计',
  `overdue_count_2y` int DEFAULT NULL COMMENT '近2年逾期次数',
  `query_count_3m` int DEFAULT NULL COMMENT '近3月征信查询次数',
  `has_house` tinyint(1) DEFAULT NULL COMMENT '是否有房',
  `has_car` tinyint(1) DEFAULT NULL COMMENT '是否有车',
  `deposit_balance` decimal(14,2) DEFAULT NULL COMMENT '存款余额',
  `social_contrib_months` int DEFAULT NULL COMMENT '社保连续缴纳月数',
  `social_base` decimal(12,2) DEFAULT NULL COMMENT '社保/公积金缴费基数',
  `operator_months` int DEFAULT NULL COMMENT '手机号实名在网月数',
  `company_name` varchar(64) DEFAULT NULL COMMENT '公司名称',
  `position` varchar(32) DEFAULT NULL COMMENT '职位',
  `industry_type` varchar(32) DEFAULT NULL COMMENT '行业',
  `work_years` int DEFAULT NULL COMMENT '工作年限',
  `employment_type` varchar(20) DEFAULT NULL COMMENT '就业类型',
  `data_source` varchar(32) NOT NULL DEFAULT 'MOCK_BANK' COMMENT 'MOCK_BANK 预置 / AUTO_GEN 自动生成',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_match_key` (`match_key`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模拟金融信息（银行/征信侧经济画像）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `monthly_repayment` (
  `repayment_id` varchar(32) NOT NULL COMMENT '还款明细ID（UUID）',
  `plan_id` varchar(32) NOT NULL COMMENT '关联还款计划ID',
  `term` int NOT NULL COMMENT '期数（第N期）',
  `principal` decimal(10,2) NOT NULL COMMENT '当期本金',
  `interest` decimal(10,2) NOT NULL COMMENT '当期利息',
  `repayment_amount` decimal(10,2) NOT NULL COMMENT '当期还款金额',
  `due_date` date NOT NULL COMMENT '到期还款日',
  `repayment_status` varchar(20) NOT NULL DEFAULT 'unpaid' COMMENT '还款状态（unpaid=未还款，paid=已还款，overdue=逾期）',
  `repayment_time` datetime DEFAULT NULL COMMENT '实际还款时间',
  PRIMARY KEY (`repayment_id`),
  KEY `idx_plan_id` (`plan_id`),
  KEY `idx_user_id` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每月还款明细表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL,
  `title` varchar(128) NOT NULL,
  `content` text,
  `type` varchar(32) DEFAULT 'system',
  `related_id` varchar(64) DEFAULT NULL,
  `is_read` tinyint(1) DEFAULT '0',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`create_time` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_transaction` (
  `transaction_id` varchar(64) NOT NULL,
  `order_no` varchar(64) NOT NULL,
  `user_id` varchar(64) NOT NULL,
  `pool_card_id` int DEFAULT NULL,
  `amount` decimal(10,2) NOT NULL,
  `type` varchar(20) NOT NULL,
  `direction` varchar(10) NOT NULL,
  `status` varchar(20) DEFAULT 'PENDING',
  `error_code` varchar(20) DEFAULT NULL,
  `error_message` text,
  `balance_before` decimal(12,2) DEFAULT NULL,
  `balance_after` decimal(12,2) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `complete_time` datetime DEFAULT NULL,
  PRIMARY KEY (`transaction_id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_threshold_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` varchar(32) NOT NULL COMMENT '浜у搧ID',
  `auto_pass_score` int NOT NULL COMMENT '鑷?姩閫氳繃绾',
  `manual_review_score` int NOT NULL COMMENT '浜哄伐瀹℃牳绾',
  `target_bad_rate` decimal(5,4) DEFAULT NULL COMMENT '鏍囧畾鏃剁殑鐩?爣鍧忚处鐜',
  `threshold_source` varchar(16) DEFAULT NULL COMMENT 'MANUAL/CALIBRATED/DEFAULT',
  `change_reason` varchar(255) DEFAULT NULL COMMENT '鍙樻洿鍘熷洜',
  `changed_by` varchar(64) DEFAULT NULL COMMENT '鎿嶄綔浜',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_product_time` (`product_id`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='浜у搧闃堝?鍙樻洿鍘嗗彶';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_threshold_suggestion` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` varchar(32) NOT NULL COMMENT '浜у搧ID',
  `suggested_auto_pass` int NOT NULL COMMENT '寤鸿?鑷?姩閫氳繃绾',
  `suggested_manual_review` int NOT NULL COMMENT '寤鸿?浜哄伐瀹℃牳绾',
  `target_bad_rate` decimal(5,4) DEFAULT NULL COMMENT '鏍囧畾鏃剁殑鐩?爣鍧忚处鐜',
  `expected_bad_rate` decimal(5,4) DEFAULT NULL COMMENT '瀹炴祴锛堟爣瀹氶泦锛夊潖璐︾巼',
  `expected_pass_rate` decimal(5,4) DEFAULT NULL COMMENT '瀹炴祴锛堟爣瀹氶泦锛夐?杩囩巼',
  `sample_size` int DEFAULT NULL COMMENT '鏍囧畾鏍锋湰閲',
  `calibrated_at` datetime DEFAULT NULL COMMENT '鏍囧畾鏃堕棿',
  `note` varchar(255) DEFAULT NULL COMMENT '璇存槑锛堝?"浜у搧鍒嗗眰涓烘ā鎷?锛',
  `adopted` tinyint NOT NULL DEFAULT '0' COMMENT '0=鏈?噰绾?1=宸查噰绾',
  `adopted_at` datetime DEFAULT NULL COMMENT '閲囩撼鏃堕棿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_product_time` (`product_id`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='浜у搧闃堝?鏍囧畾寤鸿?琛';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `repayment_plan` (
  `plan_id` varchar(32) NOT NULL COMMENT '还款计划ID（UUID）',
  `application_id` varchar(32) NOT NULL COMMENT '关联申请ID',
  `user_id` varchar(32) NOT NULL COMMENT '关联用户ID',
  `total_amount` decimal(10,2) NOT NULL COMMENT '总还款金额（本金+利息）',
  `total_principal` decimal(10,2) NOT NULL COMMENT '总本金',
  `total_interest` decimal(10,2) NOT NULL COMMENT '总利息',
  `repayment_type` varchar(40) NOT NULL COMMENT '还款方式（equal_principal_interest=等额本息，equal_principal=等额本金）',
  `term_count` int NOT NULL COMMENT '总期数（月）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`plan_id`),
  KEY `idx_application_id` (`application_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='还款计划表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `risk_decision_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `application_id` varchar(64) NOT NULL COMMENT '申请ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `scorecard_score` int DEFAULT NULL COMMENT '评分卡分数',
  `scorecard_decision` varchar(50) DEFAULT NULL COMMENT '评分卡决策',
  `decision_reason` text COMMENT '决策原因',
  `feature_details` text COMMENT '特征详情JSON',
  `validation_errors` text COMMENT '验证错误信息',
  `consistency_warnings` text COMMENT '一致性警告',
  `decision_time` datetime NOT NULL COMMENT '决策时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `model_version` varchar(64) DEFAULT NULL COMMENT '璇勫垎妯″瀷鐗堟湰锛圵4 澶氭ā鍨嬪寲锛',
  `feature_snapshot` text COMMENT '璇勫垎鎵?敤鐗瑰緛蹇?収锛圝SON锛',
  `product_id` varchar(32) DEFAULT NULL COMMENT '鏈??鍐崇瓥浣跨敤鐨勪骇鍝',
  `applied_auto_pass` int DEFAULT NULL COMMENT '鏈??鐢熸晥鐨勮嚜鍔ㄩ?杩囩嚎',
  `applied_manual_review` int DEFAULT NULL COMMENT '鏈??鐢熸晥鐨勪汉宸ュ?鏍哥嚎',
  `threshold_source` varchar(16) DEFAULT NULL COMMENT '闃堝?鏉ユ簮锛歅RODUCT/DEFAULT',
  PRIMARY KEY (`id`),
  KEY `idx_application_id` (`application_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_decision_time` (`decision_time`)
) ENGINE=InnoDB AUTO_INCREMENT=91 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风控决策日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `risk_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_code` varchar(32) DEFAULT NULL COMMENT '瑙勫垯缂栧彿锛屽? L1-01',
  `name` varchar(64) NOT NULL COMMENT '瑙勫垯鍚嶇О',
  `category` varchar(32) NOT NULL COMMENT '鍒嗙被锛堝墠绔?垎绫绘爲锛夛細鍙嶆?璇?淇＄敤椋庨櫓/瑙傚療',
  `layer` varchar(8) NOT NULL DEFAULT 'L3' COMMENT '灞傜骇锛歀1=鍓嶇疆鎷︽埅 L3=鍚庣疆淇??',
  `condition_text` varchar(512) NOT NULL COMMENT '瑙﹀彂鏉′欢锛堝彈闄愯〃杈惧紡锛歠ield op value [AND|OR ...]锛',
  `action` varchar(24) NOT NULL COMMENT '鍔ㄤ綔锛歊EJECT/MANUAL/DOWNGRADE/FLAG锛堝吋瀹逛腑鏂囷級',
  `action_param` varchar(64) DEFAULT NULL COMMENT '鍔ㄤ綔鍙傛暟锛堝?闄嶉?姣斾緥锛',
  `priority` int NOT NULL DEFAULT '100' COMMENT '鏁板?瓒婂皬瓒婂厛鎵ц?',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '1=鍚?敤 0=鍋滅敤',
  `shadow_mode` tinyint NOT NULL DEFAULT '1' COMMENT '1=褰卞瓙锛堝彧璁板綍涓嶇敓鏁堬級',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`),
  KEY `idx_layer_enabled` (`layer`,`enabled`),
  KEY `idx_priority` (`priority`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='椋庨櫓瑙勫垯琛';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `risk_rule_hit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `application_id` varchar(64) DEFAULT NULL COMMENT '鐢宠?ID锛堝彲涓虹┖锛岀嫭绔嬪喅绛栧叆鍙ｄ篃鍙?兘浼狅級',
  `user_id` varchar(64) DEFAULT NULL COMMENT '鐢ㄦ埛ID',
  `rule_id` bigint DEFAULT NULL COMMENT '瑙勫垯ID',
  `rule_code` varchar(32) DEFAULT NULL COMMENT '瑙勫垯缂栧彿锛屽? L1-01',
  `rule_name` varchar(64) DEFAULT NULL COMMENT '瑙勫垯鍚嶇О',
  `layer` varchar(8) DEFAULT NULL COMMENT 'L1 / L3',
  `action` varchar(24) DEFAULT NULL COMMENT '鍛戒腑鍔ㄤ綔锛歊EJECT/MANUAL/DOWNGRADE/FLAG',
  `shadow` tinyint NOT NULL DEFAULT '0' COMMENT '1=褰卞瓙鍛戒腑锛堟湭鐢熸晥锛',
  `hit_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rule_time` (`rule_code`,`hit_time`),
  KEY `idx_app` (`application_id`)
) ENGINE=InnoDB AUTO_INCREMENT=69 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='瑙勫垯鍛戒腑娴佹按';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `shadow_score_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `application_id` varchar(64) DEFAULT NULL COMMENT '鐢宠?ID锛堝彲绌猴級',
  `user_id` varchar(64) DEFAULT NULL COMMENT '鐢ㄦ埛ID锛堝彲绌猴級',
  `primary_model_key` varchar(64) NOT NULL COMMENT '涓绘ā鍨嬫敞鍐岄敭锛堢嚎涓婄敓鏁堬級',
  `primary_score` int DEFAULT NULL COMMENT '涓绘ā鍨嬪垎鏁',
  `primary_decision` varchar(32) DEFAULT NULL COMMENT '涓绘ā鍨嬪喅绛',
  `features_json` text COMMENT '璇锋眰鐗瑰緛蹇?収锛圝SON锛',
  `shadow_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=寰呮墦鍒?DONE=宸插洖濉?FAILED=澶辫触',
  `shadow_model_key` varchar(64) DEFAULT NULL COMMENT '褰卞瓙妯″瀷锛堝? v4-fusion / xgboost锛',
  `shadow_score` int DEFAULT NULL COMMENT '褰卞瓙鍒嗘暟',
  `shadow_decision` varchar(32) DEFAULT NULL COMMENT '褰卞瓙鍐崇瓥',
  `shadow_note` varchar(255) DEFAULT NULL COMMENT '澶囨敞锛堝?鍒嗘?璇存槑/鍥炲～鏂瑰紡锛',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `scored_at` datetime DEFAULT NULL COMMENT '褰卞瓙鎵撳垎鏃堕棿',
  PRIMARY KEY (`id`),
  KEY `idx_shadow_status` (`shadow_status`),
  KEY `idx_shadow_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='褰卞瓙璇勫垎璁板綍琛?紙鏃佽矾瀵圭収锛';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `user_id` varchar(32) NOT NULL COMMENT '用户ID（UUID）',
  `phone` varchar(11) NOT NULL COMMENT '手机号（登录账号）',
  `password` varchar(64) NOT NULL COMMENT '加密密码（BCrypt）',
  `real_name` varchar(32) NOT NULL COMMENT '真实姓名',
  `role` varchar(10) NOT NULL COMMENT '角色：user-普通用户，admin-审批者',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用，1-正常',
  `account_balance` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '用户账户余额（元）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `kyc_status` tinyint(1) DEFAULT '0' COMMENT 'KYC状态: 0-未认证, 1-已认证',
  `user_real_name` varchar(50) DEFAULT NULL COMMENT '真实姓名',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_agreement` (
  `agreement_id` bigint NOT NULL AUTO_INCREMENT COMMENT '协议ID',
  `agreement_type` varchar(50) NOT NULL COMMENT '协议类型：PRIVACY_POLICY-隐私政策, USER_SERVICE-用户服务协议, CREDIT_AUTH-征信授权书, E_SIGNATURE-电子签名协议',
  `version` varchar(20) NOT NULL COMMENT '版本号：v1.0, v1.1, v2.0',
  `title` varchar(200) NOT NULL COMMENT '协议标题',
  `content` longtext NOT NULL COMMENT '协议内容（HTML格式）',
  `summary` varchar(500) DEFAULT NULL COMMENT '协议摘要（用于列表展示）',
  `is_current` tinyint(1) DEFAULT '0' COMMENT '是否为当前生效版本：0-否, 1-是',
  `effective_date` date NOT NULL COMMENT '生效日期',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`agreement_id`),
  KEY `idx_type_version` (`agreement_type`,`version`),
  KEY `idx_is_current` (`is_current`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户协议模板表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_agreement_sign` (
  `sign_id` bigint NOT NULL AUTO_INCREMENT COMMENT '签署记录ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID（关联sys_user.user_id）',
  `agreement_id` bigint NOT NULL COMMENT '协议ID（关联user_agreement.agreement_id）',
  `agreement_type` varchar(50) NOT NULL COMMENT '协议类型（冗余字段，方便查询）',
  `agreement_version` varchar(20) NOT NULL COMMENT '协议版本（冗余字段）',
  `signature_image_url` varchar(500) DEFAULT NULL COMMENT '签名图片URL（可选，某些协议不需要签名）',
  `sign_time` datetime NOT NULL COMMENT '签署时间',
  `ip_address` varchar(50) DEFAULT NULL COMMENT '签署时IP地址',
  `device_info` varchar(200) DEFAULT NULL COMMENT '设备信息（Android/iOS/Web）',
  `is_valid` tinyint(1) DEFAULT '1' COMMENT '是否有效：0-已撤销, 1-有效',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`sign_id`),
  UNIQUE KEY `uk_user_agreement` (`user_id`,`agreement_id`) COMMENT '同一用户对同一协议只能签署一次',
  KEY `idx_user_id` (`user_id`),
  KEY `idx_agreement_id` (`agreement_id`),
  KEY `idx_user_type` (`user_id`,`agreement_type`)
) ENGINE=InnoDB AUTO_INCREMENT=37 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户协议签署记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_bank_card` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL,
  `pool_card_id` int NOT NULL,
  `card_number` varchar(19) NOT NULL,
  `alias_name` varchar(50) DEFAULT NULL,
  `is_default` tinyint(1) DEFAULT '0',
  `bind_status` varchar(20) DEFAULT 'ACTIVE',
  `bind_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_credit_profile` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL,
  `name` varchar(64) DEFAULT NULL,
  `id_card` varchar(32) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `email` varchar(128) DEFAULT NULL,
  `birth_date` date DEFAULT NULL,
  `gender` varchar(4) DEFAULT NULL,
  `education` varchar(32) DEFAULT NULL,
  `occupation` varchar(64) DEFAULT NULL,
  `family_info` json DEFAULT NULL,
  `financial_info` json DEFAULT NULL,
  `employment_info` json DEFAULT NULL,
  `age` int DEFAULT NULL,
  `debt_ratio` decimal(10,4) DEFAULT NULL,
  `monthly_income` decimal(12,2) DEFAULT NULL,
  `credit_lines` int DEFAULT NULL,
  `dependents` int DEFAULT NULL,
  `revolving_util` decimal(10,4) DEFAULT NULL,
  `credit_score` int DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  `home_address` varchar(255) DEFAULT NULL COMMENT '家庭/居住地址（阶段C2，可选填，仅用于第一层交叉验证）',
  `company_address` varchar(255) DEFAULT NULL COMMENT '单位地址（阶段C2，可选填，仅用于第一层交叉验证）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_credit_score` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(32) NOT NULL COMMENT '用户ID',
  `credit_score` int NOT NULL DEFAULT '650' COMMENT '信用分（300-950）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_id` (`user_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COMMENT='用户信用分表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_face_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `face_image_url` varchar(500) DEFAULT NULL COMMENT '人脸抓拍图URL',
  `similarity_score` decimal(5,2) DEFAULT NULL COMMENT '相似度得分',
  `is_live` tinyint(1) DEFAULT '1' COMMENT '是否活体: 0-否, 1-是',
  `result` varchar(20) DEFAULT NULL COMMENT '识别结果: PASS/FAIL',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '识别时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_latest` (`user_id`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人脸识别记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_identity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `user_real_name` varchar(50) NOT NULL COMMENT '真实姓名',
  `id_card_number` varchar(64) NOT NULL COMMENT '身份证号(加密存储)',
  `gender` varchar(4) DEFAULT NULL COMMENT '性别（男/女）',
  `birthday` date DEFAULT NULL COMMENT '出生日期',
  `id_card_front_url` varchar(500) DEFAULT NULL COMMENT '身份证正面照URL',
  `face_capture_url` varchar(500) DEFAULT NULL COMMENT '人脸抓拍图URL',
  `verify_status` tinyint(1) DEFAULT '0' COMMENT '认证状态: 0-待审核, 1-已通过, 2-已拒绝',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户实名认证表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_multi_source_data` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL COMMENT '鐢ㄦ埛ID',
  `source_type` varchar(32) NOT NULL COMMENT 'credit/operator/social/device',
  `data_json` text COMMENT '鏍囧噯鍖栨暟鎹?浇鑽凤紙JSON锛夛紝瀛楁?鍙ｅ緞瑙併?澶氭簮鏁版嵁.md銆',
  `data_version` varchar(16) DEFAULT NULL COMMENT '鏁版嵁鍙ｅ緞鐗堟湰锛堝?榻愭ā鍨?鐗瑰緛鐗堟湰锛',
  `fetch_status` varchar(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/STALE=鏁版嵁杩囨湡',
  `fetched_at` datetime DEFAULT NULL COMMENT '鏈?繎鎷夊彇鏃堕棿',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_source` (`user_id`,`source_type`)
) ENGINE=InnoDB AUTO_INCREMENT=32 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='澶氭簮鏍囧噯鍖栨暟鎹?〃';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_signature` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID（关联sys_user.user_id）',
  `signature_image_url` varchar(500) NOT NULL COMMENT '签名图片URL',
  `signature_hash` varchar(64) DEFAULT NULL COMMENT '签名图片哈希值（防篡改验证）',
  `is_default` tinyint(1) DEFAULT '1' COMMENT '是否为默认签名：0-否, 1-是',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=32 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户电子签名表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

