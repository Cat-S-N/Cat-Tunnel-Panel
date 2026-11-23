import React from "react";
import { Button } from "@heroui/button";

interface State {
  hasError: boolean;
  errorMsg: string;
}

const CHUNK_RELOAD_KEY = "tms_chunk_reload_at";

function isChunkLoadError(err: unknown): boolean {
  const msg = String((err as any)?.message || err || "");
  return /Loading chunk|Failed to fetch dynamically imported module|Importing a module script failed|ChunkLoadError/i.test(msg);
}

/**
 * 全局错误边界：防止懒加载分包失效或渲染异常导致整页黑屏/白屏。
 */
export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, State> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false, errorMsg: "" };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, errorMsg: error?.message || "页面加载异常" };
  }

  componentDidCatch(error: Error) {
    if (isChunkLoadError(error)) {
      try {
        const last = Number(sessionStorage.getItem(CHUNK_RELOAD_KEY) || "0");
        if (Date.now() - last > 10000) {
          sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now()));
          window.location.reload();
        }
      } catch {
        // ignore storage errors
      }
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="min-h-screen flex items-center justify-center p-6">
          <div className="max-w-md w-full rounded-xl border border-default-200 bg-content1 p-6 text-center space-y-4 shadow-lg">
            <div className="text-lg font-semibold">页面资源已更新或加载异常</div>
            <div className="text-xs text-default-500 break-all">{this.state.errorMsg}</div>
            <div className="flex flex-wrap items-center justify-center gap-2 pt-1">
              <Button
                variant="flat"
                onPress={() => {
                  if (window.history.length > 1) {
                    window.history.back();
                  } else {
                    window.location.href = "/";
                  }
                }}
              >
                返回上一页
              </Button>
              <Button
                variant="flat"
                onPress={() => {
                  window.location.href = "/";
                }}
              >
                返回首页
              </Button>
              <Button color="primary" onPress={() => window.location.reload()}>
                刷新重试
              </Button>
            </div>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
