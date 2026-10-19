package com.lingyao.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2.0.78.27 · SecurityConfig 白名单层回归护栏（L-01 的「另一半」）
 *
 * <h2>为什么必须单独写这个类</h2>
 * {@code DataLeakRegressionTest} 只用 Mockito 测<b>控制器层守卫</b>
 * （直接调 {@code controller.list()} 断言返回 401），<b>没有 Spring 上下文</b>，
 * 因此完全测不到 {@code SecurityConfig} 的 permitAll 白名单。
 *
 * <p>而 L-01 的真实漏洞正是<b>白名单前缀放行</b>：
 * {@code "/api/registrations"} 这一条没有方法限定，会把
 * {@code GET}（列表，含 name/phone/email）与 {@code PUT /{id}/status} 一并放行，
 * 请求根本到不了控制器守卫 —— 守卫被 URL 层直接短路。
 *
 * <p><b>已实测的负向验证</b>：把白名单改回
 * {@code .requestMatchers("/api/registrations").permitAll()} 后，
 * {@code DataLeakRegressionTest} 的 22 个用例<b>依然全绿</b>（守卫层测试对白层无感）。
 * 本类就是为了补上这个盲区。
 *
 * <h2>验证方式</h2>
 * 解析 {@code SecurityConfig.java} 源码，抽出所有
 * {@code requestMatchers(...).permitAll()} 表达式，断言：
 * <ol>
 *   <li>不存在<b>无方法限定</b>的 {@code "/api/registrations"} 放行；</li>
 *   <li>存在 {@code (HttpMethod.POST, "/api/registrations")} 与
 *       {@code (HttpMethod.GET, "/api/registrations/stats")} 两条精确放行；</li>
 *   <li>不存在任何把敏感路径无方法限定放行的条目（如 {@code /api/admin}、{@code /api/users}）。</li>
 * </ol>
 *
 * <p>为什么用源码解析而不是 {@code @SpringBootTest} + MockMvc：
 * 后者要拉起完整上下文（PostgreSQL + JPA + Redis），本仓无测试库时不可靠且慢；
 * 而白名单是<b>纯配置文本</b>，用源码断言既确定又快。
 * 代价是它不覆盖「运行时 matcher 解析行为」，故注释里标明这一局限，
 * 真正的行为验证仍需部署 staging 后用零凭据 curl 复测。
 */
class SecurityConfigWhitelistRegressionTest {

    private static final Path CONFIG = Paths.get(
            "src/main/java/com/lingyao/platform/security/SecurityConfig.java");

    /** 匹配 `.requestMatchers(<参数>).permitAll()` 的参数部分。 */
    private static final Pattern MATCHER = Pattern.compile(
            "\\.requestMatchers\\(([^)]*)\\)\\s*\\.permitAll\\(\\)", Pattern.DOTALL);

    private static String configSource() throws IOException {
        assertThat(Files.exists(CONFIG))
                .as("未找到 SecurityConfig.java（cwd=%s）", System.getProperty("user.dir"))
                .isTrue();
        return Files.readString(CONFIG, StandardCharsets.UTF_8);
    }

    private static List<String> permitAllArgs(String src) {
        List<String> out = new ArrayList<>();
        Matcher m = MATCHER.matcher(src);
        while (m.find()) {
            out.add(m.group(1).replaceAll("\\s+", " ").trim());
        }
        return out;
    }

    @Test
    @DisplayName("L-01 白名单：报名列表路径不得被无方法限定地放行")
    void registrationsMustNotBePermitAllWithoutMethod() throws IOException {
        String src = configSource();
        List<String> permits = permitAllArgs(src);

        // 直接用无方法限定的 "/api/registrations" 放行 = 把 PII 列表放开 = L-01 复发
        for (String arg : permits) {
            assertThat(arg)
                    .as("发现无方法限定的报名路径放行「%s」—— L-01 已复发！" +
                        " GET /api/registrations 会返回 name/phone/email",
                        arg)
                    .isNotEqualTo("\"/api/registrations\"");
        }
    }

