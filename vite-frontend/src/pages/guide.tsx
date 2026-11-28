import { useEffect, useMemo, useRef, useState } from "react";
import { Card, CardBody } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Accordion, AccordionItem } from "@heroui/accordion";
import { Input } from "@heroui/input";
import { Button } from "@heroui/button";
import toast from "react-hot-toast";

import { copyTextToClipboard } from "@/utils/clipboard";

/**
 * 使用说明:四个功能(协议管理 / 中转 / 端口转发 / 隧道转发)啥区别、啥时候用哪个。
 * 纯说明页,侧栏「使用说明」进来。
 *
 * 排版取向:这页是拿来「查」的不是拿来「读」的 —— 所以四个功能做成卡片网格一眼扫完,
 * 决策树画成分叉的方块而不是嵌套列表,链路图用等宽块并把关键节点挑出颜色。
 */

type Platform = "全部" | "Windows" | "macOS" | "Linux" | "Android" | "iOS";

const PLATFORMS: Platform[] = ["全部", "Windows", "macOS", "Linux", "Android", "iOS"];

const SECTIONS = [
  { id: "sec-features", label: "四个功能" },
  { id: "sec-decision", label: "该用哪个" },
  { id: "sec-clients", label: "客户端推荐" },
  { id: "sec-advanced", label: "两层中转" },
  { id: "sec-compare", label: "对照表" },
  { id: "sec-troubleshoot", label: "排障命令" },
  { id: "sec-faq", label: "常见问题" },
];

/** 四个功能的主色,全页统一:哪儿提到某个功能,颜色就是这个 */
const FEATURES = [
  {
    name: "协议管理",
    color: "primary" as const,
    dot: "bg-primary",
    icon: "🛡️",
    headline: "给车友卖翻墙 · 出口在本机",
    body: "搭 VLESS-Reality 等协议 → 出订阅给车友。出口就是搭协议的那台机器本身。",
  },
  {
    name: "中转",
    color: "warning" as const,
    dot: "bg-warning",
    icon: "🔀",
    headline: "给车友卖翻墙 · 出口换成干净落地",
    body: "前置机搭协议,流量经落地(住宅 socks / 别人的节点)出网。前置机负责抗封锁,落地负责干净出口。",
  },
  {
    name: "端口转发",
    color: "secondary" as const,
    dot: "bg-secondary",
    icon: "🔌",
    headline: "搬一个普通端口到任意地址 · 1 跳",
    body: "客户端拿到的是裸端口不是订阅。常用于救被墙的节点、给只认地址端口的服务换入口。",
  },
  {
    name: "隧道转发",
    color: "default" as const,
    dot: "bg-default-400",
    icon: "🔒",
    headline: "两台机器之间走加密隧道 · 2 跳",
    body: "由后面那台去连目标。只在「境内入口 → 境外裸落地」时才需要,其它情况端口转发就够。",
  },
];

interface ClientItem {
  name: string;
  platforms: Exclude<Platform, "全部">[];
  protocols: string;
  desc: string;
  tip: string;
}

