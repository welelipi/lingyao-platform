package com.lingyao.platform.sso;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OAuth 用户信息封装
 *
 * 关键字段：
 * - employeeId: 主平台员工唯一 ID（飞书 user_id / 钉钉 userid / 企微 userid）
 * - providerUserId: 三方原始 ID（飞书 open_id / 钉钉 unionid / 企微 userid）
 * - deptIds: 部门 ID 列表（飞书部门 ID 是字符串列表）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SsoUserInfo {
    private String employeeId;       // 主平台员工 ID
    private String providerUserId;   // 三方原始 ID
    private String displayName;      // 姓名
    private String email;
    private String mobile;
    private List<String> deptIds;    // 部门 ID 列表
    private String avatarUrl;
}