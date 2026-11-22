package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.InboundDto;
import com.admin.common.dto.InboundUserDto;
import com.admin.common.lang.R;
import com.admin.service.InboundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 * 协议入站前端控制器(合体面板:协议 + 限速)
 * </p>
 *
 * @author QAQ
 * @since 2026-07-19
 */
@RestController
@RequestMapping("/api/v1/inbound")
@CrossOrigin
public class InboundController extends BaseController {

    @Autowired
    private InboundService inboundService;

    @javax.annotation.Resource
    private com.admin.mapper.UserMapper userMapper;

    @javax.annotation.Resource
    private com.admin.mapper.InboundUserMapper inboundUserMapper;

    private boolean canManageNode(Long nodeId) {
        return com.admin.common.utils.NodePermissionHelper.canManageNode(userMapper, nodeId);
    }

    private Long nodeIdOfInbound(Long inboundId) {
        if (inboundId == null) return null;
        com.admin.entity.Inbound in = inboundService.getById(inboundId);
        return in == null ? null : in.getNodeId();
    }

    private Long nodeIdOfInboundUser(Long inboundUserId) {
        if (inboundUserId == null) return null;
        com.admin.entity.InboundUser iu = inboundUserMapper.selectById(inboundUserId);
        return iu == null ? null : nodeIdOfInbound(iu.getInboundId());
    }

    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/create")
    public R create(@Validated @RequestBody InboundDto dto) {
        if (!canManageNode(dto.getNodeId())) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.createInbound(dto);
    }

    /** 一键添加:在指定节点上把所有支持的协议一键全建出来(像 s-ui 的一键添加) */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/one-click")
    public R oneClick(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.oneClickCreate(
                nodeId,
                body.get("sni") == null ? null : String.valueOf(body.get("sni")));
    }

