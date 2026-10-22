# =============================================================================
# 应用镜像（阶段三 3.1 / 3.4）
# =============================================================================
# 构建前置：先在宿主机跑 `mvnw verify`（或 `ci.ps1`），产物在 target/*.jar
#
#   mvnw verify                        # 编译 + 测试 + 覆盖率门槛 + 打包
#   docker build -t loan-app:local .   # 用 target/*.jar 构建镜像
#
# ⚠️ 为什么不用多阶段构建（FROM maven + mvn package）：
#    实测容器内的 Maven 访问中央仓库会失败（SSL peer shut down incorrectly），
#    因为 Docker daemon 的代理只作用于 `docker pull`，**不作用于容器内的网络**。
#    而 CI 流程本来就是「先在宿主机 verify 出 jar，再 docker build」，
#    在容器里重复构建一遍既慢又容易挂 —— 因此改为单阶段，直接拷 jar。
#
#    若确实需要容器内构建（纯 Docker 流水线），参见 docker/maven-settings.xml。
# =============================================================================
FROM eclipse-temurin:17-jre-alpine

# 时区与字符编码：中文日志、PDF（Flying Saucer + simsun.ttc）都依赖它
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8

# 非 root 运行（alpine 用 addgroup/adduser）
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

# 只拷最终 jar（fat jar 已含全部依赖与 resources，包括 17.9MB 中文字体）
COPY target/*.jar app.jar

# 日志与合同目录（阶段一的 logback 支持 LOG_HOME 环境变量覆盖）
RUN mkdir -p /app/logs /app/contracts && chown -R app:app /app
USER app

EXPOSE 8080

# JVM 参数：
#   MaxRAMPercentage=75  —— 按容器内存上限算堆，比写死 -Xmx512m 更适应不同部署环境
#   UseG1GC              —— 现代默认，停顿可控
#   file.encoding=UTF-8  —— 避免容器内中文乱码
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+UseG1GC", \
    "-Duser.timezone=Asia/Shanghai", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "app.jar"]
