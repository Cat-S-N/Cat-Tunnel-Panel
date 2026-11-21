import { useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import toast from "react-hot-toast";
import { copyTextToClipboard } from "@/utils/clipboard";
import { testSocksNode } from "@/api";

export interface SocksNodeItem {
  inboundUserId?: number;
  inboundId: number;
  nodeId: number;
  nodeName: string;
  landingId?: number | null;
  type?: string;
  landingName?: string | null;
  remark?: string | null;
  host: string;
  port: number;
  username: string;
  password: string;
  colonFormat: string;
  link: string;
  lineStatus?: number;
}

/**
 * SOCKS5 代理单独展示区（不加入通用订阅与 Clash 订阅）。
 * 提供 IP:端口:账号:密码 与 socks5:// 链接复制、一键连通性测试、重置密码及收回。
 */
export function SocksNodesSection({
  nodes,
  onRemove,
  onResetPassword,
}: {
  nodes: SocksNodeItem[];
  onRemove?: (item: SocksNodeItem) => void;
  onResetPassword?: (item: SocksNodeItem) => void;
}) {
  const [testingId, setTestingId] = useState<number | null>(null);
  const [testResults, setTestResults] = useState<Record<number, { ok: boolean; latencyMs?: number; exitIp?: string; msg?: string }>>({});

  if (!nodes || nodes.length === 0) return null;

  const handleTest = async (item: SocksNodeItem) => {
    if (item.inboundUserId == null) return;
    setTestingId(item.inboundUserId);
    try {
      const res = await testSocksNode(item.inboundUserId);
      if (res.code === 0 && res.data) {
        setTestResults((prev) => ({ ...prev, [item.inboundUserId!]: res.data }));
        if (res.data.ok) {
          toast.success(`连通成功 · ${res.data.latencyMs ?? 0}ms${res.data.exitIp ? ` · 出口 ${res.data.exitIp}` : ""}`);
        } else {
          toast.error(`测试失败: ${res.data.msg || "无法连接"}`);
        }
      } else {
        toast.error(res.msg || "测试失败");
      }
    } catch {
      toast.error("测试请求失败");
    } finally {
      setTestingId(null);
    }
  };

  return (
    <div className="border border-secondary/40 bg-secondary/5 rounded-lg p-3 space-y-3">
      <div className="flex items-center gap-2 flex-wrap">
        <Chip size="sm" color="secondary" variant="flat">
          SOCKS5 独立代理
        </Chip>
        <span className="text-xs text-default-500">
          不加入上方订阅链接，单独复制配置给指纹浏览器 / 业务工具 / 客户端使用
        </span>
      </div>

      <div className="space-y-2.5">
        {nodes.map((item) => {
          const isRelay = item.type === "relay";
          const stopped = item.lineStatus === 0;
          const tr = item.inboundUserId != null ? testResults[item.inboundUserId] : undefined;
          return (
            <div
              key={`${item.inboundId}-${item.port}`}
              className={`border border-default-200 bg-background/80 rounded-lg p-3 space-y-2 ${stopped ? "opacity-70" : ""}`}
            >
              <div className="flex items-center gap-2 flex-wrap">
                <Chip size="sm" variant="flat" color={isRelay ? "warning" : "primary"}>
                  {isRelay ? `🔀 中转${item.landingName ? "→" + item.landingName : ""}` : "🖥️ 直连"}
                </Chip>
                <span className="font-medium text-sm truncate">{item.nodeName}</span>
                {item.remark && (
                  <Chip size="sm" variant="flat">{item.remark}</Chip>
                )}
                {stopped && (
                  <Chip size="sm" variant="flat" color="danger">已停用</Chip>
                )}
                {tr && (
                  <Chip size="sm" variant="flat" color={tr.ok ? "success" : "danger"} className="ml-auto">
                    {tr.ok
                      ? `✓ ${tr.latencyMs ?? 0}ms${tr.exitIp ? ` · ${tr.exitIp}` : ""}`
                      : `✕ ${tr.msg || "不通"}`}
                  </Chip>
                )}
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 text-xs text-default-600">
                <div className="truncate">
                  <span className="text-default-400">地址: </span>
                  <span className="font-mono">{item.host}:{item.port}</span>
                </div>
                <div className="truncate">
                  <span className="text-default-400">账号: </span>
                  <span className="font-mono">{item.username}</span>
                </div>
                <div className="truncate">
                  <span className="text-default-400">密码: </span>
                  <span className="font-mono">{item.password}</span>
                </div>
              </div>

              <Input
                readOnly
                size="sm"
                value={item.colonFormat}
                onClick={(e: any) => {
                  e.target?.select?.();
                }}
              />

              <div className="flex flex-wrap gap-2 items-center">
                <Button
                  size="sm"
                  color="secondary"
                  variant="flat"
                  onPress={async () => {
                    (await copyTextToClipboard(item.colonFormat))
                      ? toast.success("已复制 IP:端口:账号:密码")
                      : toast.error("复制失败");
                  }}
                >
                  复制 IP:端口:账号:密码
                </Button>
                <Button
                  size="sm"
                  variant="flat"
                  onPress={async () => {
                    (await copyTextToClipboard(item.link))
                      ? toast.success("已复制 socks5:// 链接")
                      : toast.error("复制失败");
                  }}
                >
                  复制 socks5:// 链接
                </Button>
                {item.inboundUserId != null && (
                  <Button
                    size="sm"
                    color="primary"
                    variant="flat"
                    isLoading={testingId === item.inboundUserId}
                    onPress={() => handleTest(item)}
                  >
                    ⚡ 测试
                  </Button>
                )}
                <div className="flex-1" />
                {onResetPassword && item.inboundUserId != null && (
                  <Button
                    size="sm"
                    color="warning"
                    variant="flat"
                    onPress={() => onResetPassword(item)}
                  >
                    重置密码
                  </Button>
                )}
                {onRemove && item.inboundUserId != null && (
                  <Button
                    size="sm"
                    variant="light"
                    color="danger"
                    onPress={() => onRemove(item)}
                  >
                    收回
                  </Button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
