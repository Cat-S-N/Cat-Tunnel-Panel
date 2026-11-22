import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";
import { getSubAccessLogs } from "@/api";
import { copyTextToClipboard } from "@/utils/clipboard";

interface SubAccessLogRow {
  id: number;
  userId: number;
  nodeId: number;
  nodeName?: string;
  subType: string;
  ip: string;
  userAgent: string;
  createdTime: number;
}

const fmtDateTime = (ms: number) => {
  if (!ms) return "-";
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};

const subTypeBadge = (t: string): { label: string; color: "primary" | "secondary" | "warning" } => {
  const lower = (t || "sub").toLowerCase();
  if (lower === "clash") return { label: "Clash", color: "secondary" };
  if (lower === "quanx") return { label: "QX", color: "warning" };
  return { label: "SUB", color: "primary" };
};

type ChipColor = "default" | "primary" | "secondary" | "success" | "warning" | "danger";

const detectClientBadge = (userAgent: string): { label: string; color: ChipColor } => {
  const ua = (userAgent || "").trim();
  if (!ua) return { label: "未知客户端", color: "default" };
  const lower = ua.toLowerCase();
  if (lower.includes("clash-verge") || lower.includes("clash verge")) return { label: "Clash Verge", color: "secondary" };
  if (lower.includes("mihomo") || lower.includes("clash.meta")) return { label: "Mihomo", color: "secondary" };
  if (lower.includes("flclash")) return { label: "FlClash", color: "secondary" };
  if (lower.includes("karing")) return { label: "Karing", color: "primary" };
  if (lower.includes("shadowrocket")) return { label: "Shadowrocket", color: "primary" };
  if (lower.includes("quantumult")) return { label: "Quantumult X", color: "warning" };
  if (lower.includes("sing-box") || lower.includes("singbox")) return { label: "sing-box", color: "success" };
  if (lower.includes("v2rayng")) return { label: "v2rayNG", color: "success" };
  if (lower.includes("v2rayn")) return { label: "v2rayN", color: "success" };
  if (lower.includes("stash")) return { label: "Stash", color: "warning" };
  if (lower.includes("surge")) return { label: "Surge", color: "warning" };
  if (lower.includes("hiddify")) return { label: "Hiddify", color: "primary" };
  if (lower.includes("clash")) return { label: "Clash", color: "secondary" };
  if (lower.includes("curl/") || lower.includes("wget/")) return { label: "curl / wget", color: "default" };
  if (lower.includes("mozilla/") || lower.includes("chrome/") || lower.includes("safari/")) {
    return { label: "浏览器", color: "default" };
  }
  return { label: "其他客户端", color: "default" };
};

/**
 * 可折叠的订阅拉取记录面板（展示近 50 次订阅访问 IP / UA / 客户端类型，以及 24h 多 IP 泄露警告）。
 */
