package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/**
 * 管理员开关车友的 Quantumult X 订阅权限。
 */
@Data
public class QuantumultXPermissionDto {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    private Boolean enabled;
}
