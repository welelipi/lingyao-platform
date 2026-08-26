# ════════════════════════════════════════════════════════════
# 凌瑶智数 · 后端 Docker 镜像
# ────────────────────────────────────────────────────────────
# 构建方式（在 backend/ 目录下）：
#   docker build -t lingyao/platform:1.0.0 .
#
# 启动方式：
#   docker run -d \
#     --name lingyao-platform \
#     -p 9091:9091 \
#     -v lingyao_data:/app/data \
#     -e LINGYAO_JWT_SECRET="$(openssl rand -base64 48)" \
#     lingyao/platform:1.0.0
#
# 或使用 docker-compose（推荐）：
#   docker-compose up -d
# ════════════════════════════════════════════════════════════

# ─── 阶段 1：构建 ──────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先拷贝 pom（缓存依赖层）
COPY pom.xml ./

# 拉依赖（加快后续构建）
RUN mvn -q dependency:go-offline -DskipTests 2>/dev/null || true

# 拷源码打包
COPY src src
RUN mvn -q -DskipTests package

# ─── 阶段 2：运行时镜像 ────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# 必备工具（curl 用于健康检查，tzdata 用于时区）
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl tzdata ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 时区（主人/客户都在中国）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 拷贝构建产物
COPY --from=builder /build/target/lingyao-platform.jar /app/lingyao-platform.jar

# 拷贝启动脚本 + 备份脚本
COPY docker/entrypoint.sh /app/entrypoint.sh
COPY docker/backup.sh     /app/backup.sh
RUN chmod +x /app/entrypoint.sh /app/backup.sh

# 数据与日志目录（容器卷挂载点）
RUN mkdir -p /app/data /app/logs /app/backup

# Spring Boot 端口（私有化 profile 绑定 0.0.0.0）
EXPOSE 9091

# JVM 选项（容器内存友好 + 启用容器感知）
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

# 默认私有化 profile
ENV SPRING_PROFILES_ACTIVE=private

# 健康检查（私有化下也走 /api/health）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:9091/api/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]