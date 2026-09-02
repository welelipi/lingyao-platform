package com.lingyao.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 报名邮件推送 SMTP 配置（V2.0.27 Bug-21 引入）
 *
 * application.yml:
 *   lingyao:
 *     notify:
 *       email:
 *         enabled: false          # true 时发真邮件
 *         host: smtp.qq.com
 *         port: 465
 *         username: ...
 *         password: ...
 *         from: ...
 *         ssl: true
 *
 * enabled=false（默认，dev 模式）：推送只记 WARN 日志，不抛错，不影响报名主流程。
 *
 * @since V2.0.27 2026-09-02
 */
@Component
@ConfigurationProperties(prefix = "lingyao.notify.email")
public class NotifyEmailProperties {

    /** 是否启用真实 SMTP 发送（false = dev 模式仅记日志） */
    private boolean enabled = false;

    private String host = "smtp.qq.com";
    private int port = 465;
    private String username = "";
    private String password = "";
    private String from = "";
    private boolean ssl = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public boolean isSsl() { return ssl; }
    public void setSsl(boolean ssl) { this.ssl = ssl; }
}
