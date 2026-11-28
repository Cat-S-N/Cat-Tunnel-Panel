import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Input } from "@heroui/input";
import { Spinner } from "@heroui/spinner";
import { Divider } from "@heroui/divider";
import { Switch } from "@heroui/switch";
import { Select, SelectItem } from "@heroui/select";
import { Chip } from "@heroui/chip";
import toast from 'react-hot-toast';
import { updateConfigs, testAlertNotify, getVersionInfo } from '@/api';
import { copyTextToClipboard } from '@/utils/clipboard';
import { SettingsIcon } from '@/components/icons';

import { isAdmin } from '@/utils/auth';
import { getCachedConfigs, clearConfigCache, updateSiteConfig } from '@/config/site';

// 简单的保存图标组件
const SaveIcon = ({ className }: { className?: string }) => (
  <svg
    className={className}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
    <polyline points="17,21 17,13 7,13 7,21" />
    <polyline points="7,3 7,8 15,8" />
  </svg>
);

interface ConfigItem {
  key: string;
  label: string;
  placeholder?: string;
  description?: string;
  type: 'input' | 'switch' | 'select';
  options?: { label: string; value: string; description?: string }[];
  dependsOn?: string; // 依赖的配置项key
  dependsValue?: string; // 依赖的配置项值
  sensitive?: boolean; // 敏感配置项（默认掩码显示，可点击切换明文）
  copyable?: boolean; // 是否显示快捷复制按钮
}

interface VersionInfo {
  panelVersion?: string;
  commit?: string;
  buildTime?: string | null;
  latest?: string | null;
  checkFailed?: boolean;
  updateAvailable?: boolean;
}

interface ValidationResult {
  error?: string;
  hint?: string;
}

const INVITE_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const INVITE_CODE_LENGTH = 12;
const MAX_INVITE_CONFIG_LEN = 512;
const MAX_SINGLE_INVITE_CODE_LEN = 128;

const trimInviteToken = (raw?: string): string => {
  if (!raw) return '';
  return raw.replace(/^[\s\u200B-\u200D\uFEFF]+|[\s\u200B-\u200D\uFEFF]+$/g, '');
};

const parseValidInviteCodes = (raw?: string): string[] => {
  if (!raw) return [];
  const seen = new Set<string>();
  const result: string[] = [];
  for (const part of raw.split(',')) {
    const trimmed = trimInviteToken(part);
    if (trimmed.length > 0 && !seen.has(trimmed)) {
      seen.add(trimmed);
      result.push(trimmed);
    }
  }
  return result;
};

// 使用 crypto.getRandomValues 无模偏差采样生成 12 位无歧义大写字母+数字邀请码
const generateSecureInviteCode = (length: number = INVITE_CODE_LENGTH): string => {
  const cryptoObj = typeof window !== 'undefined' ? window.crypto : undefined;
  if (!cryptoObj || typeof cryptoObj.getRandomValues !== 'function') {
    throw new Error('当前运行环境不支持安全随机数生成器');
  }
  const alphabetLen = INVITE_CODE_ALPHABET.length;
  const maxUnbiased = Math.floor(256 / alphabetLen) * alphabetLen;
  const chars: string[] = [];
  const buffer = new Uint8Array(length * 2);
  while (chars.length < length) {
    cryptoObj.getRandomValues(buffer);
    for (let i = 0; i < buffer.length && chars.length < length; i++) {
      const byte = buffer[i];
      if (byte < maxUnbiased) {
        chars.push(INVITE_CODE_ALPHABET[byte % alphabetLen]);
      }
    }
  }
  return chars.join('');
};

const resolveBoundedQuotaPreview = (
  raw: string | undefined,
  fallback: number,
  min: number,
  max: number
): { value: number; isDefault: boolean; isInvalid: boolean } => {
  const trimmed = trimInviteToken(raw);
  if (!trimmed) {
    return { value: fallback, isDefault: true, isInvalid: false };
  }
  if (!/^\d+$/.test(trimmed)) {
    return { value: fallback, isDefault: false, isInvalid: true };
  }
  const num = Number(trimmed);
  if (!Number.isSafeInteger(num) || num < min || num > max) {
    return { value: fallback, isDefault: false, isInvalid: true };
  }
  return { value: num, isDefault: false, isInvalid: false };
};

