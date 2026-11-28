import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@heroui/button";
import { Card, CardBody } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Progress } from "@heroui/progress";
import { Spinner } from "@heroui/spinner";
import Network from "@/api/network";
import { fmtBytes, latencyColor, usageProgressColor } from "@/pages/monitor";
import { PixelCatLogo } from "@/components/pixel-cat-logo";
import SkinPicker from "@/components/skin-picker";
import { useSearchShortcut } from "@/hooks/useSearchShortcut";

type StatusFilter = "all" | "online" | "offline" | "high_load";
type SortMode = "default" | "cpu_desc" | "mem_desc" | "traffic_desc";

const isHighLoad = (n: any) =>
  Boolean(n.online) && ((n.cpu ?? 0) >= 80 || (n.mem ?? 0) >= 80);

// 公开状态页:不用登录。面板里没打开时只显示一句「未开启」,不泄露任何东西
export default function StatusPage() {
  const [data, setData] = useState<any>(null);
  const [failed, setFailed] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [search, setSearch] = useState("");
  const [sortMode, setSortMode] = useState<SortMode>("default");
  const pageRef = useRef<HTMLDivElement>(null);
  const loadingRef = useRef(false);
  useSearchShortcut(pageRef);

  const load = useCallback((manual = false) => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    if (manual) setRefreshing(true);
    Network.get("/open_api/status")
      .then((r: any) => {
        if (r.code === 0) {
          setData(r.data);
          setFailed(false);
        } else {
          setFailed(true);
        }
      })
      .catch(() => setFailed(true))
      .finally(() => {
        loadingRef.current = false;
        setRefreshing(false);
      });
  }, []);

  useEffect(() => {
    load(false);
    const t = setInterval(() => load(false), 30000);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    if (data?.title) document.title = data.title;
  }, [data?.title]);

  const nodes: any[] = data?.nodes || [];
  const online = nodes.filter((n) => n.online).length;
  const offline = nodes.length - online;
  const highLoadCount = nodes.filter(isHighLoad).length;
  const onlineRate = nodes.length > 0 ? Math.round((online / nodes.length) * 100) : 0;

  const filteredNodes = useMemo(() => {
    const q = search.trim().toLowerCase();
    const list = nodes.filter((n) => {
      if (statusFilter === "online" && !n.online) return false;
      if (statusFilter === "offline" && n.online) return false;
      if (statusFilter === "high_load" && !isHighLoad(n)) return false;
      if (q) {
        const hay = `${n.name || ""} ${n.version || ""} ${n.remark || ""}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
    if (sortMode === "default") return list;
    return [...list].sort((a, b) => {
      if (sortMode === "cpu_desc") return (b.cpu ?? -1) - (a.cpu ?? -1);
      if (sortMode === "mem_desc") return (b.mem ?? -1) - (a.mem ?? -1);
      return (
        (b.rxRate || 0) + (b.txRate || 0) - ((a.rxRate || 0) + (a.txRate || 0))
      );
    });
  }, [nodes, statusFilter, search, sortMode]);

  const resetFilters = () => {
    setStatusFilter("all");
    setSearch("");
    setSortMode("default");
  };

  if (!data && !failed) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <Spinner label="正在加载节点状态..." />
      </div>
    );
  }

  if (failed && !data) {
    return (
      <div className="min-h-screen flex items-center justify-center px-4">
        <div className="max-w-sm w-full bg-content1 tms-alert-card-danger rounded-xl border border-danger/40 p-6 text-center space-y-3">
          <div className="flex justify-center">
            <PixelCatLogo size={32} />
          </div>
          <div className="text-sm font-semibold text-danger">状态页加载失败</div>
          <p className="text-xs text-default-500">网络连接不稳定或服务暂时不可用，请稍后重试。</p>
          <div>
            <Button size="sm" color="primary" variant="flat" isLoading={refreshing} onPress={() => load(true)}>
              立即重试
            </Button>
          </div>
        </div>
      </div>
    );
  }

  if (!data.enabled) {
    return (
      <div className="min-h-screen flex items-center justify-center px-4">
        <div className="tms-empty-dashed rounded-xl border border-dashed border-divider px-8 py-10 text-center space-y-2">
          <div className="flex justify-center mb-1">
            <PixelCatLogo size={30} />
          </div>
          <div className="text-sm font-medium text-foreground">状态页未开启</div>
          <div className="text-xs text-default-500">管理员当前未开放公开状态监控页面</div>
        </div>
      </div>
    );
  }

  return (
    <div ref={pageRef} className="min-h-screen px-4 py-8 max-w-6xl mx-auto">
      <div className="mb-6 space-y-3">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <PixelCatLogo size={34} className="shrink-0" />
            <div className="min-w-0">
              <h1 className="text-xl sm:text-2xl font-bold truncate">{data.title}</h1>
              <p className="text-xs sm:text-sm text-default-500 mt-1">
                每 30 秒自动刷新 · 更新于 {new Date(data.updatedAt).toLocaleTimeString()}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <Button size="sm" variant="flat" isLoading={refreshing} onPress={() => load(true)}>
              刷新
            </Button>
            <SkinPicker />
          </div>
        </div>

        {failed && (
          <div className="tms-inline-alert-danger rounded-lg border border-danger/40 bg-danger/10 px-3.5 py-2 flex items-center justify-between gap-2 flex-wrap">
            <span className="text-xs text-danger">自动刷新失败，当前显示上次成功同步的数据</span>
            <Button size="sm" color="danger" variant="flat" isLoading={refreshing} onPress={() => load(true)}>
              立即重试
            </Button>
          </div>
        )}

        <div className="flex flex-wrap gap-2">
          <Chip size="sm" variant="flat" color="default">总节点 {nodes.length}</Chip>
          <Chip size="sm" variant="flat" color="success">在线 {online}</Chip>
          <Chip size="sm" variant="flat" color={offline > 0 ? "danger" : "default"}>离线 {offline}</Chip>
          <Chip size="sm" variant="flat" color={highLoadCount > 0 ? "warning" : "default"}>高负载 {highLoadCount}</Chip>
          <Chip size="sm" variant="flat" color="primary">在线率 {onlineRate}%</Chip>
        </div>

        {nodes.length > 0 && (
          <div className="flex flex-col sm:flex-row gap-2 sm:items-center sm:justify-between">
            <div className="flex flex-wrap gap-1.5">
              {(
                [
                  { key: "all", label: "全部" },
                  { key: "online", label: "在线" },
                  { key: "offline", label: "离线" },
                  { key: "high_load", label: "高负载" },
                ] as const
              ).map((item) => (
                <Button
                  key={item.key}
                  size="sm"
                  variant={statusFilter === item.key ? "solid" : "flat"}
                  color={statusFilter === item.key ? "primary" : "default"}
                  onPress={() => setStatusFilter(item.key)}
                >
                  {item.label}
                </Button>
              ))}
            </div>

            <div className="flex flex-wrap gap-2 items-center">
              <Input
                size="sm"
                className="w-full sm:w-56"
                placeholder="搜索节点名称（按 / 聚焦）"
                value={search}
                onValueChange={setSearch}
                isClearable
                onClear={() => setSearch("")}
              />
              <div className="flex flex-wrap gap-1">
                {(
                  [
                    { key: "default", label: "默认" },
                    { key: "cpu_desc", label: "CPU↓" },
                    { key: "mem_desc", label: "内存↓" },
                    { key: "traffic_desc", label: "网速↓" },
                  ] as const
                ).map((s) => (
                  <Button
                    key={s.key}
                    size="sm"
                    variant={sortMode === s.key ? "solid" : "flat"}
                    color={sortMode === s.key ? "secondary" : "default"}
                    onPress={() => setSortMode(s.key)}
                  >
                    {s.label}
                  </Button>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {nodes.length === 0 ? (
        <div className="tms-empty-dashed py-16 px-4 text-center space-y-2 border border-dashed border-divider rounded-xl">
          <p className="text-sm font-medium text-foreground">暂无公开展示的节点</p>
          <p className="text-xs text-default-500">当前没有配置为对外公开显示的转发节点</p>
        </div>
      ) : filteredNodes.length === 0 ? (
        <div className="tms-empty-dashed py-16 px-4 text-center space-y-3 border border-dashed border-divider rounded-xl">
          <p className="text-sm text-default-500">没有匹配的节点</p>
          <Button size="sm" variant="flat" color="primary" onPress={resetFilters}>
            重置筛选
          </Button>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {filteredNodes.map((n) => (
            <Card key={n.id} className="border border-divider">
              <CardBody className="space-y-2.5 text-xs">
                <div className="flex justify-between items-center gap-2">
                  <span className="font-semibold text-sm truncate">{n.name}</span>
                  <Chip size="sm" variant="flat" color={n.online ? "success" : "danger"}>{n.online ? "在线" : "离线"}</Chip>
                </div>
                {n.online && (
                  <>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <div className="flex justify-between mb-1">
                          <span className="text-default-500">CPU</span>
                          <span className="font-mono tabular-nums">{n.cpu == null ? "-" : `${Number(n.cpu).toFixed(1)}%`}</span>
                        </div>
                        <Progress
                          size="sm"
                          value={n.cpu || 0}
                          color={usageProgressColor(n.cpu, "primary")}
                          aria-label="CPU"
                        />
                      </div>
                      <div>
                        <div className="flex justify-between mb-1">
                          <span className="text-default-500">内存</span>
                          <span className="font-mono tabular-nums">{n.mem == null ? "-" : `${Number(n.mem).toFixed(1)}%`}</span>
                        </div>
                        <Progress
                          size="sm"
                          value={n.mem || 0}
                          color={usageProgressColor(n.mem, "success")}
                          aria-label="内存"
                        />
                      </div>
                    </div>
                    <div className="flex justify-between gap-2">
                      <span className="text-default-500 shrink-0">网速</span>
                      <span className="font-mono tabular-nums truncate">↓ {fmtBytes(n.rxRate)}/s · ↑ {fmtBytes(n.txRate)}/s</span>
                    </div>
                    {(n.probes || []).length > 0 && (
                      <div className="flex flex-wrap gap-1">
                        {n.probes.map((p: any) => (
                          <Chip key={p.targetId} size="sm" variant="flat" color={latencyColor(p.avg, p.loss)}>
                            {p.name} {p.avg == null ? "不通" : `${Math.round(p.avg)}ms`}{p.loss > 0 && p.avg != null ? ` 丢${Math.round(p.loss)}%` : ""}
                          </Chip>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </CardBody>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
