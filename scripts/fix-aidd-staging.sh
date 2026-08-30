#!/bin/bash
# AIDD 9101 一键修复脚本（CVM 假死后重启首跑）
# 根因：private profile 缺 PG/Redis + -Xmx1536m 在 4GB 机器上 crash-loop → 系统假死
# 方案：test profile (SQLite) + -Xmx384m + 禁止快速 crash-loop
# 用法：ssh ubuntu@118.195.197.15 'bash -s' < fix-aidd-staging.sh

set -e

echo "═══ 1. 先停 AIDD crash-loop（防止再次拖垮系统）═══"
sudo systemctl stop aidd-backend-staging 2>/dev/null || true

echo "═══ 2. 重写 AIDD service（test profile + 384m 内存帽）═══"
sudo tee /etc/systemd/system/aidd-backend-staging.service > /dev/null <<'SVC'
[Unit]
Description=AIDD Backend (Staging)
After=network.target

[Service]
Type=simple
User=ubuntu
Group=ubuntu
WorkingDirectory=/opt/lingyao/aidd-staging
Environment=LINGYAO_JWT_SECRET=staging_jwt_secret_change_in_real_deployment_2026
Environment=SERVER_PORT=9101
Environment=SPRING_PROFILES_ACTIVE=test
Environment=COPILOT_DB_PATH=/opt/lingyao/aidd-staging/data/copilot-staging.db
ExecStart=/usr/bin/java -Xms128m -Xmx384m -jar /opt/lingyao/aidd-staging/ai-project-copilot-backend.jar
Restart=on-failure
RestartSec=15
StartLimitIntervalSec=300
StartLimitBurst=5
StandardOutput=append:/opt/lingyao/aidd-staging/logs/backend.out
StandardError=append:/opt/lingyao/aidd-staging/logs/backend.err

[Install]
WantedBy=multi-user.target
SVC

echo "═══ 3. 目录 + jar 完整性确认 ═══"
sudo mkdir -p /opt/lingyao/aidd-staging/data /opt/lingyao/aidd-staging/logs
sudo chown -R ubuntu:ubuntu /opt/lingyao/aidd-staging
ls -la /opt/lingyao/aidd-staging/ai-project-copilot-backend.jar
md5sum /opt/lingyao/aidd-staging/ai-project-copilot-backend.jar || echo "jar 丢失，需重传"
# 期望 md5: 93df4c8bc5d4b6866df2bb00bd662709, 大小: 110401222
# 若 /tmp/aidd-jar-upload.jar 还在可直接 cp

echo "═══ 4. 启动 AIDD ═══"
sudo systemctl daemon-reload
sudo systemctl restart aidd-backend-staging
echo "等待 50s Spring Boot + SQLite 建表..."
sleep 50
systemctl is-active aidd-backend-staging
sudo ss -ltn | grep 9101 || echo "9101 未监听"
tail -5 /opt/lingyao/aidd-staging/logs/backend.out 2>/dev/null

echo "═══ 5. 全服务总览 ═══"
for svc in lingyao-backend lingyao-backend-staging dinfo-backend-staging mingshu-backend-staging geom-backend-staging mpd-backend-staging aidd-backend-staging; do
  echo "$svc: $(systemctl is-active $svc)"
done
sudo ss -ltn | grep -E ":(80|9091|9092|9093|9095|9097|9099|9101)\b" | awk '{print $4}' | sort
free -m | head -2
