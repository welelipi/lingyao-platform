# 凌瑶智数 · 端口配置环境变量化 SOP（V2.0.77.23 模板）

> **owner**: 主站窗口 · **reviewer**: 主人 · **创建**: 2026-09-13
> **目标**: 把散落在代码里的端口硬编码改成 `.env` 环境变量
> **关联**: `infra/port-allocation.md` §4.2 + `infra/port-change-checklist.md`

---

## §1 为什么需要环境变量化

按 `infra/port-allocation.md` §4.2，目前硬编码散落位置：
1. `website/js/script.js` L406-408：API_BASE 用 location 智能解析但 9091 写死
2. `geom/geo-frontend/vite.config.js` L21：port→apiBase 映射表写死
3. 各子平台 frontend `.env.development` / `.env.production`
4. 后端 `application*.yml` 中的 port 配置

**改端口代价**: 改 1 处 → 要联动改 5~15 处 → 容易漏改导致服务断链

**环境变量化的好处**:
- 改端口 = 改一处 `.env` → 全栈生效
- 新人 onboarding 不需要看代码查端口
- CI 校验可以 grep 检查端口一致性

---

## §2 主站 frontend 环境变量化（V2.0.77.23 模板）

### 当前代码（`website/js/script.js` L406-408）
```js
const API_BASE = (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')
  ? window.location.protocol + '//' + window.location.hostname + ':9091'
  : '';
```

### 改后代码（V2.0.77.23）
```js
// V2.0.77.23：API_BASE 从 meta 标签读取，部署时由 Nginx 反代层注入
const API_BASE = (() => {
  const meta = document.querySelector('meta[name="api-base"]');
  if (meta && meta.content) return meta.content;
  // 兜底：dev 默认 9091，其他用相对路径
  const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
  return isLocal ? `${window.location.protocol}//${window.location.hostname}:9091` : '';
})();
```

### HTML 模板（每个页面 head 加）
```html
<meta name="api-base" content="">  <!-- dev 默认空（脚本兜底）-->
<meta name="api-base" content="https://api.lingyao-digital.com">  <!-- prod 由构建工具注入 -->
```

### Nginx 反代层注入（推荐 · 0 代码）
```nginx
# /etc/nginx/conf.d/lingyao.conf
location / {
    sub_filter '<meta name="api-base" content="">' '<meta name="api-base" content="$api_base">';
    sub_filter_types text/html;
    # ... 反代配置
}
```

---

## §3 GEOM frontend 环境变量化（V0.10.20+ 模板）

### 当前代码（`geom/geo-frontend/vite.config.js` L21）
```js
const port = parseInt(env.VITE_PORT || '5180', 10)
const apiBase = env.VITE_API_BASE || `http://127.0.0.1:${port === 5180 ? 8090 : port === 5181 ? 8091 : port === 5182 ? 8092}`
```

### 改后代码（V0.10.20）
```js
const port = parseInt(env.VITE_PORT || '5180', 10)
const apiBase = env.VITE_API_BASE  // 直接读环境变量，不再 fallback 映射表
```

### `.env` 模板
```bash
# .env.development
VITE_PORT=5180
VITE_API_BASE=http://127.0.0.1:8090

# .env.staging
VITE_PORT=5181
VITE_API_BASE=http://127.0.0.1:8091

# .env.production
VITE_PORT=5182
VITE_API_BASE=https://lingyao-digital.com/geom
```

### `package.json` scripts 简化
```json
{
  "scripts": {
    "dev": "vite",
    "dev:staging": "vite --mode staging",
    "dev:prod": "vite --mode production"
  }
}
```

---

## §4 后端 application.yml 模板（不变 · 已用环境变量）

主站 backend `application.yml` 已经用 `${LINGYAO_DB_PASSWORD:...}` 模式，端口也可以：

```yaml
server:
  port: ${LINGYAO_SERVER_PORT:9091}  # 改这里即可
```

启动时覆盖：
```bash
LINGYAO_SERVER_PORT=9092 java -jar lingyao-platform.jar
```

---

## §5 迁移清单（按工作量）

### 短期（1~2 小时 · V2.0.77.23）
- [ ] `website/js/script.js` 改 meta 读取（最简）
- [ ] 每个 HTML 页面 `<head>` 加 `<meta name="api-base" content="">`
- [ ] Nginx 反代层加 `sub_filter` 注入
- [ ] 验证：prod 部署时 API_BASE 自动从 Nginx 注入
- [ ] 验证：dev 默认 9091（脚本兜底）

### 中期（2~3 小时 · V0.10.20）
- [ ] `geom/geo-frontend/vite.config.js` 移除 port 映射表
- [ ] `.env.development` / `.env.staging` / `.env.production` 三套环境配置
- [ ] `package.json` scripts 改用 `--mode` 模式
- [ ] 验证：3 环境 npm run dev 各自启动正确端口

### 长期（治理 · 不阻塞）
- [ ] CORS 白名单抽到 `application.yml` 配置项
- [ ] Nginx 反代模板 `infra/nginx.conf.template` 集中管理
- [ ] CI 脚本 `infra/check-port-consistency.sh` 自动校验

---

## §6 验证命令

```bash
# 主站：检查 API_BASE meta 是否注入
curl -s http://localhost:9091/portal.html | grep -oE '<meta name="api-base"[^>]*>'
# dev: content="" 兜底
# prod: content="https://api.lingyao-digital.com"

# GEOM：检查 .env 是否生效
cd geom/geo-frontend && cat .env.development
# VITE_PORT=5180
# VITE_API_BASE=http://127.0.0.1:8090
```

---

## §7 不在本 SOP 范围

- ❌ OAuth 厂商后台 URI 注册（走 `infra/oauth-redirect-uri-registry.md`）
- ❌ 服务器端口防火墙规则（运维 SOP）
- ❌ 域名 DNS 切换（运维 SOP）

---

*maintained by: 主站窗口 · target version: V2.0.77.23 (frontend) / V0.10.20 (GEOM) · last reviewed: 2026-09-13*
