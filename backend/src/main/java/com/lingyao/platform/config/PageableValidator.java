package com.lingyao.platform.config;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 分页参数校验工具 — Bug-14 修复
 * page >= 0, size 1~100
 */
public final class PageableValidator {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageableValidator() {}

    public static Pageable safeOf(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize);
    }

    public static Pageable safeOf(int page, int size, Sort sort) {
        int safePage = Math.max(0, page);
        int safeSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }
}