const CLIENTS: ClientItem[] = [
  {
    name: "Clash Verge Rev",
    platforms: ["Windows", "macOS", "Linux"],
    protocols: "VLESS / Reality / Trojan / SS / Hysteria2",
    desc: "桌面端主力推荐，支持 Mihomo 内核与自动更新订阅。",
    tip: "在「订阅」页点击添加，粘贴面板生成的订阅链接即可。",
  },
  {
    name: "v2rayN",
    platforms: ["Windows", "Linux"],
    protocols: "VLESS / Reality / Trojan / SS / Hysteria2",
    desc: "老牌轻量客户端，适合习惯直接看节点延迟与日志的用户。",
    tip: "复制面板订阅地址 → 订阅分组 → 添加并更新订阅。",
  },
  {
    name: "Shadowrocket (小火箭)",
    platforms: ["iOS"],
    protocols: "VLESS / Reality / Trojan / SS / Hysteria2",
    desc: "iOS 端最通用客户端，需外区 Apple ID 下载。",
    tip: "支持扫码或点击「一键导入」，开启分流后日常使用稳定。",
  },
  {
    name: "Quantumult X",
    platforms: ["iOS", "macOS"],
    protocols: "VLESS / Trojan / SS",
    desc: "支持精细化分流规则与重写，面板已内置 QuanX 专用订阅。",
    tip: "在面板复制「QuanX 专用订阅」链接，添加到节点订阅即可。",
  },
  {
    name: "Clash Meta for Android (CMFA)",
    platforms: ["Android"],
    protocols: "VLESS / Reality / Trojan / SS / Hysteria2",
    desc: "Android 端推荐，兼容最新 Reality 与 Hysteria2 协议。",
    tip: "配置 → 新建 → 从 URL 导入，粘贴订阅地址并启用。",
  },
  {
    name: "v2rayNG",
    platforms: ["Android"],
    protocols: "VLESS / Reality / Trojan / SS / Hysteria2",
    desc: "Android 端简洁开源客户端，低内存占用。",
    tip: "右上角「+」→ 从剪贴板导入或配置订阅分组后更新。",
  },
];

interface TroubleshootSnippet {
  id: string;
  title: string;
  desc: string;
  cmd: string;
}

const TROUBLESHOOT_SNIPPETS: TroubleshootSnippet[] = [
  {
    id: "sub-check",
    title: "检查订阅地址连通性",
    desc: "在终端测试订阅链接是否能正常返回 200 状态码与节点内容",
    cmd: 'curl -sSL -o /dev/null -w "HTTP %{http_code} | 大小 %{size_download}B | 耗时 %{time_total}s\\n" "https://你的面板域名/api/v1/open_api/sub?token=你的订阅Token"',
  },
  {
    id: "gost-ver",
    title: "查看节点 gost 版本",
    desc: "确认节点端 gost 是否为最新版（避免旧版缺自签证书导致协议全 -1）",
    cmd: "gost -V",
  },
  {
    id: "port-listen",
    title: "检查本机协议监听端口",
    desc: "排查 40000+ 本地协议端口与公网转发端口是否正常监听",
    cmd: "ss -tulpn | grep -E 'gost|sing-box|4000'",
  },
];

interface FaqItem {
  key: string;
  ariaLabel: string;
  title: string;
  keywords: string;
  content: React.ReactNode;
}

const FAQ_ITEMS: FaqItem[] = [
  {
    key: "q1",
    ariaLabel: "端口转发 socks 为啥要国内机开头",
    title: "端口转发 socks 为啥要「国内机开头」,香港放第一个就用不了?",
    keywords: "端口转发 socks 国内机 香港 过墙 Reality",
    content: (
      <p className="text-sm text-default-500 leading-relaxed pb-2">
        端口转发是<b>裸转发、不加密不伪装</b>。客户端家宽直连香港这一跳要穿墙,裸 socks 容易被 GFW
        识别/干扰、香港 IP 也容易被封,加上家宽国际线路本来就烂 → 连不上。
        加一台国内机顶在前面:客户端连国内(快、不穿墙),国内机再用优质专线到香港。
        <b>而协议管理 / 中转用 Reality 伪装,客户端直连香港就行,不需要国内机。</b>
      </p>
    ),
  },
  {
    key: "q2",
    ariaLabel: "流量和到期为什么有两个地方能设",
    title: "流量和到期为什么有两个地方能设?",
    keywords: "流量 到期 用户管理 分配用户 额度",
    content: (
      <p className="text-sm text-default-500 leading-relaxed pb-2">
        分两层,不重复:<b>用户管理</b>里的流量限制只对<b>端口转发 / 隧道转发</b>生效,过期时间是
        <b>账号总到期</b>(到点这个人所有线路全停);<b>分配用户</b>时设的流量和到期,只管
        <b>这一条线路</b> —— 超了只停这条,车友其它线路照用。
        所以中转(住宅 IP、流量贵)可以单独卡个小额度,直连给大额度,互不影响。
      </p>
    ),
  },
  {
    key: "q3",
    ariaLabel: "协议全 -1 连不上但节点显示在线",
    title: "车友的协议全 -1 / 连不上,但节点显示在线?",
    keywords: "协议 -1 连不上 在线 gost sing-box 自签证书",
    content: (
      <p className="text-sm text-default-500 leading-relaxed pb-2">
        多半是节点的 gost 太旧、缺自签证书,导致 sing-box 起不来(一个协议崩,整台机的协议全崩)。
        把该节点的 gost 更新到最新版即可(新装的节点不会有这问题)。
      </p>
    ),
  },
  {
    key: "q4",
    ariaLabel: "隧道管理里那些 inbound-tunnel 是什么",
    title: "隧道管理 / 转发管理里那些 inbound-… 的东西是什么?",
    keywords: "隧道管理 转发管理 inbound-tunnel 127.0.0.1 gost",
    content: (
      <p className="text-sm text-default-500 leading-relaxed pb-2">
        搭协议时自动建的。协议本身只监听 <code className="font-mono text-xs">127.0.0.1:40000+</code>,
        要让车友连得上,得有一条 gost 转发把公网口接到它,而转发必须挂在某条隧道上 ——
        所以每台搭了协议的机器会自动生成一条 <code className="font-mono text-xs">inbound-tunnel-node*</code> 隧道
        和若干条 <code className="font-mono text-xs">inbound-*-user-*</code> 转发。
        它们归「协议管理」「中转」页管,两个列表页默认都已折叠隐藏,<b>别手动删</b>。
      </p>
    ),
  },
];

