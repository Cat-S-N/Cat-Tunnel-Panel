import axios, { AxiosResponse } from 'axios';
import toast from 'react-hot-toast';
import { getPanelAddresses, isWebViewFunc } from '@/utils/panel';

interface PanelAddress {
  name: string;
  address: string;
  inx: boolean;
}

const setPanelAddressesFunc = (newAddress: PanelAddress[]) => {
  newAddress.forEach((item) => {
    if (item.inx) {
      baseURL = `${item.address}/api/v1/`;
      axios.defaults.baseURL = baseURL;
    }
  });
};

function getWebViewPanelAddress() {
  (window as any).setAddresses = setPanelAddressesFunc;
  getPanelAddresses('setAddresses');
}

let baseURL: string = '';

export const reinitializeBaseURL = () => {
  if (isWebViewFunc()) {
    getWebViewPanelAddress();
  } else {
    baseURL = import.meta.env.VITE_API_BASE
      ? `${import.meta.env.VITE_API_BASE}/api/v1/`
      : '/api/v1/';
    axios.defaults.baseURL = baseURL;
  }
};

reinitializeBaseURL();

export interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data: T;
}

export interface RequestOptions {
  /** 是否允许在瞬时网络故障时自动重试（GET 默认 true，POST 默认 false，写操作严禁开启） */
  retryable?: boolean;
  /** 显式覆盖超时时间（毫秒） */
  timeout?: number;
  /** 可选取消信号 */
  signal?: AbortSignal;
}

const AUTH_EXPIRED_DEBOUNCE_MS = 1500;
const AUTH_EXPIRED_TOAST_ID = 'network-auth-expired';
let lastAuthExpiredAt = 0;
let authRedirectTimer: number | null = null;

function stripQuery(path: string): string {
  const idx = path.indexOf('?');
  return idx === -1 ? path : path.slice(0, idx);
}

function isPublicPathname(pathname: string): boolean {
  return pathname === '/' || pathname === '/status' || pathname === '/settings';
}

function clearAuthStorage() {
  window.localStorage.removeItem('token');
  window.localStorage.removeItem('role_id');
  window.localStorage.removeItem('name');
  window.localStorage.removeItem('admin');
}

/**
 * 处理 token 失效逻辑，对并发 401 在 1.5s 窗口内防抖去重。
 * 返回 true 表示受保护页即将整页跳转到登录页，调用方可不再额外弹出重复错误。
 */
function handleTokenExpired(reason?: string): boolean {
  clearAuthStorage();

  if (typeof window === 'undefined') {
    return false;
  }

  if (isPublicPathname(window.location.pathname)) {
    return false;
  }

  const now = Date.now();
  if (now - lastAuthExpiredAt >= AUTH_EXPIRED_DEBOUNCE_MS) {
    lastAuthExpiredAt = now;
    toast.error(reason || '登录状态已过期，请重新登录', {
      id: AUTH_EXPIRED_TOAST_ID,
    });
    if (authRedirectTimer === null) {
      authRedirectTimer = window.setTimeout(() => {
        authRedirectTimer = null;
        if (!isPublicPathname(window.location.pathname)) {
          window.location.href = '/';
        }
      }, 150);
    }
  }

  return true;
}

// 检查响应是否为 token 失效
function isTokenExpired(response: ApiResponse, path: string): boolean {
  if (stripQuery(path) === '/user/login') {
    return false;
  }
  return Boolean(response && response.code === 401);
}

/**
 * 慢接口的超时。
 *
 * 这些接口要跟【节点】来回通信:一台机器 6 个协议,每个协议都要下发 gost 服务、
 * 推限速器,而每次节点往返最多等 10 秒(后端 WebSocketServer.send_msg 的设定)。
 * 节点稍微慢一点,累加起来就轻松超过 30 秒 —— 用户看到的就是
 * "一点分配就卡住,过一会儿报 timeout of 30000ms exceeded"。
 *
 * 这里只放宽超时,不改变任何业务逻辑;真正减少往返次数的优化在后端做。
 */
const SLOW_PATHS = [
  '/inbound/assign-all',
  '/inbound/assign-self',
  '/inbound/assign',
  '/inbound/one-click',
  '/inbound/one-click-relay',
  '/inbound/delete-by-node',
  '/inbound/push-config',
  '/landing/test',
  '/landing/update',
  '/node/install',
  '/node/enable-ipv6',
  '/node/disable-ipv6',
  '/forward/create',
  '/forward/update',
  '/tunnel/diagnose',
  '/forward/diagnose',
];

const TEST_PATHS = [
  '/inbound/test-socks',
  '/config/test-alert',
  '/monitor/settings/test',
];

const VERSION_PATHS = ['/version/info'];

const DEFAULT_TIMEOUT = 30000;
const VERSION_TIMEOUT = 15000;
const TEST_TIMEOUT = 60000;
const SLOW_TIMEOUT = 180000;

