# 凌瑶智数 · 变更日志

> 维护者：凌瑶主人
> 起始：2026-08-27（首次创建，作为质量环产物）

---

## 2026-08-27 · V2.0.1 (版本号方案 A 落地)

### Commit `9d2c1f7` · 方案 A：版本号机制升级（/api/version 端点 + SSO 协议版本字段）

**触发**：主人 2026-08-27 12:16 「通过方案 A」（5 条铁律全 yes）+ 质量环产物沉淀

**满足铁律**（方案 A 五条铁律全落地）：
- **V1** 三段式 X.Y.Z：`app.version: 2.0.0 → 2.0.1`（Z 段 bump）
- **V2** FE/BE 同步：凌瑶静态 HTML 无 FE/BE 同步需求；版本号机制统一在 BE
- **V3** 跨产品主版本同步：`sso.protocol-version: "1.0"` 字段建立，与 GEOm/MPDm 对齐 V2.0.0 时同步
- **V4** 必须 CHANGELOG.md：本文件即凌瑶 CHANGELOG
- **V5** 必须 /api/version 端点：新加 `GET /api/version`（公开无鉴权）

**改动**：
- 新增 `VersionController.java`：`/api/version` 端点（公开白名单）
- `SecurityConfig.java`：permitAll 列表加 `/api/version`
- `application.yml`：新增字段 `app.release` / `app.build-time` / `app.git-commit` / `sso.protocol-version` / `sso.jwt.secret` / `sso.jwt.token-ttl-seconds`
- `application.yml`：`app.version: 2.0.0 → 2.0.1`（铁律 2：小改动也要 bump）

**验证**：
- dev 9091 启动成功
- `curl http://127.0.0.1:9091/api/version` 返回完整 JSON：
  ```json
  {
    "code": 0,
    "data": {
      "service": "lingyao-platform",
      "version": "2.0.1",
      "release": "stable",
      "build_time": "2026-08-27T12:18:59+08:00",
      "git_commit": "dev",
      "sso_protocol": "1.0"
    }
  }
  ```
- `/api/health.data.version` 同步为 `2.0.1`（铁律 2 验证）

**附属收益**：
- SSO 协议版本字段已嵌入，凌瑶 V2.0.0 + GEOm/MPDm V2.0.0 三产品对齐时直接读 `sso.protocol-version`
- 部署脚本可注入 `APP_GIT_COMMIT=<hash>` 写入 `git_commit` 字段，运维对账零成本

### 遗留（待办）

| 项 | 描述 | 来源 |
|---|---|---|
| P1-1 | GEOm `local_health.py:300` 完美状态过滤 | 质量环扫描（明早 P1.1 部署时修）|
| P2-1 | 凌瑶 `application.yml` JWT secret fail-fast | 质量环扫描 |
| P2-2 | MPDm `hospital_hoyuan.db` 移位置 | 质量环扫描 |
| P2-3 | 凌瑶 `WebConfig.java` CORS 补 9093/9094 | 质量环扫描 |
| V2-GEOm | GEOm `__version__` 重构 + 端点 + CHANGELOG | 方案 A 迁移（明早 P1.1 部署时一起做）|
| V2-MPDm | MPDm `PLATFORM_VERSION` 去 H 前缀 + 端点 + CHANGELOG | 方案 A 迁移（V2 上线前）|

---

## 历史 commit 索引（2026-08-26 之前）

参见 `git log --oneline`：

```
b2c8cbc L4.2 fix: 显式指定 -i SSH_KEY 解决 Permission denied
484295b L4.1: GitHub Actions 自动部署 staging
aa9f459 L1+L2 staging 双环境 + 拆前端 4 子产品页
fe938de ✨ 阶段 1 + 阶段 2 部署基建完成
d56de1e 🎉 初始化凌瑶智数 Git 仓库
```

---

*文档维护说明：本 CHANGELOG 由质量环产物沉淀而来；后续每次发版请同步更新本文件。*
