import { useEffect, useState } from "react";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import toast from "react-hot-toast";
import {
  getNodeUsers,
  setLineStatus,
  updateLine,
  deleteLine,
  unassignInboundUser,
  resetSubToken,
  resetSocksPassword,
} from "@/api";
import { copyTextToClipboard } from "@/utils/clipboard";
import { getSubBase } from "@/utils/sub-base";
import { SubQrToggle } from "@/components/sub-qr";
import { SocksNodesSection, SocksNodeItem } from "@/components/socks-nodes-card";
import { SubAccessLogsPanel } from "@/components/sub-access-logs-panel";

const GB = 1024 * 1024 * 1024;
const fmtGB = (bytes: number) => `${((bytes || 0) / GB).toFixed(2)} GB`;
const fmtDate = (ms?: number | null) => (ms ? new Date(ms).toLocaleDateString() : "永久");

/**
 * 查看/管理某节点线路下所有已分配用户的弹窗（超级管理员和该节点的节点管理员均可用）。
 */
export function NodeAssignedUsersModal({
  isOpen,
  onClose,
  nodeId,
  landingId,
  title,
}: {
  isOpen: boolean;
  onClose: () => void;
  nodeId: number | null;
  landingId?: number | null;
  title: string;
}) {
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<any[]>([]);
  const [subBase, setSubBase] = useState<string>(window.location.origin);
  const [keyword, setKeyword] = useState<string>("");

  const load = async () => {
    if (nodeId == null) return;
    setLoading(true);
    try {
      const [res, sb] = await Promise.all([getNodeUsers(nodeId, landingId ?? null), getSubBase()]);
      setSubBase(sb);
      if (res.code === 0) {
        setItems(Array.isArray(res.data) ? res.data : []);
      } else {
        toast.error(res.msg || "加载已分用户失败");
      }
    } catch {
      toast.error("加载已分用户失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen && nodeId != null) {
      setKeyword("");
      load();
    }
  }, [isOpen, nodeId, landingId]);

  const subUrl = (token: string) => `${subBase}/api/v1/open_api/sub?token=${token}`;
  const clashUrl = (token: string) => `${subBase}/api/v1/open_api/clash?token=${token}`;

  const kw = keyword.trim().toLowerCase();
  const filtered = kw
    ? items.filter((u) => String(u.userName || "").toLowerCase().includes(kw))
    : items;

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="3xl" scrollBehavior="inside" backdrop="blur" placement="center">
      <ModalContent>
        <ModalHeader>👥 已分配用户 — {title} ({items.length})</ModalHeader>
        <ModalBody className="space-y-3">
          {items.length > 0 && (
            <Input
              size="sm"
              placeholder="搜索用户名..."
              isClearable
              value={keyword}
              onValueChange={setKeyword}
            />
          )}

          {loading ? (
            <div className="text-center text-default-400 py-8">加载中...</div>
          ) : filtered.length === 0 ? (
            <div className="text-center text-default-400 py-8">
              {items.length === 0 ? "这条线路还没有分配给任何用户" : "没有匹配的用户名"}
            </div>
          ) : (
            filtered.map((ln) => {
              const stopped = ln.lineStatus === 0;
              const socksNodes: SocksNodeItem[] = Array.isArray(ln.socksNodes) ? ln.socksNodes : [];
              return (
                <div
                  key={ln.userId}
                  className={`border border-default-200 rounded-lg p-3 space-y-2.5 ${stopped ? "opacity-70" : ""}`}
                >
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-semibold text-sm">👤 {ln.userName}</span>
                    {stopped && <Chip size="sm" color="danger" variant="flat">已停用</Chip>}
                    <span className="text-xs text-default-500">
                      流量 {fmtGB(ln.flow)} / {ln.quotaGb > 0 ? `${ln.quotaGb} GB` : "不限"} · 到期 {fmtDate(ln.lineExpTime)}
                    </span>
                    <Chip size="sm" variant="flat" className="ml-auto">{ln.protocolCount} 协议</Chip>
                  </div>

                  {ln.subToken && (
                    <>
                      <Input
                        readOnly
                        size="sm"
                        value={subUrl(ln.subToken)}
                        onClick={(e: any) => e.target?.select?.()}
                      />
                      <div className="flex flex-wrap gap-1.5 items-center">
                        <Button
                          size="sm"
                          color="primary"
                          onPress={async () => {
                            (await copyTextToClipboard(subUrl(ln.subToken)))
                              ? toast.success("已复制订阅链接")
                              : toast.error("复制失败");
                          }}
                        >
                          复制订阅
                        </Button>
                        <SubQrToggle url={subUrl(ln.subToken)} />
                        <Button
                          size="sm"
                          variant="flat"
                          onPress={async () => {
                            (await copyTextToClipboard(clashUrl(ln.subToken)))
                              ? toast.success("已复制 Clash / Mihomo 订阅")
                              : toast.error("复制失败");
                          }}
                        >
                          Clash 版
                        </Button>
                        <Button
                          size="sm"
                          color="warning"
                          variant="light"
                          onPress={async () => {
                            if (!confirm(`确定重置「${ln.userName}」在此节点的订阅链接？旧链接将立即失效。`)) return;
                            const res = await resetSubToken({ userId: ln.userId, nodeId: ln.nodeId, landingId: ln.landingId ?? null });
                            if (res.code === 0) {
                              toast.success("已重置订阅链接");
                              load();
                            } else {
                              toast.error(res.msg || "重置失败");
                            }
                          }}
                        >
                          重置链接
                        </Button>
                        <div className="flex-1" />
                        <Button
                          size="sm"
                          variant="flat"
                          onPress={async () => {
                            const fRaw = prompt(`「${ln.userName}」流量额度(G, 0=不限)`, String(ln.quotaGb ?? 0));
                            if (fRaw === null) return;
                            const flow = Number(fRaw);
                            if (!(flow >= 0)) return toast.error("流量要填数字");
                            const leftDays = ln.lineExpTime ? Math.max(0, Math.ceil((ln.lineExpTime - Date.now()) / 86400000)) : 0;
                            const dRaw = prompt("还有多少天到期(0=永久)", String(leftDays));
                            if (dRaw === null) return;
                            const days = Number(dRaw);
                            if (!(days >= 0)) return toast.error("天数要填数字");
                            const res = await updateLine(ln.userId, ln.nodeId, ln.landingId ?? null, {
                              flow,
                              expTime: days > 0 ? Date.now() + days * 86400000 : 0,
                            });
                            if (res.code === 0) {
                              toast.success("已更新配额/到期");
                              load();
                            } else {
                              toast.error(res.msg || "更新失败");
                            }
                          }}
                        >
                          续费
                        </Button>
                        <Button
                          size="sm"
                          variant="flat"
                          color={stopped ? "success" : "warning"}
                          onPress={async () => {
                            const to = stopped ? 1 : 0;
                            const res = await setLineStatus(ln.userId, ln.nodeId, ln.landingId ?? null, to);
                            if (res.code === 0) {
                              toast.success(to === 0 ? "已停用" : "已恢复");
                              load();
                            } else {
                              toast.error(res.msg || "操作失败");
                            }
                          }}
                        >
                          {stopped ? "恢复" : "停用"}
                        </Button>
                        <Button
                          size="sm"
                          variant="light"
                          color="danger"
                          onPress={async () => {
                            if (!confirm(`确定彻底收回「${ln.userName}」在此节点的全部 ${ln.protocolCount} 个协议？`)) return;
                            const res = await deleteLine(ln.userId, ln.nodeId, ln.landingId ?? null);
                            if (res.code === 0) {
                              toast.success("已收回该用户线路");
                              load();
                            } else {
                              toast.error(res.msg || "收回失败");
                            }
                          }}
                        >
                          删除
                        </Button>
                      </div>
                    </>
                  )}

                  {Array.isArray(ln.protocols) && ln.protocols.length > 0 && (
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs text-default-400">已分协议:</span>
                      {ln.protocols.map((p: any) => (
                        <Chip
                          key={p.id}
                          size="sm"
                          variant="flat"
                          color={p.protocol === "socks" ? "warning" : "secondary"}
                          onClose={async () => {
                            const label = `${p.protocol.toUpperCase()}${p.remark ? ` (${p.remark})` : ""}`;
                            if (!confirm(`确定单独收回「${ln.userName}」的 ${label} 协议？`)) return;
                            const res = await unassignInboundUser(p.id);
                            if (res.code === 0) {
                              toast.success(`已收回 ${label}`);
                              load();
                            } else {
                              toast.error(res.msg || "收回失败");
                            }
                          }}
                        >
                          {p.protocol === "socks" ? "SOCKS5" : p.protocol === "shadowsocks" ? "SS-2022" : p.protocol.toUpperCase()}
                          {p.remark ? ` (${p.remark})` : ""}
                        </Chip>
                      ))}
                    </div>
                  )}

                  {socksNodes.length > 0 && (
                    <SocksNodesSection
                      nodes={socksNodes}
                      onResetPassword={async (item) => {
                        if (item.inboundUserId == null) return;
                        if (!confirm(`确定重置「${ln.userName}」的 SOCKS5 账号密码？旧凭据将立即失效。`)) return;
                        const res = await resetSocksPassword(item.inboundUserId);
                        if (res.code === 0) {
                          toast.success("已重置 SOCKS5 账号密码");
                          load();
                        } else {
                          toast.error(res.msg || "重置失败");
                        }
                      }}
                      onRemove={async (item) => {
                        if (item.inboundUserId == null) return;
                        if (!confirm(`确定收回「${ln.userName}」的 SOCKS5 代理？`)) return;
                        const res = await unassignInboundUser(item.inboundUserId);
                        if (res.code === 0) {
                          toast.success("已收回 SOCKS5 代理");
                          load();
                        } else {
                          toast.error(res.msg || "收回失败");
                        }
                      }}
                    />
                  )}
                  <SubAccessLogsPanel userId={ln.userId} nodeId={ln.nodeId} compact />
                </div>
              );
            })
          )}
        </ModalBody>
        <ModalFooter>
          <Button variant="light" onPress={onClose}>关闭</Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
