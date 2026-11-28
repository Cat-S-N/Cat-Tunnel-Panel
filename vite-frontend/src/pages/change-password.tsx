import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import toast from "react-hot-toast";

import { title } from "@/components/primitives";
import { PixelCatLogo } from "@/components/pixel-cat-logo";
import { updatePassword } from "@/api";
import DefaultLayout from "@/layouts/default";
import { safeLogout } from "@/utils/logout";
import { evaluatePasswordStrength } from "@/utils/password";

interface PasswordForm {
  newUsername: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

const HAS_SPACE_OR_CONTROL = /[\s\u0000-\u0020\u007f\u200b-\u200d\ufeff]/;

const getNewUsernameLiveError = (raw: string): string | undefined => {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (!trimmed) return "新用户名不能全为空格";
  if (HAS_SPACE_OR_CONTROL.test(trimmed)) {
    return "用户名不能包含空格或控制字符";
  }
  if (trimmed.length < 3) {
    return `用户名至少需要 3 位（当前 ${trimmed.length} 位）`;
  }
  if (trimmed.length > 32) {
    return "用户名长度不能超过 32 位";
  }
  return undefined;
};

const getNewPasswordLiveError = (pwd: string, currentPwd: string): string | undefined => {
  if (!pwd) return undefined;
  if (!pwd.trim()) return "新密码不能全为空白字符";
  if (pwd.length < 6) return `新密码长度至少 6 位（当前 ${pwd.length} 位）`;
  if (pwd.length > 64) return "新密码长度不能超过 64 位";
  if (currentPwd && pwd === currentPwd) return "新密码不能与当前密码相同";
  return undefined;
};

export default function ChangePasswordPage() {
  const [form, setForm] = useState<PasswordForm>({
    newUsername: "",
    currentPassword: "",
    newPassword: "",
    confirmPassword: "",
  });
  const [loading, setLoading] = useState(false);
  const [redirecting, setRedirecting] = useState(false);
  const [capsLockOn, setCapsLockOn] = useState(false);
  const [errors, setErrors] = useState<Partial<PasswordForm>>({});
  const submittingRef = useRef(false);
  const timersRef = useRef<number[]>([]);
  const navigate = useNavigate();

  const isBusy = loading || redirecting;

  useEffect(() => {
    return () => {
      timersRef.current.forEach((id) => window.clearTimeout(id));
      timersRef.current = [];
    };
  }, []);

  const validateForm = (): boolean => {
    const newErrors: Partial<PasswordForm> = {};
    const trimmedUsername = form.newUsername.trim();

    if (!trimmedUsername) {
      newErrors.newUsername = "请输入新用户名";
    } else {
      const liveUserErr = getNewUsernameLiveError(form.newUsername);
      if (liveUserErr) {
        newErrors.newUsername = liveUserErr;
      }
    }

    if (!form.currentPassword || !form.currentPassword.trim()) {
      newErrors.currentPassword = "请输入当前密码";
    }

    if (!form.newPassword || !form.newPassword.trim()) {
      newErrors.newPassword = "请输入新密码";
    } else {
      const livePwdErr = getNewPasswordLiveError(form.newPassword, form.currentPassword);
      if (livePwdErr) {
        newErrors.newPassword = livePwdErr;
      }
    }

    if (!form.confirmPassword) {
      newErrors.confirmPassword = "请再次输入新密码";
    } else if (form.confirmPassword !== form.newPassword) {
      newErrors.confirmPassword = "两次输入的新密码不一致";
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleInputChange = (field: keyof PasswordForm, value: string) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: undefined }));
    }
  };

  const handleUsernameBlur = () => {
    const trimmed = form.newUsername.trim();
    if (trimmed !== form.newUsername) {
      setForm((prev) => ({ ...prev, newUsername: trimmed }));
    }
  };

  const handlePasswordKeyEvent = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (typeof e.getModifierState === "function") {
      setCapsLockOn(e.getModifierState("CapsLock"));
    }
  };

  const logout = () => {
    safeLogout();
    navigate("/", { replace: true });
  };

  const handleSubmit = async () => {
    if (isBusy || submittingRef.current) return;
    if (!validateForm()) return;

    submittingRef.current = true;
    setLoading(true);
    let succeeded = false;
    try {
      const response = await updatePassword({
        ...form,
        newUsername: form.newUsername.trim(),
      });

      if (response.code === 0) {
        succeeded = true;
        setRedirecting(true);
        toast.success(response.msg || "账号密码修改成功");

        const t1 = window.setTimeout(() => {
          toast.success("即将跳转到登录页面，请重新登录");
          const t2 = window.setTimeout(() => {
            logout();
          }, 900);
          timersRef.current.push(t2);
        }, 700);
        timersRef.current.push(t1);
      } else {
        toast.error(response.msg || "账号密码修改失败");
      }
    } catch (error) {
      console.error("修改账号密码错误:", error);
      toast.error("修改账号密码时发生错误");
    } finally {
      setLoading(false);
      if (!succeeded) {
        submittingRef.current = false;
      }
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !isBusy && !submittingRef.current) {
      e.preventDefault();
      handleSubmit();
    }
  };

  const liveUsernameError = getNewUsernameLiveError(form.newUsername);
  const effectiveUsernameError = errors.newUsername || liveUsernameError;
  const isUsernameReady = Boolean(form.newUsername.trim()) && !effectiveUsernameError;

  const liveNewPasswordError = getNewPasswordLiveError(form.newPassword, form.currentPassword);
  const effectiveNewPasswordError = errors.newPassword || liveNewPasswordError;
  const passwordStrength = evaluatePasswordStrength(form.newPassword);

  const liveConfirmMismatch =
    Boolean(form.confirmPassword) && form.confirmPassword !== form.newPassword
      ? "两次输入的新密码不一致"
      : undefined;
  const effectiveConfirmError = errors.confirmPassword || liveConfirmMismatch;
  const isConfirmMatch =
    Boolean(form.confirmPassword) &&
    form.confirmPassword === form.newPassword &&
    !effectiveNewPasswordError;

  return (
    <DefaultLayout>
      <section className="flex flex-col items-center justify-center gap-4 py-6 sm:py-8 md:py-10 min-h-[calc(100dvh-200px)]">
        <div className="w-full max-w-lg px-4 sm:px-0">
          <Card className="w-full">
            <CardHeader className="pb-0 pt-6 px-6 flex-col items-center">
              <PixelCatLogo size={48} className="mb-2" />
              <h1 className={title({ size: "sm" })}>安全设置 · 修改账号密码</h1>
              <p className="text-small text-default-500 mt-2 text-center">
                检测到您正在使用默认凭据或需要更新密钥，请设置新的用户名与高强度密码
              </p>
            </CardHeader>

            <CardBody className="px-6 py-6">
              <form
                className="flex flex-col gap-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  handleSubmit();
                }}
              >
                <Input
                  label="新用户名"
                  placeholder="请输入新用户名（3-32位，不含空格）"
                  value={form.newUsername}
                  onChange={(e) => handleInputChange("newUsername", e.target.value)}
                  onBlur={handleUsernameBlur}
                  onKeyDown={handleKeyPress}
                  variant="bordered"
                  isDisabled={isBusy}
                  isInvalid={!!effectiveUsernameError}
                  errorMessage={effectiveUsernameError}
                  description={
                    !effectiveUsernameError
                      ? isUsernameReady
                        ? "✓ 新用户名格式可用"
                        : "支持 3-32 位字符，提交时将自动去除首尾空格"
                      : undefined
                  }
                />

                <Input
                  label="当前密码"
                  placeholder="请输入当前密码"
                  type="password"
                  value={form.currentPassword}
                  onChange={(e) => handleInputChange("currentPassword", e.target.value)}
                  onKeyDown={(e) => {
                    handlePasswordKeyEvent(e);
                    handleKeyPress(e);
                  }}
                  onKeyUp={handlePasswordKeyEvent}
                  onBlur={() => setCapsLockOn(false)}
                  variant="bordered"
                  isDisabled={isBusy}
                  isInvalid={!!errors.currentPassword}
                  errorMessage={errors.currentPassword}
                />

                <div className="flex flex-col gap-1.5">
                  <Input
                    label="新密码"
                    placeholder="请输入新密码（6-64位，不可与当前密码相同）"
                    type="password"
                    value={form.newPassword}
                    onChange={(e) => handleInputChange("newPassword", e.target.value)}
                    onKeyDown={(e) => {
                      handlePasswordKeyEvent(e);
                      handleKeyPress(e);
                    }}
                    onKeyUp={handlePasswordKeyEvent}
                    onBlur={() => setCapsLockOn(false)}
                    variant="bordered"
                    isDisabled={isBusy}
                    isInvalid={!!effectiveNewPasswordError}
                    errorMessage={effectiveNewPasswordError}
                    description={
                      !form.newPassword && !effectiveNewPasswordError
                        ? "密码长度 6-64 位，建议包含大小写字母、数字与特殊符号"
                        : undefined
                    }
                  />

                  {capsLockOn && (
                    <p className="text-xs text-warning px-1 flex items-center gap-1" role="status">
                      <span>大写锁定（Caps Lock）已开启，请注意密码大小写</span>
                    </p>
                  )}

                  {form.newPassword && (
                    <div className="space-y-1 px-0.5 pt-0.5">
                      <div className="flex items-center justify-between text-xs">
                        <span className="text-default-500">
                          密码强度（{form.newPassword.length}/64 位）
                        </span>
                        <Chip size="sm" variant="flat" color={passwordStrength.color}>
                          {passwordStrength.label}
                        </Chip>
                      </div>
                      <div className="h-1.5 w-full rounded-full bg-default-200 overflow-hidden">
                        <div
                          className={`h-full transition-all duration-300 ${
                            passwordStrength.color === "danger"
                              ? "bg-danger"
                              : passwordStrength.color === "warning"
                                ? "bg-warning"
                                : "bg-success"
                          }`}
                          style={{ width: `${passwordStrength.percent}%` }}
                        />
                      </div>
                    </div>
                  )}
                </div>

                <div className="flex flex-col gap-1">
                  <Input
                    label="确认新密码"
                    placeholder="请再次输入新密码"
                    type="password"
                    value={form.confirmPassword}
                    onChange={(e) => handleInputChange("confirmPassword", e.target.value)}
                    onKeyDown={(e) => {
                      handlePasswordKeyEvent(e);
                      handleKeyPress(e);
                    }}
                    onKeyUp={handlePasswordKeyEvent}
                    onBlur={() => setCapsLockOn(false)}
                    variant="bordered"
                    isDisabled={isBusy}
                    isInvalid={!!effectiveConfirmError}
                    errorMessage={effectiveConfirmError}
                  />
                  {isConfirmMatch && (
                    <p className="text-xs text-success-600 dark:text-success-400 px-1" role="status">
                      ✓ 两次输入的新密码一致
                    </p>
                  )}
                </div>

                <Button
                  type="submit"
                  color="primary"
                  size="lg"
                  isLoading={isBusy}
                  isDisabled={isBusy}
                  className="mt-2"
                >
                  {redirecting ? "修改成功，正在跳转登录..." : loading ? "提交修改中..." : "立即修改账号密码"}
                </Button>

                <div
                  role="note"
                  className="rounded-md border border-default-300 bg-default-100 px-3 py-2 text-xs text-default-600 dark:text-default-400 text-center"
                >
                  提示：修改账号密码成功后当前会话将自动退出，请使用新凭据重新登录
                </div>
              </form>
            </CardBody>
          </Card>
        </div>
      </section>
    </DefaultLayout>
  );
}
