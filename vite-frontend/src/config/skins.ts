// 多主题(皮肤)配置：默认「像素黑白灰」(pixel)，保留全部经典渐变皮肤自由切换。
export type PixelColorMode = "light" | "dark";

export interface Skin {
  id: string;
  name: string;
  base: "light" | "dark";
  swatch: string; // 选择器里的小色块缩影
  supportsModeToggle?: boolean; // 是否支持明暗双模式手动切换
}

export const SKINS: Skin[] = [
  // 默认皮肤：像素黑白灰，支持明暗双模式
  {
    id: "pixel",
    name: "像素黑白灰",
    base: "light",
    supportsModeToggle: true,
    swatch: "conic-gradient(#111111 0 25%, #e5e5e5 0 50%, #737373 0 75%, #ffffff 0)",
  },
  // 深空黑底 + 紫蓝渐变，颜色定义在 themes.css
  { id: "sanyue", name: "深空紫蓝", base: "dark", swatch: "linear-gradient(135deg,#8a6bff,#5b8cff)" },
  { id: "aurora", name: "极光", base: "dark", swatch: "linear-gradient(135deg,#302b63,#a855f7)" },
  { id: "mesh", name: "流光", base: "light", swatch: "linear-gradient(135deg,#c2e9fb,#fbc2eb)" },
  { id: "deepsea", name: "深海", base: "dark", swatch: "linear-gradient(135deg,#0e3a5f,#38bdf8)" },
  { id: "sunrise", name: "晨曦", base: "light", swatch: "linear-gradient(135deg,#ffaa96,#b4a0ff)" },
  { id: "cyber", name: "赛博", base: "dark", swatch: "linear-gradient(135deg,#ec4899,#22d3ee)" },
  { id: "mint", name: "薄荷", base: "light", swatch: "linear-gradient(135deg,#a7f3d0,#bae6fd)" },
  { id: "midnight", name: "午夜", base: "dark", swatch: "linear-gradient(135deg,#0b0e14,#1f2937)" },
  { id: "clean", name: "简白", base: "light", swatch: "linear-gradient(135deg,#f8fafc,#e5e7eb)" },
  { id: "forest", name: "森林", base: "dark", swatch: "linear-gradient(135deg,#065f46,#10b981)" },
  { id: "lava", name: "熔岩", base: "dark", swatch: "linear-gradient(135deg,#b91c1c,#f97316)" },
  { id: "sakura", name: "樱花", base: "light", swatch: "linear-gradient(135deg,#fbcfe8,#e9d5ff)" },
  { id: "grape", name: "葡萄", base: "dark", swatch: "linear-gradient(135deg,#6d28d9,#db2777)" },
  { id: "sand", name: "暖沙", base: "light", swatch: "linear-gradient(135deg,#fde8c8,#f5d0a9)" },
  { id: "steel", name: "钢蓝", base: "dark", swatch: "linear-gradient(135deg,#1e293b,#475569)" },
];

export const DEFAULT_SKIN = "pixel";

const SKIN_DEFAULT_VERSION_KEY = "skinDefaultV3";
const PIXEL_MODE_STORAGE_KEY = "pixel_mode";
const LEGACY_DEFAULT_SKINS = new Set(["sanyue", "aurora"]);

export const SKIN_CHANGED_EVENT = "tms-skin-changed";

export function getPixelMode(): PixelColorMode {
  if (typeof window === "undefined") return "light";
  try {
    const stored = localStorage.getItem(PIXEL_MODE_STORAGE_KEY);
    if (stored === "light" || stored === "dark") return stored;
  } catch {
    // 忽略受限存储环境异常
  }
  return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
    ? "dark"
    : "light";
}

export function savedSkin(): Skin {
  if (typeof localStorage === "undefined") return SKINS[0];
  let id = localStorage.getItem("skin");
  // 默认皮肤升级为 pixel（像素黑白灰）时，首次访问或此前停留在旧默认皮肤（sanyue / aurora）的
  // 用户平滑迁移至新默认主题；只迁移这一次，之后用户在切换器中手动切回任意其他皮肤都能正常持久化。
  if (localStorage.getItem(SKIN_DEFAULT_VERSION_KEY) !== "1") {
    if (id === null || LEGACY_DEFAULT_SKINS.has(id)) {
      id = DEFAULT_SKIN;
    }
    localStorage.setItem(SKIN_DEFAULT_VERSION_KEY, "1");
  }
  return SKINS.find((s) => s.id === id) ?? SKINS[0];
}

// 把皮肤落到 <html>：设置 data-skin 属性 + 皮肤类 + dark 类（驱动 Tailwind dark: 与 HeroUI）。
export function applySkin(id: string, pixelModeOverride?: PixelColorMode): void {
  const skin = SKINS.find((s) => s.id === id) ?? SKINS[0];
  const html = document.documentElement;
  SKINS.forEach((s) => html.classList.remove(s.id));
  html.classList.add(skin.id);
  html.setAttribute("data-skin", skin.id);

  let effectiveMode: PixelColorMode = skin.base;
  if (skin.supportsModeToggle) {
    if (pixelModeOverride) {
      effectiveMode = pixelModeOverride;
      try {
        localStorage.setItem(PIXEL_MODE_STORAGE_KEY, pixelModeOverride);
      } catch {
        // 忽略存储异常
      }
    } else {
      effectiveMode = getPixelMode();
    }
  }

  if (effectiveMode === "dark") html.classList.add("dark");
  else html.classList.remove("dark");
  html.style.colorScheme = effectiveMode;

  try {
    localStorage.setItem("skin", skin.id);
    localStorage.setItem(SKIN_DEFAULT_VERSION_KEY, "1");
  } catch {
    // 忽略存储异常
  }

  if (typeof window !== "undefined") {
    window.dispatchEvent(
      new CustomEvent(SKIN_CHANGED_EVENT, {
        detail: { skinId: skin.id, mode: effectiveMode },
      }),
    );
  }
}

export function togglePixelMode(): PixelColorMode {
  const next: PixelColorMode = getPixelMode() === "dark" ? "light" : "dark";
  applySkin("pixel", next);
  return next;
}
