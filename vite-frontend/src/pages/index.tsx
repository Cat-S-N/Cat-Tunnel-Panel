import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import toast from 'react-hot-toast';
import axios from 'axios';
import { isWebViewFunc } from '@/utils/panel';
import { siteConfig } from '@/config/site';
import { title } from "@/components/primitives";
import { PixelCatLogo } from "@/components/pixel-cat-logo";
import DefaultLayout from "@/layouts/default";
import { login, LoginData, register, RegisterData, checkCaptcha, getConfigByName } from "@/api";
import { evaluatePasswordStrength } from "@/utils/password";
import "@/utils/tac.css";
import "@/utils/tac.min.js";
import bgImage from "@/images/bg.jpg";


interface LoginForm {
  username: string;
  password: string;
  confirmPassword: string;
  inviteCode: string;
  captchaId: string;
}

const USERNAME_PATTERN = /^[a-zA-Z0-9_-]{3,32}$/;

const getRegisterUsernameLiveError = (raw: string): string | undefined => {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (!trimmed) return "用户名不能全为空格";
  if (!/^[a-zA-Z0-9_-]+$/.test(trimmed)) {
    return "用户名仅支持英文字母、数字、下划线(_)或连字符(-)";
  }
  if (trimmed.length < 3) {
    return `用户名至少需要 3 位（当前 ${trimmed.length} 位）`;
  }
  if (trimmed.length > 32) {
    return "用户名长度不能超过 32 位";
  }
  return undefined;
};

const getRegisterPasswordLiveError = (pwd: string): string | undefined => {
  if (!pwd) return undefined;
  if (!pwd.trim()) return "密码不能全为空白字符";
  if (pwd.length < 6) return `密码长度至少 6 位（当前 ${pwd.length} 位）`;
  if (pwd.length > 64) return "密码长度不能超过 64 位";
  return undefined;
};

const getRegisterInviteLiveError = (raw: string): string | undefined => {
  if (!raw) return undefined;
  if (!raw.trim()) return "邀请码不能全为空格";
  if (raw.trim().length > 128) return "邀请码长度不能超过 128 位";
  return undefined;
};



interface CaptchaConfig {
  requestCaptchaDataUrl: string;
  validCaptchaUrl: string;
  bindEl: string;
  validSuccess: (res: any, captcha: any, tac: any) => void;
  validFail?: (res: any, captcha: any, tac: any) => void;
  btnCloseFun?: (event: any, tac: any) => void;
  btnRefreshFun?: (event: any, tac: any) => void;
}

interface CaptchaStyle {
  btnUrl?: string;
  bgUrl?: string;
  logoUrl?: string | null;
  moveTrackMaskBgColor?: string;
  moveTrackMaskBorderColor?: string;
}

