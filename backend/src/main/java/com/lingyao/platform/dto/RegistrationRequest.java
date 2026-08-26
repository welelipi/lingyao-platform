package com.lingyao.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 报名请求 DTO — Bug-04/06/07/08 修复
 * 全部字段加 @NotBlank / @Size / @Email / @Pattern 校验
 */
public class RegistrationRequest {

    @NotBlank(message = "姓名不能为空")
    @Size(min = 2, max = 50, message = "姓名长度 2-50")
    @Pattern(regexp = "^[^<>\\u0000-\\u001F]{2,50}$", message = "姓名包含非法字符")
    private String name;

    @NotBlank(message = "公司名称不能为空")
    @Size(max = 100, message = "公司名称最长 100")
    @Pattern(regexp = "^[^<>\\u0000-\\u001F]{1,100}$", message = "公司名称包含非法字符")
    private String company;

    @Size(max = 50, message = "职位最长 50")
    private String position;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式错误")
    private String phone;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式错误")
    @Size(max = 100, message = "邮箱最长 100")
    private String email;

    @NotNull(message = "请选择感兴趣的产品")
    @Size(min = 1, max = 4, message = "请选择 1-4 个产品")
    private List<String> interestedProducts;

    @Size(max = 50)
    private String companySize;

    @Size(max = 200)
    private String source;

    @Size(max = 1000, message = "留言最长 1000 字")
    private String message;  // XSS 转义在 Service 层用 SanitizeUtil 做

    // Getters & Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company == null ? null : company.trim(); }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position == null ? null : position.trim(); }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone == null ? null : phone.trim(); }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null ? null : email.trim().toLowerCase(); }
    public List<String> getInterestedProducts() { return interestedProducts; }
    public void setInterestedProducts(List<String> interestedProducts) { this.interestedProducts = interestedProducts; }
    public String getCompanySize() { return companySize; }
    public void setCompanySize(String companySize) { this.companySize = companySize; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? null : message.trim(); }
}
