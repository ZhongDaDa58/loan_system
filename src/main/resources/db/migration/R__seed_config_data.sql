
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

/*!40000 ALTER TABLE `risk_rule` DISABLE KEYS */;
REPLACE INTO `risk_rule` (`id`, `rule_code`, `name`, `category`, `layer`, `condition_text`, `action`, `action_param`, `priority`, `enabled`, `shadow_mode`, `remark`, `create_time`, `update_time`) VALUES (1,'L1-01','模拟器/非真机','反欺诈','L1','device_emulator_flag = 1','REJECT',NULL,10,1,1,'命中即拒绝，不进入评分','2026-10-09 18:53:29','2026-10-13 18:13:27'),(2,'L1-02','设备黑名单即时命中','反欺诈','L1','device_blacklist_hit_now = 1','REJECT',NULL,11,1,1,'已知欺诈设备','2026-10-09 18:53:29','2026-10-13 18:13:27'),(3,'L1-03','设备集群欺诈','反欺诈','L1','device_related_app_cnt_30d >= 5 AND device_multi_account_now = 1','REJECT',NULL,12,1,1,'一机多号聚集','2026-10-09 18:53:29','2026-10-13 18:13:27'),(4,'L1-04','IP代理+属地冲突','反欺诈','L1','ip_proxy_risk >= 0.8 AND ip_geo_mismatch = 1','REJECT',NULL,13,1,1,'网络环境伪造','2026-10-09 18:53:29','2026-10-13 18:13:27'),(5,'L1-05','位置严重矛盾','反欺诈','L1','gps_deviation_km_longterm >= 200 AND ip_geo_mismatch = 1','MANUAL',NULL,14,1,1,'资料与位置严重不符','2026-10-09 18:53:29','2026-10-13 18:13:27'),(6,'L1-06','多头借贷激增','信用风险','L1','credit_query_cnt_3m >= 15','MANUAL',NULL,15,1,1,'短时间密集查询','2026-10-09 18:53:29','2026-10-13 18:13:27'),(7,'L1-07','行为异常偏高','反欺诈','L1','behavior_anomaly_score >= 0.8','MANUAL',NULL,16,1,1,'行为埋点异常','2026-10-09 18:53:29','2026-10-13 18:13:27'),(8,'L3-01','达标但设备聚集','信用风险','L3','score >= 643 AND device_related_app_cnt_30d >= 3','MANUAL',NULL,30,0,1,'分数好但设备可疑','2026-10-09 18:53:29','2026-10-12 15:59:51'),(9,'L3-02','达标但多头借贷','信用风险','L3','score >= 643 AND credit_query_cnt_3m >= 10','DOWNGRADE',NULL,31,0,1,'控制敞口','2026-10-09 18:53:29','2026-10-12 15:59:51'),(10,'L3-03','达标但负债过高','信用风险','L3','score >= 643 AND debt_to_income >= 0.8','DOWNGRADE',NULL,32,0,1,'还款压力大','2026-10-09 18:53:29','2026-10-12 15:59:51'),(11,'L3-04','新号风险','信用风险','L3','score >= 643 AND operator_realname_months < 3','MANUAL',NULL,33,0,1,'实名/在网时间极短','2026-10-09 18:53:29','2026-10-12 15:59:51'),(12,'F-01','新设备观察','观察','L3','device_first_seen_months < 3','FLAG',NULL,90,0,1,'新设备观察期，仅记录','2026-10-09 18:53:29','2026-10-12 15:59:51'),(19,'L1-08','活体未通过','反欺诈','L1','liveness_passed = 0','MANUAL',NULL,17,1,1,'§5① 活体相似度未达阈值（阶段 C1 接入 user_face_log 后生效）','2026-10-13 12:28:04','2026-10-13 18:13:27'),(20,'L1-09','活体未做（无记录）','反欺诈','L1','liveness_available = 0','MANUAL',NULL,18,1,1,'§5① 实名阶段未完成活体检测','2026-10-13 12:28:04','2026-10-13 18:13:27'),(21,'L1-10','地址与定位严重偏离','反欺诈','L1','gps_addr_distance_km >= 50','MANUAL',NULL,19,1,1,'§5② 用户填写地址与定位距离超阈值（阶段 C2~C4 接入）','2026-10-13 12:28:04','2026-10-13 18:13:27'),(22,'L1-11','定位数据缺失（无法判定）','反欺诈','L1','geo_available = 0','MANUAL',NULL,20,1,1,'§5② 无地址或无定位数据，无法做交叉验证 → 不放行','2026-10-13 12:28:04','2026-10-13 18:13:27'),(23,'L1-12','IP属地与地址不符','反欺诈','L1','geo_available = 1 AND ip_geo_consistent = 0','MANUAL',NULL,21,1,1,'§5② IP归属地与填写地址所在城市不一致','2026-10-13 12:28:04','2026-10-13 18:13:27'),(24,'L1-13','设备指纹跨账号聚集','反欺诈','L1','device_blacklist_hit_cnt >= 2','REJECT',NULL,22,1,1,'§5① 同一设备指纹关联多个账号（阶段 C5 接入跨账号检索）','2026-10-13 12:28:04','2026-10-13 18:13:27'),(25,'L1-14','信息可信度不足','反欺诈','L1','trust_level < 2','MANUAL',NULL,23,1,1,'§5④ 放行门控：综合可信度低于 MEDIUM 时转人工（trust_level 默认 0，缺省不放行）','2026-10-13 12:28:04','2026-10-13 18:13:27');
/*!40000 ALTER TABLE `risk_rule` ENABLE KEYS */;