// 关键配置项格式校验与默认值/范围提示
const validateConfigValue = (key: string, rawValue?: string): ValidationResult => {
  const value = trimInviteToken(rawValue);
  if (!value) {
    if (key === 'register_invite_code') {
      return { hint: '当前未设置邀请码：开放注册期间任何访客均可直接注册' };
    }
    if (key === 'register_default_flow') {
      return { hint: '留空将使用系统默认值：10 GB（合法范围 0 ~ 1000000）' };
    }
    if (key === 'register_default_num') {
      return { hint: '留空将使用系统默认值：5 条（合法范围 0 ~ 10000）' };
    }
    if (key === 'register_default_exp_days') {
      return { hint: '留空将使用系统默认值：30 天（合法范围 1 ~ 36500）' };
    }
    if (key === 'register_default_flow_reset_time') {
      return { hint: '留空将使用系统默认值：每月 1 日重置（合法范围 0 ~ 31，0 表示不重置）' };
    }
    return {};
  }

  if (key === 'sub_url') {
    if (!/^https?:\/\//i.test(value)) {
      return { error: '订阅地址必须以 http:// 或 https:// 开头' };
    }
    if (/[?#]/.test(value)) {
      return { error: '订阅地址不能包含 ? 或 # 参数' };
    }
    if (/\/+$/.test(value)) {
      return { hint: '检测到末尾斜杠 /，保存时将自动去除' };
    }
  } else if (key === 'webhook_url') {
    if (!/^https?:\/\//i.test(value)) {
      return { error: 'Webhook 地址必须以 http:// 或 https:// 开头' };
    }
  } else if (key === 'ip') {
    if (/^https?:\/\//i.test(value)) {
      return { error: '面板后端地址无需填写 http:// 或 https://，请直接填写 IP:PORT' };
    }
    if (!value.includes(':')) {
      return { hint: '建议使用 IP:PORT 格式（例如 1.2.3.4:6365）' };
    }
  } else if (key === 'register_invite_code') {
    if (rawValue && /[\r\n]/.test(rawValue)) {
      return { error: '邀请码不能包含换行符，多个邀请码请使用英文半角逗号 (,) 分隔' };
    }
    if (value.includes('，')) {
      return { error: '检测到中文逗号（，），多个邀请码请改用英文半角逗号 (,) 分隔' };
    }
    if (value.length > MAX_INVITE_CONFIG_LEN) {
      return { error: `邀请码配置总长度不能超过 ${MAX_INVITE_CONFIG_LEN} 个字符` };
    }
    const rawSegments = value
      .split(',')
      .map((s) => trimInviteToken(s))
      .filter((s) => s.length > 0);
    const validCodes = parseValidInviteCodes(value);
    if (validCodes.length === 0) {
      return { error: '未解析到有效邀请码，若不启用请直接清空输入框' };
    }
    if (validCodes.some((code) => code.length > MAX_SINGLE_INVITE_CODE_LEN)) {
      return { error: `单个邀请码长度不能超过 ${MAX_SINGLE_INVITE_CODE_LEN} 个字符` };
    }
    const duplicateCount = rawSegments.length - validCodes.length;
    return {
      hint: duplicateCount > 0
        ? `已识别 ${validCodes.length} 个有效邀请码（含 ${duplicateCount} 个重复项，保存时自动去重）`
        : `已识别 ${validCodes.length} 个有效邀请码，保存后登录页将要求填写邀请码`,
    };
  } else if (key === 'register_default_flow') {
    const n = Number(value);
    if (!/^\d+$/.test(value) || n < 0 || n > 1000000) {
      return { error: '请输入 0 到 1000000 之间的整数（单位：GB）' };
    }
    return { hint: n === 0 ? '设置为 0 GB：新注册账号需管理员分配流量后方可使用' : `新注册账号初始可用流量：${n} GB` };
  } else if (key === 'register_default_num') {
    const n = Number(value);
    if (!/^\d+$/.test(value) || n < 0 || n > 10000) {
      return { error: '请输入 0 到 10000 之间的整数' };
    }
    return { hint: `新注册账号最多可创建 ${n} 条转发规则` };
  } else if (key === 'register_default_exp_days') {
    const n = Number(value);
    if (!/^\d+$/.test(value) || n < 1 || n > 36500) {
      return { error: '请输入 1 到 36500 之间的整数天数' };
    }
    return { hint: `新注册账号自注册时刻起有效期为 ${n} 天` };
  } else if (key === 'register_default_flow_reset_time') {
    const n = Number(value);
    if (!/^\d+$/.test(value) || n < 0 || n > 31) {
      return { error: '请输入 0 到 31 之间的整数（0 表示不按月自动重置）' };
    }
    return {
      hint: n === 0 ? '设置为 0：流量一次性有效，不按月自动清零' : `每月 ${n} 日自动重置已用流量`,
    };
  }

  return {};
};

// 网站配置项定义
const CONFIG_ITEMS: ConfigItem[] = [
  {
    key: 'ip',
    label: '面板后端地址',
    placeholder: '请输入面板后端IP:PORT',
    description: '格式“ip:port”,用于对接转发机时使用,ip是你安装面板服务器的公网ip,端口是安装脚本内输入的后端端口。不要套CDN,不支持https,通讯数据有加密',
    type: 'input',
    copyable: true
  },
  {
    key: 'sub_url',
    label: '订阅地址(给车友的)',
    placeholder: '不填 = 用面板网页地址;例如 https://sub.example.com',
    description: '填了之后,发给车友的订阅链接(复制、二维码)都用这个地址开头,车友拿到的订阅地址里看不到面板 IP。'
      + '一般用一个开了橙云(代理)的 Cloudflare 子域名,把订阅那两个路径转到面板后端端口(TMS 客户端「面板」页有分步说明,还能先实测再保存;在这里保存不做实测,填好后请先自己用订阅链接导入试一下)。'
      + '注意:面板机自己也当转发机 / 入口时,订阅内容里的节点地址就是面板 IP,藏不住;给车友开网页账号,网页地址就是面板地址;'
      + '同一个 IP 上别再挂灰色云朵(仅 DNS)的记录,例如面板自己的域名,否则等于告诉别人源 IP;'
      + 'Cloudflare 在国内一般能用、可能慢、偶尔不通;Cloudflare 到面板这一段是 http 明文(和现在一样)。'
      + '只填 http(s)://主机名[:端口][/路径前缀],不能带 ? 或 #。清空 = 回到用面板地址。',
    type: 'input',
    copyable: true
  },
  {
    key: 'app_name',
    label: '应用名称',
    placeholder: '请输入应用名称',
    description: '在浏览器标签页和导航栏显示的应用名称',
    type: 'input'
  },
  {
    key: 'captcha_enabled',
    label: '启用验证码',
    description: '开启后，用户登录时需要完成验证码验证',
    type: 'switch'
  },
  {
    key: 'captcha_type',
    label: '验证码类型',
    description: '选择验证码的显示类型，不同类型有不同的安全级别',
    type: 'select',
    dependsOn: 'captcha_enabled',
    dependsValue: 'true',
    options: [
      { 
        label: '随机类型', 
        value: 'RANDOM', 
        description: '系统随机选择验证码类型' 
      },
      { 
        label: '滑块验证码', 
        value: 'SLIDER', 
        description: '拖动滑块完成拼图验证' 
      },
      { 
        label: '文字点选验证码', 
        value: 'WORD_IMAGE_CLICK', 
        description: '按顺序点击指定文字' 
      },
      { 
        label: '旋转验证码', 
        value: 'ROTATE', 
        description: '旋转图片到正确角度' 
      },
      { 
        label: '拼图验证码', 
        value: 'CONCAT', 
        description: '拖动滑块完成图片拼接' 
      }
    ]
  },
  {
    key: 'alert_enabled',
    label: '实时运维告警（节点离线 / 线路停用）',
    description: '开启后，当节点离线或恢复、用户线路因到期或超额自动停用时，立即推送通知',
    type: 'switch'
  },
  {
    key: 'tg_bot_token',
    label: 'Telegram Bot Token',
    placeholder: '例如 123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11',
    description: '从 @BotFather 获取的机器人 Token；需配合下方 Chat ID 使用',
    type: 'input',
    sensitive: true,
    dependsOn: 'alert_enabled',
    dependsValue: 'true'
  },
  {
    key: 'tg_chat_id',
    label: 'Telegram Chat ID',
    placeholder: '例如 1001234567 或 -1001234567890',
    description: '接收告警消息的用户或群组 ID（可通过 @userinfobot 获取）',
    type: 'input',
    dependsOn: 'alert_enabled',
    dependsValue: 'true'
  },
  {
    key: 'webhook_url',
    label: '自定义 Webhook 地址',
    placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/...',
    description: '可选。支持飞书、钉钉、企业微信机器人以及通用 JSON POST Webhook',
    type: 'input',
    sensitive: true,
    copyable: true,
    dependsOn: 'alert_enabled',
    dependsValue: 'true'
  },
  {
    key: 'register_enabled',
    label: '开放用户注册',
    description: '开启后登录页将显示「没有账号？立即注册」入口；自助注册账号角色固定为普通用户，受单 IP 频率限制与审计日志保护',
    type: 'switch'
  },
  {
    key: 'register_invite_code',
    label: '注册邀请码（可选，多个用英文逗号分隔，留空不启用）',
    placeholder: '留空表示无需邀请码；多个示例：VIP2026,CAT888',
    description: '可选。配置后新用户注册时必须填写匹配的邀请码（多个邀请码用英文逗号 , 分隔，自动忽略首尾空格）；邀请码仅在服务端校验，公开接口仅返回布尔状态绝不泄露原文',
    type: 'input',
    sensitive: true,
    copyable: true,
    dependsOn: 'register_enabled',
    dependsValue: 'true'
  },
  {
    key: 'register_default_flow',
    label: '新用户默认流量 (GB)',
    placeholder: '默认 10（范围 0 ~ 1000000）',
    description: '自助注册账号的初始总流量配额（单位：GB，合法范围 0 ~ 1000000，留空默认 10）',
    type: 'input',
    dependsOn: 'register_enabled',
    dependsValue: 'true'
  },
  {
    key: 'register_default_num',
    label: '新用户默认转发数上限',
    placeholder: '默认 5（范围 0 ~ 10000）',
    description: '自助注册账号可创建的端口转发规则上限（合法范围 0 ~ 10000，留空默认 5）',
    type: 'input',
    dependsOn: 'register_enabled',
    dependsValue: 'true'
  },
  {
    key: 'register_default_exp_days',
    label: '新用户默认有效期 (天)',
    placeholder: '默认 30（范围 1 ~ 36500）',
    description: '自助注册账号自创建时起的有效天数（合法范围 1 ~ 36500 天，留空默认 30 天）',
    type: 'input',
    dependsOn: 'register_enabled',
    dependsValue: 'true'
  },
  {
    key: 'register_default_flow_reset_time',
    label: '新用户默认流量重置日',
    placeholder: '默认 1（范围 0 ~ 31，0 表示不重置）',
    description: '每月自动清零已用流量的日期（合法范围 0 ~ 31：填 1~31 为每月对应日重置，填 0 表示不自动重置，留空默认 1）',
    type: 'input',
    dependsOn: 'register_enabled',
    dependsValue: 'true'
  }
];

// 初始化时从缓存读取非敏感配置，避免闪烁
const getInitialConfigs = (): Record<string, string> => {
  if (typeof window === 'undefined') return {};
  
  const configKeys = [
    'app_name', 'captcha_enabled', 'captcha_type', 'ip', 'sub_url', 'alert_enabled', 'tg_chat_id',
    'register_enabled', 'register_default_flow', 'register_default_num',
    'register_default_exp_days', 'register_default_flow_reset_time'
  ];
  const initialConfigs: Record<string, string> = {};
  
  try {
    configKeys.forEach(key => {
      const cachedValue = localStorage.getItem('vite_config_' + key);
      if (cachedValue) {
        initialConfigs[key] = cachedValue;
      }
    });
  } catch (error) {
  }
  
  return initialConfigs;
};

export default function ConfigPage() {
  const navigate = useNavigate();
  const initialConfigs = getInitialConfigs();
  const [configs, setConfigs] = useState<Record<string, string>>(initialConfigs);
  const [loading, setLoading] = useState(Object.keys(initialConfigs).length === 0); // 如果有缓存数据，不显示loading
  const [saving, setSaving] = useState(false);
  const [testingAlert, setTestingAlert] = useState(false);
  const [revealedKeys, setRevealedKeys] = useState<Record<string, boolean>>({});
  const [hasChanges, setHasChanges] = useState(false);
  const [originalConfigs, setOriginalConfigs] = useState<Record<string, string>>(initialConfigs);

  useEffect(() => {
    getVersionInfo(false)
      .then((res: any) => {
        if (res.code === 0 && res.data) {
          setVersionInfo(res.data);
        }
      })
      .catch(() => {});
  }, []);

  const handleRefreshVersion = async () => {
    setRefreshingVersion(true);
    try {
      const res: any = await getVersionInfo(true);
      if (res.code === 0 && res.data) {
        setVersionInfo(res.data);
        if (res.data.checkFailed) {
          toast.error('检查最新版本失败，请稍后重试');
        } else if (res.data.updateAvailable) {
          toast.success(`发现新版本构建：${res.data.latest}`);
        } else {
          toast.success(`当前已是最新构建（${res.data.commit || 'dev'}）`);
        }
      } else {
        toast.error(res.msg || '刷新版本信息失败');
      }
    } catch {
      toast.error('刷新版本信息失败');
    } finally {
      setRefreshingVersion(false);
    }
  };
  const [versionInfo, setVersionInfo] = useState<VersionInfo | null>(null);
  const [refreshingVersion, setRefreshingVersion] = useState(false);

  // 权限检查
  useEffect(() => {
    if (!isAdmin()) {
      toast.error('权限不足，只有管理员可以访问此页面');
      navigate('/dashboard', { replace: true });
      return;
    }
  }, [navigate]);

  // 加载配置数据（优先从缓存）
  const loadConfigs = async (currentConfigs?: Record<string, string>) => {
    const configsToCompare = currentConfigs || configs;
    const hasInitialData = Object.keys(configsToCompare).length > 0;
    
    // 如果已有缓存数据，不显示loading，静默更新
    if (!hasInitialData) {
      setLoading(true);
    }
    
    try {
      const configData = await getCachedConfigs();
      
      // 只有在数据有变化时才更新
      const hasDataChanged = JSON.stringify(configData) !== JSON.stringify(configsToCompare);
      if (hasDataChanged) {
        setConfigs(configData);
        setOriginalConfigs({ ...configData });
        setHasChanges(false);
      } else {
      }
    } catch (error) {
      // 只有在没有缓存数据时才显示错误
      if (!hasInitialData) {
        toast.error('加载配置出错，请重试');
      }
    } finally {
      setLoading(false);
    }
  };

  // 加载版本信息
  const loadVersionInfo = async (forceRefresh = false) => {
    if (forceRefresh) {
      setRefreshingVersion(true);
    }
    try {
      const response = await getVersionInfo(forceRefresh);
      if (response.code === 0 && response.data) {
        const info = response.data as VersionInfo;
        setVersionInfo(info);
        if (forceRefresh) {
          const currentLabel = `v${info.panelVersion || '1.1.0'} (${info.commit || 'dev'})`;
          if (info.checkFailed) {
            toast.success(`版本已刷新：${currentLabel}（远程检测暂不可用）`);
          } else if (info.updateAvailable && info.latest) {
            toast.success(`发现新版本：${info.latest}（当前 ${info.commit || 'dev'}）`);
          } else {
            toast.success(`版本已刷新：${currentLabel}，当前已是最新`);
          }
        }
      } else if (forceRefresh) {
        toast.error(response.msg || '刷新版本信息失败');
      }
    } catch {
      if (forceRefresh) {
        toast.error('刷新版本信息出错，请重试');
      }
    } finally {
      if (forceRefresh) {
        setRefreshingVersion(false);
      }
    }
  };

  useEffect(() => {
    // 延迟加载，避免阻塞初始渲染
    const timer = setTimeout(() => {
      loadConfigs(initialConfigs);
      loadVersionInfo(false);
    }, 100);

    return () => clearTimeout(timer);
  }, []); // 只在组件挂载时执行一次

  // 处理配置项变更
  const handleConfigChange = (key: string, value: string) => {
    let newConfigs = { ...configs, [key]: value };
    
    // 特殊处理：启用验证码时，如果验证码类型未设置，默认为随机
    if (key === 'captcha_enabled' && value === 'true') {
      if (!newConfigs.captcha_type) {
        newConfigs.captcha_type = 'RANDOM';
      }
    }
    
    setConfigs(newConfigs);
    
    // 检查是否有变更
    const hasChangesNow = Object.keys(newConfigs).some(
      k => newConfigs[k] !== originalConfigs[k]
    ) || Object.keys(originalConfigs).some(
      k => originalConfigs[k] !== newConfigs[k]
    );
    setHasChanges(hasChangesNow);
  };

  // 保存配置
  const handleSave = async () => {
    for (const item of CONFIG_ITEMS) {
      if (!shouldShowItem(item)) continue;
      const { error } = validateConfigValue(item.key, configs[item.key]);
      if (error) {
        toast.error(`${item.label}：${error}`);
        return;
      }
    }

    setSaving(true);
    try {
      const response = await updateConfigs(configs);
      if (response.code === 0) {
        toast.success('配置保存成功');
        
        // 清除所有配置缓存，强制下次重新获取
        clearConfigCache();
        
        // 获取变更的配置项
        const changedKeys = Object.keys(configs).filter(
          key => configs[key] !== originalConfigs[key]
        );
        
        // 订阅地址与注册邀请码后端存的是规范化后的值,输入框跟着显示存进去的样子
        const saved = { ...configs };
        if (typeof saved.sub_url === 'string') {
          saved.sub_url = saved.sub_url.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '').replace(/\/+$/, '');
        }
        if (typeof saved.register_invite_code === 'string') {
          saved.register_invite_code = parseValidInviteCodes(saved.register_invite_code).join(',');
        }
        (['register_default_flow', 'register_default_num', 'register_default_exp_days', 'register_default_flow_reset_time'] as const).forEach((k) => {
          if (typeof saved[k] === 'string') {
            const trimmed = trimInviteToken(saved[k]);
            saved[k] = trimmed && /^\d+$/.test(trimmed) ? String(Number(trimmed)) : trimmed;
          }
        });
        setConfigs(saved);
        setOriginalConfigs(saved);
        setHasChanges(false);
        
        // 如果应用名称发生变化，立即更新网站配置
        if (changedKeys.includes('app_name')) {
          await updateSiteConfig();
        }
        
        // 触发配置更新事件，通知其他组件
        window.dispatchEvent(new CustomEvent('configUpdated', { 
          detail: { changedKeys } 
        }));
      } else {
        toast.error('保存配置失败: ' + response.msg);
      }
    } catch (error) {
      toast.error('保存配置出错，请重试');
    } finally {
      setSaving(false);
    }
  };

  // 测试告警推送
  const handleTestAlert = async () => {
    setTestingAlert(true);
    try {
      const response = await testAlertNotify({
        tg_bot_token: configs.tg_bot_token || '',
        tg_chat_id: configs.tg_chat_id || '',
        webhook_url: configs.webhook_url || '',
      });
      if (response.code === 0) {
        toast.success(typeof response.data === 'string' ? response.data : '测试告警发送成功');
      } else {
        toast.error(response.msg || '测试告警发送失败');
      }
    } catch {
      toast.error('测试告警请求出错，请检查网络或配置');
    } finally {
      setTestingAlert(false);
    }
  };



  // 生成 12 位安全邀请码（已有内容时自动用英文逗号追加，为空时直接填入）
  const handleGenerateInviteCode = () => {
    try {
      const newCode = generateSecureInviteCode(INVITE_CODE_LENGTH);
      const existingNormalized = (configs.register_invite_code || '').replace(/，/g, ',');
      const existingCodes = parseValidInviteCodes(existingNormalized);
      const nextValue = existingCodes.length > 0 ? [...existingCodes, newCode].join(',') : newCode;
      if (nextValue.length > MAX_INVITE_CONFIG_LEN) {
        toast.error(`邀请码总长度不能超过 ${MAX_INVITE_CONFIG_LEN} 个字符，请先清理旧邀请码`);
        return;
      }
      handleConfigChange('register_invite_code', nextValue);
      setRevealedKeys((prev) => ({ ...prev, register_invite_code: true }));
      if (existingCodes.length > 0) {
        toast.success(`已追加新邀请码：${newCode}（共 ${existingCodes.length + 1} 个，请保存配置生效）`);
      } else {
        toast.success(`已生成邀请码：${newCode}（请保存配置生效）`);
      }
    } catch {
      toast.error('生成安全邀请码失败，请手动输入');
    }
  };

  // 一键复制当前邀请码内容
  const handleCopyInviteCodes = async () => {
    const raw = configs.register_invite_code || '';
    const validCodes = parseValidInviteCodes(raw);
    const textToCopy = validCodes.length > 0 ? validCodes.join(',') : trimInviteToken(raw);
    if (!textToCopy) {
      toast.error('当前没有可复制的邀请码');
      return;
    }
    const ok = await copyTextToClipboard(textToCopy);
    if (ok) {
      toast.success(
        validCodes.length > 1
          ? `已复制全部 ${validCodes.length} 个邀请码`
          : `已复制注册邀请码：${textToCopy}`
      );
    } else {
      toast.error('复制失败，请点击显示后手动选中复制');
    }
  };

  // 一键清空邀请码以关闭邀请码限制
  const handleClearInviteCodes = () => {
    if (!configs.register_invite_code) return;
    handleConfigChange('register_invite_code', '');
    toast.success('已清空邀请码，保存配置后将开放免邀请码注册');
  };

  // 渲染新用户默认配额实时预览摘要条
  const renderRegisterQuotaPreview = () => {
    const isOpen = configs.register_enabled === 'true';
    const inviteRaw = configs.register_invite_code || '';
    const inviteValidation = validateConfigValue('register_invite_code', inviteRaw);
    const validInviteCodes = parseValidInviteCodes(inviteRaw);
    const flowPreview = resolveBoundedQuotaPreview(configs.register_default_flow, 10, 0, 1000000);
    const numPreview = resolveBoundedQuotaPreview(configs.register_default_num, 5, 0, 10000);
    const expPreview = resolveBoundedQuotaPreview(configs.register_default_exp_days, 30, 1, 36500);
    const resetPreview = resolveBoundedQuotaPreview(configs.register_default_flow_reset_time, 1, 0, 31);

    const registerKeys = [
      'register_enabled',
      'register_invite_code',
      'register_default_flow',
      'register_default_num',
      'register_default_exp_days',
      'register_default_flow_reset_time',
    ];
    const hasRegisterUnsaved = hasChanges && registerKeys.some((k) => configs[k] !== originalConfigs[k]);

    const inviteSummary = inviteValidation.error
      ? '格式待修正'
      : validInviteCodes.length > 0
      ? `必填 (${validInviteCodes.length} 个)`
      : '无需邀请码';

    const resetSummary =
      resetPreview.value === 0 ? '不自动重置' : `每月 ${resetPreview.value} 日`;

    const summaryItems = [
      {
        label: '开放状态',
        value: isOpen ? '已开放注册' : '未开放',
        sub: isOpen ? '登录页显示入口' : '自助入口已隐藏',
        warn: false,
      },
      {
        label: '邀请码要求',
        value: inviteSummary,
        sub: inviteValidation.error
          ? '存在校验错误'
          : validInviteCodes.length > 0
          ? '受邀校验开启'
          : '访客可直接注册',
        warn: Boolean(inviteValidation.error),
      },
      {
        label: '默认流量',
        value: `${flowPreview.value} GB`,
        sub: flowPreview.isInvalid
          ? '输入越界→回退默认'
          : flowPreview.value === 0
          ? '0 GB 初始额度'
          : flowPreview.isDefault
          ? '系统默认值'
          : '自定义配额',
        warn: flowPreview.isInvalid,
      },
      {
        label: '转发数上限',
        value: `${numPreview.value} 条`,
        sub: numPreview.isInvalid
          ? '输入越界→回退默认'
          : numPreview.isDefault
          ? '系统默认值'
          : '自定义上限',
        warn: numPreview.isInvalid,
      },
      {
        label: '有效期天数',
        value: `${expPreview.value} 天`,
        sub: expPreview.isInvalid
          ? '输入越界→回退默认'
          : expPreview.isDefault
          ? '系统默认值'
          : '自注册起算',
        warn: expPreview.isInvalid,
      },
      {
        label: '流量重置日',
        value: resetSummary,
        sub: resetPreview.isInvalid
          ? '输入越界→回退默认'
          : resetPreview.value === 0
          ? '一次性配额'
          : resetPreview.isDefault
          ? '系统默认值'
          : '按月清零已用',
        warn: resetPreview.isInvalid,
      },
    ];

    return (
      <div className="tms-subpanel mt-4 rounded-md border border-default-200 bg-default-50/80 p-3.5 space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold tracking-wide text-foreground">
              新用户默认配额实时预览
            </span>
            <Chip
              size="sm"
              variant="flat"
              color={isOpen ? 'primary' : 'default'}
              className="h-5 text-[11px]"
            >
              {isOpen
                ? validInviteCodes.length > 0
                  ? '受邀注册生效中'
                  : '公开注册生效中'
                : '注册入口关闭'}
            </Chip>
          </div>
          {hasRegisterUnsaved && (
            <span className="text-[11px] font-mono text-warning-600 dark:text-warning-400">
              * 当前预览含未保存修改，点击右上角「保存配置」后正式生效
            </span>
          )}
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2">
          {summaryItems.map((entry) => (
            <div
              key={entry.label}
              className="tms-metric-tile rounded border border-default-200 bg-content1 px-2.5 py-2 flex flex-col justify-between gap-0.5"
            >
              <span className="text-[11px] text-default-500 truncate">{entry.label}</span>
              <span
                className={`text-xs font-semibold font-mono tabular-nums truncate ${
                  entry.warn ? 'text-danger' : 'text-foreground'
                }`}
              >
                {entry.value}
              </span>
              <span
                className={`text-[10px] truncate ${
                  entry.warn ? 'text-danger-500' : 'text-default-400'
                }`}
              >
                {entry.sub}
              </span>
            </div>
          ))}
        </div>
      </div>
    );
  };

  // 检查配置项是否应该显示（依赖检查）
  const shouldShowItem = (item: ConfigItem): boolean => {
    if (!item.dependsOn || !item.dependsValue) {
      return true;
    }
    return configs[item.dependsOn] === item.dependsValue;
  };

  // 渲染不同类型的配置项
  const renderConfigItem = (item: ConfigItem) => {
    const isChanged = hasChanges && configs[item.key] !== originalConfigs[item.key];
    
    switch (item.type) {
      case 'input': {
        const validation = validateConfigValue(item.key, configs[item.key]);
        const currentValue = configs[item.key] || '';
        const inputElement = (
          <Input
            type={item.sensitive && !revealedKeys[item.key] ? 'password' : 'text'}
            value={currentValue}
            onChange={(e) => handleConfigChange(item.key, e.target.value)}
            placeholder={item.placeholder}
            variant="bordered"
            size="md"
            isInvalid={Boolean(validation.error)}
            errorMessage={validation.error}
            description={!validation.error ? validation.hint : undefined}
            endContent={
              item.sensitive ? (
                <button
                  type="button"
                  onClick={() =>
                    setRevealedKeys((prev) => ({ ...prev, [item.key]: !prev[item.key] }))
                  }
                  className="text-xs text-default-500 hover:text-primary px-1 shrink-0"
                >
                  {revealedKeys[item.key] ? '隐藏' : '显示'}
                </button>
              ) : undefined
            }
            classNames={{
              input: "text-sm",
              inputWrapper: isChanged 
                ? "border-warning-300 data-[hover=true]:border-warning-400" 
                : ""
            }}
          />
        );

        if (item.key === 'register_invite_code') {
          const hasAnyText = currentValue.length > 0;
          const hasCopyableInvite = trimInviteToken(currentValue).length > 0;
          return (
            <div className="flex flex-col sm:flex-row sm:items-start gap-2">
              <div className="flex-1 min-w-0">{inputElement}</div>
              <div className="flex flex-wrap items-center gap-1.5 shrink-0">
                <Button
                  size="md"
                  variant="flat"
                  color="primary"
                  onPress={handleGenerateInviteCode}
                  className="shrink-0 font-mono text-xs sm:text-sm"
                >
                  生成邀请码
                </Button>
                <Button
                  size="md"
                  variant="flat"
                  color="default"
                  isDisabled={!hasCopyableInvite}
                  onPress={handleCopyInviteCodes}
                  className="shrink-0 text-xs sm:text-sm"
                >
                  复制邀请码
                </Button>
                {hasAnyText && (
                  <Button
                    size="md"
                    variant="flat"
                    color="danger"
                    onPress={handleClearInviteCodes}
                    className="shrink-0 text-xs sm:text-sm"
                  >
                    清空
                  </Button>
                )}
              </div>
            </div>
          );
        }

        if (!item.copyable) {
          return inputElement;
        }

        return (
          <div className="flex items-start gap-2">
            <div className="flex-1 min-w-0">{inputElement}</div>
            <Button
              size="md"
              variant="flat"
              color="default"
              isDisabled={!currentValue.trim()}
              onPress={async () => {
                const ok = await copyTextToClipboard(currentValue.trim());
                if (ok) {
                  toast.success(`已复制${item.label}`);
                } else {
                  toast.error('复制失败，请手动选中复制');
                }
              }}
              className="shrink-0"
            >
              复制
            </Button>
          </div>
        );
      }

      case 'switch':
        return (
          <Switch
            isSelected={configs[item.key] === 'true'}
            onValueChange={(checked) => handleConfigChange(item.key, checked ? 'true' : 'false')}
            color="primary"
            size="md"
            classNames={{
              wrapper: isChanged ? "border-warning-300" : ""
            }}
          >
            <span className="text-sm text-gray-700 dark:text-gray-300">
              {configs[item.key] === 'true' ? '已启用' : '已禁用'}
            </span>
          </Switch>
        );

      case 'select':
        return (
          <Select
            selectedKeys={configs[item.key] ? [configs[item.key]] : []}
            onSelectionChange={(keys) => {
              const selectedKey = Array.from(keys)[0] as string;
              if (selectedKey) {
                handleConfigChange(item.key, selectedKey);
              }
            }}
            placeholder="请选择验证码类型"
            variant="bordered"
            size="md"
            classNames={{
              trigger: isChanged 
                ? "border-warning-300 data-[hover=true]:border-warning-400" 
                : ""
            }}
          >
            {item.options?.map((option) => (
              <SelectItem 
                key={option.value}
                description={option.description}
              >
                {option.label}
              </SelectItem>
            )) || []}
          </Select>
        );

      default:
        return null;
    }
  };

  if (loading) {
    return (
      
        <div className="flex items-center justify-center min-h-[400px]">
          <Spinner size="lg" label="加载配置中..." />
        </div>
      
    );
  }

  return (
    
      <div className="p-6 max-w-4xl mx-auto">
        {/* 页面标题 */}
        <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
          <div className="flex items-center gap-3">
            <SettingsIcon className="w-8 h-8 text-primary" />
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-2xl font-bold">网站配置</h1>
                <Chip size="sm" variant="flat" color={hasChanges ? "warning" : "success"}>
                  {hasChanges ? '有未保存修改' : '已同步'}
                </Chip>
                {versionInfo && (
                  <Chip size="sm" variant="flat" color={versionInfo.updateAvailable ? "warning" : "default"}>
                    v{versionInfo.panelVersion || '1.1.0'} ({versionInfo.commit || 'dev'})
                    {versionInfo.updateAvailable && versionInfo.latest ? ` → 可更新 ${versionInfo.latest}` : ''}
                  </Chip>
                )}
              </div>
              <p className="text-gray-600 dark:text-gray-400">
                管理网站的基本信息和显示设置
              </p>
            </div>
          </div>
          <Button
            size="sm"
            variant="flat"
            color="primary"
            onPress={handleRefreshVersion}
            isLoading={refreshingVersion}
          >
            {refreshingVersion ? '检查中...' : '🔄 强制刷新版本'}
          </Button>
        </div>

        <Card className="shadow-md">
          <CardHeader className="pb-4">
            <div className="flex justify-between items-center w-full">
              <div>
                <h2 className="text-xl font-semibold">基本设置</h2>
                <p className="text-sm text-gray-600 dark:text-gray-400">
                  配置网站的基本信息，这些设置会影响网站的显示效果
                </p>
              </div>
              <div className="flex gap-2">
                <Button
                  variant="flat"
                  color="secondary"
                  onPress={handleTestAlert}
                  isLoading={testingAlert}
                >
                  {testingAlert ? '发送中...' : '🔔 测试告警'}
                </Button>
                <Button
                  color="primary"
                  startContent={<SaveIcon className="w-4 h-4" />}
                  onClick={handleSave}
                  isLoading={saving}
                  disabled={!hasChanges}
                >
                  {saving ? '保存中...' : '保存配置'}
                </Button>
              </div>
            </div>
          </CardHeader>

          <Divider />

          <CardBody className="space-y-6 pt-6">
            {CONFIG_ITEMS.map((item, index) => {
              // 检查配置项是否应该显示
              if (!shouldShowItem(item)) {
                return null;
              }

              // 计算是否是最后一个显示的项目（用于决定是否显示分隔线）
              const remainingItems = CONFIG_ITEMS.slice(index + 1).filter(shouldShowItem);
              const isLastItem = remainingItems.length === 0;

              return (
                <div key={item.key} className="space-y-3">
                  {item.key === 'register_enabled' && (
                    <div className="rounded-md border border-default-200 bg-default-100/70 px-3.5 py-2.5 flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <div className="text-sm font-semibold text-foreground">用户自助注册与初始配额</div>
                        <div className="text-xs text-default-500">
                          管理登录页自助注册入口、受邀注册校验及新账号默认套餐额度
                        </div>
                      </div>
                      <Chip
                        size="sm"
                        variant="flat"
                        color={configs.register_enabled === 'true' ? 'primary' : 'default'}
                      >
                        {configs.register_enabled === 'true'
                          ? (parseValidInviteCodes(configs.register_invite_code).length > 0
                            ? `已开放 · 邀请码模式 (${parseValidInviteCodes(configs.register_invite_code).length}个)`
                            : '已开放 · 公开注册')
                          : '未开放'}
                      </Chip>
                    </div>
                  )}
                  <div className="flex flex-col gap-1">
                    <label className="text-sm font-medium text-gray-700 dark:text-gray-300">
                      {item.label}
                    </label>
                    {item.description && (
                      <p className="text-xs text-gray-500 dark:text-gray-400">
                        {item.description}
                      </p>
                    )}
                  </div>
                  
                  {/* 渲染配置项 */}
                  {renderConfigItem(item)}

                  {/* 注册配置区块底部的实时配额摘要条 */}
                  {((item.key === 'register_enabled' && configs.register_enabled !== 'true') ||
                    item.key === 'register_default_flow_reset_time') &&
                    renderRegisterQuotaPreview()}
                  
                  {/* 分隔线 */}
                  {!isLastItem && (
                    <Divider className="mt-6" />
                  )}
                </div>
              );
            })}
          </CardBody>
        </Card>

        {/* 操作提示 */}
        {hasChanges && (
          <Card className="tms-alert-card-warning mt-4 bg-warning-50 dark:bg-warning-900/20 border-warning-200 dark:border-warning-800">
            <CardBody className="py-3">
              <div className="flex items-center gap-2 text-warning-700 dark:text-warning-300">
                <div className="w-2 h-2 bg-warning-500 rounded-full animate-pulse" />
                <span className="text-sm">
                  检测到配置变更，请记得保存您的修改
                </span>
              </div>
            </CardBody>
          </Card>
        )}
      </div>
    
  );
} 