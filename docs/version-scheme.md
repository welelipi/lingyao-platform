# 凌瑶智数 + GEOm + MPDm 统一版本号方案

> v1.0 草案 · 2026-08-27 主人下达 · 来源：全站质量安全扫描发现版本号同步机制缺位
>
> 适用：跨三个产品的所有 commit / 部署 / 对账场景

---

## 一、现状诊断

| 项目 | 当前版本号 | 定义位置 | 端点 | 问题 |
|---|---|---|---|---|
| 凌瑶智数（Java）| `2.0.0` | `application.yml: app.version` | `/api/health` 含 `version` | ✅ 已规范化（commit c0089d0） |
| GEOm / ly-platform 对外版（Python）| `0.9.13.0-pub` | `app/core/version.py: __version__` | `/api/version` 404 | ❌ 4 段 + `-pub` 后缀不规范 |
| MPDm / 皓元（Python）| `H1.0.3` | `config.py: PLATFORM_VERSION` | `/api/version` 404 | ❌ `H` 品牌前缀不一致 |

**核心问题**：

1. **三产品格式不统一** → 跨产品对账肉眼比对困难
2. **staging/prod 版本号无差异** → 预发布/生产混淆
3. **缺乏 SSO 协议版本字段** → V2 SSO 上线后协议兼容性无法声明
4. **没有 CI 卡口** → commit 不 bump 版本号也能 merge
5. **CHANGELOG.md 仅凌瑶有** → GEOm / MPDm 变更不可追溯

---

## 二、五条核心铁律（跨产品强制）

### 铁律 V1 · 统一三段式 X.Y.Z

所有产品版本号统一为 `X.Y.Z` 三段式（去掉 GEOm 的 4 段、MPDm 的 H 前缀）：

```
X  (Major)  架构级变更 · 破坏性升级 · 必须跨产品同步
Y  (Minor)  新功能模块 · 向后兼容 · 可独立 bump
Z  (Patch)  Bug 修复 / 文案微调 · 必须 bump（铁律 2 强化：小改动也要 +1）
```

### 铁律 V2 · 每个产品内部 FE/BE 同步

每个产品的 `package.json`（FE）+ 后端配置（BE）的版本号必须一致。
凌瑶（静态 HTML）只需 BE 一处；GEOm/MPDm 必须 FE + BE 两处一致。

### 铁律 V3 · 跨产品主版本号同步

SSO 协议版本升级时，凌瑶 / GEOm / MPDm 的 **X 大版本号必须一起 bump**。

| 里程碑 | SSO 协议 | 凌瑶 X | GEOm X | MPDm X | 触发条件 |
|---|---|---|---|---|---|
| V1.x | — | 1.x | 0.x | 1.x | 当前独立阶段 |
| **V2.0.0** | SSO v1.0（JWT 自签）| **2.0.0** | **2.0.0** | **2.0.0** | V2 上线日（凌瑶已 2.0.0，GEOm/MPDm 跟随）|
| V3.0.0 | SSO v2.0（多租户 JWT）| 3.0.0 | 3.0.0 | 3.0.0 | 多租户上线日（计划中）|

### 铁律 V4 · 每个产品必须 CHANGELOG.md

每次 commit 必须在 `<项目根>/CHANGELOG.md` 加一行（含 commit hash + 描述 + 验证步骤）。

| 项目 | CHANGELOG.md 状态 |
|---|---|
| 凌瑶智数 | ✅ 已建（commit 1a26ef4）|
| GEOm | ❌ 待补 |
| MPDm | ❌ 待补 |

### 铁律 V5 · 每个产品必须有版本号 endpoint

公开端点 `GET /api/version`（无需鉴权）返回标准 JSON（见第六节）。

| 项目 | 端点状态 |
|---|---|
| 凌瑶智数 | ✅ `/api/health.data.version`（含 version 字段）|
| GEOm | ❌ `/api/version` 404 |
| MPDm | ❌ `/api/version` 404 |

---

## 三、版本号迁移计划

### 一次性迁移（V2 SSO 上线前完成）