/** 小节标题,统一样式 */
function SectionTitle({ children, hint }: { children: React.ReactNode; hint?: string }) {
  return (
    <div className="flex items-baseline gap-3 flex-wrap">
      <h2 className="text-base font-bold">{children}</h2>
      {hint && <span className="text-xs text-default-400">{hint}</span>}
    </div>
  );
}

/** 决策树里的一个分支块 */
function Branch({
  tag,
  title,
  color,
  children,
}: {
  tag: string;
  title: string;
  color: "primary" | "warning" | "secondary" | "default";
  children: React.ReactNode;
}) {
  const ring = {
    primary: "border-primary/40 bg-primary/5",
    warning: "border-warning/40 bg-warning/5",
    secondary: "border-secondary/40 bg-secondary/5",
    default: "border-default-300 bg-default-100/50 dark:border-default-200/60 dark:bg-default-100/30",
  }[color];
  return (
    <div
      className={`tms-guide-branch ${color !== "default" ? "tms-guide-branch-accent" : ""} rounded-xl border ${ring} p-4 space-y-2 h-full`}
    >
      <div className="flex items-center gap-2">
        <span className="text-lg">{tag}</span>
        <span className="font-semibold text-sm">{title}</span>
      </div>
      <div className="text-sm text-default-600 space-y-2">{children}</div>
    </div>
  );
}

/** 「→ 用这个功能」的结论条 */
function Verdict({ name, color }: { name: string; color: "primary" | "warning" | "secondary" | "default" }) {
  return (
    <div className="flex items-center gap-2 pt-1">
      <span className="text-default-400 text-xs">用</span>
      <Chip size="sm" color={color} variant="flat" className="tms-feature-chip font-medium">
        {name}
      </Chip>
    </div>
  );
}

