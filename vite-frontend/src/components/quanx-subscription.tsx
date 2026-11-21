import { useState } from "react";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import toast from "react-hot-toast";
import { setQuantumultXPermission } from "@/api";
import { copyTextToClipboard } from "@/utils/clipboard";
import { SubQrToggle } from "@/components/sub-qr";

export function QuanxSubscription({ base, token }: { base: string; token: string }) {
  const url = `${base}/api/v1/open_api/quanx?token=${encodeURIComponent(token)}`;
  return (
    <div className="space-y-2 min-w-0 pt-2 border-t border-default-200">
      <Input
        label="Quantumult X"
        labelPlacement="outside"
        readOnly
        size="sm"
        value={url}
        onClick={(e: any) => {
          e.target?.select?.();
        }}
      />
      <div className="flex flex-wrap gap-2 items-start">
        <Button
          size="sm"
          variant="flat"
          onPress={async () => {
            (await copyTextToClipboard(url))
              ? toast.success("已复制 Quantumult X 订阅")
              : toast.error("复制失败");
          }}
        >
          复制 Quantumult X
        </Button>
        <SubQrToggle url={url} />
      </div>
    </div>
  );
}

export function QuanxPermission({
  userId,
  enabled,
  onChange,
}: {
  userId: number;
  enabled: boolean;
  onChange: (next: boolean) => void;
}) {
  const [saving, setSaving] = useState(false);
  return (
    <label className="inline-flex items-center gap-2 text-sm cursor-pointer">
      <input
        type="checkbox"
        className="w-4 h-4 accent-primary"
        checked={enabled}
        disabled={saving}
        onChange={async (event) => {
          const next = event.target.checked;
          setSaving(true);
          try {
            const result = await setQuantumultXPermission(userId, next);
            if (result.code !== 0) throw new Error(result.msg || "保存失败");
            onChange(next);
            toast.success("Quantumult X 权限已保存");
          } catch (error) {
            toast.error(error instanceof Error ? error.message : "保存失败");
          } finally {
            setSaving(false);
          }
        }}
      />
      Quantumult X 订阅权限
    </label>
  );
}