export default function IndexPage() {
  const [form, setForm] = useState<LoginForm>({
    username: "",
    password: "",
    confirmPassword: "",
    inviteCode: "",
    captchaId: "",
  });
  const [loading, setLoading] = useState(false);
  const [errors, setErrors] = useState<Partial<LoginForm>>({});
  const [showCaptcha, setShowCaptcha] = useState(false);
  const [capsLockOn, setCapsLockOn] = useState(false);
  const [registerEnabled, setRegisterEnabled] = useState(false);
  const [inviteRequired, setInviteRequired] = useState(false);
  const [isRegisterMode, setIsRegisterMode] = useState(false);
  const [registeredUsername, setRegisteredUsername] = useState<string | null>(null);
  const navigate = useNavigate();
  const tacInstanceRef = useRef<any>(null);
  const captchaContainerRef = useRef<HTMLDivElement>(null);
  const submittingRef = useRef(false);
  const passwordInputRef = useRef<HTMLInputElement>(null);
  const [isWebView, setIsWebView] = useState(false);

  const releaseSubmitLock = () => {
    submittingRef.current = false;
    setLoading(false);
  };

  // 查询公开注册开关与邀请码要求（零泄露布尔信号）
  const fetchRegisterPublicFlags = () => {
    getConfigByName("register_enabled")
      .then((res) => {
        setRegisterEnabled(res.code === 0 && res.data?.value === "true");
      })
      .catch(() => {});
    getConfigByName("register_invite_required")
      .then((res) => {
        setInviteRequired(res.code === 0 && res.data?.value === "true");
      })
      .catch(() => {});
  };

  // 清理验证码实例
  useEffect(() => {
    return () => {
      if (tacInstanceRef.current) {
        tacInstanceRef.current.destroyWindow();
        tacInstanceRef.current = null;
      }
    };
  }, []);
  // 检测是否在WebView中运行，并查询是否开放自助注册及邀请码要求
  useEffect(() => {
    setIsWebView(isWebViewFunc());
    fetchRegisterPublicFlags();
  }, []);

  // 验证表单（与后端校验规则严格一致）
  const validateForm = (): boolean => {
    const newErrors: Partial<LoginForm> = {};
    const username = form.username.trim();

    if (!username) {
      newErrors.username = '请输入用户名';
    } else if (isRegisterMode && !USERNAME_PATTERN.test(username)) {
      newErrors.username = getRegisterUsernameLiveError(form.username) || '用户名需为3-32位字母、数字、下划线或连字符';
    }

    if (!form.password || !form.password.trim()) {
      newErrors.password = '请输入密码';
    } else if (form.password.length < 6) {
      newErrors.password = '密码长度至少6位';
    } else if (isRegisterMode && form.password.length > 64) {
      newErrors.password = '密码长度不能超过64位';
    }

    if (isRegisterMode) {
      if (!form.confirmPassword) {
        newErrors.confirmPassword = '请再次输入密码';
      } else if (form.confirmPassword !== form.password) {
        newErrors.confirmPassword = '两次输入的密码不一致';
      }

      if (inviteRequired) {
        const invite = form.inviteCode.trim();
        if (!invite) {
          newErrors.inviteCode = '请输入邀请码';
        } else if (invite.length > 128) {
          newErrors.inviteCode = '邀请码长度不能超过128位';
        }
      }
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 处理输入变化
  const handleInputChange = (field: keyof LoginForm, value: string) => {
    setForm(prev => ({ ...prev, [field]: value }));
    if (field === 'username' && registeredUsername && value.trim() !== registeredUsername) {
      setRegisteredUsername(null);
    }
    // 清除该字段的提交错误
    if (errors[field]) {
      setErrors(prev => ({ ...prev, [field]: undefined }));
    }
  };

  // 初始化验证码
  const initCaptcha = async () => {
    if (!window.TAC || !captchaContainerRef.current) {
      return;
    }

    try {
      // 清理之前的验证码实例
      if (tacInstanceRef.current) {
        tacInstanceRef.current.destroyWindow();
        tacInstanceRef.current = null;
      }

      // 使用axios的baseURL，确保在WebView中使用正确的面板地址
      const baseURL = axios.defaults.baseURL || (import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/');
      
      const config: CaptchaConfig = {
        requestCaptchaDataUrl: `${baseURL}captcha/generate`,
        validCaptchaUrl: `${baseURL}captcha/verify`, 
        bindEl: "#captcha-container",
        validSuccess: (res: any, _: any, tac: any) => {
          

          form.captchaId = res.data.validToken

          setShowCaptcha(false);
          tac.destroyWindow();
          if (isRegisterMode) {
            performRegister(res.data.validToken);
          } else {
            performLogin();
          }
        },
        validFail: (_: any, _captcha: any, tac: any) => {
          tac.reloadCaptcha();
        },
        btnCloseFun: (_event: any, tac: any) => {
          setShowCaptcha(false);
          tac.destroyWindow();
          releaseSubmitLock();
        },
        btnRefreshFun: (_event: any, tac: any) => {
          tac.reloadCaptcha();
        }
      };

      // 仅以页面实际暗黑状态为准，避免系统暗黑而面板手动浅色时误判
      const isDarkMode = document.documentElement.classList.contains('dark');
      const isPixelSkin = document.documentElement.getAttribute('data-skin') === 'pixel';
      
      // 根据主题调整颜色
      const trackColor = isPixelSkin
        ? (isDarkMode ? "#525252" : "#737373")
        : (isDarkMode ? "#4a5568" : "#7db0be");
      
      const style: CaptchaStyle = {
        bgUrl: bgImage,
        logoUrl: null,
        moveTrackMaskBgColor: trackColor,
        moveTrackMaskBorderColor: trackColor
      };

      tacInstanceRef.current = new window.TAC(config, style);
      tacInstanceRef.current.init();

    } catch (error) {
      console.error('初始化验证码失败:', error);
      toast.error('验证码初始化失败，请刷新页面重试');
      setShowCaptcha(false);
      releaseSubmitLock();
    }
  };

  // 执行登录请求
  const performLogin = async () => {


    try {
      const loginData: LoginData = {
        username: form.username.trim(),
        password: form.password,
        captchaId: form.captchaId,
      };

      const response = await login(loginData);
      
      if (response.code !== 0) {
        toast.error(response.msg || "登录失败");
        return;
      }

      setRegisteredUsername(null);

      // 检查是否需要强制修改密码
      if (response.data.requirePasswordChange) {
        localStorage.setItem('token', response.data.token);
        localStorage.setItem("role_id", response.data.role_id.toString());
        localStorage.setItem("name", response.data.name);
        localStorage.setItem("admin", (response.data.role_id === 0).toString());
        toast.success('检测到默认密码，即将跳转到修改密码页面');
        navigate("/change-password");
        return;
      }

      // 保存登录信息
      localStorage.setItem('token', response.data.token);
      localStorage.setItem("role_id", response.data.role_id.toString());
      localStorage.setItem("name", response.data.name);
      localStorage.setItem("admin", (response.data.role_id === 0).toString());

      // 登录成功:管理员进仪表板;车友进「我的订阅」
      toast.success('登录成功');
      navigate(response.data.role_id === 0 ? "/dashboard" : "/my-sub");

    } catch (error) {
      console.error('登录错误:', error);
      toast.error("网络错误，请稍后重试");
    } finally {
      releaseSubmitLock();
    }
  };

  const performRegister = async (captchaId?: string) => {
    try {
      const trimmedUsername = form.username.trim();
      const trimmedInvite = form.inviteCode.trim();
      const registerData: RegisterData = {
        username: trimmedUsername,
        password: form.password,
        confirmPassword: form.confirmPassword,
        inviteCode: inviteRequired ? trimmedInvite : (trimmedInvite || undefined),
        captchaId: captchaId || form.captchaId || "",
      };
      const response = await register(registerData);
      if (response.code !== 0) {
        toast.error(response.msg || "注册失败");
        return;
      }
      toast.success(`账号「${trimmedUsername}」注册成功，请输入密码登录`);
      setRegisteredUsername(trimmedUsername);
      setIsRegisterMode(false);
      setErrors({});
      setForm({
        username: trimmedUsername,
        password: "",
        confirmPassword: "",
        inviteCode: "",
        captchaId: "",
      });
      setTimeout(() => {
        passwordInputRef.current?.focus();
      }, 60);
    } catch (error) {
      console.error('注册错误:', error);
      toast.error("网络错误，请稍后重试");
    } finally {
      releaseSubmitLock();
    }
  };

  const handleLogin = async () => {
    if (loading || submittingRef.current) return;
    if (!validateForm()) return;

    submittingRef.current = true;
    setLoading(true);

    try {
      // 先检查是否需要验证码
      const checkResponse = await checkCaptcha();
      
      if (checkResponse.code !== 0) {
        toast.error("检查验证码状态失败，请重试" + checkResponse.msg);
        releaseSubmitLock();
        return;
      }

      // 根据返回值决定是否显示验证码
      if (checkResponse.data === 0) {
        // 不需要验证码，直接登录或注册
        if (isRegisterMode) {
          await performRegister();
        } else {
          await performLogin();
        }
      } else {
        // 需要验证码，显示验证码弹层
        setShowCaptcha(true);
        // 延时初始化验证码，确保DOM已渲染
        setTimeout(() => {
          initCaptcha();
        }, 100);
      }
    } catch (error) {
      console.error('检查验证码状态错误:', error);
      toast.error("网络错误，请稍后重试" + error);
      releaseSubmitLock();
    }
  };


  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !loading && !submittingRef.current) {
      e.preventDefault();
      handleLogin();
    }
  };

  // 检测密码框大写锁定状态
  const handlePasswordKeyEvent = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (typeof e.getModifierState === 'function') {
      setCapsLockOn(e.getModifierState('CapsLock'));
    }
  };

  // 用户名失焦时自动去除首尾空白
  const handleUsernameBlur = () => {
    const trimmed = form.username.trim();
    if (trimmed !== form.username) {
      setForm(prev => ({ ...prev, username: trimmed }));
    }
  };

  // 邀请码失焦时自动去除首尾空白
  const handleInviteBlur = () => {
    const trimmed = form.inviteCode.trim();
    if (trimmed !== form.inviteCode) {
      setForm(prev => ({ ...prev, inviteCode: trimmed }));
    }
  };

  const liveUsernameError = isRegisterMode ? getRegisterUsernameLiveError(form.username) : undefined;
  const effectiveUsernameError = errors.username || liveUsernameError;
  const isUsernameReady = isRegisterMode && Boolean(form.username.trim()) && !effectiveUsernameError;

  const livePasswordError = isRegisterMode ? getRegisterPasswordLiveError(form.password) : undefined;
  const effectivePasswordError = errors.password || livePasswordError;
  const passwordStrength = evaluatePasswordStrength(form.password);

  const liveConfirmMismatch =
    isRegisterMode && Boolean(form.confirmPassword) && form.confirmPassword !== form.password
      ? "两次输入的密码不一致"
      : undefined;
  const effectiveConfirmError = errors.confirmPassword || liveConfirmMismatch;
  const isConfirmMatch =
    isRegisterMode &&
    Boolean(form.confirmPassword) &&
    form.confirmPassword === form.password &&
    !effectivePasswordError;

  const liveInviteError = isRegisterMode && inviteRequired ? getRegisterInviteLiveError(form.inviteCode) : undefined;
  const effectiveInviteError = errors.inviteCode || liveInviteError;

  return (
    <DefaultLayout>
      <section className="flex flex-col items-center justify-center gap-4 py-4 sm:py-8 md:py-10 pb-20 min-h-[calc(100dvh-120px)] sm:min-h-[calc(100dvh-200px)]">
        <div className="w-full max-w-md px-4 sm:px-0">
          <Card className="w-full">
            <CardHeader className="pb-0 pt-6 px-6 flex-col items-center">
              <PixelCatLogo size={48} className="mb-2" />
              <h1 className={title({ size: "sm" })}>{isRegisterMode ? "注册账号" : "登陆"}</h1>
              <p className="text-small text-default-500 mt-2">
                {isRegisterMode
                  ? (inviteRequired ? "凭邀请码创建一个新的账号" : "创建一个新的账号")
                  : "请输入您的账号信息"}
              </p>
            </CardHeader>
            <CardBody className="px-6 py-6">
              <form
                className="flex flex-col gap-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  handleLogin();
                }}
              >
                {!isRegisterMode && registeredUsername && (
                  <div
                    role="status"
                    className="rounded-md border border-default-300 bg-default-100 px-3 py-2 text-xs text-foreground flex items-center justify-between gap-2"
                  >
                    <span>
                      账号 <strong className="font-mono">{registeredUsername}</strong> 注册成功，已自动填入用户名，请输入密码登录。
                    </span>
                    <button
                      type="button"
                      className="text-default-500 hover:text-foreground shrink-0 px-1"
                      onClick={() => setRegisteredUsername(null)}
                      aria-label="关闭提示"
                    >
                      ✕
                    </button>
                  </div>
                )}

                <Input
                  label="用户名"
                  placeholder={isRegisterMode ? "3-32位字母、数字、_ 或 -" : "请输入用户名"}
                  value={form.username}
                  onChange={(e) => handleInputChange('username', e.target.value)}
                  onBlur={handleUsernameBlur}
                  onKeyDown={handleKeyPress}
                  variant="bordered"
                  isDisabled={loading}
                  isInvalid={!!effectiveUsernameError}
                  errorMessage={effectiveUsernameError}
                  description={
                    isRegisterMode && !effectiveUsernameError
                      ? (isUsernameReady ? "✓ 用户名格式可用" : "支持 3-32 位英文字母、数字、下划线(_)或连字符(-)")
                      : undefined
                  }
                />
                
                <div className="flex flex-col gap-1.5">
                  <Input
                    ref={passwordInputRef}
                    label="密码"
                    placeholder={isRegisterMode ? "请输入密码（6-64位）" : "请输入密码"}
                    type="password"
                    value={form.password}
                    onChange={(e) => handleInputChange('password', e.target.value)}
                    onKeyDown={(e) => {
                      handlePasswordKeyEvent(e);
                      handleKeyPress(e);
                    }}
                    onKeyUp={handlePasswordKeyEvent}
                    onBlur={() => setCapsLockOn(false)}
                    variant="bordered"
                    isDisabled={loading}
                    isInvalid={!!effectivePasswordError}
                    errorMessage={effectivePasswordError}
                    description={
                      isRegisterMode && !form.password && !effectivePasswordError
                        ? "密码长度 6-64 位，建议包含大小写字母、数字与特殊符号"
                        : undefined
                    }
                  />
                  {capsLockOn && (
                    <p className="text-xs text-warning px-1 flex items-center gap-1" role="status">
                      <span>大写锁定（Caps Lock）已开启，请注意密码大小写</span>
                    </p>
                  )}
                  {isRegisterMode && form.password && (
                    <div className="space-y-1 px-0.5 pt-0.5">
                      <div className="flex items-center justify-between text-xs">
                        <span className="text-default-500">
                          密码强度（{form.password.length}/64 位）
                        </span>
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
                    </div>
                  )}
                </div>

                {isRegisterMode && (
                  <div className="flex flex-col gap-1">
                    <Input
                      label="确认密码"
                      placeholder="请再次输入密码"
                      type="password"
                      value={form.confirmPassword}
                      onChange={(e) => handleInputChange('confirmPassword', e.target.value)}
                      onKeyDown={handleKeyPress}
                      variant="bordered"
                      isDisabled={loading}
                      isInvalid={!!effectiveConfirmError}
                      errorMessage={effectiveConfirmError}
                    />
                    {isConfirmMatch && (
                      <p className="text-xs text-success-600 dark:text-success-400 px-1" role="status">
                        ✓ 两次输入的密码一致
                      </p>
                    )}
                  </div>
                )}

                {isRegisterMode && inviteRequired && (
                  <Input
                    label="邀请码"
                    placeholder="请输入管理员发放的邀请码"
                    value={form.inviteCode}
                    onChange={(e) => handleInputChange('inviteCode', e.target.value)}
                    onBlur={handleInviteBlur}
                    onKeyDown={handleKeyPress}
                    variant="bordered"
                    isDisabled={loading}
                    isInvalid={!!effectiveInviteError}
                    errorMessage={effectiveInviteError}
                    description={!effectiveInviteError ? "当前站点已开启受邀注册，请填写有效邀请码" : undefined}
                  />
                )}

                
                <Button
                  type="submit"
                  color="primary"
                  size="lg"
                  isLoading={loading}
                  isDisabled={loading}
                  className="mt-2"
                >
                  {loading
                    ? (showCaptcha ? "验证中..." : (isRegisterMode ? "注册提交中..." : "登录中..."))
                    : (isRegisterMode ? "立即注册" : "登录")}
                </Button>

                {registerEnabled && (
                  <div className="text-center pt-1">
                    <button
                      type="button"
                      className="text-sm text-default-500 hover:text-foreground underline underline-offset-4 transition-colors disabled:opacity-50"
                      disabled={loading}
                      onClick={() => {
                        const enteringRegister = !isRegisterMode;
                        setIsRegisterMode(enteringRegister);
                        setErrors({});
                        setRegisteredUsername(null);
                        setForm(prev => ({
                          ...prev,
                          password: "",
                          confirmPassword: "",
                          inviteCode: "",
                          captchaId: "",
                        }));
                        if (enteringRegister) {
                          fetchRegisterPublicFlags();
                        }
                      }}
                    >
                      {isRegisterMode ? "已有账号？返回登录" : "没有账号？立即注册"}
                    </button>
                  </div>
                )}
              </form>
            </CardBody>
          </Card>

        </div>


      {/* 版权信息 - 固定在底部，不占据布局空间 */}
      
               <div className="fixed inset-x-0 bottom-4 text-center py-4">
               <p className="text-xs text-gray-400 dark:text-gray-500">
                 Powered by <span className="text-gray-500 dark:text-gray-400">Cat Tunnel Panel</span>
               </p>
               <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
                 v{ isWebView ? siteConfig.app_version : siteConfig.version}
               </p>
             </div>
      
   

        {/* 验证码弹层 */}
        {showCaptcha && (
          <div className="fixed inset-0 z-50 flex items-center justify-center">
            {/* 背景遮罩层 - 模糊效果，暗黑模式下更深 */}
            <div className="absolute inset-0 bg-black/60 dark:bg-black/80 backdrop-blur-sm captcha-backdrop-enter" />
           {/* 验证码容器 */}
           <div className="mb-4">
                <div 
                  id="captcha-container" 
                  ref={captchaContainerRef}
                  className="w-full flex justify-center"
                  style={{
                    filter: document.documentElement.classList.contains('dark')
                      ? 'brightness(0.8) contrast(0.9)' : 'none'
                  }}
                />
              </div>
          </div>
        )}
      </section>
    </DefaultLayout>
  );
}
