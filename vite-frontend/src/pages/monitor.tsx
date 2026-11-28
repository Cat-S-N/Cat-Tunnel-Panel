import { useEffect, useState, useCallback, useMemo, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Chip } from "@heroui/chip";
import { Switch } from "@heroui/switch";
import { Spinner } from "@heroui/spinner";
import { Progress } from "@heroui/progress";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import toast from "react-hot-toast";
import NodeMonitorModal from "@/components/node-monitor";
import { copyTextToClipboard } from "@/utils/clipboard";
import {
  getMonitorOverview, getMonitorSettings, saveMonitorSettings, testMonitorTelegram,
  saveNodeMeta, getProbeTargets, saveProbeTarget, deleteProbeTarget,
} from "@/api";

// 监控:总览 / 告警 / 延迟目标 / 公开状态页
type Tab = "overview" | "alert" | "targets" | "status";
const TABS: { key: Tab; label: string }[] = [
  { key: "overview", label: "总览" },
  { key: "alert", label: "告警" },
  { key: "targets", label: "延迟目标" },
  { key: "status", label: "状态页" },
];

export const fmtBytes = (v?: number | null) => {
  if (v == null) return "-";
  if (!v) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let n = v;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n < 10 && i > 0 ? 2 : n < 100 && i > 0 ? 1 : 0)} ${u[i]}`;
};

export const latencyColor = (avg: number | null, loss: number) =>
  avg == null || loss >= 50 ? "danger" : loss > 0 || avg > 200 ? "warning" : "success";

export const usageProgressColor = (
  val: number | null | undefined,
  normalColor: "primary" | "success" | "secondary" = "primary",
) => {
  const n = Number(val ?? 0);
  if (n >= 85) return "danger";
  if (n >= 80) return "warning";
  return normalColor;
};

const daysLeft = (date?: string | null) => {
  if (!date) return null;
  const d = new Date(date + "T00:00:00");
  const t = new Date(); t.setHours(0, 0, 0, 0);
  return Math.round((d.getTime() - t.getTime()) / 86400000);
};

function Overview({ ver, onChart, onMeta }: { ver: number; onChart: (n: any) => void; onMeta: (n: any) => void }) {
  const navigate = useNavigate();
  const [nodes, setNodes] = useState<any[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState<"all" | "online" | "offline" | "high_load">("all");
  const [searchKeyword, setSearchKeyword] = useState("");
  const [sortBy, setSortBy] = useState<"default" | "cpu_desc" | "mem_desc" | "speed_desc">("default");
  const loadingRef = useRef(false);

  const load = useCallback((manual = false) => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    if (manual) setRefreshing(true);
    getMonitorOverview()
      .then((r: any) => {
        if (r.code === 0) {
          setNodes(r.data || []);
          setLoadError(null);
        } else {
          setLoadError(r.msg || "获取监控总览失败");
        }
      })
      .catch(() => setLoadError("网络请求异常，请检查连接后重试"))
      .finally(() => {
        loadingRef.current = false;
        setRefreshing(false);
      });
  }, []);

  useEffect(() => {
    load(false);
    const t = setInterval(() => load(false), 30000);
    return () => clearInterval(t);
  }, [load, ver]);

  const stats = useMemo(() => {
    const list = nodes || [];
    let online = 0;
    let offline = 0;
    let highLoad = 0;
    for (const n of list) {
      if (n.online) online += 1;
      else offline += 1;
      const cpu = Number(n.cpu ?? n.cpuUsage ?? 0);
      const mem = Number(n.mem ?? n.memoryUsage ?? 0);
      if (n.online && (cpu >= 80 || mem >= 80)) highLoad += 1;
    }
    return { total: list.length, online, offline, highLoad };
  }, [nodes]);

  const filteredNodes = useMemo(() => {
    const list = nodes || [];
    const kw = searchKeyword.trim().toLowerCase();
    const filtered = list.filter((n) => {
      const cpu = Number(n.cpu ?? n.cpuUsage ?? 0);
      const mem = Number(n.mem ?? n.memoryUsage ?? 0);
      if (statusFilter === "online" && !n.online) return false;
      if (statusFilter === "offline" && n.online) return false;
      if (statusFilter === "high_load" && (!n.online || (cpu < 80 && mem < 80))) return false;
      if (!kw) return true;
      const fields = [n.name, n.publicName, n.serverIp, n.version, n.remark];
      return fields.some((f) => f && String(f).toLowerCase().includes(kw));
    });
    if (sortBy === "cpu_desc") {
      return [...filtered].sort((a, b) => Number(b.cpu ?? b.cpuUsage ?? -1) - Number(a.cpu ?? a.cpuUsage ?? -1));
    }
    if (sortBy === "mem_desc") {
      return [...filtered].sort((a, b) => Number(b.mem ?? b.memoryUsage ?? -1) - Number(a.mem ?? a.memoryUsage ?? -1));
    }
    if (sortBy === "speed_desc") {
      return [...filtered].sort((a, b) => {
        const aSpd =
          Number(a.rxRate ?? a.downSpeed ?? a.downloadSpeed ?? 0) +
          Number(a.txRate ?? a.upSpeed ?? a.uploadSpeed ?? 0);
        const bSpd =
          Number(b.rxRate ?? b.downSpeed ?? b.downloadSpeed ?? 0) +
          Number(b.txRate ?? b.upSpeed ?? b.uploadSpeed ?? 0);
        return bSpd - aSpd;
      });
    }
    return filtered;
  }, [nodes, statusFilter, searchKeyword, sortBy]);

  if (nodes === null) {
    return loadError ? (
      <div className="bg-content1 tms-alert-card-danger rounded-lg border border-danger/40 bg-danger/5 py-14 px-4 text-center space-y-3">
        <div className="text-sm font-semibold text-danger">加载监控总览失败</div>
        <div className="text-xs text-default-500 max-w-md mx-auto break-words">{loadError}</div>
        <div>
          <Button size="sm" color="primary" variant="flat" isLoading={refreshing} onPress={() => load(true)}>
            重试加载
          </Button>
        </div>
      </div>
    ) : (
      <div className="flex justify-center py-16">
        <Spinner label="正在加载监控数据..." />
      </div>
    );
  }

  if (nodes.length === 0) {
    return (
      <div className="tms-empty-dashed rounded-lg border border-dashed border-divider py-14 px-4 text-center space-y-3">
        <div className="text-sm font-semibold text-foreground">暂无转发机监控数据</div>
        <div className="text-xs text-default-500 max-w-md mx-auto leading-relaxed">
          添加并接入转发机后，这里将每 30 秒自动汇总在线状态、CPU/内存负载、实时网速、月度流量与 TCP 建连延迟。
        </div>
        <div className="flex justify-center gap-2 pt-1">
          <Button size="sm" color="primary" variant="flat" onPress={() => navigate("/node")}>
            前往转发机管理
          </Button>
          <Button size="sm" variant="flat" isLoading={refreshing} onPress={() => load(true)}>
            刷新
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {loadError && (
        <div className="tms-inline-alert-danger rounded-lg border border-danger/40 bg-danger/10 px-3.5 py-2.5 flex items-center justify-between gap-3 flex-wrap">
          <div className="text-xs text-danger font-medium min-w-0 break-words">
            自动刷新异常：{loadError}（当前展示上次已加载数据）
          </div>
          <Button size="sm" color="danger" variant="flat" isLoading={refreshing} onPress={() => load(true)}>
            立即重试
          </Button>
        </div>
      )}

      <div className="flex flex-col gap-2.5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <Chip size="sm" variant="flat" color="default">总节点：{stats.total}</Chip>
            <Chip size="sm" variant="flat" color="success">在线：{stats.online}</Chip>
            <Chip size="sm" variant="flat" color={stats.offline > 0 ? "danger" : "default"}>离线：{stats.offline}</Chip>
            <Chip size="sm" variant="flat" color={stats.highLoad > 0 ? "warning" : "default"}>高负载(≥80%)：{stats.highLoad}</Chip>
          </div>
          <Button size="sm" variant="flat" isLoading={refreshing} onPress={() => load(true)}>
            刷新
          </Button>
        </div>

        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
          <Input
            size="sm"
            className="w-full sm:w-64"
            placeholder="搜索名称 / IP / 版本（按 / 聚焦）"
            isClearable
            value={searchKeyword}
            onValueChange={setSearchKeyword}
            onClear={() => setSearchKeyword("")}
          />
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex flex-wrap items-center gap-1">
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
                  color={
                    statusFilter === item.key
                      ? item.key === "online"
                        ? "success"
                        : item.key === "offline"
                        ? "danger"
                        : item.key === "high_load"
                        ? "warning"
                        : "primary"
                      : "default"
                  }
                  onPress={() => setStatusFilter(item.key)}
                >
                  {item.label}
                </Button>
              ))}
            </div>
            <div className="flex flex-wrap items-center gap-1">
              {(
                [
                  { key: "default", label: "默认排序" },
                  { key: "cpu_desc", label: "CPU↓" },
                  { key: "mem_desc", label: "内存↓" },
                  { key: "speed_desc", label: "网速↓" },
                ] as const
              ).map((item) => (
                <Button
                  key={item.key}
                  size="sm"
                  variant={sortBy === item.key ? "solid" : "flat"}
                  color={sortBy === item.key ? "secondary" : "default"}
                  onPress={() => setSortBy(item.key)}
                >
                  {item.label}
                </Button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {filteredNodes.length === 0 ? (
        <div className="tms-empty-dashed rounded-lg border border-dashed border-divider py-12 px-4 text-center space-y-3">
          <div className="text-sm font-medium text-foreground">无匹配监控节点</div>
          <div className="text-xs text-default-500">当前筛选或搜索条件下没有匹配的节点</div>
          <div>
            <Button
              size="sm"
              color="primary"
              variant="flat"
              onPress={() => {
                setStatusFilter("all");
                setSearchKeyword("");
                setSortBy("default");
              }}
            >
              重置筛选
            </Button>
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
          {filteredNodes.map((n) => {
            const left = daysLeft(n.expireDate);
            const quota = n.quotaGb ? n.quotaGb * 1024 ** 3 : 0;
            const pct = quota ? Math.min(100, (n.cycleUsed / quota) * 100) : 0;
            return (
              <Card key={n.id} className="border border-divider">
                <CardHeader className="pb-1 flex justify-between items-start gap-2">
                  <div className="min-w-0">
                    <div className="font-semibold text-sm truncate">{n.name}</div>
                    <div className="text-xs text-default-500 font-mono truncate">{n.serverIp}</div>
                  </div>
                  <div className="flex flex-wrap justify-end gap-1 flex-shrink-0">
                    {left != null && (
                      <Chip size="sm" variant="flat" color={left < 0 ? "danger" : left <= 3 ? "warning" : "default"}>
                        {left < 0 ? `已过期 ${-left} 天` : left === 0 ? "今天到期" : `${left} 天后到期`}
                      </Chip>
                    )}
                    <Chip size="sm" variant="flat" color={n.online ? "success" : "danger"}>{n.online ? "在线" : "离线"}</Chip>
                  </div>
                </CardHeader>
                <CardBody className="pt-1 space-y-2.5 text-xs">
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
                    <span className="text-default-500 shrink-0">网速(近 1 分钟)</span>
                    <span className="font-mono tabular-nums truncate">↓ {fmtBytes(n.rxRate)}/s · ↑ {fmtBytes(n.txRate)}/s</span>
                  </div>
                  <div>
                    <div className="flex justify-between gap-2 mb-1">
                      <span className="text-default-500">本月流量{n.resetDay > 1 ? `(每月 ${n.resetDay} 号重置)` : ""}</span>
                      <span className="font-mono tabular-nums">{fmtBytes(n.cycleUsed)}{quota ? ` / ${n.quotaGb} GB` : ""}</span>
                    </div>
                    {quota > 0 && (
                      <Progress
                        size="sm"
                        value={pct}
                        color={pct >= 100 ? "danger" : pct >= 80 ? "warning" : "secondary"}
                        aria-label="流量"
                      />
                    )}
                  </div>
                  <div className="flex flex-wrap gap-1 min-h-6 items-center">
                    {!n.online ? (
                      <span className="text-default-400">离线中，延迟暂停监测</span>
                    ) : !n.probeSupported ? (
                      <span className="text-default-400">延迟监测：节点需升级到 1.2.5+</span>
                    ) : (n.probes || []).length === 0 ? (
                      <span className="text-default-400">延迟：等待首批探测数据</span>
                    ) : (
                      n.probes.map((p: any) => (
                        <Chip key={p.targetId} size="sm" variant="flat" color={latencyColor(p.avg, p.loss)}>
                          {p.name} {p.avg == null ? "不通" : `${Math.round(p.avg)}ms`}{p.loss > 0 && p.avg != null ? ` 丢${Math.round(p.loss)}%` : ""}
                        </Chip>
                      ))
                    )}
                  </div>
                  <div className="flex gap-1.5 pt-1">
                    <Button size="sm" variant="flat" color="primary" className="flex-1" onPress={() => onChart(n)}>曲线</Button>
                    <Button size="sm" variant="flat" className="flex-1" onPress={() => onMeta(n)}>到期 / 额度</Button>
                  </div>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}

function MetaModal({ node, onClose, onSaved }: { node: any | null; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<any>({});
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (node) {
      setF({
        expireDate: node.expireDate || "",
        quotaGb: node.quotaGb ?? "",
        resetDay: String(node.resetDay || 1),
        trafficMode: node.trafficMode || "both",
        publicShow: node.publicShow !== false,
        publicName: node.publicName || "",
      });
    }
  }, [node]);

  const save = async () => {
    if (saving || !node) return;
    const resetNum = Number(f.resetDay || 1);
    if (!Number.isInteger(resetNum) || resetNum < 1 || resetNum > 31) {
      toast.error("每月重置日请填写 1 ~ 31 之间的整数");
      return;
    }
    const quotaStr = String(f.quotaGb ?? "").trim();
    if (quotaStr !== "" && (!/^\d+$/.test(quotaStr) || Number(quotaStr) < 0)) {
      toast.error("月流量额度请填写非负整数（GB），留空表示不限");
      return;
    }

    setSaving(true);
    try {
      const r: any = await saveNodeMeta({ nodeId: node.id, ...f });
      if (r.code === 0) {
        toast.success("已保存节点到期与额度设置");
        onSaved();
        onClose();
      } else {
        toast.error(r.msg || "保存失败");
      }
    } catch {
      toast.error("保存请求出错，请检查网络后重试");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal isOpen={!!node} onClose={() => !saving && onClose()} placement="center">
      <ModalContent>
        <ModalHeader>{node?.name} · 到期 / 额度</ModalHeader>
        <ModalBody className="space-y-2">
          <Input
            type="date"
            label="VPS 到期日"
            description="填写后到期前自动提醒；留空 = 不提醒"
            value={f.expireDate || ""}
            onValueChange={(v) => setF({ ...f, expireDate: v })}
          />
          <Input
            type="number"
            label="月流量额度(GB)"
            description="留空或 0 = 不限流量、不发额度提醒"
            value={String(f.quotaGb ?? "")}
            onValueChange={(v) => setF({ ...f, quotaGb: v })}
          />
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            <Input
              type="number"
              label="每月几号重置"
              value={f.resetDay || "1"}
              min={1}
              max={31}
              onValueChange={(v) => setF({ ...f, resetDay: v })}
            />
            <Select
              label="流量统计口径"
              selectedKeys={[f.trafficMode || "both"]}
              onSelectionChange={(k) => setF({ ...f, trafficMode: Array.from(k)[0] || "both" })}
            >
              <SelectItem key="both">上下行相加</SelectItem>
              <SelectItem key="out">只算上行</SelectItem>
              <SelectItem key="max">取较大的一边</SelectItem>
            </Select>
          </div>
          <p className="text-xs text-default-500 leading-relaxed">
            流量自面板采集起统计，与云厂商账单可能存在细微偏差，建议作为运维预警参考。
          </p>
          <div className="flex items-center justify-between gap-3 pt-1">
            <span className="text-sm">在公开状态页显示</span>
            <Switch size="sm" isSelected={!!f.publicShow} onValueChange={(v) => setF({ ...f, publicShow: v })} />
          </div>
          <Input
            label="状态页显示名称"
            description="留空默认显示转发机名；如需隐藏机房特征可自定义别名"
            value={f.publicName || ""}
            onValueChange={(v) => setF({ ...f, publicName: v })}
          />
        </ModalBody>
        <ModalFooter>
          <Button variant="light" isDisabled={saving} onPress={onClose}>取消</Button>
          <Button color="primary" isLoading={saving} isDisabled={saving} onPress={save}>保存</Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function AlertSettings() {
  const [s, setS] = useState<any>(null);
  const [orig, setOrig] = useState<any>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [token, setToken] = useState("");
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [clearing, setClearing] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    getMonitorSettings()
      .then((r: any) => {
        if (r.code === 0 && r.data) {
          setS(r.data);
          setOrig(r.data);
        } else {
          setLoadError(r.msg || "获取告警设置失败");
        }
      })
      .catch(() => setLoadError("网络请求异常，加载告警设置失败"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (loading && !s) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="正在加载告警配置..." />
      </div>
    );
  }

  if (loadError && !s) {
    return (
      <div className="max-w-2xl bg-content1 tms-alert-card-danger rounded-lg border border-danger/40 bg-danger/5 py-12 px-4 text-center space-y-3">
        <div className="text-sm font-semibold text-danger">加载告警设置失败</div>
        <div className="text-xs text-default-500">{loadError}</div>
        <div>
          <Button size="sm" color="primary" variant="flat" onPress={load}>
            重试加载
          </Button>
        </div>
      </div>
    );
  }

  const busy = saving || testing || clearing;
  const hasUnsavedTelegram =
    token.trim() !== "" || (orig && (s.tg_chat || "").trim() !== (orig.tg_chat || "").trim());
  const hasAnyChanges =
    hasUnsavedTelegram ||
    (orig &&
      (String(s.alert_offline_min ?? "3") !== String(orig.alert_offline_min ?? "3") ||
        String(s.alert_expire_days ?? "3") !== String(orig.alert_expire_days ?? "3") ||
        String(s.alert_traffic ?? "0") !== String(orig.alert_traffic ?? "0")));

  const save = async () => {
    if (busy) return;
    const offMin = Number(s.alert_offline_min ?? 0);
    const expDays = Number(s.alert_expire_days ?? 0);
    if (!Number.isFinite(offMin) || offMin < 0 || !Number.isInteger(offMin)) {
      toast.error("掉线报警分钟数请填写非负整数（0 表示关闭）");
      return;
    }
    if (!Number.isFinite(expDays) || expDays < 0 || !Number.isInteger(expDays)) {
      toast.error("到期提前提醒天数请填写非负整数（0 表示关闭）");
      return;
    }

    setSaving(true);
    try {
      const r: any = await saveMonitorSettings({
        tg_token: token,
        tg_chat: s.tg_chat,
        alert_offline_min: String(offMin),
        alert_expire_days: String(expDays),
        alert_traffic: s.alert_traffic,
      });
      if (r.code === 0) {
        toast.success("告警配置已保存");
        setToken("");
        load();
      } else {
        toast.error(r.msg || "保存失败");
      }
    } catch {
      toast.error("保存告警配置出错，请稍后重试");
    } finally {
      setSaving(false);
    }
  };

  const test = async () => {
    if (busy) return;
    if (hasUnsavedTelegram) {
      toast.error("检测到未保存的 Bot Token 或 Chat ID 修改，请先点击「保存」后再测试");
      return;
    }
    setTesting(true);
    try {
      const r: any = await testMonitorTelegram();
      if (r.code === 0) {
        toast.success("测试消息已发送，请前往 Telegram 查收");
      } else {
        toast.error(r.msg || "发送失败");
      }
    } catch {
      toast.error("测试推送请求异常，请检查网络或配置");
    } finally {
      setTesting(false);
    }
  };

  const clearToken = async () => {
    if (busy) return;
    setClearing(true);
    try {
      const r: any = await saveMonitorSettings({ clear_tg_token: true });
      if (r.code === 0) {
        toast.success("已清除 Bot Token，Telegram 告警已关闭");
        setToken("");
        load();
      } else {
        toast.error(r.msg || "清除失败");
      }
    } catch {
      toast.error("清除 Token 请求异常，请重试");
    } finally {
      setClearing(false);
    }
  };

  const ready = s.tg_token_set === "1" && !!(orig?.tg_chat || s.tg_chat || "").trim();

  return (
    <Card className="max-w-2xl border border-divider">
      <CardBody className="space-y-3.5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="text-sm flex flex-wrap items-center gap-2">
            <span className="font-medium">Telegram 节点与额度监控告警</span>
            <Chip size="sm" variant="flat" color={ready ? "success" : "warning"}>
              {ready ? "已启用" : "未就绪（需填齐 Token 与 Chat ID）"}
            </Chip>
          </div>
          {hasAnyChanges && (
            <Chip size="sm" variant="flat" color="warning">
              有未保存修改
            </Chip>
          )}
        </div>

        <Input
          type="password"
          label="Bot Token"
          value={token}
          onValueChange={setToken}
          placeholder={s.tg_token_set === "1" ? "已保存密钥，留空保持不变" : "123456:ABC-DEF..."}
          description="向 @BotFather 发送 /newbot 创建机器人获取 Token（密钥仅加密存储于服务端，不回传明文）"
        />
        <Input
          label="Chat ID"
          value={s.tg_chat || ""}
          onValueChange={(v) => setS({ ...s, tg_chat: v })}
          description="先向机器人发送任意消息激活会话，再通过 @userinfobot 获取个人 ID（群组一般为负数 ID）"
        />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Input
            type="number"
            min={0}
            label="掉线多少分钟报警"
            value={s.alert_offline_min ?? "3"}
            onValueChange={(v) => setS({ ...s, alert_offline_min: v })}
            description="0 = 不报警；节点恢复上线时也会推送恢复通知"
          />
          <Input
            type="number"
            min={0}
            label="到期前几天开始提醒"
            value={s.alert_expire_days ?? "3"}
            onValueChange={(v) => setS({ ...s, alert_expire_days: v })}
            description="0 = 不提醒；开启后每天上午 10 点巡检提醒"
          />
        </div>
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <div className="text-sm">月度流量阈值提醒</div>
            <div className="text-xs text-default-500">已设置月流量额度的转发机，使用率达到 80% 与 100% 时各推送一次预警</div>
          </div>
          <Switch
            size="sm"
            isSelected={s.alert_traffic === "1"}
            onValueChange={(v) => setS({ ...s, alert_traffic: v ? "1" : "0" })}
          />
        </div>
        <p className="text-xs text-default-500">
          提示：单台转发机的 VPS 到期日与月度流量额度请在「总览」对应卡片点击「到期 / 额度」单独设置。
        </p>
        {hasUnsavedTelegram && (
          <div className="tms-inline-alert-warning rounded-md border border-warning/40 bg-warning/10 px-3 py-2 text-xs text-warning-700 dark:text-warning-300">
            已修改 Bot Token 或 Chat ID，请先点击「保存」使新凭证落库后再发送测试消息。
          </div>
        )}
        <div className="flex flex-wrap items-center gap-2 pt-1">
          <Button color="primary" isLoading={saving} isDisabled={busy} onPress={save}>
            保存设置
          </Button>
          <Button
            variant="flat"
            isLoading={testing}
            isDisabled={busy || !ready || Boolean(hasUnsavedTelegram)}
            onPress={test}
          >
            发一条测试
          </Button>
          {s.tg_token_set === "1" && (
            <Button
              variant="light"
              color="danger"
              isLoading={clearing}
              isDisabled={busy}
              onPress={clearToken}
            >
              清除 Token
            </Button>
          )}
        </div>
      </CardBody>
    </Card>
  );
}

function Targets() {
  const [list, setList] = useState<any[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [edit, setEdit] = useState<any | null>(null);
  const [savingTarget, setSavingTarget] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [targetToDelete, setTargetToDelete] = useState<any | null>(null);
  const [deletingTarget, setDeletingTarget] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    getProbeTargets()
      .then((r: any) => {
        if (r.code === 0) {
          setList(r.data || []);
        } else {
          setLoadError(r.msg || "获取延迟探测目标失败");
        }
      })
      .catch(() => setLoadError("网络请求异常，获取延迟探测目标失败"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const save = async () => {
    if (savingTarget || !edit) return;
    const name = String(edit.name || "").trim();
    const host = String(edit.host || "").trim();
    const portNum = Number(edit.port);
    if (!name) {
      toast.error("请填写目标名称（例如：上海电信）");
      return;
    }
    if (!host) {
      toast.error("请填写探测目标 IP 或域名");
      return;
    }
    if (!Number.isInteger(portNum) || portNum < 1 || portNum > 65535) {
      toast.error("端口范围应为 1 ~ 65535 的整数");
      return;
    }

    setSavingTarget(true);
    try {
      const r: any = await saveProbeTarget({
        ...edit,
        name,
        host,
        port: portNum,
      });
      if (r.code === 0) {
        toast.success("探测目标已保存，在线转发机将自动同步");
        setEdit(null);
        load();
      } else {
        toast.error(r.msg || "保存失败");
      }
    } catch {
      toast.error("保存探测目标出错，请稍后重试");
    } finally {
      setSavingTarget(false);
    }
  };

  const handleToggleTarget = async (t: any, enabled: boolean) => {
    if (busyId !== null) return;
    setBusyId(t.id);
    try {
      const r: any = await saveProbeTarget({ ...t, enabled });
      if (r.code === 0) {
        toast.success(enabled ? `已启用「${t.name}」` : `已停用「${t.name}」`);
        load();
      } else {
        toast.error(r.msg || "切换状态失败");
      }
    } catch {
      toast.error("请求异常，切换状态失败");
    } finally {
      setBusyId(null);
    }
  };

  const confirmDelete = async () => {
    if (!targetToDelete || deletingTarget) return;
    setDeletingTarget(true);
    try {
      const r: any = await deleteProbeTarget(targetToDelete.id);
      if (r.code === 0) {
        toast.success(`已删除探测目标「${targetToDelete.name}」`);
        setTargetToDelete(null);
        load();
      } else {
        toast.error(r.msg || "删除失败");
      }
    } catch {
      toast.error("删除请求异常，请稍后重试");
    } finally {
      setDeletingTarget(false);
    }
  };

  if (loading && !list) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="正在加载延迟探测目标..." />
      </div>
    );
  }

  if (loadError && !list) {
    return (
      <div className="max-w-2xl bg-content1 tms-alert-card-danger rounded-lg border border-danger/40 bg-danger/5 py-12 px-4 text-center space-y-3">
        <div className="text-sm font-semibold text-danger">加载延迟目标失败</div>
        <div className="text-xs text-default-500">{loadError}</div>
        <div>
          <Button size="sm" color="primary" variant="flat" onPress={load}>
            重试加载
          </Button>
        </div>
      </div>
    );
  }

  const items = list || [];

  return (
    <div className="max-w-2xl space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-default-500 leading-relaxed flex-1 min-w-[220px]">
          每台在线转发机每 5 分钟对启用目标各测 4 次 TCP 建连耗时（观察趋势与丢包率尤为直观，需节点程序 1.2.5+，上限 20 个目标）。
        </p>
        <Button
          size="sm"
          color="primary"
          variant="flat"
          onPress={() => setEdit({ name: "", host: "", port: 80, enabled: true })}
        >
          添加目标
        </Button>
      </div>

      {loadError && (
        <div className="tms-inline-alert-danger rounded-lg border border-danger/40 bg-danger/10 px-3 py-2 flex items-center justify-between gap-2 flex-wrap">
          <span className="text-xs text-danger">{loadError}</span>
          <Button size="sm" color="danger" variant="flat" onPress={load}>
            重试
          </Button>
        </div>
      )}

      {items.length === 0 ? (
        <div className="tms-empty-dashed rounded-lg border border-dashed border-divider py-12 px-4 text-center space-y-3">
          <div className="text-sm font-medium text-foreground">暂无延迟探测目标</div>
          <div className="text-xs text-default-500 max-w-md mx-auto">
            添加各地运营商或常用入口（如上海电信、广州移动、Cloudflare `1.1.1.1:443`）后，即可在监控总览与状态页查看实时建连延迟。
          </div>
          <div>
            <Button
              size="sm"
              color="primary"
              variant="flat"
              onPress={() => setEdit({ name: "", host: "", port: 443, enabled: true })}
            >
              添加第一个探测目标
            </Button>
          </div>
        </div>
      ) : (
        items.map((t) => {
          const isRowBusy = busyId === t.id;
          return (
            <Card key={t.id} className="border border-divider">
              <CardBody className="flex flex-col sm:flex-row sm:items-center justify-between gap-2.5 py-2.5">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium truncate">{t.name}</span>
                    {t.enabled !== 1 && (
                      <Chip size="sm" variant="flat" color="default">已停用</Chip>
                    )}
                  </div>
                  <div className="text-xs text-default-500 font-mono break-all">{t.host}:{t.port}</div>
                </div>
                <div className="flex items-center justify-end gap-1.5 flex-shrink-0">
                  <Switch
                    size="sm"
                    isDisabled={isRowBusy}
                    isSelected={t.enabled === 1}
                    onValueChange={(v) => handleToggleTarget(t, v)}
                  />
                  <Button
                    size="sm"
                    variant="flat"
                    isDisabled={isRowBusy}
                    onPress={() => setEdit({ ...t })}
                  >
                    编辑
                  </Button>
                  <Button
                    size="sm"
                    variant="flat"
                    color="danger"
                    isDisabled={isRowBusy}
                    onPress={() => setTargetToDelete(t)}
                  >
                    删除
                  </Button>
                </div>
              </CardBody>
            </Card>
          );
        })
      )}

      <Modal isOpen={!!edit} onClose={() => !savingTarget && setEdit(null)} placement="center">
        <ModalContent>
          <ModalHeader>{edit?.id ? "编辑探测目标" : "添加探测目标"}</ModalHeader>
          <ModalBody className="space-y-2">
            <Input
              label="名称"
              value={edit?.name || ""}
              onValueChange={(v) => setEdit({ ...edit, name: v })}
              placeholder="如：上海电信 / 广州移动"
            />
            <Input
              label="目标地址"
              value={edit?.host || ""}
              onValueChange={(v) => setEdit({ ...edit, host: v })}
              placeholder="IPv4、IPv6 或域名"
            />
            <Input
              type="number"
              min={1}
              max={65535}
              label="TCP 端口"
              value={String(edit?.port ?? "")}
              onValueChange={(v) => setEdit({ ...edit, port: v })}
              description="常用端口如 80、443 或目标开放的公网 TCP 端口"
            />
          </ModalBody>
          <ModalFooter>
            <Button variant="light" isDisabled={savingTarget} onPress={() => setEdit(null)}>
              取消
            </Button>
            <Button color="primary" isLoading={savingTarget} isDisabled={savingTarget} onPress={save}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={!!targetToDelete} onClose={() => !deletingTarget && setTargetToDelete(null)} placement="center" size="sm">
        <ModalContent>
          <ModalHeader>确认删除探测目标</ModalHeader>
          <ModalBody>
            <p className="text-sm text-default-600">
              确定要删除延迟探测目标 <span className="font-semibold text-foreground">「{targetToDelete?.name}」</span>（<code className="text-xs font-mono">{targetToDelete?.host}:{targetToDelete?.port}</code>）吗？对应的历史延迟记录也将一并清理。
            </p>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" isDisabled={deletingTarget} onPress={() => setTargetToDelete(null)}>
              取消
            </Button>
            <Button color="danger" isLoading={deletingTarget} isDisabled={deletingTarget} onPress={confirmDelete}>
              确认删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

function StatusSettings() {
  const [s, setS] = useState<any>(null);
  const [savedTitle, setSavedTitle] = useState("");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [savingSwitch, setSavingSwitch] = useState(false);
  const [savingTitle, setSavingTitle] = useState(false);
  const [copied, setCopied] = useState(false);
  const copyTimerRef = useRef<number | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    getMonitorSettings()
      .then((r: any) => {
        if (r.code === 0 && r.data) {
          setS(r.data);
          setSavedTitle(r.data.status_title || "");
        } else {
          setLoadError(r.msg || "获取状态页设置失败");
        }
      })
      .catch(() => setLoadError("网络请求异常，加载状态页设置失败"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    return () => {
      if (copyTimerRef.current !== null) window.clearTimeout(copyTimerRef.current);
    };
  }, [load]);

  if (loading && !s) {
    return (
      <div className="flex justify-center py-16">
        <Spinner label="正在加载状态页设置..." />
      </div>
    );
  }

  if (loadError && !s) {
    return (
      <div className="max-w-2xl bg-content1 tms-alert-card-danger rounded-lg border border-danger/40 bg-danger/5 py-12 px-4 text-center space-y-3">
        <div className="text-sm font-semibold text-danger">加载状态页设置失败</div>
        <div className="text-xs text-default-500">{loadError}</div>
        <div>
          <Button size="sm" color="primary" variant="flat" onPress={load}>
            重试加载
          </Button>
        </div>
      </div>
    );
  }

  const url = `${window.location.origin}/status`;
  const currentTitle = s.status_title || "";
  const titleChanged = currentTitle.trim() !== savedTitle.trim();

  const handleToggleStatus = async (enabled: boolean) => {
    if (savingSwitch) return;
    const prev = s.status_enabled;
    const nextVal = enabled ? "1" : "0";
    setS({ ...s, status_enabled: nextVal });
    setSavingSwitch(true);
    try {
      const r: any = await saveMonitorSettings({
        status_enabled: nextVal,
        status_title: currentTitle.trim(),
      });
      if (r.code === 0) {
        setSavedTitle(currentTitle.trim());
        toast.success(enabled ? "公开状态页已开启" : "公开状态页已关闭");
      } else {
        setS((curr: any) => ({ ...curr, status_enabled: prev }));
        toast.error(r.msg || "保存失败");
      }
    } catch {
      setS((curr: any) => ({ ...curr, status_enabled: prev }));
      toast.error("保存请求出错，请检查网络后重试");
    } finally {
      setSavingSwitch(false);
    }
  };

  const handleSaveTitle = async () => {
    if (savingTitle || !titleChanged) return;
    const trimmed = currentTitle.trim();
    if (trimmed.length > 200) {
      toast.error("页面标题长度不能超过 200 个字符");
      return;
    }
    setSavingTitle(true);
    try {
      const r: any = await saveMonitorSettings({
        status_enabled: s.status_enabled,
        status_title: trimmed,
      });
      if (r.code === 0) {
        setS({ ...s, status_title: trimmed });
        setSavedTitle(trimmed);
        toast.success("状态页标题已保存");
      } else {
        toast.error(r.msg || "保存失败");
      }
    } catch {
      toast.error("保存标题出错，请稍后重试");
    } finally {
      setSavingTitle(false);
    }
  };

  const handleCopyUrl = async () => {
    const ok = await copyTextToClipboard(url);
    if (ok) {
      setCopied(true);
      if (copyTimerRef.current !== null) window.clearTimeout(copyTimerRef.current);
      copyTimerRef.current = window.setTimeout(() => setCopied(false), 1800);
      toast.success("已复制状态页地址");
    } else {
      toast.error("复制失败，请手动选中复制");
    }
  };

  return (
    <Card className="max-w-2xl border border-divider">
      <CardBody className="space-y-3.5">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium">公开状态页</span>
              <Chip size="sm" variant="flat" color={s.status_enabled === "1" ? "success" : "default"}>
                {s.status_enabled === "1" ? "对外访问中" : "已关闭"}
              </Chip>
            </div>
            <div className="text-xs text-default-500 mt-0.5 leading-relaxed">
              无需登录即可查看节点在线率、CPU、内存、实时网速与 TCP 延迟；自动隐藏公网 IP、到期日与流量配额。
            </div>
          </div>
          <Switch
            isDisabled={savingSwitch}
            isSelected={s.status_enabled === "1"}
            onValueChange={handleToggleStatus}
          />
        </div>

        <div className="flex flex-col sm:flex-row sm:items-end gap-2">
          <Input
            className="flex-1"
            label="页面标题"
            placeholder="默认：节点状态监控"
            value={currentTitle}
            onValueChange={(v) => setS({ ...s, status_title: v })}
            onKeyDown={(e) => {
              if (e.key === "Enter" && titleChanged) handleSaveTitle();
            }}
          />
          <Button
            color="primary"
            variant="flat"
            className="shrink-0"
            isLoading={savingTitle}
            isDisabled={!titleChanged || savingTitle}
            onPress={handleSaveTitle}
          >
            保存标题
          </Button>
        </div>

        <div className="flex flex-wrap sm:flex-nowrap items-center gap-2">
          <code className="w-full sm:flex-1 text-xs font-mono bg-default-100 rounded px-2.5 py-2 break-all">
            {url}
          </code>
          <div className="flex items-center gap-1.5 shrink-0">
            <Button size="sm" variant="flat" color={copied ? "success" : "default"} onPress={handleCopyUrl}>
              {copied ? "已复制 ✓" : "复制链接"}
            </Button>
            <Button size="sm" variant="flat" onPress={() => window.open(url, "_blank", "noopener,noreferrer")}>
              新窗口打开
            </Button>
          </div>
        </div>
        <p className="text-xs text-default-500">
          哪些转发机参与公开展示及展示别名，可在「总览」对应节点的「到期 / 额度」弹窗中单独控制。
        </p>
      </CardBody>
    </Card>
  );
}

export default function MonitorPage() {
  const [tab, setTab] = useState<Tab>("overview");
  const [chartNode, setChartNode] = useState<any | null>(null);
  const [metaNode, setMetaNode] = useState<any | null>(null);
  const [ver, setVer] = useState(0);
  return (
    <div className="px-3 lg:px-6 py-6 space-y-4">
      <div className="flex gap-1.5 flex-wrap">
        {TABS.map((t) => (
          <Button
            key={t.key}
            size="sm"
            variant={tab === t.key ? "solid" : "flat"}
            color={tab === t.key ? "primary" : "default"}
            onPress={() => setTab(t.key)}
          >
            {t.label}
          </Button>
        ))}
      </div>
      {tab === "overview" && <Overview ver={ver} onChart={setChartNode} onMeta={setMetaNode} />}
      {tab === "alert" && <AlertSettings />}
      {tab === "targets" && <Targets />}
      {tab === "status" && <StatusSettings />}
      <NodeMonitorModal node={chartNode} onClose={() => setChartNode(null)} />
      <MetaModal node={metaNode} onClose={() => setMetaNode(null)} onSaved={() => setVer((v) => v + 1)} />
    </div>
  );
}