export default function GuidePage() {
  const [platform, setPlatform] = useState<Platform>("全部");
  const [keyword, setKeyword] = useState("");
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [copyingId, setCopyingId] = useState<string | null>(null);
  const copyTimerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current !== null) {
        window.clearTimeout(copyTimerRef.current);
      }
    };
  }, []);

  const normalizedQuery = keyword.trim().toLowerCase();

  const filteredFeatures = useMemo(() => {
    if (!normalizedQuery) return FEATURES;
    return FEATURES.filter((f) =>
      `${f.name} ${f.headline} ${f.body}`.toLowerCase().includes(normalizedQuery),
    );
  }, [normalizedQuery]);

  const allPlatformKeywordClients = useMemo(() => {
    if (!normalizedQuery) return CLIENTS;
    return CLIENTS.filter((c) =>
      `${c.name} ${c.protocols} ${c.desc} ${c.tip} ${c.platforms.join(" ")}`
        .toLowerCase()
        .includes(normalizedQuery),
    );
  }, [normalizedQuery]);

  const filteredClients = useMemo(() => {
    if (platform === "全部") return allPlatformKeywordClients;
    return allPlatformKeywordClients.filter((c) => c.platforms.includes(platform));
  }, [platform, allPlatformKeywordClients]);

  const filteredSnippets = useMemo(() => {
    if (!normalizedQuery) return TROUBLESHOOT_SNIPPETS;
    return TROUBLESHOOT_SNIPPETS.filter((s) =>
      `${s.title} ${s.desc} ${s.cmd}`.toLowerCase().includes(normalizedQuery),
    );
  }, [normalizedQuery]);

  const filteredFaqs = useMemo(() => {
    if (!normalizedQuery) return FAQ_ITEMS;
    return FAQ_ITEMS.filter((item) =>
      `${item.title} ${item.keywords}`.toLowerCase().includes(normalizedQuery),
    );
  }, [normalizedQuery]);

  const hasAnyMatch =
    filteredFeatures.length > 0 ||
    filteredClients.length > 0 ||
    filteredSnippets.length > 0 ||
    filteredFaqs.length > 0;

  const hasActiveFilter = platform !== "全部" || Boolean(normalizedQuery);

  const handleCopy = async (id: string, text: string, label: string) => {
    if (copyingId === id) return;
    setCopyingId(id);
    try {
      const ok = await copyTextToClipboard(text);
      if (ok) {
        setCopiedId(id);
        if (copyTimerRef.current !== null) {
          window.clearTimeout(copyTimerRef.current);
        }
        copyTimerRef.current = window.setTimeout(() => {
          setCopiedId(null);
          copyTimerRef.current = null;
        }, 2000);
        toast.success(`已复制：${label}`);
      } else {
        toast.error("复制失败，请手动选中复制");
      }
    } finally {
      setCopyingId(null);
    }
  };

  const scrollToSection = (id: string) => {
    const el = document.getElementById(id);
    if (el) {
      el.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  };

  const resetFilters = () => {
    setPlatform("全部");
    setKeyword("");
  };

  return (
    <div className="p-4 lg:p-6 space-y-6 max-w-[1400px]">
      {/* 页头 */}
      <div className="space-y-1">
        <h1 className="text-2xl font-bold">使用说明</h1>
        <p className="text-sm text-default-500">
          四个功能什么区别、什么时候用哪个 —— 拿不准就看下面那个决策树,问两句就定了。
        </p>
      </div>

      {/* 顶部快捷导航 + 平台筛选 + 搜索框 */}
      <Card className="border border-divider">
        <CardBody className="space-y-3 p-3.5 sm:p-4">
          <div className="flex flex-col sm:flex-row gap-3 sm:items-center sm:justify-between">
            <Input
              isClearable
              aria-label="搜索使用指南"
              className="w-full sm:max-w-sm"
              placeholder="搜索客户端、协议、功能或常见问题..."
              size="sm"
              value={keyword}
              onClear={() => setKeyword("")}
              onValueChange={setKeyword}
            />
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className="text-xs text-default-400 mr-1">跳转:</span>
              {SECTIONS.map((sec) => (
                <Chip
                  key={sec.id}
                  as="button"
                  className="cursor-pointer hover:opacity-80 transition-opacity"
                  color="default"
                  size="sm"
                  variant="flat"
                  onClick={() => scrollToSection(sec.id)}
                >
                  {sec.label}
                </Chip>
              ))}
            </div>
          </div>

          <div className="flex items-center justify-between gap-2 flex-wrap pt-1 border-t border-divider/60">
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className="text-xs text-default-400 mr-1">客户端平台:</span>
              {PLATFORMS.map((p) => (
                <Chip
                  key={p}
                  as="button"
                  className="cursor-pointer transition-colors"
                  color={platform === p ? "primary" : "default"}
                  size="sm"
                  variant={platform === p ? "solid" : "flat"}
                  onClick={() => setPlatform(p)}
                >
                  {p}
                </Chip>
              ))}
            </div>
            {hasActiveFilter && (
              <div className="flex items-center gap-2 flex-wrap">
                {platform !== "全部" && filteredClients.length > 0 && (
                  <Button
                    size="sm"
                    variant="flat"
                    className="h-6 min-w-0 px-2 text-xs"
                    onPress={() => scrollToSection("sec-clients")}
                  >
                    查看 {filteredClients.length} 款 {platform} 客户端 ↓
                  </Button>
                )}
                <Button
                  size="sm"
                  variant="light"
                  className="h-6 min-w-0 px-2 text-xs"
                  onPress={resetFilters}
                >
                  重置筛选
                </Button>
              </div>
            )}
          </div>
        </CardBody>
      </Card>

      {/* 无匹配空状态 */}
      {!hasAnyMatch && (
        <Card className="border border-divider">
          <CardBody className="py-10 text-center space-y-3">
            <div className="text-sm text-default-500">
              {platform !== "全部" && keyword.trim() ? (
                <>
                  在「<span className="font-medium text-foreground">{platform}</span>」平台下未找到与「
                  <span className="font-medium text-foreground">{keyword.trim()}</span>」相关的内容
                  {allPlatformKeywordClients.length > 0 && (
                    <span>（其他平台有 {allPlatformKeywordClients.length} 款匹配客户端）</span>
                  )}
                </>
              ) : (
                <>
                  没有找到与「<span className="font-medium text-foreground">{keyword || platform}</span>」相关的内容
                </>
              )}
            </div>
            <div className="flex items-center justify-center gap-2 flex-wrap">
              {platform !== "全部" && allPlatformKeywordClients.length > 0 && (
                <Button color="primary" size="sm" variant="solid" onPress={() => setPlatform("全部")}>
                  切换到全部平台 ({allPlatformKeywordClients.length})
                </Button>
              )}
              <Button color="primary" size="sm" variant="flat" onPress={resetFilters}>
                重置筛选与搜索
              </Button>
            </div>
          </CardBody>
        </Card>
      )}

      {/* 四个功能:卡片网格,一眼扫完 */}
      {filteredFeatures.length > 0 && (
        <div id="sec-features" className="scroll-mt-4">
          <SectionTitle hint="每个功能一句话">四个功能</SectionTitle>
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4 mt-3">
            {filteredFeatures.map((f) => (
              <Card key={f.name} className="border border-divider h-full">
                <CardBody className="space-y-3">
                  <div className="flex items-center gap-2">
                    <span className="text-xl">{f.icon}</span>
                    <Chip size="sm" color={f.color} variant="flat" className="tms-feature-chip font-medium">
                      {f.name}
                    </Chip>
                  </div>
                  <div className="font-medium text-sm leading-snug">{f.headline}</div>
                  <div className="text-xs text-default-500 leading-relaxed">{f.body}</div>
                </CardBody>
              </Card>
            ))}
          </div>
        </div>
      )}

      {/* 决策树 */}
      {!normalizedQuery && (
        <div id="sec-decision" className="scroll-mt-4">
          <SectionTitle hint="照着问两句">该用哪个</SectionTitle>

          <div className="mt-3 space-y-4">
            {/* 第一问 */}
            <Card className="border border-divider">
              <CardBody className="space-y-4">
                <div className="flex items-center gap-2">
                  <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary text-primary-foreground text-xs font-bold shrink-0">
                    1
                  </span>
                  <span className="font-semibold text-sm">客户端要拿到的是「订阅」还是「一个端口」?</span>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <Branch tag="📋" title="订阅 —— 给车友翻墙用" color="primary">
                    <div className="flex items-start gap-2 flex-wrap">
                      <span className="text-default-400 shrink-0">·</span>
                      <span>出口就用这台机</span>
                      <Chip size="sm" color="primary" variant="flat" className="tms-feature-chip">协议管理</Chip>
                    </div>
                    <div className="flex items-start gap-2 flex-wrap">
                      <span className="text-default-400 shrink-0">·</span>
                      <span>出口要换成住宅 IP 或别的节点</span>
                      <Chip size="sm" color="warning" variant="flat" className="tms-feature-chip">中转</Chip>
                    </div>
                    <div className="tms-guide-done-hint text-xs text-success pt-1">✓ 到这就完了,第二问不用看</div>
                  </Branch>

                  <Branch tag="🔌" title="一个端口 —— 自己用 / 搬服务" color="default">
                    <p>客户端不是翻墙客户端,拿到的是 IP:端口。</p>
                    <div className="text-xs text-default-500 pt-1">↓ 接着问第二句</div>
                  </Branch>
                </div>
              </CardBody>
            </Card>

            {/* 第二问 */}
            <Card className="border border-divider">
              <CardBody className="space-y-4">
                <div className="flex items-center gap-2">
                  <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary text-primary-foreground text-xs font-bold shrink-0">
                    2
                  </span>
                  <span className="font-semibold text-sm">要搬的东西自己加密吗?</span>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <Branch tag="🔒" title="自己加密" color="secondary">
                    <p className="text-xs text-default-500">VLESS / Trojan / SS / Hysteria 这些协议</p>
                    <p>搬的是密文,过不过墙都无所谓。落地那台什么都不用装,别人机场的节点也能搬。</p>
                    <Verdict name="端口转发" color="secondary" />
                  </Branch>

                  <Branch tag="📭" title="裸的" color="default">
                    <p className="text-xs text-default-500">socks5 / SSH / 游戏 / 数据库 / 明文服务</p>
                    <p className="font-medium">再看入口机到落地这一段过不过墙:</p>

                    <div className="space-y-2 pt-1">
                      <div className="tms-inline-panel rounded-lg bg-default-100 dark:bg-default-100/50 p-2.5 space-y-1">
                        <div className="text-xs font-medium">不过墙</div>
                        <div className="text-xs text-default-500">两头都在境内,或两头都在境外</div>
                        <div className="flex items-center gap-2">
                          <span className="text-default-400 text-xs">还是用</span>
                          <Chip size="sm" color="secondary" variant="flat" className="tms-feature-chip">端口转发</Chip>
                        </div>
                      </div>

                      <div className="tms-guide-callout rounded-lg border border-warning/40 bg-warning/5 p-2.5 space-y-1">
                        <div className="text-xs font-medium">过墙</div>
                        <div className="text-xs text-default-500">
                          境内入口 → 境外裸落地,比如河北直连泰国住宅 socks
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-default-400 text-xs">这才轮到</span>
                          <Chip size="sm" color="default" variant="flat" className="tms-feature-chip">隧道转发</Chip>
                        </div>
                        <div className="text-xs text-default-500">中间加一台境外机,把过墙那段包进加密隧道</div>
                      </div>
                    </div>
                  </Branch>
                </div>
              </CardBody>
            </Card>

            {/* 结论 */}
            <div className="tms-guide-verdict rounded-xl border border-success/30 bg-success/5 px-4 py-3 text-sm">
              <span className="font-semibold">结论:隧道转发只在最后那一格才需要。</span>
              <span className="text-default-600">
                {" "}其它情况端口转发都够,还更省事(只有入口机要装 gost)。别看落地是不是裸的就下结论 —— 关键是那段路
                <b>过不过墙</b>。
              </span>
            </div>
          </div>
        </div>
      )}

      {/* 客户端推荐 */}
      {(filteredClients.length > 0 || (hasAnyMatch && platform !== "全部")) && (
        <div id="sec-clients" className="scroll-mt-4">
          <SectionTitle hint={`当前平台: ${platform}`}>客户端推荐与订阅导入</SectionTitle>
          {filteredClients.length === 0 ? (
            <Card className="border border-divider mt-3">
              <CardBody className="py-8 text-center space-y-2.5">
                <div className="text-sm text-default-500">
                  当前「<span className="font-medium text-foreground">{platform}</span>」平台下没有匹配的推荐客户端
                </div>
                <div className="flex items-center justify-center gap-2 flex-wrap">
                  <Button size="sm" color="primary" variant="flat" onPress={() => setPlatform("全部")}>
                    切换到全部平台
                  </Button>
                  {normalizedQuery && (
                    <Button size="sm" variant="light" onPress={resetFilters}>
                      清空搜索词
                    </Button>
                  )}
                </div>
              </CardBody>
            </Card>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 mt-3">
              {filteredClients.map((c) => (
                <Card key={c.name} className="border border-divider h-full">
                  <CardBody className="space-y-2.5">
                    <div className="flex items-start justify-between gap-2">
                      <div className="font-semibold text-sm">{c.name}</div>
                      <div className="flex items-center gap-1 flex-wrap justify-end">
                        {c.platforms.map((p) => (
                          <Chip key={p} size="sm" variant="flat" color={platform === p ? "primary" : "default"}>
                            {p}
                          </Chip>
                        ))}
                      </div>
                    </div>
                    <div className="text-xs text-primary font-mono">{c.protocols}</div>
                    <div className="text-xs text-default-500 leading-relaxed">{c.desc}</div>
                    <div className="tms-inline-panel rounded-lg bg-default-100/80 dark:bg-default-100/40 px-2.5 py-2 text-xs text-default-600">
                      💡 {c.tip}
                    </div>
                  </CardBody>
                </Card>
              ))}
            </div>
          )}
        </div>
      )}

      {/* 进阶搭法 */}
      {!normalizedQuery && (
        <div id="sec-advanced" className="scroll-mt-4">
          <SectionTitle hint="国内入口 + 住宅出口 + 能发订阅">进阶:两层中转</SectionTitle>
          <Card className="border border-divider mt-3">
            <CardBody className="space-y-4">
              <p className="text-sm text-default-600">
                想同时拿到「客户端连国内不过墙、晚高峰稳」和「出口是干净住宅 IP」,还要有订阅能分车友 ——
                用<b>两层中转</b>,别用隧道转发。
              </p>

              {/* 搭法 */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <div className="tms-inline-panel rounded-lg border border-divider p-3 space-y-1">
                  <div className="text-xs text-default-400">第一层</div>
                  <div className="text-sm">
                    中转 = 前置 <b>香港</b> + 落地 <b>泰国住宅 socks</b>
                  </div>
                </div>
                <div className="tms-inline-panel rounded-lg border border-divider p-3 space-y-1">
                  <div className="text-xs text-default-400">第二层</div>
                  <div className="text-sm">
                    中转 = 前置 <b>河北</b> + 落地 <b>第一层那条 vless:// 链接</b>
                  </div>
                </div>
              </div>

              {/* 链路图 */}
              <div className="tms-inline-panel rounded-lg bg-default-100 dark:bg-default-100/50 p-4 overflow-x-auto">
                <div className="flex items-center gap-2 text-sm font-mono whitespace-nowrap">
                  <span className="tms-guide-hop px-2 py-1 rounded bg-default-200 dark:bg-default-200/70">客户端</span>
                  <span className="tms-guide-arrow text-primary text-xs">──Reality──▶</span>
                  <span className="tms-guide-hop px-2 py-1 rounded bg-primary/15 text-primary font-medium">河北</span>
                  <span className="tms-guide-arrow text-warning text-xs">──VLESS──▶</span>
                  <span className="tms-guide-hop px-2 py-1 rounded bg-warning/15 text-warning font-medium">香港</span>
                  <span className="tms-guide-arrow text-success text-xs">──socks5──▶</span>
                  <span className="tms-guide-hop px-2 py-1 rounded bg-success/15 text-success font-medium">泰国住宅</span>
                </div>
              </div>

              <div className="text-xs text-default-500 space-y-2">
                <p>
                  <b>拿第一层的链接:</b>给它点「🔑 我自己用」拿到订阅地址,浏览器打开,把里面那条 vless://
                  复制出来,粘到第二层的落地框。
                </p>
                <p>
                  <b>为什么不用隧道转发做这件事:</b>隧道方案客户端要手配裸 socks5(明文、没订阅),而且
                  <b>住宅的账号密码得填进客户端</b> —— 等于把凭据交给车友。两层中转全程加密、订阅自动指向河北、
                  每人独立 UUID 和限速,住宅凭据只留在香港那台机上。机器数量一样是两台。
                </p>
              </div>
            </CardBody>
          </Card>
        </div>
      )}

      {/* 对照表 */}
      {!normalizedQuery && (
        <div id="sec-compare" className="scroll-mt-4">
          <SectionTitle hint="横向对比 · 窄屏可左右滑动">对照表</SectionTitle>
          <Card className="border border-divider mt-3">
            <CardBody>
              <div className="overflow-x-auto">
                <table className="w-full text-sm border-collapse min-w-[720px]">
                  <thead>
                    <tr className="border-b border-default-200">
                      <th className="py-2.5 pr-4 text-left text-xs font-medium text-default-400 w-36"></th>
                      {FEATURES.map((f) => (
                        <th key={f.name} className="py-2.5 pr-4 text-left">
                          <div className="flex items-center gap-1.5">
                            <span className={`tms-guide-dot w-2 h-2 rounded-full ${f.dot}`} />
                            <span className="font-semibold">{f.name}</span>
                          </div>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="[&>tr]:border-b [&>tr]:border-default-100 [&>tr:last-child]:border-0">
                    {[
                      ["客户端拿到", "订阅", "订阅", "裸端口", "裸端口"],
                      ["抗封锁伪装", "有", "有", "无", "入口→出口有"],
                      ["出口在哪", "本机", "落地(任意)", "任意地址", "出口机去连的任意地址"],
                      ["要装 gost 的机器", "1 台", "1 台(前置机)", "1 台(入口机)", "2 台(入口+出口)"],
                      ["跳数", "1", "2(前置+落地)", "1", "2(加密)"],
                      ["卖给谁", "翻墙车友", "翻墙车友", "要搬端口的", "要搬端口的"],
                    ].map((row) => (
                      <tr key={row[0]}>
                        <td className="py-2.5 pr-4 text-xs text-default-400 align-top">{row[0]}</td>
                        {row.slice(1).map((cell, i) => (
                          <td key={i} className="py-2.5 pr-4 align-top">
                            {cell}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </CardBody>
          </Card>
        </div>
      )}

      {/* 常用排障命令一键复制 */}
      {filteredSnippets.length > 0 && (
        <div id="sec-troubleshoot" className="scroll-mt-4">
          <SectionTitle hint="点击按钮即可复制">常用排障命令</SectionTitle>
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mt-3">
            {filteredSnippets.map((item) => {
              const isCopied = copiedId === item.id;
              return (
                <Card key={item.id} className="border border-divider">
                  <CardBody className="space-y-2.5">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-semibold text-sm min-w-0 truncate">{item.title}</span>
                      <Button
                        color={isCopied ? "success" : "primary"}
                        size="sm"
                        variant="flat"
                        className="shrink-0"
                        isDisabled={copyingId === item.id}
                        onPress={() => handleCopy(item.id, item.cmd, item.title)}
                      >
                        {isCopied ? "✓ 已复制" : "复制命令"}
                      </Button>
                    </div>
                    <p className="text-xs text-default-500">{item.desc}</p>
                    <pre className="rounded-lg bg-default-100 dark:bg-default-100/50 p-2.5 text-xs font-mono overflow-x-auto text-foreground">
                      <code>{item.cmd}</code>
                    </pre>
                  </CardBody>
                </Card>
              );
            })}
          </div>
        </div>
      )}

      {/* 常见问题 */}
      {filteredFaqs.length > 0 && (
        <div id="sec-faq" className="scroll-mt-4">
          <SectionTitle hint="都是踩过的坑">常见问题</SectionTitle>
          <Card className="border border-divider mt-3">
            <CardBody className="px-2">
              <Accordion variant="light" selectionMode="multiple">
                {filteredFaqs.map((item) => (
                  <AccordionItem
                    key={item.key}
                    aria-label={item.ariaLabel}
                    title={<span className="text-sm font-medium">{item.title}</span>}
                  >
                    {item.content}
                  </AccordionItem>
                ))}
              </Accordion>
            </CardBody>
          </Card>
        </div>
      )}
    </div>
  );
}