| 项目 | 当前 | 目标 | 迁移动作 |
|---|---|---|---|
| 凌瑶智数 | `2.0.0` | `2.0.0` | ✅ 已对齐（commit c0089d0）|
| GEOm | `0.9.13.0-pub` | `2.0.0` | 4 段 → 3 段；去 `-pub` 后缀；跳到 2.0.0 与凌瑶对齐 |
| MPDm | `H1.0.3` | `2.0.0` | 去 `H` 前缀；跳到 2.0.0 与凌瑶对齐 |

**GEOm 迁移示例**：
```python
# app/core/version.py
__version__ = "2.0.0"           # 原 0.9.13.0-pub
RELEASE = "stable"                # 原混在 version string 里
BUILD_TIME = "2026-08-27T..."
GIT_COMMIT = "abc1234"            # 部署脚本写入
SSO_PROTOCOL = "1.0"              # 新增字段
```

**MPDm 迁移示例**：
```python
# app/config.py
PLATFORM_VERSION: str = os.environ.get("MPD_PLATFORM_VERSION", "2.0.0")  # 原 "H1.0.3"
RELEASE: str = os.environ.get("MPD_RELEASE", "stable")
SSO_PROTOCOL: str = os.environ.get("MPD_SSO_PROTOCOL", "1.0")
```

### 长期演进（每次 commit bump）

| 触发场景 | bump 哪一段 | 跨产品同步？| 例子 |
|---|---|---|---|
| 改了文案 / UI 微调 / Bug 修复 | Z | 否（仅改的产品）| `2.0.0` → `2.0.1` |
| 新功能模块 / 新接口 | Y | 否（仅改的产品）| `2.0.0` → `2.1.0` |
| 数据库 schema 不兼容变更 | X | 否（产品内强制升级）| `2.x.0` → `3.0.0` |
| **SSO 协议升级** | **X** | **是（三产品必同步）**| `2.x.0` → `3.0.0` |

---

## 四、staging 预发布标签

staging 部署用 `-rc.N` 后缀，生产去掉：

| 环境 | 版本号示例 | 来源 |
|---|---|---|
| 凌瑶生产 | `2.0.0` | `application.yml: app.version` |
| 凌瑶 staging | `2.0.0-rc.1` | 部署脚本注入 `RELEASE=rc.1` |
| 凌瑶 dev | `2.0.0-dev` | IDE profile |

**deploy-staging.sh 注入示例**：
```bash
# scripts/deploy-staging.sh
ssh ubuntu@118.195.197.15 "
  sed -i 's|app.version=.*|app.version=2.0.0-rc.1|' /opt/lingyao/application-staging.yml
  systemctl restart lingyao-staging
"
# /api/version 应返回 "release": "rc.1"
```

---

## 五、commit SOP（开发者必做）

每次 commit 前按顺序检查：

```
1. 是否改了用户可见的文案/UI？
   是 → bump Z（铁律 2：小改动也要 +1）

2. 是否加了新功能模块 / 新接口？
   是 → bump Y，Z 归零

3. 是否做了架构级变更（数据库 schema 不兼容 / 鉴权重构）？
   是 → bump X，Y/Z 归零；若 SSO 协议版本升级则三产品必同步

4. 是否改了 SSO 协议字段（sso.jwt.secret / token 格式 / 端点）？
   是 → 三产品同步 bump X + SSO_PROTOCOL 字段同步

5. CHANGELOG.md 加一行（含 commit hash + 描述 + 验证步骤）

6. 精准 add（铁律 5：禁止 git add -A）

7. commit author 用主人身份（铁律 11）

8. push 需主人明确（铁律 6）
```

---

## 六、版本号 endpoint 标准格式

每个产品必须实现 `GET /api/version`（公开端点，无需鉴权）：

```json
{
  "code": 0,
  "data": {
    "service": "lingyao-platform",
    "version": "2.0.0",
    "release": "stable",
    "build_time": "2026-08-27T12:00:00Z",
    "git_commit": "c0089d0",
    "sso_protocol": "1.0"
  }
}
```

字段说明：

| 字段 | 必填 | 含义 |
|---|---|---|
| `service` | ✅ | 产品标识（`lingyao-platform` / `geo-platform` / `mpd-platform`）|
| `version` | ✅ | 三段式版本号 `X.Y.Z` |
| `release` | ✅ | `stable` / `rc.N` / `dev` |
| `build_time` | ✅ | ISO 8601 构建时间 |
| `git_commit` | ✅ | 部署时的 git commit hash |
| `sso_protocol` | ✅ | SSO 协议版本（V2 阶段写 `1.0`）|