    /** 一键搭中转:在前置机上建全套协议,流量经内联粘贴的落地出网 */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/one-click-relay")
    public R oneClickRelay(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.oneClickRelay(
                nodeId,
                String.valueOf(body.get("link")),
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                body.get("sni") == null ? null : String.valueOf(body.get("sni")));
    }

    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/list")
    public R list() {
        R res = inboundService.getInbounds();
        if (res.getCode() != 0 || com.admin.common.utils.NodePermissionHelper.isSuperAdmin()) {
            return res;
        }
        java.util.Set<Long> allowed = com.admin.common.utils.NodePermissionHelper.getCurrentManagedNodeIds(userMapper);
        if (res.getData() instanceof java.util.List<?>) {
            java.util.List<Object> filtered = new java.util.ArrayList<>();
            for (Object item : (java.util.List<?>) res.getData()) {
                Long nid = null;
                if (item instanceof com.admin.entity.Inbound) {
                    nid = ((com.admin.entity.Inbound) item).getNodeId();
                } else if (item instanceof Map<?, ?>) {
                    Object nObj = ((Map<?, ?>) item).get("nodeId");
                    if (nObj != null) nid = Long.valueOf(String.valueOf(nObj));
                }
                if (nid != null && allowed.contains(nid)) {
                    filtered.add(item);
                }
            }
            return R.ok(filtered);
        }
        return res;
    }

    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> body) {
        Long id = asLong(body.get("id"));
        if (!canManageNode(nodeIdOfInbound(id))) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.deleteInbound(id);
    }

    /** 清空某节点上目标组的协议(relay=true 清某落地的中转;否则清直连) */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/delete-by-node")
    public R deleteByNode(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        Boolean relay = body.get("relay") != null && Boolean.parseBoolean(String.valueOf(body.get("relay")));
        Long landingId = body.get("landingId") == null ? null : Long.valueOf(String.valueOf(body.get("landingId")));
        return inboundService.deleteInboundsByNode(nodeId, relay, landingId);
    }

    /**
     * 把某台机器的 sing-box 配置按数据库重新下发一遍。
     *
     * 【为什么需要手动这一下】pushNodeSingbox 只在建/删入站、改落地时才跑,
     * 平时没人碰。可节点那头的 sing-box 是会掉的 —— 进程崩了、机器重启没起来、
     * 配置被手工动过,库里有这条入站,机器上却没在监听。
     * 这时转发诊断报「所有TCP连接尝试都失败」,而管理员在面板上无事可做:
     * 以前只能在那台机器上随便建一条协议再删掉,去蹭一次重推。
     * 下发的内容就是库里的全量(SetSingboxConfig 整份覆盖),所以重复点没有副作用。
     */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/push-config")
    public R pushConfig(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.pushNodeConfig(nodeId);
    }

    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/assign")
    public R assign(@Validated @RequestBody InboundUserDto dto) {
        if (!canManageNode(nodeIdOfInbound(dto.getInboundId()))) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.assignUser(dto);
    }

    /** 一键给车友分配全部协议 → 出订阅链接 */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/assign-all")
    public R assignAll(@RequestBody InboundUserDto dto) {
        if (!canManageNode(dto.getNodeId())) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.assignAllToUser(dto);
    }

    /**
     * 「我自己用」:把这台机器(或这条中转)的协议开给【当前登录的管理员】自己。
     * 自己用不需要限速/限流量/限到期,所以这三项一律不设;省掉"先建车友再分配"的麻烦。
     */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/assign-self")
    public R assignSelf(@RequestBody InboundUserDto dto) {
        if (!canManageNode(dto.getNodeId())) {
            return R.err(403, "无权管理该节点");
        }
        Integer uid = com.admin.common.utils.JwtUtil.getUserIdFromToken();
        if (uid == null) {
            return R.err("未登录");
        }
        dto.setUserId(uid.longValue());
        dto.setSpeedId(null);
        dto.setExpTime(null);
        dto.setFlow(null);
        return inboundService.assignAllToUser(dto);
    }

    /** 取某车友的订阅 token(兼容旧接口) */
    @RequireRole
    @PostMapping("/user-sub")
    public R userSub(@RequestBody Map<String, Object> body) {
        return R.ok(inboundService.getUserSubToken(Long.valueOf(String.valueOf(body.get("userId")))));
    }

    /** 取某车友的所有订阅线路(车友×机器,直连/中转各一条) */
    @RequireRole
    @PostMapping("/user-lines")
    public R userLines(@RequestBody Map<String, Object> body) {
        return inboundService.getUserLines(Long.valueOf(String.valueOf(body.get("userId"))));
    }

    /** 车友自助:取【我自己】的订阅线路(不需要管理员权限,只能看自己的) */
    @PostMapping("/my-lines")
    public R myLines() {
        Integer uid = com.admin.common.utils.JwtUtil.getUserIdFromToken();
        if (uid == null) {
            return R.err("未登录");
        }
        return inboundService.getUserLines(uid.longValue(), true);
    }

    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/unassign")
    public R unassign(@RequestBody Map<String, Object> body) {
        Long id = asLong(body.get("id"));
        if (!canManageNode(nodeIdOfInboundUser(id))) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.unassignUser(id);
    }

    /** 重置订阅 Token（单条线路或聚合订阅） */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/reset-sub-token")
    public R resetSubToken(@RequestBody Map<String, Object> body) {
        boolean resetAll = body.get("resetAll") != null && Boolean.parseBoolean(String.valueOf(body.get("resetAll")));
        if (resetAll) {
            if (!com.admin.common.utils.NodePermissionHelper.isSuperAdmin()) {
                return R.err(403, "仅超级管理员可重置聚合订阅链接");
            }
            return inboundService.resetSubToken(asLong(body.get("userId")), null, null, true);
        }
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.resetSubToken(asLong(body.get("userId")), nodeId, asLong(body.get("landingId")), false);
    }

    /** 重置指定 SOCKS5 分配记录的账号密码并立即重推节点配置 */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/reset-socks-password")
    public R resetSocksPassword(@RequestBody Map<String, Object> body) {
        Long id = asLong(body.get("id"));
        if (!canManageNode(nodeIdOfInboundUser(id))) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.resetSocksPassword(id);
    }

    /** 测试指定 SOCKS5 分配记录的连通性（普通用户可测自己的，管理员可测其有权管理的） */
    @PostMapping("/test-socks")
    public R testSocks(@RequestBody Map<String, Object> body) {
        Integer uid = com.admin.common.utils.JwtUtil.getUserIdFromToken();
        if (uid == null) {
            return R.err(401, "未登录");
        }
        boolean isAdmin = com.admin.common.utils.NodePermissionHelper.isSuperAdmin();
        return inboundService.testSocksNode(asLong(body.get("id")), uid.longValue(), isAdmin);
    }

    /** 查询某节点线路（nodeId × landingId）下所有已分配的用户、订阅与 SOCKS5 信息 */
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/node-users")
    public R nodeUsers(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.getNodeUsers(nodeId, asLong(body.get("landingId")));
    }

    /**
     * 停用 / 恢复某车友的一条线路。
     * landingId 允许缺省或为 null —— 那表示这台机器的直连线路。
     */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/line-status")
    public R lineStatus(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.setLineStatus(
                asLong(body.get("userId")),
                nodeId,
                asLong(body.get("landingId")),
                body.get("status") == null ? null : Integer.valueOf(String.valueOf(body.get("status"))));
    }

    /**
     * 改一条线路的额度 / 到期 / 限速(续费)。字段传 null = 该项不改。
     * 分配之后原来只能停用或取消,想加流量/续期得删了重分 —— 而重分会换
     * UUID 和端口,车友手上的订阅当场作废。这个接口就是来堵这个坑的。
     */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/line-update")
    public R lineUpdate(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.updateLine(
                asLong(body.get("userId")),
                nodeId,
                asLong(body.get("landingId")),
                asLong(body.get("flow")),
                asLong(body.get("expTime")),
                body.get("speedId") == null ? null : Integer.valueOf(String.valueOf(body.get("speedId"))));
    }

    /** 彻底收回某车友的一条线路(不可逆) */
    @LogAnnotation
    @RequireRole(allowNodeAdmin = true)
    @PostMapping("/line-delete")
    public R lineDelete(@RequestBody Map<String, Object> body) {
        Long nodeId = asLong(body.get("nodeId"));
        if (!canManageNode(nodeId)) {
            return R.err(403, "无权管理该节点");
        }
        return inboundService.deleteLine(asLong(body.get("userId")), nodeId,
                asLong(body.get("landingId")));
    }

    /** 给协议改显示名(写 Inbound.remark,车友订阅里的节点名随之变干净) */
    @LogAnnotation
    @RequireRole
    @PostMapping("/rename")
    public R rename(@RequestBody Map<String, Object> body) {
        return inboundService.renameInbound(asLong(body.get("id")),
                body.get("remark") == null ? "" : String.valueOf(body.get("remark")));
    }

    /** 某车友的节点名(线路别名 / 协议名 / 转发名,只进他自己的订阅)+ 订阅里实际显示成什么 */
    @RequireRole
    @PostMapping("/user-names")
    public R userNames(@RequestBody Map<String, Object> body) {
        Long userId;
        try {
            userId = asLong(body.get("userId"));
        } catch (NumberFormatException e) {
            return R.err("车友编号不对");
        }
        return inboundService.getUserNodeNames(userId);
    }

    /** 批量保存某车友的节点名:items[{scope, nodeId, landingId | inboundId | forwardId, name}],name 空 = 清掉 */
    @LogAnnotation
    @RequireRole
    @PostMapping("/user-names-save")
    @SuppressWarnings("unchecked")
    public R userNamesSave(@RequestBody Map<String, Object> body) {
        Object items = body.get("items");
        if (!(items instanceof java.util.List)) {
            return R.err("参数不全");
        }
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Object o : (java.util.List<Object>) items) {
            if (o instanceof Map) {
                list.add((Map<String, Object>) o);
            }
        }
        Long userId;
        try {
            userId = asLong(body.get("userId"));
        } catch (NumberFormatException e) {
            return R.err("车友编号不对");
        }
        return inboundService.saveUserNodeNames(userId, list);
    }

    /** null / 空串都当没传。直连线路的 landingId 本来就是空的,不能当成参数错误。 */
    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equals(s)) {
            return null;
        }
        return Long.valueOf(s);
    }
}
