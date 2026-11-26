import { Button } from "@heroui/button";
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
import "@/utils/tac.css";
import "@/utils/tac.min.js";
import bgImage from "@/images/bg.jpg";


interface LoginForm {
  username: string;
  password: string;
  confirmPassword: string;
  captchaId: string;
}



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
    captchaId: "",
  });
  const [loading, setLoading] = useState(false);
  const [errors, setErrors] = useState<Partial<LoginForm>>({});
  const [showCaptcha, setShowCaptcha] = useState(false);
  const [capsLockOn, setCapsLockOn] = useState(false);
  const [registerEnabled, setRegisterEnabled] = useState(false);
  const [isRegisterMode, setIsRegisterMode] = useState(false);
  const navigate = useNavigate();
  const tacInstanceRef = useRef<any>(null);
  const captchaContainerRef = useRef<HTMLDivElement>(null);
  const [isWebView, setIsWebView] = useState(false);
  // 清理验证码实例
  useEffect(() => {
    return () => {
      if (tacInstanceRef.current) {
        tacInstanceRef.current.destroyWindow();
        tacInstanceRef.current = null;
      }
    };
  }, []);
  // 检测是否在WebView中运行，并查询是否开放自助注册
  useEffect(() => {
    setIsWebView(isWebViewFunc());
    getConfigByName("register_enabled")
      .then((res) => {
        if (res.code === 0 && res.data?.value === "true") {
          setRegisterEnabled(true);
        }
      })
      .catch(() => {});
  }, []);
  // 验证表单
  const validateForm = (): boolean => {
    const newErrors: Partial<LoginForm> = {};
    const username = form.username.trim();

    if (!username) {
      newErrors.username = '请输入用户名';
    } else if (isRegisterMode && !/^[a-zA-Z0-9_-]{3,32}$/.test(username)) {
      newErrors.username = '用户名需为3-32位字母、数字、下划线或连字符';
    }

    if (!form.password.trim()) {
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
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 处理输入变化
  const handleInputChange = (field: keyof LoginForm, value: string) => {
    setForm(prev => ({ ...prev, [field]: value }));
    // 清除该字段的错误
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
          setLoading(false);
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
      setLoading(false);
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
      setLoading(false);
    }
  };

  const performRegister = async (captchaId?: string) => {
    try {
      const registerData: RegisterData = {
        username: form.username.trim(),
        password: form.password,
        confirmPassword: form.confirmPassword,
        captchaId: captchaId || form.captchaId || "",
      };
      const response = await register(registerData);
      if (response.code !== 0) {
        toast.error(response.msg || "注册失败");
        return;
      }
      toast.success("注册成功，请登录");
      setIsRegisterMode(false);
      setErrors({});
      setForm(prev => ({
        ...prev,
        username: registerData.username,
        password: "",
        confirmPassword: "",
        captchaId: "",
      }));
    } catch (error) {
      console.error('注册错误:', error);
      toast.error("网络错误，请稍后重试");
    } finally {
      setLoading(false);
    }
  };

  const handleLogin = async () => {
    if (loading) return;
    if (!validateForm()) return;

    setLoading(true);

    try {
      // 先检查是否需要验证码
      const checkResponse = await checkCaptcha();
      
      if (checkResponse.code !== 0) {
        toast.error("检查验证码状态失败，请重试" + checkResponse.msg);
        setLoading(false);
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
      setLoading(false);
    }
  };


  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !loading) {
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

  return (
    <DefaultLayout>
      <section className="flex flex-col items-center justify-center gap-4 py-4 sm:py-8 md:py-10 pb-20 min-h-[calc(100dvh-120px)] sm:min-h-[calc(100dvh-200px)]">
        <div className="w-full max-w-md px-4 sm:px-0">
          <Card className="w-full">
            <CardHeader className="pb-0 pt-6 px-6 flex-col items-center">
              <PixelCatLogo size={48} className="mb-2" />
              <h1 className={title({ size: "sm" })}>{isRegisterMode ? "注册账号" : "登陆"}</h1>
              <p className="text-small text-default-500 mt-2">
                {isRegisterMode ? "创建一个新的账号" : "请输入您的账号信息"}
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
                <Input
                  label="用户名"
                  placeholder="请输入用户名"
                  value={form.username}
                  onChange={(e) => handleInputChange('username', e.target.value)}
                  onBlur={handleUsernameBlur}
                  onKeyDown={handleKeyPress}
                  variant="bordered"
                  isDisabled={loading}
                  isInvalid={!!errors.username}
                  errorMessage={errors.username}
                />
                
                <div className="flex flex-col gap-1">
                  <Input
                    label="密码"
                    placeholder="请输入密码"
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
                    isInvalid={!!errors.password}
                    errorMessage={errors.password}
                  />
                  {capsLockOn && (
                    <p className="text-xs text-warning px-1 flex items-center gap-1" role="status">
                      <span>大写锁定（Caps Lock）已开启，请注意密码大小写</span>
                    </p>
                  )}
                </div>

                {isRegisterMode && (
                  <Input
                    label="确认密码"
                    placeholder="请再次输入密码"
                    type="password"
                    value={form.confirmPassword}
                    onChange={(e) => handleInputChange('confirmPassword', e.target.value)}
                    onKeyDown={handleKeyPress}
                    variant="bordered"
                    isDisabled={loading}
                    isInvalid={!!errors.confirmPassword}
                    errorMessage={errors.confirmPassword}
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
                    ? (showCaptcha ? "验证中..." : (isRegisterMode ? "注册中..." : "登录中..."))
                    : (isRegisterMode ? "注册" : "登录")}
                </Button>

                {registerEnabled && (
                  <div className="text-center pt-1">
                    <button
                      type="button"
                      className="text-sm text-default-500 hover:text-foreground underline underline-offset-4 transition-colors"
                      disabled={loading}
                      onClick={() => {
                        setIsRegisterMode(prev => !prev);
                        setErrors({});
                        setForm(prev => ({ ...prev, password: "", confirmPassword: "", captchaId: "" }));
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