    @Test
    @DisplayName("L-01 白名单：必须保留两条精确方法匹配放行（否则报名/统计功能会 403）")
    void registrationsMustKeepPrecisePublicEndpoints() throws IOException {
        String src = configSource();
        List<String> permits = permitAllArgs(src);

        boolean hasPostSubmit = permits.stream().anyMatch(a ->
                a.contains("HttpMethod.POST") && a.contains("\"/api/registrations\""));
        boolean hasGetStats = permits.stream().anyMatch(a ->
                a.contains("HttpMethod.GET") && a.contains("\"/api/registrations/stats\""));

        assertThat(hasPostSubmit)
                .as("缺少 POST /api/registrations 的公开放行，游客报名会 403")
                .isTrue();
        assertThat(hasGetStats)
                .as("缺少 GET /api/registrations/stats 的公开放行，未登录页统计会 403")
                .isTrue();
    }

    @Test
    @DisplayName("白名单卫生：不得无方法限定放行任何敏感前缀")
    void noSensitivePrefixIsPermitAllWithoutMethod() throws IOException {
        String src = configSource();
        String[] forbidden = {
                "\"/api/admin", "\"/api/users", "\"/api/company",
                "\"/api/sub",     "\"/api/audit", "\"/api/push",
                "\"/api/auth/me", "\"/api/products",
        };
        for (String arg : permitAllArgs(src)) {
            for (String bad : forbidden) {
                assertThat(arg)
                        .as("敏感前缀「%s」被无方法限定放行：%s", bad, arg)
                        .doesNotStartWith(bad);
            }
        }
    }

    @Test
    @DisplayName("白名单卫生：非 dev 分支内不得出现 /** 通配放行 api 业务路径")
    void noWildcardPermitAllOnApiPaths() throws IOException {
        String src = configSource();

        // /api/_diag/** 与 /h2-console/** 虽含 /**，但位于 if (dev) 分支内，
        // prod/staging 不加载（见 devOnlyEndpointsStayGated 用例），故豁免。
        // 这里排除 dev-only 路径，检查其余 api 路径是否误用通配放行。
        java.util.Set<String> devOnly = new java.util.HashSet<>(
                java.util.Arrays.asList("\"/api/_diag/**\"", "\"/h2-console/**\""));

        for (String arg : permitAllArgs(src)) {
            if (arg.startsWith("\"/api/") && !devOnly.contains(arg)) {
                assertThat(arg.contains("/**"))
                        .as("api 路径使用 /** 通配放行：%s —— 会把子路径一并放开", arg)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("dev-only 端点必须仍在 dev 分支内（_diag / h2-console 不得对 prod 放开）")
    void devOnlyEndpointsStayGated() throws IOException {
        String src = configSource();

        int devIdx = src.indexOf("if (dev)");
        assertThat(devIdx).as("未找到 if (dev) 分支 —— dev-only 隔离可能被破坏").isGreaterThan(-1);

        String afterDev = src.substring(devIdx);
        int diagIdx = afterDev.indexOf("/api/_diag/**");
        int h2Idx = afterDev.indexOf("/h2-console/**");

        assertThat(diagIdx).as("/api/_diag/** 的 permitAll 应位于 dev 分支内").isGreaterThan(-1);
        assertThat(h2Idx).as("/h2-console/** 的 permitAll 应位于 dev 分支内").isGreaterThan(-1);

        // 两处都必须出现在 dev 分支内、且位于 "非 dev 兜底 authenticated" 之前
        int authenticatedIdx = src.indexOf(".requestMatchers(\"/api/**\").authenticated()");
        assertThat(diagIdx).as("_diag 放行位置异常").isLessThan(authenticatedIdx);
        assertThat(h2Idx).as("h2-console 放行位置异常").isLessThan(authenticatedIdx);
    }

    @Test
    @DisplayName("jwt-fingerprint 必须保留 permitAll（否则密钥轮换时首屏必然 401）")
    void jwtFingerprintMustRemainPublic() throws IOException {
        String src = configSource();
        List<String> permits = permitAllArgs(src);
        assertThat(permits.stream().anyMatch(a -> a.contains("/api/auth/jwt-fingerprint")))
                .as("jwt-fingerprint 的 permitAll 丢失 —— 旧 token 失效时前端无法免鉴权读到新指纹，" +
                    "主动识别机制形同虚设")
                .isTrue();
    }
}
