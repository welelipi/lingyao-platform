package com.lingyao.platform.service;

import com.lingyao.platform.config.NotifyEmailProperties;
import com.lingyao.platform.entity.Company;
import com.lingyao.platform.entity.SysUser;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 试用邀请邮件服务 — V2.0.29（V2 按主人 2026-09-02 09:21/09:22 需求变更重写）
 *
 * 变更点：
 * 1. 邮件按【实际开通的产品】动态生成介绍与指引（开通哪个写哪个，不再固定 GEO）
 * 2. 邮件中【禁止出现内部开发代号】（GEOM/MPDM/AIDD/D-Info/PORM 等），一律用对外正式产品名
 * 3. 每个产品介绍 ≤2 句话，正式且简要；排版朴素，不放产品链接（将来再内置）
 *
 * 邮件结构：
 * 您好，您的试用账号已开通 → 产品介绍（开通哪个写哪个）→ 登录网址+账号密码 → 试用期 → 新手指引
 *
 * 失败只记 ERROR 日志，不回滚开通事务。
 */
@Service
public class TrialInviteService {

    private static final Logger log = LoggerFactory.getLogger(TrialInviteService.class);

    /** 登录入口（主站登录页） */
    private static final String LOGIN_URL = "http://localhost:8765/portal.html";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");

    /**
     * 对外产品目录 — 主人 2026-09-02 拍板：邮件严禁出现 GEOM/MPDM/AIDD/D-Info 等开发代号。
     * key = product 表 code；value = [0]对外正式名 [1]一句话介绍(≤2句) [2]新手指引一句话
     * 未来新增产品只需在此加一行。
     */
    private static final Map<String, String[]> PRODUCT_CATALOG = Map.of(
            "GEO", new String[]{
                    "智能 GEO 监测平台",
                    "帮助您监测品牌与关键词在各大 AI 平台回答中的表现，提升品牌监控的准确度，并为您提供关键词优化方向与可落地的优化建议。",
                    "添加您的品牌关键词并发起一次监测任务，几分钟后即可查看品牌在各 AI 平台的可见度报告与优化建议。"},
            "HPD", new String[]{
                    "智能医院潜力预测平台",
                    "基于医院数据模型预测医院销售潜力，帮助您快速识别高价值医院、合理分配市场资源。",
                    "上传或选择目标医院清单，系统将自动生成医院潜力分级与预测报告。"},
            "AIDD", new String[]{
                    "AI 立项智能辅助平台",
                    "面向药物研发立项场景，提供研发方向的智能检索、数据分析与立项评估辅助，让立项决策更快、更有依据。",
                    "输入目标疾病领域或分子方向，即可获得相关研发情报与立项参考分析。"},
            "DINFO", new String[]{
                    "智能信息填报系统",
                    "帮助您高效完成各类繁杂的信息填报工作，支持智能填写与多任务管理，大幅减少重复劳动。",
                    "创建一个填报任务，系统将辅助您智能完成表格内容的填写与汇总导出。"},
            "PORM", new String[]{
                    "智能协作平台",
                    "为团队提供透明可追溯的协作中枢，工单、审批与任务进展全程可见，让协作更高效。",
                    "创建一张工单或发起一次审批，即可体验全流程可追溯的协作管理。"}
    );

    @Autowired
    private NotifyEmailProperties emailProps;

    /** mail starter 在 classpath 时注入；否则保持 null（降级为记日志） */
    @Autowired(required = false)
    private JavaMailSender mailSender;

    /**
     * 异步发送试用邀请邮件
     *
     * @param company         已开通的公司（licenseEnd 即试用截止）
     * @param admin           公司管理员
     * @param rawPassword     初始密码明文（仅创建时存在这一次发送机会）
     * @param trialDays       试用天数
     * @param productCodes    实际开通的产品 code 列表（如 GEO / HPD / AIDD / DINFO），介绍按此动态生成
     */
    @Async
    public void sendTrialInviteAsync(Company company, SysUser admin, String rawPassword,
                                     int trialDays, List<String> productCodes) {
        String to = admin.getEmail();
        if (to == null || to.isBlank() || !to.contains("@")) {
            log.warn("📧 试用邀请邮件跳过：管理员 {} 无有效邮箱", admin.getUsername());
            return;
        }

        String subject = buildSubject(productCodes, trialDays);
        String html = buildInviteHtml(company, admin, rawPassword, trialDays, productCodes);

        if (mailSender == null || !emailProps.isEnabled()) {
            log.warn("📧 [DEV 模式] 试用邀请邮件未真实发送（enabled=false）。收件: {}, 主题: {}", to, subject);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(emailProps.getFrom() != null && !emailProps.getFrom().isBlank()
                    ? emailProps.getFrom() : emailProps.getUsername());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("✅ 试用邀请邮件发送成功：to={}, company={}, admin={}, products={}",
                    to, company.getCode(), admin.getUsername(), productCodes);
        } catch (Exception e) {
            log.error("❌ 试用邀请邮件发送失败：to={}, company={}, error={}", to, company.getCode(), e.getMessage(), e);
        }
    }

