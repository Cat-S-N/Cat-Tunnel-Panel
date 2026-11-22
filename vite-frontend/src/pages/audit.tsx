import { useEffect, useState } from "react";
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
import toast from "react-hot-toast";
import { getAuditLogs, clearAuditLogs } from "@/api";

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

const fmtTime = (ms: number) => (ms ? new Date(ms).toLocaleString() : "-");

const escapeCsvCell = (raw: unknown): string => {
  let str = raw === null || raw === undefined ? "" : String(raw);
  if (/^[=+\-@\t\r]/.test(str)) {
    str = "'" + str;
  }
  if (/[",\n\r]/.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
};

type AuditStatusFilter = "all" | "success" | "failed";

const roleChipColor = (role: string): "danger" | "warning" | "default" => {
  if (role === "超级管理员") return "danger";
  if (role === "节点管理员") return "warning";
  return "default";
};

export default function AuditPage() {
  const [loading, setLoading] = useState(true);
  const [clearing, setClearing] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [status, setStatus] = useState<AuditStatusFilter>("all");
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [failedCount, setFailedCount] = useState(0);
  const [rows, setRows] = useState<AuditLogRow[]>([]);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const size = 20;

  const loadLogs = async (targetPage = page, targetKeyword = keyword, targetStatus = status) => {
    setLoading(true);
    try {
      const res = await getAuditLogs({
        page: targetPage,
        size,
        keyword: targetKeyword.trim() || undefined,
        status: targetStatus,
      });
      if (res.code === 0 && res.data) {
        setRows(Array.isArray(res.data.records) ? res.data.records : []);
        setTotal(Number(res.data.total || 0));
        setFailedCount(Number(res.data.failedCount || 0));
      } else {
        toast.error(res.msg || "加载操作日志失败");
      }
    } catch {
      toast.error("加载操作日志失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadLogs(page, keyword, status);
  }, [page, keyword, status]);

  const handleSearch = () => {
    setPage(1);
    setKeyword(searchInput);
  };

  const handleStatusChange = (nextStatus: AuditStatusFilter) => {
    if (nextStatus === status) return;
    setPage(1);
    setStatus(nextStatus);
  };

  const handleExportCsv = () => {
    if (rows.length === 0) {
      toast.error("当前页暂无可导出的记录");
      return;
    }
    const headers = ["时间", "操作人", "角色", "动作", "方法", "来源IP", "结果", "参数摘要"];
    const lines = [
      headers.map(escapeCsvCell).join(","),
      ...rows.map((row) =>
        [
          fmtTime(row.createdTime),
          row.username || "匿名",
          row.roleLabel || "-",
          row.action || "",
          row.method || "",
          row.ip || "-",
          row.resultCode === 0 ? "成功" : `失败(${row.resultCode})`,
          row.params || "",
        ]
          .map(escapeCsvCell)
          .join(",")
      ),
    ];
    const blob = new Blob(["\uFEFF" + lines.join("\r\n")], {
      type: "text/csv;charset=utf-8;",
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `audit-logs-page-${page}.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const handleClear = async () => {
    if (!confirm("确定清空全部历史操作审计日志吗？此操作不可恢复。")) return;
    setClearing(true);
    try {
      const res = await clearAuditLogs();
      if (res.code === 0) {
        toast.success(typeof res.data === "string" ? res.data : "已清空操作审计日志");
        setPage(1);
        loadLogs(1, keyword);
      } else {
        toast.error(res.msg || "清空失败");
      }
    } catch {
      toast.error("清空操作审计日志失败");
    } finally {
      setClearing(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / size));

  return (
    <div className="p-4 sm:p-6 max-w-7xl mx-auto space-y-4">
      <Card>
        <CardHeader className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-bold">📜 操作审计日志</h1>
              <Chip size="sm" variant="flat" color="default">
                共 {total} 条
              </Chip>
              <Chip size="sm" variant="flat" color={failedCount > 0 ? "danger" : "default"}>
                失败 {failedCount} 条
              </Chip>
            </div>
            <p className="text-xs text-default-500 mt-0.5">
              自动记录超级管理员、节点管理员及用户的关键变更操作（自动脱敏密码与密钥，保留 90 天）
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1">
              {(
                [
                  { key: "all", label: "全部" },
                  { key: "success", label: "成功" },
                  { key: "failed", label: "失败" },
                ] as const
              ).map((item) => (
                <Button
                  key={item.key}
                  size="sm"
                  variant={status === item.key ? "solid" : "flat"}
                  color={
                    status === item.key
                      ? item.key === "failed"
                        ? "danger"
                        : item.key === "success"
                        ? "success"
                        : "primary"
                      : "default"
                  }
                  onPress={() => handleStatusChange(item.key)}
                >
                  {item.label}
                </Button>
              ))}
            </div>
            <Input
              size="sm"
              className="w-60"
              placeholder="搜索用户名 / 动作 / IP / 参数"
              isClearable
              value={searchInput}
              onValueChange={setSearchInput}
              onKeyDown={(e) => {
                if (e.key === "Enter") handleSearch();
              }}
              onClear={() => {
                setSearchInput("");
                setPage(1);
                setKeyword("");
              }}
            />
            <Button size="sm" color="primary" onPress={handleSearch}>
              搜索
            </Button>
            <Button size="sm" variant="flat" onPress={handleExportCsv} isDisabled={rows.length === 0}>
              导出当前页 CSV
            </Button>
            <Button size="sm" variant="flat" onPress={() => loadLogs(page, keyword, status)} isLoading={loading}>
              刷新
            </Button>
            <Button size="sm" color="danger" variant="flat" onPress={handleClear} isLoading={clearing}>
              清空日志
            </Button>
          </div>
        </CardHeader>

        <CardBody>
          {loading && rows.length === 0 ? (
            <div className="py-12 flex justify-center">
              <Spinner label="加载审计日志中..." />
            </div>
          ) : (
            <>
              <Table aria-label="操作审计日志表格" removeWrapper>
                <TableHeader>
                  <TableColumn>时间</TableColumn>
                  <TableColumn>操作人</TableColumn>
                  <TableColumn>动作</TableColumn>
                  <TableColumn>来源 IP</TableColumn>
                  <TableColumn>结果</TableColumn>
                  <TableColumn>参数摘要</TableColumn>
                </TableHeader>
                <TableBody emptyContent="暂无操作审计日志">
                  {rows.map((row) => (
                    <TableRow key={row.id}>
                      <TableCell className="text-xs text-default-500 whitespace-nowrap">
                        {fmtTime(row.createdTime)}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-1.5 whitespace-nowrap">
                          <span className="text-sm font-medium">{row.username || "匿名"}</span>
                          {row.roleLabel && row.roleLabel !== "-" && (
                            <Chip size="sm" variant="flat" color={roleChipColor(row.roleLabel)}>
                              {row.roleLabel}
                            </Chip>
                          )}
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="space-y-0.5">
                          <div className="text-sm font-medium">{row.action}</div>
                          <div className="text-[11px] font-mono text-default-400">{row.method}</div>
                        </div>
                      </TableCell>
                      <TableCell className="font-mono text-xs whitespace-nowrap">{row.ip || "-"}</TableCell>
                      <TableCell>
                        <Chip
                          size="sm"
                          variant="flat"
                          color={row.resultCode === 0 ? "success" : "danger"}
                        >
                          {row.resultCode === 0 ? "成功" : `失败(${row.resultCode})`}
                        </Chip>
                      </TableCell>
                      <TableCell className="max-w-xs">
                        {row.params ? (
                          <button
                            type="button"
                            onClick={() => setExpandedId(expandedId === row.id ? null : row.id)}
                            className={`text-left font-mono text-xs text-default-600 hover:text-primary ${
                              expandedId === row.id ? "whitespace-pre-wrap break-all" : "truncate block max-w-[260px]"
                            }`}
                            title="点击展开/收起完整脱敏参数"
                          >
                            {row.params}
                          </button>
                        ) : (
                          <span className="text-xs text-default-400">-</span>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              <div className="flex flex-wrap items-center justify-between gap-2 pt-4">
                <span className="text-xs text-default-500">共 {total} 条记录</span>
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
    </div>
  );
}