const MAX_RETRIES = 1;
const RETRY_DELAY_MS = 500;
const RETRYABLE_HTTP_STATUSES = new Set([502, 503, 504]);

function timeoutFor(path: string, customTimeout?: number): number {
  if (typeof customTimeout === 'number' && customTimeout > 0) {
    return customTimeout;
  }
  const cleanPath = stripQuery(path);
  if (SLOW_PATHS.some((p) => cleanPath.startsWith(p))) {
    return SLOW_TIMEOUT;
  }
  if (TEST_PATHS.some((p) => cleanPath.startsWith(p))) {
    return TEST_TIMEOUT;
  }
  if (VERSION_PATHS.some((p) => cleanPath.startsWith(p))) {
    return VERSION_TIMEOUT;
  }
  return DEFAULT_TIMEOUT;
}

function isHtmlPayload(data: unknown): boolean {
  if (typeof data !== 'string') return false;
  const trimmed = data.trim().toLowerCase();
  return (
    /^\s*<(?:!doctype|html|head|body|title|h1|center)/i.test(data) ||
    trimmed.includes('<html') ||
    trimmed.includes('</html>')
  );
}

function extractBackendMessage(data: unknown): string | null {
  if (!data || typeof data !== 'object') {
    return null;
  }
  const obj = data as Record<string, unknown>;
  if (typeof obj.msg === 'string' && obj.msg.trim()) {
    return obj.msg.trim();
  }
  if (
    typeof obj.message === 'string' &&
    obj.message.trim() &&
    obj.message.trim() !== 'No message available'
  ) {
    return obj.message.trim();
  }
  return null;
}

function normalizeSuccessResponse<T>(raw: unknown): ApiResponse<T> {
  if (raw && typeof raw === 'object' && typeof (raw as any).code === 'number') {
    const obj = raw as ApiResponse<T>;
    return {
      code: obj.code,
      msg: typeof obj.msg === 'string' ? obj.msg : '',
      data: obj.data,
    };
  }

  if (raw === '' || raw === null || raw === undefined) {
    return {
      code: -1,
      msg: '服务端返回空响应，请稍后重试',
      data: null as T,
    };
  }

  if (typeof raw === 'string') {
    return {
      code: -1,
      msg: isHtmlPayload(raw)
        ? '服务返回了非 JSON 页面，请检查反向代理或网关配置'
        : '服务响应格式异常，请稍后重试',
      data: null as T,
    };
  }

  const backendMsg = extractBackendMessage(raw);
  return {
    code: -1,
    msg: backendMsg || '服务返回的数据格式异常，请稍后重试',
    data: null as T,
  };
}

function isCanceledError(error: any): boolean {
  return (
    axios.isCancel(error) ||
    error?.code === 'ERR_CANCELED' ||
    error?.name === 'CanceledError' ||
    error?.name === 'AbortError'
  );
}

function isTimeoutError(error: any): boolean {
  return (
    error?.code === 'ECONNABORTED' ||
    error?.code === 'ETIMEDOUT' ||
    /timeout/i.test(String(error?.message || ''))
  );
}

function mapHttpStatusMessage(status: number, isHtml: boolean): string {
  switch (status) {
    case 400:
      return '请求参数有误，请检查后重试';
    case 401:
      return '登录状态已过期，请重新登录';
    case 403:
      return '没有权限执行此操作';
    case 404:
      return '请求的接口不存在，请检查服务版本或反代路径';
    case 408:
      return '请求超时，请稍后重试';
    case 429:
      return '请求过于频繁，请稍后再试';
    case 500:
      return '服务器内部错误，请查看服务日志或稍后重试';
    case 502:
      return '网关错误（502），后端服务可能正在重启或不可达';
    case 503:
      return '服务暂不可用（503），请稍后重试';
    case 504:
      return '网关响应超时（504），请稍后重试';
    default:
      if (isHtml) {
        return `网关返回异常页面（HTTP ${status}），请检查反向代理配置`;
      }
      if (status >= 500) {
        return `服务端异常（HTTP ${status}），请稍后重试`;
      }
      if (status >= 400) {
        return `请求失败（HTTP ${status}），请检查后重试`;
      }
      return '网络请求失败，请稍后重试';
  }
}