    private String buildSubject(List<String> productCodes, int trialDays) {
        String products = formalProductNames(productCodes);
        return String.format("【凌瑶智数】您的试用账号已开通 — %s（%d 天试用）", products, trialDays);
    }

    private String buildInviteHtml(Company company, SysUser admin, String rawPassword,
                                   int trialDays, List<String> productCodes) {
        LocalDateTime end = company.getLicenseEnd();
        String endStr = end == null ? "开通起 " + trialDays + " 天" : end.format(DATE_FMT);
        String displayName = admin.getDisplayName() != null && !admin.getDisplayName().isBlank()
                ? admin.getDisplayName() : admin.getUsername();

        // ── 产品介绍区：开通哪个写哪个（正式名 + ≤2 句话介绍），朴素排版 ──
        StringBuilder productSection = new StringBuilder();
        for (String code : productCodes) {
            String[] p = PRODUCT_CATALOG.get(code);
            if (p == null) {
                // 未收录的产品：不出现代号，用通用描述兜底
                productSection.append("""
                        <p style="font-size:14px;line-height:1.8;margin:0 0 10px;">• <strong>平台功能模块</strong> — 一个为您业务提效的专业功能模块，登录后即可体验。</p>
                        """);
                continue;
            }
            productSection.append("""
                    <p style="font-size:14px;line-height:1.8;margin:0 0 10px;">• <strong>%s</strong> — %s</p>
                    """.formatted(esc(p[0]), esc(p[1])));
        }

        // ── 新手指引区：多产品列"进入各产品"一步；单产品写该产品的专属首步 ──
        String guideStep2;
        if (productCodes.size() == 1) {
            String[] p = PRODUCT_CATALOG.get(productCodes.get(0));
            String how = (p != null) ? p[2] : "登录后即可开始使用该功能模块。";
            guideStep2 = """
                    <tr>
                      <td style="vertical-align:top;padding:6px 0;"><span style="display:inline-block;width:24px;height:24px;line-height:24px;text-align:center;background:#16325C;color:#fff;border-radius:4px;font-size:12px;">2</span></td>
                      <td style="padding:6px 0;"><strong>开始使用</strong> — %s</td>
                    </tr>
                    """.formatted(esc(how));
        } else {
            String names = formalProductNames(productCodes);
            guideStep2 = """
                    <tr>
                      <td style="vertical-align:top;padding:6px 0;"><span style="display:inline-block;width:24px;height:24px;line-height:24px;text-align:center;background:#16325C;color:#fff;border-radius:4px;font-size:12px;">2</span></td>
                      <td style="padding:6px 0;"><strong>进入产品</strong> — 登录后在工作台点击对应产品卡片（%s），即可进入使用</td>
                    </tr>
                    """.formatted(esc(names));
        }

        return """
                <div style="max-width:640px;margin:0 auto;font-family:'PingFang SC','Microsoft YaHei',sans-serif;color:#1F2937;">
                  <!-- 头部 -->
                  <div style="background:#16325C;border-radius:8px 8px 0 0;padding:24px 40px;text-align:center;">
                    <h1 style="color:#FFFFFF;font-size:20px;margin:0;letter-spacing:2px;">凌瑶智数</h1>
                  </div>

                  <!-- 正文 -->
                  <div style="border:1px solid #E5E7EB;border-top:none;border-radius:0 0 8px 8px;padding:32px 40px;background:#FFFFFF;">

                    <h2 style="font-size:17px;color:#0A1F3D;margin:0 0 16px;">您好，%s：</h2>

                    <p style="font-size:14px;line-height:1.8;margin:0 0 20px;">
                      您的试用账号已开通。我们已为 <strong>%s</strong> 创建平台账号，本期试用的产品如下：
                    </p>

                    <!-- 产品介绍：开通哪个写哪个 -->
                    <div style="background:#F7F9FC;border-radius:6px;padding:16px 20px;margin:0 0 24px;">
                      %s
                    </div>

                    <!-- 登录信息 -->
                    <p style="font-size:14px;font-weight:bold;color:#0A1F3D;margin:0 0 10px;">登录信息</p>
                    <table style="width:100%%;border-collapse:collapse;font-size:13.5px;margin:0 0 20px;">
                      <tr>
                        <td style="padding:9px 14px;background:#F9FAFB;border:1px solid #E5E7EB;width:100px;">登录网址</td>
                        <td style="padding:9px 14px;border:1px solid #E5E7EB;"><a href="%s" style="color:#16325C;">%s</a></td>
                      </tr>
                      <tr>
                        <td style="padding:9px 14px;background:#F9FAFB;border:1px solid #E5E7EB;">用户名</td>
                        <td style="padding:9px 14px;border:1px solid #E5E7EB;font-family:Monaco,monospace;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding:9px 14px;background:#F9FAFB;border:1px solid #E5E7EB;">初始密码</td>
                        <td style="padding:9px 14px;border:1px solid #E5E7EB;font-family:Monaco,monospace;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding:9px 14px;background:#F9FAFB;border:1px solid #E5E7EB;">试用期</td>
                        <td style="padding:9px 14px;border:1px solid #E5E7EB;">%d 天（至 %s 截止）</td>
                      </tr>
                    </table>

                    <p style="font-size:12.5px;color:#6B7280;margin:0 0 24px;">
                      出于安全考虑，初始密码仅在本邮件中发送一次，请登录后及时修改，并妥善保管本邮件。
                    </p>

                    <!-- 新手指引 -->
                    <p style="font-size:14px;font-weight:bold;color:#0A1F3D;margin:0 0 10px;">使用指引</p>
                    <table style="width:100%%;font-size:13.5px;line-height:1.7;margin:0 0 28px;border-collapse:collapse;">
                      <tr>
                        <td style="width:36px;vertical-align:top;padding:6px 0;"><span style="display:inline-block;width:24px;height:24px;line-height:24px;text-align:center;background:#16325C;color:#fff;border-radius:4px;font-size:12px;">1</span></td>
                        <td style="padding:6px 0;"><strong>登录平台</strong> — 访问上方登录网址，输入用户名和初始密码</td>
                      </tr>
                      %s
                      <tr>
                        <td style="vertical-align:top;padding:6px 0;"><span style="display:inline-block;width:24px;height:24px;line-height:24px;text-align:center;background:#16325C;color:#fff;border-radius:4px;font-size:12px;">3</span></td>
                        <td style="padding:6px 0;"><strong>获得支持</strong> — 使用过程中如有任何疑问，可直接回复本邮件联系我们</td>
                      </tr>
                    </table>

                    <div style="text-align:center;margin:0 0 20px;">
                      <a href="%s" style="display:inline-block;background:#16325C;color:#FFFFFF;text-decoration:none;font-size:14px;padding:11px 40px;border-radius:6px;">立即登录</a>
                    </div>

                    <p style="font-size:13px;line-height:1.8;color:#374151;margin:0 0 6px;">祝使用顺利！</p>
                    <p style="font-size:13px;color:#374151;margin:0;">凌瑶智数 团队</p>
                  </div>

                  <!-- 页脚 -->
                  <p style="font-size:11px;color:#9CA3AF;text-align:center;margin:14px 0 0;">
                    本邮件由凌瑶智数平台自动发送 · 试用期至 %s
                  </p>
                </div>
                """.formatted(
                esc(displayName),
                esc(company.getName()),
                productSection.toString(),
                LOGIN_URL, LOGIN_URL,
                esc(admin.getUsername()),
                esc(rawPassword),
                trialDays, endStr,
                guideStep2,
                LOGIN_URL,
                endStr);
    }

    /** 把产品 code 列表转成对外正式名（顿号连接）；未收录的跳过代号 */
    private String formalProductNames(List<String> productCodes) {
        StringBuilder sb = new StringBuilder();
        for (String code : productCodes) {
            String[] p = PRODUCT_CATALOG.get(code);
            if (p == null) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(p[0]);
        }
        return sb.length() > 0 ? sb.toString() : "平台功能模块";
    }

    /** 简单 HTML 转义，防公司名/用户名含特殊字符破坏排版 */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
