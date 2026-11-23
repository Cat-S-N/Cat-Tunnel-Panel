import { useState } from "react";

import { CLIENT_DOWNLOAD_URL } from "@/config/site";
import { isAdmin } from "@/utils/auth";

// 关掉后记在浏览器里,不再显示
const DISMISS_KEY = "tms_client_banner_dismissed";

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISS_KEY) === "1";
  } catch {
    return false;
  }
}

/**
 * 管理页顶部的「推荐使用 TMS 客户端」提示条。
 * 只给管理员看;车友(子账号)什么都不渲染,页面和原来完全一样。
 */
export default function ClientRecommendBanner() {
  const [dismissed, setDismissed] = useState<boolean>(readDismissed);

  if (!CLIENT_DOWNLOAD_URL || dismissed || !isAdmin()) return null;

  const close = () => {
    setDismissed(true);
    try {
      localStorage.setItem(DISMISS_KEY, "1");
    } catch {
      // 存不了就只在本次关掉
    }
  };

  return (
    <div className="tms-recommend-banner mx-4 mt-3 lg:mx-6 flex items-center justify-between gap-3 rounded-xl border border-blue-200 bg-blue-50/90 px-3.5 py-2.5 text-sm text-blue-800 dark:border-blue-800/80 dark:bg-blue-950/40 dark:text-blue-200">
      <div className="flex flex-1 flex-wrap items-center gap-x-2.5 gap-y-1.5 leading-6">
        <span>推荐使用 TMS 客户端管理：常用功能都在客户端里，更省事。</span>
        <a
          className="tms-recommend-banner-link inline-flex items-center gap-1 rounded-md bg-blue-600/10 px-2.5 py-0.5 text-xs font-medium text-blue-700 hover:bg-blue-600/20 dark:bg-blue-400/15 dark:text-blue-200 dark:hover:bg-blue-400/25 transition-colors"
          href={CLIENT_DOWNLOAD_URL}
          rel="noopener noreferrer"
          target="_blank"
        >
          下载 TMS 客户端 ↗
        </a>
      </div>
      <button
        aria-label="关闭提示"
        className="tms-recommend-banner-close shrink-0 flex h-7 w-7 items-center justify-center rounded-md text-blue-500 hover:bg-blue-200/60 hover:text-blue-700 dark:text-blue-300 dark:hover:bg-blue-900/60 dark:hover:text-blue-100 transition-colors"
        type="button"
        onClick={close}
      >
        ✕
      </button>
    </div>
  );
}
