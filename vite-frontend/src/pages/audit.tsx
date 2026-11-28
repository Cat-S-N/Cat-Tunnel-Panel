import { useEffect, useMemo, useRef, useState } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Input } from "@heroui/input";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import {
  Table,
  TableHeader,
  TableColumn,
  TableBody,
  TableRow,
  TableCell,
} from "@heroui/table";
import { Pagination } from "@heroui/pagination";
import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
} from "@heroui/modal";
import toast from "react-hot-toast";
import {
  getAuditLogs,
  clearAuditLogs,
  type AuditCategoryFilter,
  type AuditStatusFilter,
} from "@/api";
import { copyTextToClipboard } from "@/utils/clipboard";
import { SearchIcon } from "@/components/icons";

interface AuditLogRow {
  id: number;
  userId?: number | null;
  username: string;
  roleLabel: string;
  action: string;
  method: string;
  ip: string;
  params: string;
  resultCode: number;
  createdTime: number;
}

const PAGE_SIZE = 20;
const EXPORT_BATCH_SIZE = 100;
const MAX_EXPORT_RECORDS = 1000;

const CATEGORY_OPTIONS: ReadonlyArray<{
  key: AuditCategoryFilter;
  label: string;
  hint: string;
}> = [
  { key: "all", label: "全部类型", hint: "展示全部操作审计事件" },
  { key: "login", label: "用户登录", hint: "用户登录（含登录成功与密码/限流失败尝试）" },
  { key: "register", label: "自助注册", hint: "用户自助注册（含受邀注册与失败拦截）" },
  { key: "password", label: "改密", hint: "修改账号密码（自动抹除全部密码字段）" },
  {
    key: "config",
    label: "系统配置",
    hint: "网站配置更新、邀请码与告警设置变更、测试告警、清空审计日志",
  },
  {
    key: "user",
    label: "用户管理",
    hint: "创建/更新/删除用户、重置用户流量、QX 订阅权限、分配节点管理权限",
  },
  {
    key: "node",
    label: "节点与协议",
    hint: "转发机节点、协议入站、一键搭中转、用户线路配额与落地管理",
  },
  {
    key: "forward",
    label: "转发与隧道",
    hint: "端口/隧道转发启停与排序、隧道管理、用户隧道分配与限速规则",
  },
];

const STATUS_OPTIONS: ReadonlyArray<{
  key: AuditStatusFilter;
  label: string;
}> = [
  { key: "all", label: "全部结果" },
  { key: "success", label: "成功" },
  { key: "failed", label: "失败" },
];

const fmtTime = (ms: number): string => {
  if (!ms) return "-";
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return "-";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours()
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};

const buildExportTimestamp = (): string => {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(
    d.getHours()
  )}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
};

/**
 * CSV 安全单元格转义：
 * 1. 防电子表格公式注入（Excel / WPS / Calc）：以 =, +, -, @, |, Tab, CR, LF 开头，
 *    或前导空白后紧跟危险公式前缀时，统一前置单引号 "'"；
 * 2. 标准 RFC 4180 引号转义：内部双引号翻倍为 ""，含逗号/引号/换行时整体包裹双引号。
 */
