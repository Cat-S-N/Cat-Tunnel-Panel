package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class RegisterDto {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^\\s*[a-zA-Z0-9_-]{3,32}\\s*$", message = "用户名需为3-32位字母、数字、下划线或连字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在6-64位之间")
    private String password;

    @Size(max = 64, message = "确认密码长度不能超过64位")
    private String confirmPassword;

    @Size(max = 128, message = "邀请码长度不能超过128位")
    private String inviteCode;

    @Size(max = 128, message = "验证码标识过长")
    private String captchaId;
}