function classifyAxiosError<T>(error: any): {
  response: ApiResponse<T>;
  isAuthExpired: boolean;
  isCanceled: boolean;
} {
  if (isCanceledError(error)) {
    return {
      response: { code: -2, msg: '请求已取消', data: null as T },
      isAuthExpired: false,
      isCanceled: true,
    };
  }

  if (error?.response) {
    const status = Number(error.response.status || 0);
    const rawData = error.response.data;
    const backendMsg = extractBackendMessage(rawData);
    const bodyCode =
      rawData && typeof rawData === 'object' && typeof rawData.code === 'number'
        ? rawData.code
        : undefined;

    if (status === 401 || bodyCode === 401) {
      return {
        response: {
          code: 401,
          msg: backendMsg || '登录状态已过期，请重新登录',
          data: null as T,
        },
        isAuthExpired: true,
        isCanceled: false,
      };
    }

    const msg = backendMsg || mapHttpStatusMessage(status, isHtmlPayload(rawData));
    const code = bodyCode !== undefined && bodyCode !== 0 ? bodyCode : -1;
    return {
      response: { code, msg, data: null as T },
      isAuthExpired: false,
      isCanceled: false,
    };
  }

  if (isTimeoutError(error)) {
    return {
      response: {
        code: -1,
        msg: '请求超时，服务器响应较慢，请稍后重试',
        data: null as T,
      },
      isAuthExpired: false,
      isCanceled: false,
    };
  }

  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return {
      response: {
        code: -1,
        msg: '当前网络已断开，请检查网络连接后重试',
        data: null as T,
      },
      isAuthExpired: false,
      isCanceled: false,
    };
  }

  return {
    response: {
      code: -1,
      msg: '无法连接到服务器，请检查网络或面板服务状态',
      data: null as T,
    },
    isAuthExpired: false,
    isCanceled: false,
  };
}

function shouldRetryRequest(
  error: any,
  attempt: number,
  canRetry: boolean,
  signal?: AbortSignal,
): boolean {
  if (!canRetry || attempt >= MAX_RETRIES || isCanceledError(error) || signal?.aborted) {
    return false;
  }

  if (error?.response) {
    return RETRYABLE_HTTP_STATUSES.has(Number(error.response.status));
  }

  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    return false;
  }

  return isTimeoutError(error) || error?.code === 'ERR_NETWORK' || !error?.response;
}

function delayWithAbort(ms: number, signal?: AbortSignal): Promise<boolean> {
  return new Promise((resolve) => {
    if (signal?.aborted) {
      resolve(false);
      return;
    }

    let timer: number | null = null;
    const onAbort = () => {
      if (timer !== null) {
        window.clearTimeout(timer);
        timer = null;
      }
      signal?.removeEventListener('abort', onAbort);
      resolve(false);
    };

    timer = window.setTimeout(() => {
      timer = null;
      signal?.removeEventListener('abort', onAbort);
      resolve(true);
    }, ms);

    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

async function sendRequest<T>(
  method: 'get' | 'post',
  path: string,
  data: any,
  options?: RequestOptions,
): Promise<ApiResponse<T> | null> {
  if (baseURL === '') {
    return { code: -1, msg: ' - 请先设置面板地址', data: null as T };
  }

  // 严禁对非幂等写操作 POST 自动重试：仅 GET 默认可重试，POST 需显式标注 retryable: true
  const canRetry = method === 'get' ? options?.retryable !== false : options?.retryable === true;
  const timeout = timeoutFor(path, options?.timeout);

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    const token = window.localStorage.getItem('token');
    const headers: Record<string, string> = {};
    if (token) {
      headers['Authorization'] = token;
    }
    if (method === 'post') {
      headers['Content-Type'] = 'application/json';
    }

    try {
      const response: AxiosResponse<ApiResponse<T>> =
        method === 'get'
          ? await axios.get(path, {
              params: data,
              timeout,
              signal: options?.signal,
              headers,
            })
          : await axios.post(path, data, {
              timeout,
              signal: options?.signal,
              headers,
            });

      const normalized = normalizeSuccessResponse<T>(response.data);
      if (isTokenExpired(normalized, path)) {
        if (handleTokenExpired(normalized.msg)) {
          return null;
        }
      }
      return normalized;
    } catch (error: any) {
      if (shouldRetryRequest(error, attempt, canRetry, options?.signal)) {
        const proceeded = await delayWithAbort(RETRY_DELAY_MS, options?.signal);
        if (!proceeded) {
          return { code: -2, msg: '请求已取消', data: null as T };
        }
        continue;
      }

      const classified = classifyAxiosError<T>(error);
      if (classified.isCanceled) {
        console.debug(`${method.toUpperCase()}请求已取消:`, path);
      } else {
        console.error(`${method.toUpperCase()}请求错误:`, error);
      }

      if (classified.isAuthExpired && stripQuery(path) !== '/user/login') {
        if (handleTokenExpired(classified.response.msg)) {
          return null;
        }
      }

      return classified.response;
    }
  }

  return { code: -1, msg: '网络请求失败，请稍后重试', data: null as T };
}

const Network = {
  get: function <T = any>(
    path: string = '',
    data: any = {},
    options?: RequestOptions,
  ): Promise<ApiResponse<T>> {
    return new Promise((resolve) => {
      sendRequest<T>('get', path, data, options).then((res) => {
        if (res !== null) {
          resolve(res);
        }
      });
    });
  },

  post: function <T = any>(
    path: string = '',
    data: any = {},
    options?: RequestOptions,
  ): Promise<ApiResponse<T>> {
    return new Promise((resolve) => {
      sendRequest<T>('post', path, data, options).then((res) => {
        if (res !== null) {
          resolve(res);
        }
      });
    });
  },
};

export default Network;
