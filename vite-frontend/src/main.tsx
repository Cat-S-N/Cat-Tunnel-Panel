
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";

import App from "./App.tsx";
import { Provider } from "./provider.tsx";
import "@/styles/globals.css";
import "@/styles/themes.css";
import { applySkin, savedSkin } from "@/config/skins";

// 首屏就把皮肤落到 <html>,避免主题闪烁
applySkin(savedSkin().id);

// 发布新前端后，旧标签页懒加载旧分包失败时自动刷新一次（10 秒内防死循环）
window.addEventListener("vite:preloadError", (event) => {
  event.preventDefault();
  try {
    const key = "tms_preload_reload_at";
    const last = Number(sessionStorage.getItem(key) || "0");
    if (Date.now() - last > 10000) {
      sessionStorage.setItem(key, String(Date.now()));
      window.location.reload();
    }
  } catch {
    window.location.reload();
  }
});

ReactDOM.createRoot(document.getElementById("root")!).render(
  <BrowserRouter>
    <Provider>
      <App />
    </Provider>
  </BrowserRouter>
);