export function SubAccessLogsPanel({
  userId,
  nodeId,
  compact = false,
}: {
  userId: number;
  nodeId?: number | null;
  compact?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [pulls24h, setPulls24h] = useState(0);
  const [uniqueIps24h, setUniqueIps24h] = useState(0);
  const [logs, setLogs] = useState<SubAccessLogRow[]>([]);

  useEffect(() => {
    setOpen(false);
    setPulls24h(0);
    setUniqueIps24h(0);
    setLogs([]);
  }, [userId, nodeId]);

  const fetchLogs = async () => {
    setLoading(true);
    try {
      const res = await getSubAccessLogs(userId, nodeId ?? null);
      if (res.code === 0 && res.data) {
        setPulls24h(Number(res.data.pulls24h || 0));
        setUniqueIps24h(Number(res.data.uniqueIps24h || 0));
        setLogs(Array.isArray(res.data.logs) ? res.data.logs : []);
      } else {
        toast.error(res.msg || "获取订阅拉取记录失败");
      }
    } catch {
      toast.error("获取订阅拉取记录失败");
    } finally {
      setLoading(false);
    }
  };

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) {
      fetchLogs();
    }
  };

  const handleCopyIp = async (ip: string) => {
    if (!ip || ip === "-") return;
    const ok = await copyTextToClipboard(ip);
    if (ok) {
      toast.success(`已复制 IP: ${ip}`);
    } else {
      toast.error("复制失败");
    }
  };

  const ipStats = useMemo(() => {
    const counts = new Map<string, number>();
    for (const row of logs) {
      const ip = (row.ip || "").trim() || "-";
      counts.set(ip, (counts.get(ip) || 0) + 1);
    }
    return Array.from(counts.entries())
      .map(([ip, count]) => ({ ip, count }))
      .sort((a, b) => b.count - a.count);
  }, [logs]);

  return (
    <div className={compact ? "pt-1" : "border border-default-200 rounded-lg p-3"}>
      <div className="flex items-center justify-between gap-2">
        <button
          type="button"
          onClick={toggle}
          className="text-xs font-medium text-primary hover:underline flex items-center gap-1"
        >
          <span>{open ? "▾" : "▸"}</span>
          <span>📊 订阅拉取记录（防泄露审计）</span>
        </button>
        {open && (
          <Button size="sm" variant="light" className="h-6 min-w-12 text-xs" onPress={fetchLogs} isLoading={loading}>
            刷新
          </Button>
        )}
      </div>

      {open && (
        <div className="mt-2 space-y-2">
          {loading && logs.length === 0 ? (
            <div className="py-3 flex justify-center">
              <Spinner size="sm" label="加载记录中..." />
            </div>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2 text-xs">
                <Chip size="sm" variant="flat" color="default">
                  24h 拉取：{pulls24h} 次
                </Chip>
                <Chip size="sm" variant="flat" color={uniqueIps24h >= 3 ? "danger" : "success"}>
                  24h 独立 IP：{uniqueIps24h} 个
                </Chip>
                {uniqueIps24h >= 3 && (
                  <Chip size="sm" variant="flat" color="warning">
                    ⚠️ 24h 内多 IP 拉取，疑似多人共享/订阅泄露
                  </Chip>
                )}
              </div>

              {ipStats.length > 0 && (
                <div className="flex flex-wrap items-center gap-1.5 text-xs">
                  <span className="text-default-500 shrink-0">IP 分布：</span>
                  {ipStats.map((item) => (
                    <Chip
                      key={item.ip}
                      size="sm"
                      variant={ipStats.length > 1 ? "solid" : "flat"}
                      color={ipStats.length > 1 ? "warning" : "default"}
                      className="h-5 text-[11px] font-mono cursor-pointer"
                      onClick={() => handleCopyIp(item.ip)}
                      title="点击复制 IP"
                    >
                      {item.ip} × {item.count}
                    </Chip>
                  ))}
                </div>
              )}

              {logs.length === 0 ? (
                <div className="text-xs text-default-400 py-2">
                  暂无拉取记录（用户更新订阅后会自动记录 IP 与客户端）
                </div>
              ) : (
                <div className="max-h-48 overflow-y-auto border border-default-100 rounded divide-y divide-default-100">
                  {logs.map((row) => {
                    const badge = subTypeBadge(row.subType);
                    const client = detectClientBadge(row.userAgent);
                    return (
                      <div key={row.id} className="px-2 py-1.5 text-xs flex flex-wrap items-center gap-x-2 gap-y-1">
                        <span className="text-default-400 font-mono shrink-0">{fmtDateTime(row.createdTime)}</span>
                        <Chip size="sm" variant="flat" color={badge.color} className="h-5 text-[11px]">
                          {badge.label}
                        </Chip>
                        <Chip size="sm" variant="flat" color={client.color} className="h-5 text-[11px]">
                          {client.label}
                        </Chip>
                        <button
                          type="button"
                          onClick={() => handleCopyIp(row.ip)}
                          className="font-mono font-medium text-foreground hover:text-primary hover:underline cursor-pointer"
                          title="点击复制 IP"
                        >
                          {row.ip || "-"}
                        </button>
                        {row.nodeName && (
                          <span className="text-default-500 shrink-0">[{row.nodeName}]</span>
                        )}
                        <span
                          className="text-default-400 truncate max-w-[260px]"
                          title={row.userAgent || "-"}
                        >
                          {row.userAgent || "未知客户端"}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