**对账场景**：
- 开发者 FE 控制台 vs BE `/api/version` → `version` 一致 = FE/BE 同步
- 运维部署后 `/api/version` → `git_commit` 匹配 tag = 部署产物正确
- 凌瑶签发的 JWT 头部 `sso_protocol=1.0` vs GEOm `/api/version` 同字段 → SSO 协议兼容

---

## 七、迁移 checklist（落地动作清单）

### 立即做（今天 / 明天 GEOm 部署时一起做）

- [ ] **凌瑶智数**（已完成）
  - [x] `application.yml: app.version = 2.0.0`
  - [x] `HealthController.java` 用 `@Value` 注入
  - [x] `/api/health` 返回 `version: 2.0.0`
  - [x] `CHANGELOG.md` 新建

- [ ] **GEOm**（明早 P1.1 部署时一起做）
  - [ ] `app/core/version.py: __version__ = "2.0.0"`
  - [ ] 去掉 `-pub` 后缀
  - [ ] 新增 `release` / `build_time` / `git_commit` / `sso_protocol` 字段
  - [ ] 实现 `GET /api/version` 端点
  - [ ] 新建 `CHANGELOG.md`
  - [ ] commit + push（一次 commit 包含所有变更）

- [ ] **MPDm**（P2 阶段，V2 上线前完成）
  - [ ] `config.py: PLATFORM_VERSION = "2.0.0"`（去 H 前缀）
  - [ ] 新增 release / build_time / git_commit / sso_protocol 字段
  - [ ] 实现 `GET /api/version` 端点
  - [ ] 新建 `CHANGELOG.md`
  - [ ] commit + push

### 后续做（V2 SSO 上线时）

- [ ] 跨产品主版本号对齐到 V2.0.0（验证三产品 `/api/version` 一致）
- [ ] SSO `protocol_version` 字段嵌入 JWT token 头部
- [ ] 凌瑶 `application.yml: sso.protocol-version: 1.0`
- [ ] GEOm / MPDm SSO 验证时检查 token 头部 `protocol_version` 与本地一致

### 长期维护（每季度审计）

- [ ] CI 流水线检查 CHANGELOG.md 是否更新
- [ ] CI 流水线检查 FE/BE 版本号一致性
- [ ] 跨产品主版本号同步检查
- [ ] `/api/version` 端点可达性 + 字段完整性

---

## 八、与现有铁律的兼容性

| 现有铁律 | 兼容性 |
|---|---|
| 铁律 1 · 重启服务必发前端地址 | ✅ 兼容（版本号不影响访问地址）|
| 铁律 2 · 前后端版本号同步（小改动也要 bump）| ✅ **本方案强化推广到跨产品** |
| 铁律 5 · commit 精确 add（禁止 git add -A）| ✅ 兼容（commit SOP 第 6 步强制）|
| 铁律 6 · 推送需主人明确 | ✅ 兼容（commit SOP 第 8 步强制）|
| 铁律 8 · 后端改动必须重启 + dev 验证 | ✅ 兼容（验证 `/api/version` 必跑）|
| 铁律 11 · commit author 是主人身份 | ✅ 兼容（commit SOP 第 7 步强制）|

---

## 九、主人拍板项（5 条铁律）

| # | 决策点 | 推荐 | 备选 |
|---|---|---|---|
| 1 | 三段式 X.Y.Z | ✅ 推荐 | 保留 H 前缀 / 保留 4 段 |
| 2 | 每个产品内部 FE/BE 同步 | ✅ 推荐 | 仅凌瑶强制 |
| 3 | 跨产品主版本号同步 | ✅ 推荐（V2 SSO 一起上）| 完全独立 |
| 4 | 每个产品必须 CHANGELOG.md | ✅ 推荐 | 仅生产环境强制 |
| 5 | 每个产品必须有 `/api/version` 端点 | ✅ 推荐 | 仅 staging+prod 强制 |

**主人您选 yes/no 给我，我立刻落地**：
- 5 条全 yes → 一次性写完所有 adapter + 端点
- 部分 yes → 我标注哪些先做哪些缓做
- 全部 no → 我问下您想要什么样的方案