import { useEffect } from 'react';
import type { RefObject } from 'react';

const SEARCH_INPUT_SELECTOR = 'input[type="search"], input[placeholder*="搜索"]';

function isElementVisible(el: HTMLElement): boolean {
  if (!el.isConnected) return false;
  const style = window.getComputedStyle(el);
  if (
    style.display === 'none' ||
    style.visibility === 'hidden' ||
    style.opacity === '0'
  ) {
    return false;
  }
  const rect = el.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0;
}

function hasOpenModalOrDrawer(): boolean {
  const overlays = document.querySelectorAll<HTMLElement>(
    '[role="dialog"], [role="alertdialog"], [aria-modal="true"]',
  );
  for (let i = 0; i < overlays.length; i++) {
    if (isElementVisible(overlays[i])) {
      return true;
    }
  }
  return false;
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  const tagName = target.tagName;
  if (tagName === 'INPUT' || tagName === 'TEXTAREA' || tagName === 'SELECT') {
    return true;
  }
  if (target.isContentEditable) {
    return true;
  }
  return Boolean(
    target.closest('[contenteditable="true"], [contenteditable=""], [role="textbox"]'),
  );
}

function findFirstVisibleSearchInput(container: HTMLElement): HTMLInputElement | null {
  const inputs = container.querySelectorAll<HTMLInputElement>(SEARCH_INPUT_SELECTOR);
  for (let i = 0; i < inputs.length; i++) {
    const input = inputs[i];
    if (input.disabled || input.readOnly) continue;
    if (input.closest('[role="dialog"], [aria-modal="true"]')) continue;
    if (isElementVisible(input)) {
      return input;
    }
  }
  return null;
}

/**
 * 全局 `/` 快捷搜索支持：
 * - 非输入态且无模态框/抽屉打开时按 `/` 聚焦主内容区首个可见搜索输入框并选中内容；
 * - 焦点在该搜索框内时按 `Escape` 自动失焦（不干扰弹窗原生 Escape 关闭）。
 */
export function useSearchShortcut(
  containerRef: RefObject<HTMLElement | null>,
  options?: { disabled?: boolean },
): void {
  const disabled = options?.disabled ?? false;

  useEffect(() => {
    if (disabled) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.isComposing || e.keyCode === 229) {
        return;
      }

      if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        if (hasOpenModalOrDrawer()) return;
        if (isEditableTarget(e.target) || isEditableTarget(document.activeElement)) {
          return;
        }
        const container = containerRef.current;
        if (!container) return;

        const targetInput = findFirstVisibleSearchInput(container);
        if (!targetInput) return;

        e.preventDefault();
        targetInput.focus();
        targetInput.select();
        return;
      }

      if (e.key === 'Escape') {
        if (hasOpenModalOrDrawer()) return;
        const activeEl = document.activeElement;
        if (
          activeEl instanceof HTMLInputElement &&
          containerRef.current?.contains(activeEl) &&
          activeEl.matches(SEARCH_INPUT_SELECTOR)
        ) {
          activeEl.blur();
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [containerRef, disabled]);
}
