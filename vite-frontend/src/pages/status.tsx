import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@heroui/button";
import { Card, CardBody } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Progress } from "@heroui/progress";
import { Spinner } from "@heroui/spinner";
import Network from "@/api/network";
import { fmtBytes, latencyColor } from "@/pages/monitor";
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
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [search, setSearch] = useState("");
  const [sortMode, setSortMode] = useState<SortMode>("default");
  const pageRef = useRef<HTMLDivElement>(null);
  useSearchShortcut(pageRef);

  useEffect(() => {
    let alive = true;
    const load = () =>
      Network.get("/open_api/status")
        .then((r: any) => { if (!alive) return; if (r.code === 0) { setData(r.data); setFailed(false); } else setFailed(true); })
        .catch(() => alive && setFailed(true));
    load();
    const t = setInterval(load, 30000);
    return () => { alive = false; clearInterval(t); };
  }, []);

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

  if (!data && !failed) return <div className="min-h-screen flex items-center justify-center"><Spinner /></div>;
  if (failed && !data) return <div className="min-h-screen flex items-center justify-center text-default-500 text-sm">暂时打不开,稍后再试</div>;
  if (!data.enabled) return <div className="min-h-screen flex items-center justify-center text-default-500 text-sm">状态页未开启</div>;

  return (
    <div ref={pageRef} className="min-h-screen px-4 py-8 max-w-6xl mx-auto">
      <div className="mb-6 space-y-3">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <PixelCatLogo size={34} className="shrink-0" />
            <div className="min-w-0">
              <h1 className="text-2xl font-bold truncate">{data.title}</h1>
              <p className="text-sm text-default-500 mt-1">
                每 30 秒自动刷新 · 更新于 {new Date(data.updatedAt).toLocaleTimeString()}
              </p>
            </div>
          </div>
          <div className="shrink-0">
            <SkinPicker />
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          <Chip size="sm" variant="flat" color="default">总节点 {nodes.length}</Chip>
          <Chip size="sm" variant="flat" color="success">在线 {online}</Chip>
          <Chip size="sm" variant="flat" color={offline > 0 ? "danger" : "default"}>离线 {offline}</Chip>
          <Chip size="sm" variant="flat" color={highLoadCount > 0 ? "warning" : "default"}>高负载 {highLoadCount}</Chip>
          <Chip size="sm" variant="flat" color="primary">在线率 {onlineRate}%</Chip>
        </div>

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
              placeholder="搜索节点名称 / 版本 / 备注"
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
                  { key: "traffic_desc", label: "总流量↓" },
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
      </div>

      {filteredNodes.length === 0 ? (
        <div className="tms-empty-dashed py-16 text-center space-y-3 border border-dashed border-divider rounded-xl">
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
                      <div className="flex justify-between mb-1"><span className="text-default-500">CPU</span><span>{n.cpu == null ? "-" : `${n.cpu.toFixed(1)}%`}</span></div>
                      <Progress size="sm" value={n.cpu || 0} color="primary" aria-label="CPU" />
                    </div>
                    <div>
                      <div className="flex justify-between mb-1"><span className="text-default-500">内存</span><span>{n.mem == null ? "-" : `${n.mem.toFixed(1)}%`}</span></div>
                      <Progress size="sm" value={n.mem || 0} color="success" aria-label="内存" />
                    </div>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-default-500">网速</span>
                    <span>↓ {fmtBytes(n.rxRate)}/s · ↑ {fmtBytes(n.txRate)}/s</span>
                  </div>
                  {(n.probes || []).length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {n.probes.map((p: any) => (
                        <Chip key={p.targetId} size="sm" variant="flat" color={latencyColor(p.avg, p.loss)}>
                          {p.name} {p.avg == null ? "不通" : `${Math.round(p.avg)}ms`}
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
