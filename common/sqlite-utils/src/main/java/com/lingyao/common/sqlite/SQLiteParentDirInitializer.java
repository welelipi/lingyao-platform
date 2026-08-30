package com.lingyao.common.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SQLite 父目录自动创建工具（凌瑶空间通用 · v1.0 · 2026-08-30）
 *
 * <p>背景：SQLite JDBC 不会自动创建父目录（H2 会），启动会报 "path to '...': '...' does not exist"。
 * 这是 SQLite + Spring Boot 项目的通用痛点，凌瑶空间所有 Java + SQLite 模块（POR-M 明枢、
 * 未来 GEOM Java 版、未来其他模块）都需要这个工具类。
 *
 * <p>使用方式（在 Spring Boot 项目的 application-{profile}.yml 加 SQLite 配置即可）：
 * <pre>
 * spring:
 *   datasource:
 *     url: jdbc:sqlite:${user.dir}/sqlite-data/mingshu.db?journal_mode=WAL&busy_timeout=5000
 *     driver-class-name: org.sqlite.JDBC
 * </pre>
 *
 * <p>本类会通过 spring.factories 自动注册，BeanFactoryPostProcessor 是 Spring 最早可触发阶段。
 *
 * <p>踩坑历史：8 阶段迁移（P0-1 POR-M 实战）
 * <ul>
 *   <li>@Configuration 构造器 → 不触发（lazy bean）
 *   <li>@Component + ApplicationListener → 不触发（lazy listener）
 *   <li>spring.factories → 唯一可行
 * </ul>
 *
 * @author 凌瑶智数 · AI 工具层
 * @since 1.0.0
 */
@Component
public class SQLiteParentDirInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(SQLiteParentDirInitializer.class);

    /**
     * SQLite JDBC URL 前缀
     */
    public static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";

    /**
     * Environment 自动注入（在 BeanFactoryPostProcessor 阶段可用）
     */
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (environment == null) {
            log.debug("Environment not yet set, skip SQLite parent dir setup");
            return;
        }

        String url = environment.getProperty("spring.datasource.url");
        if (url == null) {
            log.debug("spring.datasource.url not configured, skip SQLite parent dir setup");
            return;
        }

        if (!url.startsWith(SQLITE_URL_PREFIX)) {
            log.debug("Not a SQLite datasource ({}), skip", SQLITE_URL_PREFIX);
            return;
        }

        Path parentDir = extractParentDir(url);
        if (parentDir == null) {
            log.warn("Cannot extract SQLite parent dir from url: {}", url);
            return;
        }

        if (Files.exists(parentDir)) {
            log.info("SQLite parent dir already exists: {}", parentDir.toAbsolutePath());
            return;
        }

        try {
            Files.createDirectories(parentDir);
            log.info("✅ Created SQLite parent dir: {}", parentDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("❌ Failed to create SQLite parent dir: {}", parentDir, e);
            throw new RuntimeException(
                "Cannot create SQLite parent dir: " + parentDir + " (url=" + url + ")", e);
        }
    }

    /**
     * 从 jdbc:sqlite:URL 中解析出数据库文件的父目录路径
     *
     * <p>支持格式：
     * <ul>
     *   <li>jdbc:sqlite:/abs/path/db.db
     *   <li>jdbc:sqlite:relative/path/db.db
     *   <li>jdbc:sqlite:${user.dir}/sqlite-data/db.db?journal_mode=WAL&busy_timeout=5000
     * </ul>
     *
     * @param url 完整 JDBC URL
     * @return 父目录 Path（无父目录返回 null，如 ":memory:"）
     */
    static Path extractParentDir(String url) {
        if (url == null || !url.startsWith(SQLITE_URL_PREFIX)) {
            return null;
        }

        // 去掉 jdbc:sqlite: 前缀
        String remainder = url.substring(SQLITE_URL_PREFIX.length());

        // 去掉 ? 后面的查询参数
        int queryIdx = remainder.indexOf('?');
        if (queryIdx >= 0) {
            remainder = remainder.substring(0, queryIdx);
        }

        if (remainder.isEmpty()) {
            return null;
        }

        // 特殊处理 :memory:
        if (":memory:".equals(remainder) || remainder.startsWith(":memory:")) {
            return null;
        }

        Path dbPath = Paths.get(remainder);
        Path parent = dbPath.getParent();
        return parent;
    }
}