/*!40000 ALTER TABLE `loan_product` DISABLE KEYS */;
REPLACE INTO `loan_product` (`product_id`, `product_name`, `min_amount`, `max_amount`, `min_term`, `max_term`, `interest_rate`, `status`, `create_time`, `update_time`, `auto_pass_score`, `manual_review_score`, `target_bad_rate`, `target_pass_rate`, `threshold_source`, `threshold_updated_at`) VALUES ('product_001','个人信用贷',1000.00,200000.00,8,36,8.50,1,'2025-12-05 19:49:35','2026-10-13 11:14:46',690,648,0.0300,NULL,'CALIBRATED','2026-10-12 19:14:00'),('product_0d80dcfe84e5','商品业务贷',100000.00,500000.00,12,60,20.00,1,'2026-06-09 21:44:57','2026-10-20 17:55:40',705,660,0.0600,NULL,'CALIBRATED','2026-10-12 19:14:00'),('product_14c58091fec1','房贷',10000.00,100000.00,1,20,12.00,0,'2026-07-01 00:10:35','2026-10-09 18:50:56',684,630,0.0150,NULL,'CALIBRATED','2026-10-09 18:50:57');
/*!40000 ALTER TABLE `loan_product` ENABLE KEYS */;

/*!40000 ALTER TABLE `loan_pricing_tier` DISABLE KEYS */;
REPLACE INTO `loan_pricing_tier` (`id`, `product_id`, `min_score`, `max_score`, `annual_rate`, `tier_label`, `remark`, `enabled`, `create_time`, `update_time`) VALUES (1,NULL,720,9999,0.0960,'A','低风险档（v4 P90 以上）',1,'2026-10-13 18:13:27','2026-10-13 18:13:27'),(2,NULL,697,719,0.1080,'B','中低风险档（v4 P75~P90）',1,'2026-10-13 18:13:27','2026-10-13 18:13:27'),(3,NULL,679,696,0.1200,'C','中等风险档（v4 P50~P75）',1,'2026-10-13 18:13:27','2026-10-13 18:13:27'),(4,NULL,652,678,0.1320,'D','中高风险档（v4 P25~P50）',1,'2026-10-13 18:13:27','2026-10-13 18:13:27'),(5,NULL,0,651,0.1560,'E','高风险档（v4 P25 以下）',1,'2026-10-13 18:13:27','2026-10-13 18:13:27');
/*!40000 ALTER TABLE `loan_pricing_tier` ENABLE KEYS */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

