# Loan （Loan System）

简要说明
-------
这是一个基于 Spring Boot 4 + Java 17 的贷款/风控系统后端服务样例。项目使用 MyBatis 操作数据库，支持生成 PDF（OpenPDF + Flying Saucer）、JWT 认证、Redis、Swagger（openapi）等常见组件。项目源码位于 `src/main/java`，资源文件位于 `src/main/resources`。

主要特性
-------
- Spring Boot 4 + Java 17
- MyBatis（XML Mapper） + MySQL
- Redis（Spring Data Redis）
- JWT（io.jsonwebtoken）
- OpenAPI/Swagger（springdoc）
- HTML -> PDF（Flying Saucer + OpenPDF）
- Thymeleaf 模板渲染
- Lombok
- Springdoc OpenAPI

技术栈
-------
- Java 17
- Spring Boot 4
- MyBatis
- MySQL
- Redis
- OpenPDF / Flying Saucer
- Thymeleaf
- Lombok
- Springdoc OpenAPI

快速开始（Windows / PowerShell）
-------
1. 构建（使用项目自带的 Maven wrapper）
```powershell
# 在项目根目录（含 mvnw.cmd）执行：
.\mvnw.cmd clean package -DskipTests
```

2. 运行（通过可执行 JAR）
```powershell
# 默认配置读取环境变量（例如 DB_PASSWORD）。示例：
$env:DB_PASSWORD = 'your_db_password'
$env:SPRING_PROFILES_ACTIVE = 'dev'   # 或 'prod'
java -jar .\target\loan-0.0.1-SNAPSHOT.jar
```

或者直接使用 Maven 运行（开发环境）：
```powershell
$env:DB_PASSWORD = 'your_db_password'
.\mvnw.cmd spring-boot:run
```

重要配置（参考 `src/main/resources/application.properties`）
-------
- `spring.profiles.active`：默认 `dev`，生产环境请使用环境变量或 JVM 参数覆盖。
- 数据库（默认）：
  - URL: `spring.datasource.url=jdbc:mysql://localhost:3306/loan_risk_control?...`
  - 用户名: `spring.datasource.username`
  - 密码: 从环境变量 `DB_PASSWORD` 读取，默认在 `application.properties` 中写了示例默认值（请在生产中覆盖）。
- JWT:
  - `jwt.secret`（默认值在 `application.properties` 中）：请在生产中替换为更强的密钥。
  - `jwt.expiration`（单位 ms）
- Swagger UI 路径: `/swagger-ui.html`（由 `springdoc` 提供）

示例：创建数据库（MySQL）
-------
请在 MySQL 中创建库并设置字符集（示例）：
```sql
CREATE DATABASE loan_risk_control CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```
然后在 `application.properties` 中或通过环境变量配置数据库连接信息。

主要路径与文件
-------
- 项目描述文件：`pom.xml`
- Spring 配置：`src/main/resources/application.properties`、`application-dev.properties`、`application-prod.properties`
- MyBatis mappers：`src/main/resources/mapper/*.xml`（运行时也在 `target/classes/mapper`）
- 模板与资源：
  - 合同模板：`src/main/resources/templates/contract_template.html`
  - 字体：`src/main/resources/fonts/simsun.ttc`
  - 印章：`src/main/resources/stamps/`（例如 `company_stamp.png`）
  - 静态签名图片：`src/main/resources/static/signatures/`
- 合同样例（PDF）：`contracts/` 目录下的 draft_*.pdf
- 帮助参考：`HELP.md`

启动与调试小贴士
-------
- Swagger（接口文档）地址：`http://{host}:{port}/swagger-ui.html`（默认端口 8080）
- 若端口被占用或需临时修改端口：在运行时传入参数 `--server.port=8081` 或在 `application.properties` 中修改 `server.port`。
- 开发时可通过 `spring-boot-devtools` 自动重启/热加载。
- 在生产环境务必更换 `jwt.secret` 和数据库密码，并关闭 `devtools`（已经在 pom 中设置为 runtime 可选）。

关于 PDF 生成
-------
项目集成了 OpenPDF 和 Flying Saucer，用于将 HTML（Thymeleaf 渲染）转换为 PDF。相关模板和资源文件（字体、印章、签名图片）位于 `src/main/resources/templates`、`fonts`、`stamps`、`static` 等目录。请确保运行时能访问到这些资源。

打包与部署
-------
- 本项目使用 Spring Boot 的可执行 Jar 打包：`.\mvnw.cmd clean package`
- 生成的 Jar：`target\loan-0.0.1-SNAPSHOT.jar`
- 部署时建议：
  - 使用系统级进程管理（systemd / Windows服务 / Docker 等）
  - 使用外部化配置（环境变量或配置中心）覆盖敏感信息
  - 对数据库与 Redis 使用专用账户与网络访问控制

安全注意事项
-------
- 请勿在版本控制或公开仓库中提交生产用的 `DB_PASSWORD`、`jwt.secret` 等敏感信息。
- 生产环境应启用 HTTPS / 反向代理并限制管理接口访问。
- 若使用 Swagger，在生产环境可考虑保护或禁用外网访问。

测试
-------
- 单元/集成测试依赖在 `pom.xml` 中（`spring-boot-starter-test`）。运行：
```powershell
.\mvnw.cmd test
```

常见问题
-------
- 如果无法连接 MySQL：确认 `DB_PASSWORD`、`spring.datasource.url` 中数据库名和主机/端口正确，数据库已创建并允许访问。
- 如果 PDF 的中文显示异常：确认使用的字体（`simsun.ttc`）已包含并在 Flying Saucer/OpenPDF 渲染时被正确注册。

贡献与联系
-------
欢迎提交 Issues 或 Pull Requests（假设在 Git 仓库中）。在提交之前请确保：
- 修复包含测试或已手动验证
- 不提交敏感信息