export const escapeCsvCell = (raw: unknown): string => {
  let str = raw === null || raw === undefined ? "" : String(raw);
  if (/^(?:[=+\-@|\t\r\n]|[\t\r\n ]+[=+\-@|])/.test(str)) {
    str = "'" + str;
  }
  if (/[",\n\r]/.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
};

const triggerBlobDownload = (blob: Blob, filename: string) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
};

const roleChipColor = (role: string): "danger" | "warning" | "default" => {
  if (role === "超级管理员") return "danger";
  if (role === "节点管理员") return "warning";
  return "default";
};

const matchesLocalKeyword = (row: AuditLogRow, rawKeyword: string): boolean => {
  const kw = rawKeyword.trim().toLowerCase();
  if (!kw) return true;
  const haystacks = [
    row.username || "匿名",
    row.roleLabel,
    row.action,
    row.method,
    row.ip,
    row.params,
  ];
  return haystacks.some((field) => field && field.toLowerCase().includes(kw));
};

export default function AuditPage() {
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [clearing, setClearing] = useState(false);
  const [clearModalOpen, setClearModalOpen] = useState(false);
  const [exportingFormat, setExportingFormat] = useState<"csv" | "json" | null>(null);

  const [searchInput, setSearchInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState<AuditStatusFilter>("all");
  const [category, setCategory] = useState<AuditCategoryFilter>("all");

  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [failedCount, setFailedCount] = useState(0);
  const [rows, setRows] = useState<AuditLogRow[]>([]);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [copiedRowId, setCopiedRowId] = useState<number | null>(null);

  const requestSeqRef = useRef(0);
  const debounceTimerRef = useRef<number | null>(null);
  const copyTimerRef = useRef<number | null>(null);

  // 搜索词即时过滤：输入停顿 250ms 自动同步到查询条件并回到第 1 页
  useEffect(() => {
    const trimmedInput = searchInput.trim();
    const trimmedKeyword = keyword.trim();
    if (trimmedInput === trimmedKeyword) {
      return;
    }
    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = window.setTimeout(() => {
      setPage(1);
      setKeyword(searchInput);
    }, 250);
    return () => {
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
    };
  }, [searchInput, keyword]);

  useEffect(() => {
    return () => {
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
      }
      if (copyTimerRef.current !== null) {
        window.clearTimeout(copyTimerRef.current);
      }
    };
  }, []);

  const loadLogs = async (
    targetPage = page,
    targetKeyword = keyword,
    targetStatus = status,
    targetCategory = category,
    showFullSpinner = !hasLoaded
  ) => {
    const seq = ++requestSeqRef.current;
    if (showFullSpinner) {
      setLoading(true);
    } else {
      setRefreshing(true);
    }
    try {
      const res = await getAuditLogs({
        page: targetPage,
        size: PAGE_SIZE,
        keyword: targetKeyword.trim() || undefined,
        status: targetStatus,
        category: targetCategory,
      });
      if (seq !== requestSeqRef.current) {
        return;
      }
      if (res.code === 0 && res.data) {
        setRows(Array.isArray(res.data.records) ? res.data.records : []);
        setTotal(Number(res.data.total || 0));
        setFailedCount(Number(res.data.failedCount || 0));
        setLoadError(null);
        setHasLoaded(true);
      } else {
        const msg = res.msg || "加载操作审计日志失败";
        setLoadError(msg);
        toast.error(msg);
      }
    } catch {
      if (seq !== requestSeqRef.current) {
        return;
      }
      const msg = "网络异常，加载操作审计日志失败";
      setLoadError(msg);
      toast.error(msg);
    } finally {
      if (seq === requestSeqRef.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  };

  useEffect(() => {
    loadLogs(page, keyword, status, category, !hasLoaded);
  }, [page, keyword, status, category]);

  // 在 250ms 防抖尚未向后端发出请求前，先对当前已加载页做瞬时客户端过滤，响应零延迟
  const displayedRows = useMemo(() => {
    const pendingTrimmed = searchInput.trim();
    if (!pendingTrimmed || pendingTrimmed === keyword.trim()) {
      return rows;
    }
    return rows.filter((row) => matchesLocalKeyword(row, pendingTrimmed));
  }, [rows, searchInput, keyword]);

  const hasActiveFilters =
    category !== "all" ||
    status !== "all" ||
    Boolean(searchInput.trim()) ||
    Boolean(keyword.trim());

  const activeCategoryLabel = useMemo(
    () => CATEGORY_OPTIONS.find((item) => item.key === category)?.label || "全部类型",
    [category]
  );

  const handleImmediateSearch = () => {
    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    if (page === 1 && searchInput === keyword) {
      loadLogs(1, searchInput, status, category, false);
      return;
    }
    setPage(1);
    setKeyword(searchInput);
  };

  const handleQuickKeyword = (nextVal: string) => {
    const clean = nextVal.trim();
    if (!clean) return;
    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    setSearchInput(clean);
    setPage(1);
    setKeyword(clean);
  };

  const handleStatusChange = (nextStatus: AuditStatusFilter) => {
    if (nextStatus === status) return;
    setPage(1);
    setStatus(nextStatus);
  };

  const handleCategoryChange = (nextCategory: AuditCategoryFilter) => {
    if (nextCategory === category) return;
    setPage(1);
    setCategory(nextCategory);
  };

  const handleResetFilters = () => {
    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    setSearchInput("");
    setKeyword("");
    setStatus("all");
    setCategory("all");
    setPage(1);
  };

  const handleCopyParams = async (rowId: number, text: string) => {
    const ok = await copyTextToClipboard(text);
    if (ok) {
      setCopiedRowId(rowId);
      if (copyTimerRef.current !== null) {
        window.clearTimeout(copyTimerRef.current);
      }
      copyTimerRef.current = window.setTimeout(() => {
        setCopiedRowId((prev) => (prev === rowId ? null : prev));
      }, 1500);
      toast.success("已复制脱敏参数摘要");
    } else {
      toast.error("复制失败，请手动展开后选择复制");
    }
  };

  /**
   * 获取当前筛选条件下的导出数据集：
   * - 若全部匹配结果已在当前页内存中且搜索词已同步，直接复用当前结果；
   * - 若匹配结果跨多页，按每页 100 条拉取当前筛选结果（单次安全上限 1000 条）。
   */
  const collectFilteredRowsForExport = async (): Promise<{
    records: AuditLogRow[];
    totalMatched: number;
    effectiveKeyword: string;
  } | null> => {
    const effectiveKeyword = searchInput.trim();
    if (
      effectiveKeyword === keyword.trim() &&
      page === 1 &&
      total <= rows.length &&
      rows.length > 0
    ) {
      return { records: rows, totalMatched: total, effectiveKeyword };
    }

    const collected: AuditLogRow[] = [];
    let totalMatched = 0;
    const maxPages = Math.ceil(MAX_EXPORT_RECORDS / EXPORT_BATCH_SIZE);

    for (let p = 1; p <= maxPages; p++) {
      const res = await getAuditLogs({
        page: p,
        size: EXPORT_BATCH_SIZE,
        keyword: effectiveKeyword || undefined,
        status,
        category,
      });
      if (res.code !== 0 || !res.data) {
        toast.error(res.msg || "拉取导出数据失败");
        return null;
      }
      const batch: AuditLogRow[] = Array.isArray(res.data.records)
        ? res.data.records
        : [];
      totalMatched = Number(res.data.total || 0);
      collected.push(...batch);
      if (
        batch.length < EXPORT_BATCH_SIZE ||
        collected.length >= totalMatched ||
        collected.length >= MAX_EXPORT_RECORDS
      ) {
        break;
      }
    }

    return {
      records: collected.slice(0, MAX_EXPORT_RECORDS),
      totalMatched,
      effectiveKeyword,
    };
  };

  const handleExport = async (format: "csv" | "json") => {
    if (exportingFormat !== null) return;
    setExportingFormat(format);
    try {
      const dataset = await collectFilteredRowsForExport();
      if (!dataset) return;
      const { records, totalMatched, effectiveKeyword } = dataset;
      if (records.length === 0) {
        toast.error("当前筛选条件下暂无可导出的记录");
        return;
      }

      const stamp = buildExportTimestamp();
      const filterSuffix = category !== "all" ? `-${category}` : "";

      if (format === "csv") {
        const headers = [
          "记录ID",
          "时间",
          "操作人",
          "用户ID",
          "角色",
          "动作",
          "控制器方法",
          "来源IP",
          "结果",
          "状态码",
          "参数摘要(已脱敏)",
        ];
        const lines = [
          headers.map(escapeCsvCell).join(","),
          ...records.map((row) =>
            [
              row.id,
              fmtTime(row.createdTime),
              row.username || "匿名",
              row.userId ?? "",
              row.roleLabel || "-",
              row.action || "",
              row.method || "",
              row.ip || "-",
              row.resultCode === 0 ? "成功" : `失败(${row.resultCode})`,
              row.resultCode,
              row.params || "",
            ]
              .map(escapeCsvCell)
              .join(",")
          ),
        ];
        const blob = new Blob(["\uFEFF" + lines.join("\r\n")], {
          type: "text/csv;charset=utf-8;",
        });
        triggerBlobDownload(blob, `audit-logs${filterSuffix}-${stamp}.csv`);
      } else {
        const payload = {
          exportedAt: new Date().toISOString(),
          filters: {
            category,
            categoryLabel: activeCategoryLabel,
            status,
            keyword: effectiveKeyword || null,
          },
          exportedCount: records.length,
          totalMatched,
          records: records.map((row) => ({
            id: row.id,
            createdTime: row.createdTime,
            createdTimeText: fmtTime(row.createdTime),
            userId: row.userId ?? null,
            username: row.username || "匿名",
            roleLabel: row.roleLabel || "-",
            action: row.action || "",
            method: row.method || "",
            ip: row.ip || "-",
            resultCode: row.resultCode,
            resultText: row.resultCode === 0 ? "成功" : `失败(${row.resultCode})`,
            params: row.params || "",
          })),
        };
        const blob = new Blob([JSON.stringify(payload, null, 2)], {
          type: "application/json;charset=utf-8;",
        });
        triggerBlobDownload(blob, `audit-logs${filterSuffix}-${stamp}.json`);
      }

      if (totalMatched > records.length) {
        toast.success(
          `已安全导出最近 ${records.length} 条记录（共匹配 ${totalMatched} 条，单次上限 ${MAX_EXPORT_RECORDS} 条）`
        );
      } else {
        toast.success(`已导出 ${records.length} 条审计记录（${format.toUpperCase()}）`);
      }
    } catch {
      toast.error(`导出 ${format.toUpperCase()} 失败，请稍后重试`);
    } finally {
      setExportingFormat(null);
    }
  };

  const handleConfirmClear = async () => {
    if (clearing) return;
    setClearing(true);
    try {
      const res = await clearAuditLogs();
      if (res.code === 0) {
        toast.success(typeof res.data === "string" ? res.data : "已清空操作审计日志");
        setClearModalOpen(false);
        setExpandedId(null);
        setPage(1);
        loadLogs(1, keyword, status, category, false);
      } else {
        toast.error(res.msg || "清空失败");
      }
    } catch {
      toast.error("清空操作审计日志失败");
    } finally {
      setClearing(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="px-3 sm:px-6 py-4 sm:py-6 max-w-7xl mx-auto space-y-4">
      <Card>
        <CardHeader className="flex flex-col gap-3.5 pb-2">
          {/* 标题统计区 + 右侧操作按钮组 */}
          <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3 w-full">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-lg sm:text-xl font-bold text-foreground">操作审计日志</h1>
                <Chip size="sm" variant="flat" color="default" className="font-mono tabular-nums">
                  匹配 {total} 条
                </Chip>
                <Chip
                  size="sm"
                  variant="flat"
                  color={failedCount > 0 ? "danger" : "default"}
                  className="font-mono tabular-nums"
                >
                  失败 {failedCount} 条
                </Chip>
              </div>
              <p className="text-xs text-default-500 mt-1 leading-relaxed">
                自动记录超级管理员、节点管理员及用户的关键操作事件（密码、邀请码、密钥全程自动脱敏，保留最近 90 天）
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <Button
                size="sm"
                variant="flat"
                onPress={() => handleExport("csv")}
                isLoading={exportingFormat === "csv"}
                isDisabled={
                  (total === 0 && displayedRows.length === 0) ||
                  exportingFormat !== null
                }
              >
                导出 CSV
              </Button>
              <Button
                size="sm"
                variant="flat"
                onPress={() => handleExport("json")}
                isLoading={exportingFormat === "json"}
                isDisabled={
                  (total === 0 && displayedRows.length === 0) ||
                  exportingFormat !== null
                }
              >
                导出 JSON
              </Button>
              <Button
                size="sm"
                variant="flat"
                onPress={() => loadLogs(page, keyword, status, category, false)}
                isLoading={refreshing}
                isDisabled={loading && !hasLoaded}
              >
                刷新
              </Button>
              <Button
                size="sm"
                color="danger"
                variant="flat"
                onPress={() => setClearModalOpen(true)}
                isDisabled={clearing || (hasLoaded && !hasActiveFilters && total === 0)}
              >
                清空日志
              </Button>
            </div>
          </div>

          {/* 检索与快捷筛选面板 */}
          <div className="tms-subpanel w-full rounded-md border border-default-200 bg-default-50/70 p-2.5 sm:p-3 space-y-2.5">
            {/* 第一行：即时关键词搜索 + 状态切换 + 重置 */}
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2.5">
              <div className="flex items-center gap-2 flex-1 max-w-xl">
                <Input
                  size="sm"
                  className="flex-1 min-w-0"
                  placeholder="搜索用户名 / 来源IP / 动作 / 参数详情（按 / 聚焦）"
                  startContent={<SearchIcon className="w-4 h-4 text-default-400 shrink-0" />}
                  isClearable
                  value={searchInput}
                  onValueChange={setSearchInput}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") handleImmediateSearch();
                  }}
                  onClear={() => {
                    if (debounceTimerRef.current !== null) {
                      window.clearTimeout(debounceTimerRef.current);
                      debounceTimerRef.current = null;
                    }
                    setSearchInput("");
                    setPage(1);
                    setKeyword("");
                  }}
                />
                <Button
                  size="sm"
                  color="primary"
                  onPress={handleImmediateSearch}
                  className="shrink-0"
                >
                  搜索
                </Button>
              </div>

              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-xs text-default-500 mr-0.5 select-none">状态:</span>
                {STATUS_OPTIONS.map((item) => {
                  const active = status === item.key;
                  return (
                    <Button
                      key={item.key}
                      size="sm"
                      variant={active ? "solid" : "flat"}
                      color={
                        active
                          ? item.key === "failed"
                            ? "danger"
                            : item.key === "success"
                            ? "success"
                            : "primary"
                          : "default"
                      }
                      className="h-7 min-w-0 px-2.5 text-xs font-mono tabular-nums"
                      onPress={() => handleStatusChange(item.key)}
                    >
                      {item.key === "failed" && failedCount > 0
                        ? `${item.label} (${failedCount})`
                        : item.label}
                    </Button>
                  );
                })}
                {hasActiveFilters && (
                  <Button
                    size="sm"
                    variant="light"
                    color="default"
                    className="h-7 min-w-0 px-2 text-xs"
                    onPress={handleResetFilters}
                  >
                    重置筛选
                  </Button>
                )}
              </div>
            </div>

            {/* 第二行：事件类型快捷筛选 */}
            <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
              <span className="text-xs text-default-500 mr-0.5 select-none">事件类型:</span>
              {CATEGORY_OPTIONS.map((item) => {
                const active = category === item.key;
                return (
                  <Button
                    key={item.key}
                    size="sm"
                    variant={active ? "solid" : "flat"}
                    color={active ? "primary" : "default"}
                    title={item.hint}
                    className="h-7 min-w-0 px-2.5 text-xs"
                    onPress={() => handleCategoryChange(item.key)}
                  >
                    {item.label}
                  </Button>
                );
              })}
            </div>
          </div>
        </CardHeader>

        <CardBody className="pt-2 space-y-3">
          {/* 已加载数据后刷新/筛选异常提示条 */}
          {loadError && hasLoaded && (
            <div className="tms-inline-alert-danger rounded-lg border border-danger/40 bg-danger/10 px-3.5 py-2.5 flex items-center justify-between gap-3 flex-wrap">
              <div className="text-xs sm:text-sm text-danger font-medium min-w-0 break-words">
                加载异常：{loadError}（当前展示上次已加载记录）
              </div>
              <Button
                size="sm"
                color="danger"
                variant="flat"
                isLoading={refreshing}
                onPress={() => loadLogs(page, keyword, status, category, false)}
              >
                重试
              </Button>
            </div>
          )}

          {/* 首次加载中 */}
          {loading && !hasLoaded ? (
            <div className="py-16 flex justify-center">
              <Spinner label="正在加载操作审计日志..." />
            </div>
          ) : loadError && !hasLoaded ? (
            /* 首次加载失败错误卡片 */
            <div className="bg-content1 tms-alert-card-danger rounded-lg border border-danger/40 bg-danger/5 py-14 px-4 text-center space-y-4">
              <div className="space-y-1.5">
                <h3 className="text-base font-semibold text-danger">加载操作审计日志失败</h3>
                <p className="text-sm text-default-500 max-w-md mx-auto break-words">
                  {loadError}
                </p>
              </div>
              <div>
                <Button
                  size="sm"
                  color="primary"
                  variant="flat"
                  onPress={() => loadLogs(page, keyword, status, category, true)}
                >
                  重试加载
                </Button>
              </div>
            </div>
          ) : displayedRows.length === 0 ? (
            hasActiveFilters ? (
              /* 筛选无结果空状态引导 */
              <div className="tms-empty-dashed rounded-lg border border-dashed border-default-300 py-14 px-4 text-center space-y-3">
                <div className="space-y-1">
                  <h3 className="text-base font-semibold text-foreground">
                    未找到匹配的审计记录
                  </h3>
                  <p className="text-xs sm:text-sm text-default-500 max-w-lg mx-auto break-words">
                    当前筛选条件（类型：{activeCategoryLabel}
                    {status !== "all"
                      ? ` · 状态：${status === "success" ? "仅成功" : "仅失败"}`
                      : ""}
                    {searchInput.trim() ? ` · 关键词：「${searchInput.trim()}」` : ""}
                    ）下没有匹配的日志记录。
                  </p>
                </div>
                <div className="flex justify-center items-center gap-2 pt-1">
                  <Button
                    size="sm"
                    color="primary"
                    variant="flat"
                    onPress={handleResetFilters}
                  >
                    一键重置筛选
                  </Button>
                  <Button
                    size="sm"
                    variant="flat"
                    isLoading={refreshing}
                    onPress={() => loadLogs(page, keyword, status, category, false)}
                  >
                    重新查询
                  </Button>
                </div>
              </div>
            ) : (
              /* 系统尚无任何日志空状态 */
              <div className="tms-empty-dashed rounded-lg border border-dashed border-default-300 py-16 px-4 text-center space-y-3">
                <div className="space-y-1">
                  <h3 className="text-base font-semibold text-foreground">
                    暂无操作审计日志
                  </h3>
                  <p className="text-xs sm:text-sm text-default-500 max-w-md mx-auto">
                    当管理员或用户执行登录、注册、改密、配置变更、节点与转发管理等操作时，系统将自动脱敏记录在此。
                  </p>
                </div>
                <div>
                  <Button
                    size="sm"
                    variant="flat"
                    isLoading={refreshing}
                    onPress={() => loadLogs(1, keyword, status, category, false)}
                  >
                    刷新列表
                  </Button>
                </div>
              </div>
            )
          ) : (
            <>
              {/* 桌面端高密度像素表格 */}
              <div className="hidden md:block overflow-x-auto">
                <Table aria-label="操作审计日志表格" removeWrapper>
                  <TableHeader>
                    <TableColumn>时间</TableColumn>
                    <TableColumn>操作人</TableColumn>
                    <TableColumn>事件动作</TableColumn>
                    <TableColumn>来源 IP</TableColumn>
                    <TableColumn>结果</TableColumn>
                    <TableColumn>脱敏参数摘要</TableColumn>
                  </TableHeader>
                  <TableBody emptyContent="暂无操作审计日志">
                    {displayedRows.map((row) => {
                      const isExpanded = expandedId === row.id;
                      const isCopied = copiedRowId === row.id;
                      return (
                        <TableRow key={row.id}>
                          <TableCell className="text-xs font-mono tabular-nums text-default-500 whitespace-nowrap align-top">
                            {fmtTime(row.createdTime)}
                          </TableCell>
                          <TableCell className="align-top">
                            <div className="flex items-center gap-1.5 whitespace-nowrap">
                              <button
                                type="button"
                                onClick={() => handleQuickKeyword(row.username || "匿名")}
                                className="text-sm font-medium text-foreground hover:underline text-left"
                                title="点击按该操作人筛选"
                              >
                                {row.username || "匿名"}
                              </button>
                              {row.roleLabel && row.roleLabel !== "-" && (
                                <Chip
                                  size="sm"
                                  variant="flat"
                                  color={roleChipColor(row.roleLabel)}
                                  className="tms-role-badge"
                                >
                                  {row.roleLabel}
                                </Chip>
                              )}
                            </div>
                          </TableCell>
                          <TableCell className="align-top">
                            <div className="space-y-0.5">
                              <button
                                type="button"
                                onClick={() => handleQuickKeyword(row.action)}
                                className="text-sm font-medium text-foreground hover:underline text-left"
                                title="点击按该动作名称检索"
                              >
                                {row.action}
                              </button>
                              <div className="text-[11px] font-mono text-default-400">
                                {row.method}
                              </div>
                            </div>
                          </TableCell>
                          <TableCell className="font-mono text-xs whitespace-nowrap align-top">
                            {row.ip && row.ip !== "-" ? (
                              <button
                                type="button"
                                onClick={() => handleQuickKeyword(row.ip)}
                                className="hover:underline text-foreground"
                                title="点击按该来源 IP 检索"
                              >
                                {row.ip}
                              </button>
                            ) : (
                              <span className="text-default-400">-</span>
                            )}
                          </TableCell>
                          <TableCell className="whitespace-nowrap align-top">
                            <Chip
                              size="sm"
                              variant="flat"
                              color={row.resultCode === 0 ? "success" : "danger"}
                            >
                              {row.resultCode === 0
                                ? "成功"
                                : `失败(${row.resultCode})`}
                            </Chip>
                          </TableCell>
                          <TableCell className="max-w-sm align-top">
                            {row.params ? (
                              <div className="space-y-1">
                                <button
                                  type="button"
                                  onClick={() =>
                                    setExpandedId(isExpanded ? null : row.id)
                                  }
                                  className={`text-left font-mono text-xs text-default-600 hover:text-foreground ${
                                    isExpanded
                                      ? "whitespace-pre-wrap break-all block"
                                      : "truncate block max-w-[280px] lg:max-w-[360px]"
                                  }`}
                                  title="点击展开或收起完整脱敏参数"
                                >
                                  {row.params}
                                </button>
                                {isExpanded && (
                                  <div className="flex items-center gap-2 pt-0.5">
                                    <button
                                      type="button"
                                      onClick={() =>
                                        handleCopyParams(row.id, row.params)
                                      }
                                      className="text-[11px] font-mono text-default-500 hover:text-foreground underline"
                                    >
                                      {isCopied ? "✓ 已复制" : "复制参数"}
                                    </button>
                                    <button
                                      type="button"
                                      onClick={() => setExpandedId(null)}
                                      className="text-[11px] font-mono text-default-400 hover:text-foreground"
                                    >
                                      收起
                                    </button>
                                  </div>
                                )}
                              </div>
                            ) : (
                              <span className="text-xs text-default-400">-</span>
                            )}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </div>

              {/* 移动端窄屏自适应像素卡片列表（零横向溢出） */}
              <div className="md:hidden space-y-2.5">
                {displayedRows.map((row) => {
                  const isExpanded = expandedId === row.id;
                  const isCopied = copiedRowId === row.id;
                  return (
                    <div
                      key={row.id}
                      className="tms-subpanel-card rounded-md border border-default-200 p-3 space-y-2"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <button
                            type="button"
                            onClick={() => handleQuickKeyword(row.action)}
                            className="text-sm font-semibold text-foreground text-left break-words hover:underline"
                          >
                            {row.action}
                          </button>
                          <div className="text-[11px] font-mono text-default-400 break-all">
                            {row.method}
                          </div>
                        </div>
                        <Chip
                          size="sm"
                          variant="flat"
                          color={row.resultCode === 0 ? "success" : "danger"}
                          className="shrink-0"
                        >
                          {row.resultCode === 0
                            ? "成功"
                            : `失败(${row.resultCode})`}
                        </Chip>
                      </div>

                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-default-600">
                        <div className="flex items-center gap-1.5 min-w-0">
                          <span className="text-default-400">操作人:</span>
                          <button
                            type="button"
                            onClick={() =>
                              handleQuickKeyword(row.username || "匿名")
                            }
                            className="font-medium text-foreground truncate max-w-[140px] hover:underline"
                          >
                            {row.username || "匿名"}
                          </button>
                          {row.roleLabel && row.roleLabel !== "-" && (
                            <Chip
                              size="sm"
                              variant="flat"
                              color={roleChipColor(row.roleLabel)}
                              className="tms-role-badge h-5 text-[10px]"
                            >
                              {row.roleLabel}
                            </Chip>
                          )}
                        </div>
                        <div className="flex items-center gap-1 min-w-0">
                          <span className="text-default-400">IP:</span>
                          {row.ip && row.ip !== "-" ? (
                            <button
                              type="button"
                              onClick={() => handleQuickKeyword(row.ip)}
                              className="font-mono text-foreground break-all hover:underline"
                            >
                              {row.ip}
                            </button>
                          ) : (
                            <span className="font-mono text-default-400">-</span>
                          )}
                        </div>
                      </div>

                      <div className="text-[11px] font-mono tabular-nums text-default-400">
                        {fmtTime(row.createdTime)}
                      </div>

                      {row.params && (
                        <div className="tms-inline-panel rounded border border-default-200 bg-default-50 p-2 space-y-1">
                          <button
                            type="button"
                            onClick={() =>
                              setExpandedId(isExpanded ? null : row.id)
                            }
                            className={`w-full text-left font-mono text-xs text-default-600 ${
                              isExpanded
                                ? "whitespace-pre-wrap break-all"
                                : "line-clamp-2 break-all"
                            }`}
                          >
                            {row.params}
                          </button>
                          <div className="flex items-center justify-end gap-3 pt-0.5">
                            <button
                              type="button"
                              onClick={() => handleCopyParams(row.id, row.params)}
                              className="text-[11px] font-mono text-default-500 hover:text-foreground underline"
                            >
                              {isCopied ? "✓ 已复制" : "复制参数"}
                            </button>
                            <button
                              type="button"
                              onClick={() =>
                                setExpandedId(isExpanded ? null : row.id)
                              }
                              className="text-[11px] font-mono text-default-500 hover:text-foreground"
                            >
                              {isExpanded ? "收起详情" : "展开详情"}
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              {/* 底部分页条 */}
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pt-3 border-t border-divider">
                <span className="text-xs font-mono tabular-nums text-default-500">
                  共 {total} 条记录 · 第 {page}/{totalPages} 页
                </span>
                {totalPages > 1 && (
                  <Pagination
                    size="sm"
                    total={totalPages}
                    page={page}
                    onChange={setPage}
                  />
                )}
              </div>
            </>
          )}
        </CardBody>
      </Card>

      {/* 清空历史审计日志确认弹窗 */}
      <Modal
        isOpen={clearModalOpen}
        onClose={() => !clearing && setClearModalOpen(false)}
        size="md"
        placement="center"
      >
        <ModalContent>
          <ModalHeader className="text-base font-bold">
            确认清空操作审计日志
          </ModalHeader>
          <ModalBody>
            <p className="text-sm text-default-600 leading-relaxed">
              确定清空全部历史操作审计日志吗？此操作不可恢复，清空动作本身将保留一条新的「清空操作审计日志」审计记录以便追溯。
            </p>
          </ModalBody>
          <ModalFooter>
            <Button
              size="sm"
              variant="flat"
              onPress={() => setClearModalOpen(false)}
              isDisabled={clearing}
            >
              取消
            </Button>
            <Button
              size="sm"
              color="danger"
              onPress={handleConfirmClear}
              isLoading={clearing}
            >
              确认清空
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
