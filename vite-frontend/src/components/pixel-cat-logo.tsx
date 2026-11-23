import * as React from "react";
import { IconSvgProps } from "@/types";

export interface PixelCatLogoProps extends IconSvgProps {
  /** 可选自定义图片 Logo URL；未传或加载失败时回退为黑色像素风小猫 */
  logoUrl?: string | null;
  alt?: string;
}

/**
 * 黑色像素风小猫 Logo（18x18 整数像素网格，crispEdges 锐利渲染）。
 * - 本体始终保持纯黑像素猫造型，配高辨识度白色像素眼、浅灰鼻尖与深灰内耳细节；
 * - 暗色模式下通过底层 1px 浅灰像素描边轮廓，确保黑色小猫在深色背景中锐利清晰。
 */
export const PixelCatLogo: React.FC<PixelCatLogoProps> = ({
  size = 32,
  width,
  height,
  className = "",
  logoUrl,
  alt = "Cat Tunnel Panel Logo",
  style,
  ...props
}) => {
  const [imgFailed, setImgFailed] = React.useState(false);

  React.useEffect(() => {
    setImgFailed(false);
  }, [logoUrl]);

  const resolvedWidth = width ?? size;
  const resolvedHeight = height ?? size;

  if (logoUrl && !imgFailed) {
    return (
      <img
        src={logoUrl}
        alt={alt}
        width={resolvedWidth}
        height={resolvedHeight}
        onError={() => setImgFailed(true)}
        className={`object-contain shrink-0 ${className}`}
        style={{
          width: resolvedWidth,
          height: resolvedHeight,
          imageRendering: "pixelated",
          ...style,
        }}
      />
    );
  }

  return (
    <svg
      viewBox="0 0 18 18"
      width={resolvedWidth}
      height={resolvedHeight}
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      shapeRendering="crispEdges"
      role="img"
      aria-label={alt}
      className={`shrink-0 ${className}`}
      style={{
        imageRendering: "pixelated",
        ...style,
      }}
      {...props}
    >
      {/* 暗色模式 1px 浅灰像素外描边（位于黑猫剪影下方，确保深色背景下轮廓锐利） */}
      <g className="opacity-0 dark:opacity-100" fill="#E5E5E5">
        <rect x="3" y="1" width="1" height="1" />
        <rect x="14" y="1" width="1" height="1" />
        <rect x="2" y="2" width="3" height="1" />
        <rect x="13" y="2" width="3" height="1" />
        <rect x="2" y="3" width="4" height="1" />
        <rect x="12" y="3" width="4" height="1" />
        <rect x="2" y="4" width="14" height="1" />
        <rect x="1" y="5" width="16" height="6" />
        <rect x="2" y="11" width="14" height="1" />
        <rect x="3" y="12" width="12" height="1" />
        <rect x="4" y="13" width="10" height="1" />
        <rect x="3" y="14" width="12" height="2" />
        <rect x="4" y="16" width="10" height="1" />
      </g>

      {/* 黑色像素小猫主体剪影（双尖猫耳 + 圆润像素脸庞 + 半身胸肩） */}
      <g fill="#111111">
        {/* 左耳 */}
        <rect x="3" y="2" width="1" height="1" />
        <rect x="3" y="3" width="2" height="1" />
        <rect x="3" y="4" width="3" height="1" />
        {/* 右耳 */}
        <rect x="14" y="2" width="1" height="1" />
        <rect x="13" y="3" width="2" height="1" />
        <rect x="12" y="4" width="3" height="1" />
        {/* 头部与脸庞 */}
        <rect x="3" y="5" width="12" height="1" />
        <rect x="2" y="6" width="14" height="5" />
        <rect x="3" y="11" width="12" height="1" />
        <rect x="4" y="12" width="10" height="1" />
        {/* 颈部与半身 */}
        <rect x="5" y="13" width="8" height="1" />
        <rect x="4" y="14" width="10" height="2" />
      </g>

      {/* 深灰内耳点缀 */}
      <g fill="#525252">
        <rect x="4" y="4" width="1" height="1" />
        <rect x="13" y="4" width="1" height="1" />
      </g>

      {/* 高对比白色像素眼睛 */}
      <g fill="#FFFFFF">
        <rect x="5" y="8" width="2" height="2" />
        <rect x="11" y="8" width="2" height="2" />
      </g>

      {/* 浅灰小鼻尖 */}
      <g fill="#D4D4D4">
        <rect x="8" y="10" width="2" height="1" />
      </g>

      {/* 像素胡须点睛 */}
      <g fill="#737373">
        <rect x="2" y="10" width="1" height="1" />
        <rect x="15" y="10" width="1" height="1" />
      </g>
    </svg>
  );
};

export default PixelCatLogo;
