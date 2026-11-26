package com.admin.controller;


import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.entity.User;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/user")
public class UserController extends BaseController {

    @LogAnnotation
    @RequireRole
    @PostMapping("/quantumult-x")
    public R quantumultX(@Validated @RequestBody QuantumultXPermissionDto dto) {
        if (userService.getById(dto.getId()) == null) {
            return R.err("用户不存在");
        }
        User update = new User();
        update.setId(dto.getId());
        update.setQuantumultXEnabled(Boolean.TRUE.equals(dto.getEnabled()) ? 1 : 0);
        update.setUpdatedTime(System.currentTimeMillis());
        return userService.updateById(update) ? R.ok("权限已保存") : R.err("保存失败");
    }

    @LogAnnotation
    @PostMapping("/login")
    public R login(@Validated @RequestBody LoginDto loginDto) {
        return userService.login(loginDto);
    }

    @LogAnnotation
    @PostMapping("/register")
    public R register(@Validated @RequestBody RegisterDto registerDto) {
        return userService.register(registerDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody UserDto userDto) {
        return userService.createUser(userDto);
    }


    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/list")
    public R readAll() {
        return userService.getAllUsers();
    }

    /** 当前登录用户的权限信息（前端据此渲染「协议管理 / 中转」等节点管理员菜单） */
    @PostMapping("/my-permissions")
    public R myPermissions() {
        Integer uid = com.admin.common.utils.JwtUtil.getUserIdFromToken();
        if (uid == null) {
            return R.err(401, "未登录");
        }
        boolean isAdmin = com.admin.common.utils.NodePermissionHelper.isSuperAdmin();
        User u = userService.getById(uid.longValue());
        java.util.Set<Long> managed = u == null
                ? java.util.Collections.emptySet()
                : com.admin.common.utils.NodePermissionHelper.parseNodeIds(u.getManagedNodes());
        com.alibaba.fastjson.JSONObject data = new com.alibaba.fastjson.JSONObject();
        data.put("isAdmin", isAdmin);
        data.put("isNodeAdmin", !isAdmin && !managed.isEmpty());
        data.put("managedNodeIds", com.admin.common.utils.NodePermissionHelper.toSortedList(managed));
        return R.ok(data);
    }

    /** 仅超级管理员可调用：给指定用户分配或清空可管理的节点列表 */
    @LogAnnotation
    @RequireRole
    @PostMapping("/managed-nodes")
    public R setManagedNodes(@RequestBody Map<String, Object> body) {
        if (body == null || body.get("userId") == null) {
            return R.err("缺少用户ID");
        }
        Long userId = Long.valueOf(String.valueOf(body.get("userId")));
        User target = userService.getById(userId);
        if (target == null) {
            return R.err("用户不存在");
        }
        if (Integer.valueOf(0).equals(target.getRoleId())) {
            return R.err("超级管理员默认拥有全部节点权限，无需单独分配");
        }
        java.util.List<Long> nodeIds = new java.util.ArrayList<>();
        Object raw = body.get("nodeIds");
        if (raw instanceof java.util.Collection<?>) {
            for (Object item : (java.util.Collection<?>) raw) {
                if (item != null) {
                    nodeIds.add(Long.valueOf(String.valueOf(item)));
                }
            }
        }
        User update = new User();
        update.setId(userId);
        update.setManagedNodes(com.admin.common.utils.NodePermissionHelper.joinNodeIds(nodeIds));
        update.setUpdatedTime(System.currentTimeMillis());
        return userService.updateById(update) ? R.ok("节点管理权限已保存") : R.err("保存失败");
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody UserUpdateDto userUpdateDto) {
        return userService.updateUser(userUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return userService.deleteUser(id);
    }

    @LogAnnotation
    @PostMapping("/package")
    public R getUserPackageInfo() {
        return userService.getUserPackageInfo();
    }

    @LogAnnotation
    @PostMapping("/updatePassword")
    public R updatePassword(@Validated @RequestBody ChangePasswordDto changePasswordDto) {
        return userService.updatePassword(changePasswordDto);
    }

    /**
     * 车友「在用」状态:{users:{userId: 最后有流量的毫秒}, forwards:{forwardId: 同}, now: 面板当前毫秒}。
     * 只在内存里记(面板重启后从头记)。客户端每 10~15 秒拉一次,所以请求日志只打 DEBUG。
     */
    @LogAnnotation(quiet = true)
    @RequireRole
    @PostMapping("/online-map")
    public R onlineMap() {
        return R.ok(com.admin.common.utils.OnlineTracker.snapshot(System.currentTimeMillis()));
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reset")
    public R reset(@Validated @RequestBody ResetFlowDto resetFlowDto) {
        return userService.reset(resetFlowDto);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.admin.common.utils.AuditLogStore auditLogStore;

    /** 分页查询操作审计日志（仅超级管理员可用） */
    @RequireRole
    @PostMapping("/audit-logs")
    public R auditLogs(@RequestBody(required = false) Map<String, Object> body) {
        if (auditLogStore == null) {
            return R.err("审计日志服务未初始化");
        }
        int page = 1;
        int size = 20;
        String keyword = null;
        Long userId = null;
        String status = null;
        if (body != null) {
            if (body.get("page") != null) {
                try { page = Integer.parseInt(String.valueOf(body.get("page"))); } catch (NumberFormatException ignored) {}
            }
            if (body.get("size") != null) {
                try { size = Integer.parseInt(String.valueOf(body.get("size"))); } catch (NumberFormatException ignored) {}
            }
            if (body.get("keyword") != null) {
                keyword = String.valueOf(body.get("keyword"));
            }
            if (body.get("status") != null) {
                status = String.valueOf(body.get("status"));
            }
            if (body.get("userId") != null) {
                try {
                    String s = String.valueOf(body.get("userId")).trim();
                    if (!s.isEmpty() && !"null".equals(s)) {
                        userId = Long.valueOf(s);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return R.ok(auditLogStore.query(page, size, keyword, userId, status));
    }

    /** 清空全部操作审计日志（仅超级管理员可用） */
    @LogAnnotation
    @RequireRole
    @PostMapping("/audit-logs-clear")
    public R clearAuditLogs() {
        if (auditLogStore == null) {
            return R.err("审计日志服务未初始化");
        }
        int deleted = auditLogStore.clearAll();
        return R.ok("已清空 " + deleted + " 条操作记录");
    }

}
