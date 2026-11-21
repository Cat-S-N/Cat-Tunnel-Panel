package com.admin.common.utils;

import com.admin.entity.User;
import com.admin.mapper.UserMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 节点管理员权限工具：
 * - role_id == 0 为超级管理员，拥有全部节点权限；
 * - role_id != 0 且 user.managed_nodes 非空为节点管理员，仅能管理白名单中的节点。
 */
public final class NodePermissionHelper {

    private NodePermissionHelper() {
    }

    public static boolean isSuperAdmin() {
        Integer roleId = JwtUtil.getRoleIdFromToken();
        return roleId != null && roleId == 0;
    }

    public static Set<Long> parseNodeIds(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try {
                long id = Long.parseLong(s);
                if (id > 0) set.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return set;
    }

    public static String joinNodeIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        Set<Long> clean = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) clean.add(id);
        }
        return clean.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static Set<Long> getManagedNodeIds(UserMapper userMapper, Long userId) {
        if (userMapper == null || userId == null) {
            return Collections.emptySet();
        }
        User u = userMapper.selectById(userId);
        return u == null ? Collections.emptySet() : parseNodeIds(u.getManagedNodes());
    }

    public static Set<Long> getCurrentManagedNodeIds(UserMapper userMapper) {
        Integer uid = JwtUtil.getUserIdFromToken();
        if (uid == null) {
            return Collections.emptySet();
        }
        return getManagedNodeIds(userMapper, uid.longValue());
    }

    public static boolean canManageNode(UserMapper userMapper, Long nodeId) {
        if (isSuperAdmin()) {
            return true;
        }
        if (nodeId == null) {
            return false;
        }
        return getCurrentManagedNodeIds(userMapper).contains(nodeId);
    }

    public static List<Long> toSortedList(Set<Long> ids) {
        List<Long> list = new ArrayList<>(ids);
        Collections.sort(list);
        return list;
    }
}
