import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import toast from 'react-hot-toast';

import { title } from "@/components/primitives";
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

export default function ChangePasswordPage() {
  const [form, setForm] = useState<PasswordForm>({
    newUsername: '',
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });
  const [loading, setLoading] = useState(false);
  const [errors, setErrors] = useState<Partial<PasswordForm>>({});
  const navigate = useNavigate();
  const passwordStrength = evaluatePasswordStrength(form.newPassword);
  const isSameAsCurrent = Boolean(
    form.currentPassword && form.newPassword && form.currentPassword === form.newPassword
  );
  const isConfirmMatch = Boolean(form.confirmPassword) && form.confirmPassword === form.newPassword;

  const validateForm = (): boolean => {
    const newErrors: Partial<PasswordForm> = {};

    if (!form.newUsername.trim()) {
      newErrors.newUsername = '请输入新用户名';
    } else if (form.newUsername.length < 3) {
      newErrors.newUsername = '用户名长度至少3位';
    } else if (form.newUsername.length > 20) {
      newErrors.newUsername = '用户名长度不能超过20位';
    }

    if (!form.currentPassword.trim()) {
      newErrors.currentPassword = '请输入当前密码';
    }

    if (!form.newPassword.trim()) {
      newErrors.newPassword = '请输入新密码';
    } else if (form.newPassword.length < 6) {
      newErrors.newPassword = '新密码长度不能少于6位';
    } else if (form.newPassword.length > 20) {
      newErrors.newPassword = '新密码长度不能超过20位';
    }

    if (!form.confirmPassword.trim()) {
      newErrors.confirmPassword = '请再次输入新密码';
    } else if (form.confirmPassword !== form.newPassword) {
      newErrors.confirmPassword = '两次输入密码不一致';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleInputChange = (field: keyof PasswordForm, value: string) => {
    setForm(prev => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors(prev => ({ ...prev, [field]: undefined }));
    }
  };

  const handleSubmit = async () => {
    if (!validateForm()) return;

    setLoading(true);
    try {
      const response = await updatePassword(form);
      
      if (response.code === 0) {
        toast.success(response.msg || '账号密码修改成功');
        
        // 使用 toast 确认对话框的替代方案
        setTimeout(() => {
          toast.success('即将跳转到登陆页面，请重新登录');
          setTimeout(() => {
            logout();
          }, 1000);
        }, 1000);
      } else {
        toast.error(response.msg || '账号密码修改失败');
      }
    } catch (error) {
      console.error('修改账号密码错误:', error);
      toast.error('修改账号密码时发生错误');
    } finally {
      setLoading(false);
    }
  };

  const logout = () => {
    safeLogout();
    navigate('/');
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !loading) {
      handleSubmit();
    }
  };

  return (
    <DefaultLayout>
      <section className="flex flex-col items-center justify-center gap-4 py-8 md:py-10 min-h-[calc(100dvh-200px)]">
        <div className="w-full max-w-lg">
          <Card className="w-full">
            <CardHeader className="pb-0 pt-6 px-6 flex-col items-center">
              <div className="w-12 h-12 bg-warning-100 rounded-full flex items-center justify-center mb-3">
                <svg className="w-6 h-6 text-warning-600" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                </svg>
              </div>
              
              <h1 className={title({ size: "sm" })}>安全提醒</h1>
              <p className="text-small text-default-500 mt-2 text-center">检测到您使用的是默认账号密码，为了您的账户安全，请立即修改</p>
            </CardHeader>
            
            <CardBody className="px-6 py-6">
              <div className="flex flex-col gap-4">
                <Input
                  label="新用户名"
                  placeholder="请输入新用户名（至少3位）"
                  value={form.newUsername}
                  onChange={(e) => handleInputChange('newUsername', e.target.value)}
                  onKeyDown={handleKeyPress}
                  variant="bordered"
                  isDisabled={loading}
                  isInvalid={!!errors.newUsername}
                  errorMessage={errors.newUsername}
                />
                
                <Input
                  label="当前密码"
                  placeholder="请输入当前密码"
                  type="password"
                  value={form.currentPassword}
                  onChange={(e) => handleInputChange('currentPassword', e.target.value)}
                  onKeyDown={handleKeyPress}
                  variant="bordered"
                  isDisabled={loading}
                  isInvalid={!!errors.currentPassword}
                  errorMessage={errors.currentPassword}
                />
                
                <Input
                  label="新密码"
                  placeholder="请输入新密码（至少6位）"
                  type="password"
                  value={form.newPassword}
                  onChange={(e) => handleInputChange('newPassword', e.target.value)}
                  onKeyDown={handleKeyPress}
                  variant="bordered"
                  isDisabled={loading}
                  isInvalid={!!errors.newPassword}
                  errorMessage={errors.newPassword}
                />

                {form.newPassword && (
                  <div className="space-y-1.5 -mt-2">
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-default-500">密码强度（建议包含大小写字母、数字与特殊字符）</span>
                      <Chip size="sm" variant="flat" color={passwordStrength.color}>
                        {passwordStrength.label}
                      </Chip>
                    </div>
                    <div className="h-1.5 w-full rounded-full bg-default-200 overflow-hidden">
                      <div
                        className={`h-full transition-all duration-300 ${
                          passwordStrength.color === 'danger'
                            ? 'bg-danger'
                            : passwordStrength.color === 'warning'
                              ? 'bg-warning'
                              : 'bg-success'
                        }`}
                        style={{ width: `${passwordStrength.percent}%` }}
                      />
                    </div>
                    {isSameAsCurrent && (
                      <p className="text-xs text-warning-600 dark:text-warning-400">
                        新密码与当前密码相同，建议更换为不同的密码
                      </p>
                    )}
                  </div>
                )}
                
                <Input
                  label="确认新密码"
                  placeholder="请再次输入新密码"
                  type="password"
                  value={form.confirmPassword}
                  onChange={(e) => handleInputChange('confirmPassword', e.target.value)}
                  onKeyDown={handleKeyPress}
                  variant="bordered"
                  isDisabled={loading}
                  isInvalid={!!errors.confirmPassword}
                  errorMessage={errors.confirmPassword}
                />

                {form.confirmPassword && (
                  <p
                    className={`text-xs -mt-2 ${
                      isConfirmMatch
                        ? 'text-success-600 dark:text-success-400'
                        : 'text-danger-600 dark:text-danger-400'
                    }`}
                  >
                    {isConfirmMatch ? '两次输入的新密码一致' : '两次输入的新密码不一致'}
                  </p>
                )}
                
                <Button
                  color="warning"
                  size="lg"
                  onClick={handleSubmit}
                  isLoading={loading}
                  disabled={loading}
                  className="mt-2"
                >
                  {loading ? "修改中..." : "立即修改账号密码"}
                </Button>
                
                <div className="bg-warning-50 border border-warning-200 text-warning-700 px-3 py-2 rounded-lg text-sm text-center">
                  ⚠️ 注意：修改账号密码后需要重新登录
                </div>
              </div>
            </CardBody>
          </Card>
        </div>
      </section>
    </DefaultLayout>
  );
} 