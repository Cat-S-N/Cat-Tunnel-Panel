import { useEffect, useState } from "react";
import { Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from "@heroui/dropdown";
import { Button } from "@heroui/button";
import {
  SKINS,
  savedSkin,
  applySkin,
  getPixelMode,
  togglePixelMode,
  SKIN_CHANGED_EVENT,
  PixelColorMode,
} from "@/config/skins";

// 头部的主题(皮肤)选择器，自包含状态，不依赖布局。
// 当使用「像素黑白灰」主题时，提供一键明暗双模式切换按钮。
export default function SkinPicker() {
  const [current, setCurrent] = useState<string>(() => savedSkin().id);
  const [pixelMode, setPixelMode] = useState<PixelColorMode>(() => getPixelMode());

  useEffect(() => {
    const handleSkinChange = () => {
      setCurrent(savedSkin().id);
      setPixelMode(getPixelMode());
    };
    window.addEventListener(SKIN_CHANGED_EVENT, handleSkinChange);
    return () => window.removeEventListener(SKIN_CHANGED_EVENT, handleSkinChange);
  }, []);

  const swatch = (bg: string, isPixel?: boolean) => (
    <span
      style={{
        width: 18,
        height: 18,
        borderRadius: isPixel ? 2 : 6,
        backgroundImage: bg,
        display: "inline-block",
        border: isPixel ? "1.5px solid var(--pixel-border, #171717)" : "1px solid rgba(128,128,128,.4)",
        imageRendering: isPixel ? "pixelated" : undefined,
      }}
    />
  );

  const currentSkin = SKINS.find((s) => s.id === current) ?? SKINS[0];
  const triggerLabel = `切换主题（当前：${currentSkin.name}）`;

  return (
    <div className="inline-flex items-center gap-1">
      {currentSkin.supportsModeToggle && (
        <Button
          isIconOnly
          size="sm"
          variant="light"
          onPress={() => setPixelMode(togglePixelMode())}
          aria-label={pixelMode === "dark" ? "切换到像素浅色模式" : "切换到像素暗色模式"}
          title={pixelMode === "dark" ? "当前暗色模式，点击切换浅色" : "当前浅色模式，点击切换暗色"}
        >
          {pixelMode === "dark" ? (
            <svg
              viewBox="0 0 16 16"
              width="16"
              height="16"
              fill="currentColor"
              shapeRendering="crispEdges"
              aria-hidden="true"
            >
              <rect x="7" y="1" width="2" height="2" />
              <rect x="7" y="13" width="2" height="2" />
              <rect x="1" y="7" width="2" height="2" />
              <rect x="13" y="7" width="2" height="2" />
              <rect x="3" y="3" width="2" height="2" />
              <rect x="11" y="3" width="2" height="2" />
              <rect x="3" y="11" width="2" height="2" />
              <rect x="11" y="11" width="2" height="2" />
              <rect x="5" y="5" width="6" height="6" />
            </svg>
          ) : (
            <svg
              viewBox="0 0 16 16"
              width="16"
              height="16"
              fill="currentColor"
              shapeRendering="crispEdges"
              aria-hidden="true"
            >
              <rect x="6" y="2" width="4" height="2" />
              <rect x="4" y="4" width="4" height="2" />
              <rect x="3" y="6" width="4" height="4" />
              <rect x="4" y="10" width="5" height="2" />
              <rect x="6" y="12" width="6" height="2" />
              <rect x="10" y="10" width="3" height="2" />
            </svg>
          )}
        </Button>
      )}
      <Dropdown placement="bottom-end">
        <DropdownTrigger>
          <Button
            isIconOnly
            variant="light"
            aria-label={triggerLabel}
            title={triggerLabel}
          >
            {swatch(currentSkin.swatch, currentSkin.id === "pixel")}
          </Button>
        </DropdownTrigger>
        <DropdownMenu
          aria-label="主题"
          selectionMode="single"
          selectedKeys={new Set([current])}
          onAction={(key) => {
            const nextId = String(key);
            applySkin(nextId);
            setCurrent(nextId);
            if (nextId === "pixel") {
              setPixelMode(getPixelMode());
            }
          }}
        >
          {SKINS.map((s) => (
            <DropdownItem
              key={s.id}
              startContent={swatch(s.swatch, s.id === "pixel")}
            >
              {s.name}
            </DropdownItem>
          ))}
        </DropdownMenu>
      </Dropdown>
    </div>
  );
}
