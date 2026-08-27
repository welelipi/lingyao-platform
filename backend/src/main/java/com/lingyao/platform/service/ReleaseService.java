package com.lingyao.platform.service;

import com.lingyao.platform.entity.ReleaseHistory;
import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.ReleaseHistoryRepository;
import com.lingyao.platform.repository.SysUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 发布服务 — V2.0.5 R-7
 *
 * 核心职责：
 * 1. 接收发布请求 → 写 ReleaseHistory (RUNNING)
 * 2. 异步调用 deploy 脚本（SSH 到 staging 机器 / 本地调用 prod 脚本）
 * 3. 捕获 stdout/stderr → 写 log
 * 4. 更新 ReleaseHistory (SUCCESS/FAILED)
 * 5. 触发飞书/钉钉 Webhook 推送
 *
 * 部署架构（V2.0.5）：
 * - 后端 jar 跑在 prod 机器（118.195.197.15:9091）
 * - "发布 staging" = SSH 到 staging 机器（同一台 ubuntu@localhost）跑 release-to-staging.sh
 * - "晋升生产" = 本地直接跑 deploy-prod.sh（无需 SSH）
 *
 * 前置条件（CVM 一次性配置）：
 *   1. 生成 release 专用 SSH key：
 *      sudo -u ubuntu ssh-keygen -t ed25519 -f /home/ubuntu/.ssh/release_staging_key -N ""
 *   2. 公钥加到 authorized_keys（同机器自调用）：
 *      sudo -u ubuntu bash -c "cat /home/ubuntu/.ssh/release_staging_key.pub >> /home/ubuntu/.ssh/authorized_keys"
 *   3. 验证：sudo -u ubuntu ssh -i /home/ubuntu/.ssh/release_staging_key ubuntu@localhost echo OK
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    /** 部署脚本最长等待 5 分钟（Spring Boot 启动 30s + 健康检查 + 缓冲） */
    private static final long DEPLOY_TIMEOUT_SEC = 300;

    /** log 字段最大保留字节数（避免超长撑爆数据库） */
    private static final int MAX_LOG_BYTES = 60_000;

    @Autowired private ReleaseHistoryRepository historyRepo;
    @Autowired private SysUserRepository userRepo;
    @Autowired private WebhookService webhookService;

    @Value("${lingyao.release.staging.ssh-host:127.0.0.1}")
    private String stagingSshHost;

    @Value("${lingyao.release.staging.ssh-port:22}")
    private int stagingSshPort;

    @Value("${lingyao.release.staging.ssh-user:ubuntu}")
    private String stagingSshUser;

    @Value("${lingyao.release.staging.ssh-key-path:/home/ubuntu/.ssh/release_staging_key}")
    private String stagingSshKeyPath;

    @Value("${lingyao.release.staging.deploy-script:/opt/lingyao/staging/deploy-staging.sh}")
    private String stagingDeployScript;

    @Value("${lingyao.release.prod.deploy-script:/opt/lingyao/deploy-prod.sh}")
    private String prodDeployScript;

    /**
     * 初始化 staging 部署记录（同步）— 立即返回 historyId，Controller 不阻塞
     */
    public Long initStagingDeploy(String jarPath, Long userId) {
        SysUser user = userRepo.findById(userId).orElse(null);
        String username = user != null ? user.getUsername() : "unknown";

        ReleaseHistory hist = new ReleaseHistory();
        hist.setEnv(ReleaseHistory.ReleaseEnv.STAGING);
        hist.setVersion(extractVersionFromJar(jarPath));
        hist.setJarFilename(extractFilename(jarPath));
        hist.setDeployedBy(username);
        hist.setDeployedById(userId);
        hist.setStatus(ReleaseHistory.ReleaseStatus.RUNNING);
        hist.setStartedAt(LocalDateTime.now());
        hist = historyRepo.save(hist);

        log.info("[R-7] STAGING deploy init by {}, historyId={}", username, hist.getId());
        return hist.getId();
    }

    /**
     * 异步执行 staging 部署（@Async — Controller 调用后立即返回，部署在后台线程跑）
     *
     * @param histId  initStagingDeploy 创建的 historyId
     * @param jarPath jar 路径（已经是 CVM 上路径，不需要 scp）
     */
    @Async
    public CompletableFuture<Void> executeStagingDeploy(Long histId, String jarPath) {
        ReleaseHistory hist = historyRepo.findById(histId).orElseThrow(
                () -> new IllegalArgumentException("release_history 不存在: " + histId));

        log.info("[R-7] STAGING deploy exec started, historyId={}", histId);

        try {
            String sshCmd = String.format(
                    "ssh -i %s -p %d -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o UserKnownHostsFile=/dev/null %s@%s bash %s '%s'",
                    stagingSshKeyPath, stagingSshPort, stagingSshUser, stagingSshHost, stagingDeployScript, jarPath);

            DeployResult result = exec(sshCmd, DEPLOY_TIMEOUT_SEC);
            finalizeHistory(hist, result);
            pushWebhook(hist);
        } catch (Exception e) {
            log.error("[R-7] STAGING deploy failed", e);
            hist.setStatus(ReleaseHistory.ReleaseStatus.FAILED);
            hist.setErrorMessage(e.getMessage());
            hist.setFinishedAt(LocalDateTime.now());
            hist.setDurationSec((int) Duration.between(hist.getStartedAt(), hist.getFinishedAt()).getSeconds());
            hist.setLog(truncateLog((hist.getLog() == null ? "" : hist.getLog()) + "\n\n[ERROR] " + e.getMessage()));
            historyRepo.save(hist);
            pushWebhook(hist);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 初始化 prod 晋升记录（同步）
     */
    public Long initProdPromote(Long userId) {
        SysUser user = userRepo.findById(userId).orElse(null);
        String username = user != null ? user.getUsername() : "unknown";

        ReleaseHistory hist = new ReleaseHistory();
        hist.setEnv(ReleaseHistory.ReleaseEnv.PROD);
        hist.setVersion(readCurrentVersion());
        hist.setJarFilename("lingyao-platform.jar");
        hist.setDeployedBy(username);
        hist.setDeployedById(userId);
        hist.setStatus(ReleaseHistory.ReleaseStatus.RUNNING);
        hist.setStartedAt(LocalDateTime.now());
        hist = historyRepo.save(hist);

        log.info("[R-7] PROD promote init by {}, historyId={}", username, hist.getId());
        return hist.getId();
    }

    /**
     * 异步执行 prod 晋升（@Async — 直接本地跑 deploy-prod.sh）
     */
    @Async
    public CompletableFuture<Void> executeProdPromote(Long histId) {
        ReleaseHistory hist = historyRepo.findById(histId).orElseThrow(
                () -> new IllegalArgumentException("release_history 不存在: " + histId));

        log.info("[R-7] PROD promote exec started, historyId={}", histId);

        try {
            // V2.0.6 R-7 修复：prod 晋升必须走 SSH（脱离 prod jar 进程组），
            // 否则 systemctl restart 会把 deploy-prod.sh 一起 SIGTERM 杀掉（exit 143）
            String sshCmd = String.format(
                    "ssh -i %s -p %d -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o UserKnownHostsFile=/dev/null %s@%s sudo -n bash %s /opt/lingyao/lingyao-platform.jar",
                    stagingSshKeyPath, stagingSshPort, stagingSshUser, stagingSshHost, prodDeployScript);
            DeployResult result = exec(sshCmd, DEPLOY_TIMEOUT_SEC);
            finalizeHistory(hist, result);
            pushWebhook(hist);
        } catch (Exception e) {
            log.error("[R-7] PROD promote failed", e);
            hist.setStatus(ReleaseHistory.ReleaseStatus.FAILED);
            hist.setErrorMessage(e.getMessage());
            hist.setFinishedAt(LocalDateTime.now());
            hist.setDurationSec((int) Duration.between(hist.getStartedAt(), hist.getFinishedAt()).getSeconds());
            hist.setLog(truncateLog((hist.getLog() == null ? "" : hist.getLog()) + "\n\n[ERROR] " + e.getMessage()));
            historyRepo.save(hist);
            pushWebhook(hist);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 检查是否有正在执行的部署（防并发）
     */
    public boolean hasRunningDeployment() {
        List<ReleaseHistory> running = historyRepo.findByStatus(ReleaseHistory.ReleaseStatus.RUNNING);
        return !running.isEmpty();
    }

    /**
     * 获取某环境的当前版本（最后一次 SUCCESS）
     */
    public ReleaseHistory getCurrentVersion(ReleaseHistory.ReleaseEnv env) {
        return historyRepo.findFirstByEnvAndStatusOrderByFinishedAtDesc(env, ReleaseHistory.ReleaseStatus.SUCCESS)
                .orElse(null);
    }

    // ── 私有方法 ─────────────────────────────────────

    /**
     * 执行 shell 命令并捕获 stdout/stderr
     */
    private DeployResult exec(String cmd, long timeoutSec) throws IOException, InterruptedException {
        log.info("[R-7] exec: {}", cmd);
        // V2.0.6 R-7 关键修复：prod 部署必须用 systemd-run 创建独立 transient service，
        // 否则 systemctl restart lingyao-backend 会 SIGTERM 整个进程组（包括 deploy-prod.sh / ssh client），
        // 表现：log 截断到 "systemctl restart"，exit code 143 = SIGTERM
        // setsid 修复不够，因为 systemd 用 cgroup 隔离进程，prod cgroup stop 会 kill 所有子进程
        // 默认 service 模式（不传 --scope）：systemd-run 创建 transient service 并 wait 到 cmd 完成
        // 不传 --unit 让 systemd-run 自动生成唯一 unit 名（run-XXXX.service）
        ProcessBuilder pb = new ProcessBuilder("systemd-run", "bash", "-c", cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append("\n");
            }
        }

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("部署超时（>" + timeoutSec + "s），已强制终止");
        }

        int exitCode = proc.exitValue();
        return new DeployResult(exitCode, out.toString());
    }

    private void finalizeHistory(ReleaseHistory hist, DeployResult result) {
        hist.setFinishedAt(LocalDateTime.now());
        hist.setDurationSec((int) Duration.between(hist.getStartedAt(), hist.getFinishedAt()).getSeconds());
        hist.setLog(truncateLog(result.stdout));
        if (result.exitCode == 0) {
            hist.setStatus(ReleaseHistory.ReleaseStatus.SUCCESS);
        } else {
            hist.setStatus(ReleaseHistory.ReleaseStatus.FAILED);
            hist.setErrorMessage("脚本退出码非零: " + result.exitCode);
        }
        historyRepo.save(hist);
    }

    private void pushWebhook(ReleaseHistory hist) {
        try {
            webhookService.pushReleaseNotification(hist);
            hist.setWebhookStatus("SUCCESS");
        } catch (Exception e) {
            log.warn("[R-7] Webhook 推送失败: {}", e.getMessage());
            hist.setWebhookStatus("FAILED: " + e.getMessage());
        }
        historyRepo.save(hist);
    }

    private String truncateLog(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_LOG_BYTES) return s;
        return "...(truncated)\n" + s.substring(s.length() - MAX_LOG_BYTES);
    }

    private String extractFilename(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String extractVersionFromJar(String jarPath) {
        // 从 jar 文件名猜版本（默认 fallback）
        String fn = extractFilename(jarPath);
        if (fn == null) return "unknown";
        // 匹配 lingyao-platform-2.0.5.jar 或 lingyao-platform.jar
        if (fn.matches("lingyao-platform-(\\d+\\.\\d+\\.\\d+)\\.jar")) {
            return fn.replaceAll("lingyao-platform-(\\d+\\.\\d+\\.\\d+)\\.jar", "$1");
        }
        return "current";
    }

    @Value("${app.version:1.0.0}")
    private String appVersion;

    private String readCurrentVersion() {
        return appVersion;
    }

    /** exec 结果封装 */
    private record DeployResult(int exitCode, String stdout) {}
}