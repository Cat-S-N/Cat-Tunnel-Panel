import { useState } from "react";
import { Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from "@heroui/dropdown";
import { Button } from "@heroui/button";
import { SKINS, savedSkin, applySkin } from "@/config/skins";

// 头部的主题(皮肤)选择器,自包含状态,不依赖布局。
export default function SkinPicker() {
  const [current, setCurrent] = useState<string>(savedSkin().id);

  const swatch = (bg: string) => (
    <span
      style={{
        width: 18,
        height: 18,
        borderRadius: 6,
        backgroundImage: bg,
        display: "inline-block",
        border: "1px solid rgba(128,128,128,.4)",
      }}
    />
  );

  const currentSkin = SKINS.find((s) => s.id === current) ?? SKINS[0];
  const currentSwatch = currentSkin.swatch;
  const triggerLabel = `切换主题（当前：${currentSkin.name}）`;

  return (
    <Dropdown placement="bottom-end">
      <DropdownTrigger>
        <Button
          isIconOnly
          variant="light"
          aria-label={triggerLabel}
          title={triggerLabel}
        >
          {swatch(currentSwatch)}
        </Button>
      </DropdownTrigger>
      <DropdownMenu
        aria-label="主题"
        selectionMode="single"
        selectedKeys={new Set([current])}
        onAction={(key) => {
          applySkin(String(key));
          setCurrent(String(key));
        }}
      >
        {SKINS.map((s) => (
          <DropdownItem key={s.id} startContent={swatch(s.swatch)}>
            {s.name}
          </DropdownItem>
        ))}
      </DropdownMenu>
    </Dropdown>
  );
}
