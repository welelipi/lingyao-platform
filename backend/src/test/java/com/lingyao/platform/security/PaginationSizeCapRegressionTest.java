package com.lingyao.platform.security;

import com.lingyao.platform.config.PageableValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V1.1.77 护栏：分页 size 上限回归。
 *
 * <p>背景（2026-10-19 三产品 UAT 挖出，探针编号 P1-1/P1-2）：
 * 项目早已有 {@link PageableValidator}（注释写明「Bug-14 修复，size 1~100」），
 * 但有两处仍裸用 {@code PageRequest.of(page, size)}，size 完全由调用方决定 ——
 * 可传 size=1000000 把整表审计日志 / 授权记录一次性拉进内存。
 *
 * <p>本测试分两层：
 * <ol>
 *   <li><b>行为层</b>：直接验证 {@code PageableValidator.safeOf} 的钳位行为，
 *       并验证两个被修端点的源码已改用校验器。</li>
 *   <li><b>源码层</b>：全量扫描 {@code src/main/java}，禁止任何
 *       「裸 PageRequest.of 且没有 size 上限」的新增写法 —— 这是防未来回归的关键，
 *       因为探针只能发现已存在的缺陷，不能防止将来再犯。</li>
 * </ol>
 *
 * <p><b>变异测试说明</b>：第 2 层是本文件的牙齿。若把两处修复还原成裸
 * {@code PageRequest.of}，{@link #noBarePageRequestWithoutSizeCap()} 必须失败。
 */
class PaginationSizeCapRegressionTest {

    private static final Path SRC_ROOT = Paths.get("src/main/java");

    /** 匹配 PageRequest.of( 的调用（排除 PageableValidator 自身的实现）。 */
    private static final Pattern PAGE_REQUEST_OF =
            Pattern.compile("PageRequest\\s*\\.\\s*of\\s*\\(");

    /** 匹配 size 上限钳位表达式，例如 Math.min(..., 100)。 */
    private static final Pattern SIZE_CAP =
            Pattern.compile("Math\\s*\\.\\s*min\\s*\\(");

    @Test
    @DisplayName("PageableValidator 把 size 钳位在 1~100")
    void validatorClampsSize() {
        assertEquals(100, PageableValidator.safeOf(0, 1000000).getPageSize(),
                "超大 size 必须被钳到 MAX_PAGE_SIZE");
        assertEquals(100, PageableValidator.safeOf(0, 999999).getPageSize());
        assertEquals(20, PageableValidator.safeOf(0, 0).getPageSize(),
                "size<=0 应回落到默认 20，而不是 0 或抛异常");
        assertEquals(20, PageableValidator.safeOf(0, -5).getPageSize());
        assertEquals(1, PageableValidator.safeOf(0, 1).getPageSize(),
                "最小 size 应为 1");
        assertEquals(0, PageableValidator.safeOf(-3, 10).getPageNumber(),
                "负 page 应归零");
    }

    @Test
    @DisplayName("带 Sort 的重载同样钳位")
    void validatorClampsSizeWithSort() {
        Pageable p = PageableValidator.safeOf(
                0, 1000000, org.springframework.data.domain.Sort.by("id").descending());
        assertEquals(100, p.getPageSize());
        assertTrue(p.getSort().isSorted(), "Sort 不应被钳位逻辑丢掉");
    }

    @Test
    @DisplayName("🔴 全量源码扫描：不得出现无 size 上限的裸 PageRequest.of")
    void noBarePageRequestWithoutSizeCap() {
        List<String> violations = new ArrayList<>();
        for (Path file : listJavaFiles(SRC_ROOT)) {
            String src = read(file);
            Matcher m = PAGE_REQUEST_OF.matcher(src);
            while (m.find()) {
                // PageableValidator 自身是校验器实现，跳过（它的 size 已钳位）
                if (file.toString().endsWith("PageableValidator.java")) {
                    continue;
                }
                // 取该调用点所在的语句块（到分号或右括号为止）判断是否带上限
                int end = Math.min(src.length(), m.end() + 220);
                String callWindow = src.substring(m.start(), end);
                boolean hasCap = SIZE_CAP.matcher(callWindow).find();
                boolean usesValidator = callWindow.contains("PageableValidator.safeOf");
                if (!hasCap && !usesValidator) {
                    int line = (int) src.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
                    violations.add(file + ":" + line + " → " + firstLine(callWindow));
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "发现无 size 上限的裸 PageRequest.of（调用方可拉全表进内存）：\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("两个被修端点确实改用了 PageableValidator")
    void fixedEndpointsUseValidator() {
        String admin = read(SRC_ROOT.resolve(
                "com/lingyao/platform/controller/AdminController.java"));
        assertTrue(admin.contains("PageableValidator.safeOf"),
                "AdminController.listAllGrants 未改用 PageableValidator（size 无上限）");

        String sso = read(SRC_ROOT.resolve(
                "com/lingyao/platform/service/SsoConfigService.java"));
        assertTrue(sso.contains("PageableValidator.safeOf"),
                "SsoConfigService.listAuditLogs 未改用 PageableValidator（size 无上限）");

        // 返回给前端的分页参数必须反映「实际生效值」，否则前端会拿到
        // 自己请求的 size，与真实结果对不上（口径不一致的隐蔽 bug）。
        assertTrue(sso.contains("pageable.getPageNumber()")
                        && sso.contains("pageable.getPageSize()"),
                "listAuditLogs 返回的 page/size 应取 pageable 实际值，而非原始入参");
    }

    private static List<Path> listJavaFiles(Path root) {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码失败: " + root, e);
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + p, e);
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl > 0 ? s.substring(0, nl) : s;
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }
}
