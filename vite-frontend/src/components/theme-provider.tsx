import React, { useEffect } from 'react';
import { applySkin, savedSkin } from '@/config/skins';

interface ThemeProviderProps {
  children: React.ReactNode;
}

// 应用已保存的皮肤（默认 pixel 像素黑白灰）。
export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  useEffect(() => {
    applySkin(savedSkin().id);
  }, []);

  return <>{children}</>;
};
