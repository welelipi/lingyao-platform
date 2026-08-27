# 凌瑶智数 · 变更日志

> 维护者：凌瑶主人
> 起始：2026-08-27（首次创建，作为质量环产物）

---

## 2026-08-27 · V2.0.0 (质量环首次加固)

### Commit `c0089d0` · P1-2 修复：版本号注入机制

**触发**：2026-08-27 全站质量安全扫描（`project-quality-loop` skill v0.2.0）

**改动**：
- `HealthController.java`：硬编码 `"version":"1.0.0"` 改为 `@Value("${app.version:1.0.0}")` 注入
- `application.yml`：新增 `app.version: 2.0.0`
- `application.yml` 重构：`app:` 和 `lingyao:` 配置从 `---` 后面的 dev profile 块移到 `---` 前面的默认块（修复 `${lingyao.jwt.secret}` 占位符解析失败的隐藏 bug）

**满足铁律**：#2（前后端版本号同步机制建立）

**验证**：
- dev 端口 9091 启动成功（`Tomcat started on port 9091`）
- `curl http://127.0.0.1:9091/api/health` 返回：
  ```json
  {
    "code": 0,
    "data": {
      "version": "2.0.0",
      "status": "UP",
      "service": "lingyao-platform"
    }
  }
  ```

**附属收益**：
- 修复了 `${lingyao.jwt.secret}` 占位符解析失败（之前 dev profile 块中 jwtUtil bean 找不到 secret）
- 避免下次更新版本号要改代码（运维友好）

### 遗留（待办）

| 项 | 描述 | 来源 |
|---|---|---|
| P1-1 | GEOm `local_health.py:300` 完美状态过滤 | 质量环扫描 |
| P2-1 | 凌瑶 `application.yml` JWT secret fail-fast | 质量环扫描 |
| P2-2 | MPDm `hospital_hoyuan.db` 移位置 | 质量环扫描 |
| P2-3 | 凌瑶 `WebConfig.java` CORS 补 9093/9094 | 质量环扫描 |

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
