package com.lingyao.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingyao.platform.controller.AdminController;
import com.lingyao.platform.controller.RegistrationController;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.CompanyAuditLog;
import com.lingyao.platform.entity.NotificationChannel;
import com.lingyao.platform.entity.Registration;
import com.lingyao.platform.entity.SysUser;
import com.lingyao.platform.repository.CompanyAuditLogRepository;
import com.lingyao.platform.repository.NotificationChannelRepository;
import com.lingyao.platform.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V2.0.78.27 · 四项数据泄露的反向回归测试（L-01 / L-02 / L-03 / L-04）。
 *
 * 设计原则：**测真实对象，不复述生产逻辑**。
 * 每个用例都直接调用被修复的 Controller 方法 / Service 方法，
 * 而不是把生产代码抄一遍再断言抄对了 —— 后者只会得到一个恒真的测试。
 *
 * ① L-01 报名 PII 零凭据可读 —— 由本类 {@link RegistrationPiiGuards} + SecurityConfigTest 覆盖
 * ② L-02 审计日志越权 + fail-open —— 由本类 {@link AuditLogFailClosed} 覆盖
 * ③ L-03 推送密钥越权暴露 —— 由本类 {@link PushHealthNoSecretLeak} 覆盖
 * ④ L-04 passwordHash 明文进响应 —— 由本类 {@link PasswordHashNotSerialized} 覆盖
 */
class DataLeakRegressionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        CurrentUser.clear();
    }

    /** 把当前登录人装进 JwtAuthFilter#getCurrentUser 依赖的 SecurityContext + ThreadLocal。 */
    private void loginAs(CurrentUser user) {
        CurrentUser.set(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        user, null, Collections.emptyList()));
    }

    private static CurrentUser employee() {
        CurrentUser u = new CurrentUser();
        u.setUserId(4242L);
        u.setUsername("leaky-employee");
        u.setCompanyId(null);
        u.setPlatformAdmin(false);
        return u;
    }

    private static CurrentUser superAdmin() {
        CurrentUser u = new CurrentUser();
        u.setUserId(1L);
        u.setUsername("admin");
        u.setCompanyId(null);
        u.setPlatformAdmin(true);
        return u;
    }

    // ════════════════════════════════════════════════════════
    // ① L-01：报名 PII 端点的控制器守卫（真实调用控制器）
    // ════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L-01 报名 PII 端点守卫")
    class RegistrationPiiGuards {

        private RegistrationController controller;
        private com.lingyao.platform.service.RegistrationService registrationService;

        @BeforeEach
        void setUp() {
            controller = new RegistrationController();
            registrationService = mock(com.lingyao.platform.service.RegistrationService.class);
            inject(controller, "service", registrationService);
        }

        @Test
        @DisplayName("L-01a 报名列表确实含 PII —— 锁定前提：被匿名可读就是全站 PII 泄露")
        void registrationListIndeedCarriesPii() throws Exception {
            Registration reg = new Registration();
            reg.setId(2L);
            reg.setName("Winn Liu");
            reg.setPhone("15510855337");
            reg.setEmail("188407408@qq.com");

            String json = mapper.writeValueAsString(reg);
            assertThat(json).contains("Winn Liu").contains("15510855337").contains("188407408@qq.com");
        }

        @Test
        @DisplayName("L-01b 匿名调 list → 401 且 Service 完全没被触达")
        void anonymousListIsRejected() {
            SecurityContextHolder.clearContext();
            CurrentUser.clear();

            ApiResponse<Page<Registration>> resp = controller.list(0, 20, null);

            assertThat(resp.getCode()).isEqualTo(401);
            assertThat(resp.getData()).isNull();
            verify(registrationService, never()).list(any(), any());
        }

        @Test
        @DisplayName("L-01c 普通员工调 list → 403 且 Service 完全没被触达")
        void employeeListIsRejected() {
            loginAs(employee());

            ApiResponse<Page<Registration>> resp = controller.list(0, 20, null);

            assertThat(resp.getCode()).isEqualTo(403);
            assertThat(resp.getData()).isNull();
            verify(registrationService, never()).list(any(), any());
        }

        @Test
        @DisplayName("L-01d 普通员工调 get({id}) → 403，Service 完全没被触达")
        void employeeGetByIdIsRejected() {
            loginAs(employee());

            ApiResponse<Registration> resp = controller.get(2L);

            assertThat(resp.getCode()).isEqualTo(403);
            assertThat(resp.getData()).isNull();
            verify(registrationService, never()).findById(any());
        }

        @Test
        @DisplayName("L-01e 普通员工调 updateStatus → 403，Service 完全没被触达")
        void employeeUpdateStatusIsRejected() {
            loginAs(employee());

            ApiResponse<Registration> resp =
                    controller.updateStatus(2L, Collections.singletonMap("status", "CONTACTED"));

            assertThat(resp.getCode()).isEqualTo(403);
            assertThat(resp.getData()).isNull();
            verify(registrationService, never()).updateStatus(anyLong(), any());
        }

        @Test
        @DisplayName("L-01f 普通员工调 stats → 仍公开可用（不能把公开统计一起堵掉）")
        void employeeCanStillReadStats() {
            loginAs(employee());
            when(registrationService.countByStatus(any())).thenReturn(1L);

            ApiResponse<?> resp = controller.stats();

            assertThat(resp.getCode()).isEqualTo(0);
            assertThat(resp.getData()).isNotNull();
        }

        @Test
        @DisplayName("L-01g 平台超管调 list → 放行")
        void platformAdminListIsAllowed() {
            loginAs(superAdmin());
            when(registrationService.list(any(), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            ApiResponse<Page<Registration>> resp = controller.list(0, 20, null);

            assertThat(resp.getCode()).isEqualTo(0);
            verify(registrationService, times(1)).list(any(), any());
        }
    }

    // ════════════════════════════════════════════════════════
    // ② L-02：AuditLogService fail-open → fail-closed + listAuditLogs 守卫
    // ════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L-02 审计日志 fail-closed")
    class AuditLogFailClosed {

        private CompanyAuditLogRepository repo;
        private AuditLogService service;

        @BeforeEach
        void setUp() {
            repo = mock(CompanyAuditLogRepository.class);
            service = new AuditLogService();
            inject(service, "repo", repo);
        }

        @Test
        @DisplayName("L-02a 无登录上下文(cu==null) → 空页，绝不退化为全表")
        void noUserReturnsEmptyInsteadOfWholeTable() {
            CurrentUser.clear();
            SecurityContextHolder.clearContext();

            Page<CompanyAuditLog> page = service.listForCurrentCompany(0, 20);

            assertThat(page.getContent()).isEmpty();
            verify(repo, never()).findAllByOrderByCreatedAtDesc(any());
            verify(repo, never()).findByCompanyIdOrderByCreatedAtDesc(anyLong(), any());
        }

        @Test
        @DisplayName("L-02b 普通员工未绑定公司(companyId==null) → 空页，绝不退化为全表")
        void employeeWithoutCompanyReturnsEmptyInsteadOfWholeTable() {
            // 这正是实测里读到 109 条跨公司记录的那条 token 的形态
            loginAs(employee());

            Page<CompanyAuditLog> page = service.listForCurrentCompany(0, 20);

            assertThat(page.getContent()).isEmpty();
            verify(repo, never()).findAllByOrderByCreatedAtDesc(any());
            verify(repo, never()).findByCompanyIdOrderByCreatedAtDesc(anyLong(), any());
        }

        @Test
        @DisplayName("L-02c 平台超管未绑定公司 → 仍可看全表（合法路径不能被误伤）")
        void platformAdminStillSeesWholeTable() {
            loginAs(superAdmin());
            when(repo.findAllByOrderByCreatedAtDesc(any()))
                    .thenReturn(new PageImpl<>(Collections.singletonList(new CompanyAuditLog())));

            Page<CompanyAuditLog> page = service.listForCurrentCompany(0, 20);

            assertThat(page.getContent()).hasSize(1);
            verify(repo, times(1)).findAllByOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("L-02d 有公司归属 → 按公司过滤，原有行为不变")
        void tenantUserStillScopedToOwnCompany() {
            CurrentUser tenantUser = new CurrentUser();
            tenantUser.setUserId(7L);
            tenantUser.setCompanyId(88L);
            tenantUser.setPlatformAdmin(false);
            loginAs(tenantUser);
            when(repo.findByCompanyIdOrderByCreatedAtDesc(anyLong(), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            service.listForCurrentCompany(0, 20);

            verify(repo, times(1)).findByCompanyIdOrderByCreatedAtDesc(eq(88L), any());
            verify(repo, never()).findAllByOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("L-02e 普通员工调 listAuditLogs → 403，审计查询根本没发生")
        void employeeCannotListAuditLogs() {
            loginAs(employee());
            AdminController admin = new AdminController();
            AuditLogService svc = mock(AuditLogService.class);
            inject(admin, "auditLogService", svc);

            ApiResponse<Page<CompanyAuditLog>> resp = admin.listAuditLogs(0, 20);

            assertThat(resp.getCode()).isEqualTo(403);
            assertThat(resp.getData()).isNull();
            verify(svc, never()).listForCurrentCompany(any(Integer.class), any(Integer.class));
        }

        @Test
        @DisplayName("L-02f 平台超管调 listAuditLogs → 放行")
        void platformAdminCanListAuditLogs() {
            loginAs(superAdmin());
            AdminController admin = new AdminController();
            AuditLogService svc = mock(AuditLogService.class);
            inject(admin, "auditLogService", svc);
            when(svc.listForCurrentCompany(any(Integer.class), any(Integer.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            ApiResponse<Page<CompanyAuditLog>> resp = admin.listAuditLogs(0, 20);

            assertThat(resp.getCode()).isEqualTo(0);
            verify(svc, times(1)).listForCurrentCompany(any(Integer.class), any(Integer.class));
        }
    }

    // ════════════════════════════════════════════════════════
    // ③ L-03：pushHealth 守卫 + 响应体不含密钥（真实调用控制器）
    // ════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L-03 推送通道密钥不外泄")
    class PushHealthNoSecretLeak {

        private AdminController admin;
        private NotificationChannelRepository channelRepo;

        @BeforeEach
        void setUp() {
            admin = new AdminController();
            channelRepo = mock(NotificationChannelRepository.class);
            inject(admin, "channelRepo", channelRepo);
            inject(admin, "auditLogService", mock(AuditLogService.class));
        }

        private NotificationChannel channelWithRealSecrets() {
            NotificationChannel ch = new NotificationChannel();
            ch.setId(1L);
            ch.setChannelType(NotificationChannel.ChannelType.FEISHU);
            ch.setName("飞书群");
            ch.setWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/SUPER-SECRET-KEY");
            ch.setSecret("wx-AppSecret-PLAINTEXT");
            ch.setStatus(NotificationChannel.Status.ACTIVE);
            return ch;
        }

        @Test
        @DisplayName("L-03a 前提锁定：NotificationChannel 实体确实承载 webhookUrl 与 secret")
        void entityIndeedCarriesSecrets() {
            NotificationChannel ch = channelWithRealSecrets();
            assertThat(ch.getWebhookUrl()).contains("SUPER-SECRET-KEY");
            assertThat(ch.getSecret()).isEqualTo("wx-AppSecret-PLAINTEXT");
        }

        @Test
        @DisplayName("L-03b 匿名调 pushHealth → 401，通道表根本没被读")
        void anonymousPushHealthIsRejected() {
            SecurityContextHolder.clearContext();
            CurrentUser.clear();

            ApiResponse<?> resp = admin.pushHealth();

            assertThat(resp.getCode()).isEqualTo(401);
            assertThat(resp.getData()).isNull();
            verify(channelRepo, never()).findAll();
        }

        @Test
        @DisplayName("L-03c 普通员工调 pushHealth → 403，通道表根本没被读")
        void employeePushHealthIsRejected() {
            loginAs(employee());

            ApiResponse<?> resp = admin.pushHealth();

            assertThat(resp.getCode()).isEqualTo(403);
            assertThat(resp.getData()).isNull();
            verify(channelRepo, never()).findAll();
        }

        @Test
        @DisplayName("L-03d 超管调 pushHealth → 响应体 JSON 绝不含 webhookUrl 原文与 secret")
        void pushHealthResponseLeaksNoSecret() throws Exception {
            loginAs(superAdmin());
            when(channelRepo.findByStatusOrderBySortOrderAsc(any()))
                    .thenReturn(Collections.singletonList(channelWithRealSecrets()));
            when(channelRepo.count()).thenReturn(1L);
            when(channelRepo.findAll()).thenReturn(Collections.singletonList(channelWithRealSecrets()));

            ApiResponse<?> resp = admin.pushHealth();
            String json = mapper.writeValueAsString(resp);

            assertThat(resp.getCode()).isEqualTo(0);
            // 🔴 核心断言：密钥原文一个字都不能出现
            assertThat(json)
                    .doesNotContain("SUPER-SECRET-KEY")
                    .doesNotContain("open.feishu.cn")
                    .doesNotContain("wx-AppSecret-PLAINTEXT")
                    .doesNotContain("\"webhookUrl\"")
                    .doesNotContain("\"secret\"");
            // 诊断真正需要的字段仍要保留，不能把端点掏空
            assertThat(json).contains("activeChannels").contains("totalChannels")
                    .contains("FEISHU").contains("webhookConfigured").contains("secretConfigured");
        }

        @Test
        @DisplayName("L-03e REPLACE_ME 占位不算「已配置」")
        void placeholderWebhookIsNotConfigured() throws Exception {
            loginAs(superAdmin());
            NotificationChannel placeholder = new NotificationChannel();
            placeholder.setId(2L);
            placeholder.setChannelType(NotificationChannel.ChannelType.WECHAT_WORK);
            placeholder.setName("企微占位");
            placeholder.setWebhookUrl("REPLACE_ME");
            placeholder.setSecret("REPLACE_ME");
            placeholder.setStatus(NotificationChannel.Status.INACTIVE);

            when(channelRepo.findByStatusOrderBySortOrderAsc(any())).thenReturn(Collections.emptyList());
            when(channelRepo.count()).thenReturn(1L);
            when(channelRepo.findAll()).thenReturn(Collections.singletonList(placeholder));

            ApiResponse<?> resp = admin.pushHealth();
            String json = mapper.writeValueAsString(resp);

            assertThat(json).doesNotContain("REPLACE_ME");
            // 已配置位应为 false
            assertThat(json.contains("\"webhookConfigured\":true")).isFalse();
            assertThat(json.contains("\"secretConfigured\":true")).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════
    // ④ L-04：passwordHash 不得进响应
    // ════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L-04 passwordHash 不进 JSON")
    class PasswordHashNotSerialized {

        @Test
        @DisplayName("L-04a SysUser 序列化后不含 passwordHash 字段")
        void sysUserJsonHasNoPasswordHash() throws Exception {
            SysUser u = new SysUser();
            u.setId(1L);
            u.setUsername("admin");
            u.setPasswordHash("$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZ012");
            u.setEmail("admin@example.com");

            String json = mapper.writeValueAsString(u);

            assertThat(json).doesNotContain("passwordHash").doesNotContain("$2a$10$");
            assertThat(json).contains("admin").contains("admin@example.com");
        }

        @Test
        @DisplayName("L-04b Page<SysUser>（listUsers 的真实返回形态）同样不含 passwordHash")
        void pageOfSysUserHasNoPasswordHash() throws Exception {
            SysUser u = new SysUser();
            u.setId(9L);
            u.setUsername("tenant-admin");
            u.setPasswordHash("$2a$10$leakedhashleakedhashleakedhashleakedhas");

            Pageable pageable = PageRequest.of(0, 20);
            Page<SysUser> page = new PageImpl<>(Collections.singletonList(u), pageable, 1);

            String json = mapper.writeValueAsString(page);

            assertThat(json).doesNotContain("passwordHash").doesNotContain("leakedhash");
            assertThat(json).contains("tenant-admin");
        }

        @Test
        @DisplayName("L-04c ApiResponse 包裹 Page<SysUser> 也不含 passwordHash（真实响应包装形态）")
        void apiResponseWrappedPageHasNoPasswordHash() throws Exception {
            SysUser u = new SysUser();
            u.setId(9L);
            u.setUsername("tenant-admin");
            u.setPasswordHash("$2a$10$leakedhashleakedhash");

            ApiResponse<Page<SysUser>> resp = ApiResponse.ok(
                    new PageImpl<>(Collections.singletonList(u), PageRequest.of(0, 20), 1));

            String json = mapper.writeValueAsString(resp);

            assertThat(json).doesNotContain("passwordHash").doesNotContain("leakedhash");
        }

        @Test
        @DisplayName("L-04d @JsonIgnore 不阻断内部 Java 侧读写（登录/改密逻辑不受影响）")
        void javaAccessorsStillWork() {
            SysUser u = new SysUser();
            u.setPasswordHash("$2a$10$roundtrip");
            assertThat(u.getPasswordHash()).isEqualTo("$2a$10$roundtrip");
        }
    }

    // ── 测试辅助：字段注入（生产类用 @Autowired 私有字段，无构造器注入）──
    private static void inject(Object target, String fieldName, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("字段不存在: " + fieldName);
    }
}
