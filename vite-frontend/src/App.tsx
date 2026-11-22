import { Route, Routes, useNavigate, Navigate } from "react-router-dom";
import { Suspense, lazy, useEffect, useState } from "react";

import IndexPage from "@/pages/index";
const ChangePasswordPage = lazy(() => import("@/pages/change-password"));
const DashboardPage = lazy(() => import("@/pages/dashboard"));
const ForwardPage = lazy(() => import("@/pages/forward"));
const TunnelPage = lazy(() => import("@/pages/tunnel"));
const NodePage = lazy(() => import("@/pages/node"));
const UserPage = lazy(() => import("@/pages/user"));
const ProfilePage = lazy(() => import("@/pages/profile"));
const LimitPage = lazy(() => import("@/pages/limit"));
const InboundPage = lazy(() => import("@/pages/inbound"));
const RelayPage = lazy(() => import("@/pages/relay"));
const LandingPage = lazy(() => import("@/pages/landing"));
const GuidePage = lazy(() => import("@/pages/guide"));
const MySubPage = lazy(() => import("@/pages/my-sub"));
const ConfigPage = lazy(() => import("@/pages/config"));
const AuditPage = lazy(() => import("@/pages/audit"));
const MonitorPage = lazy(() => import("@/pages/monitor"));
const StatusPage = lazy(() => import("@/pages/status"));
const SettingsPage = lazy(() => import("@/pages/settings").then((m) => ({ default: m.SettingsPage })));

import AdminLayout from "@/layouts/admin";
import H5Layout from "@/layouts/h5";
import H5SimpleLayout from "@/layouts/h5-simple";

import { isLoggedIn, isAdmin } from "@/utils/auth";
import { siteConfig } from "@/config/site";
import { ErrorBoundary } from "@/components/error-boundary";

// 检测是否为H5模式
const useH5Mode = () => {
  // 立即检测H5模式，避免初始渲染时的闪屏
  const getInitialH5Mode = () => {
    // 检测移动设备或小屏幕
    const isMobile = window.innerWidth <= 768;
    // 检测是否为移动端浏览器
    const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    // 检测URL参数是否包含h5模式
    const urlParams = new URLSearchParams(window.location.search);
    const isH5Param = urlParams.get('h5') === 'true';
    
    return isMobile || isMobileBrowser || isH5Param;
  };

  const [isH5, setIsH5] = useState(getInitialH5Mode);

  useEffect(() => {
    const checkH5Mode = () => {
      // 检测移动设备或小屏幕
      const isMobile = window.innerWidth <= 768;
      // 检测是否为移动端浏览器
      const isMobileBrowser = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
      // 检测URL参数是否包含h5模式
      const urlParams = new URLSearchParams(window.location.search);
      const isH5Param = urlParams.get('h5') === 'true';
      
      setIsH5(isMobile || isMobileBrowser || isH5Param);
    };

    window.addEventListener('resize', checkH5Mode);
    
    return () => window.removeEventListener('resize', checkH5Mode);
  }, []);

  return isH5;
};

// 简化的路由保护组件 - 使用 React Router 导航避免循环
const ProtectedRoute = ({ children, useSimpleLayout = false, skipLayout = false }: { children: React.ReactNode, useSimpleLayout?: boolean, skipLayout?: boolean }) => {
  const authenticated = isLoggedIn();
  const isH5 = useH5Mode();
  const navigate = useNavigate();
  
  useEffect(() => {
    if (!authenticated) {
      // 使用 React Router 导航，避免无限跳转
      navigate('/', { replace: true });
    }
  }, [authenticated, navigate]);

  if (!authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-white dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
      </div>
    );
  }

  // 如果跳过布局，直接返回子组件
  if (skipLayout) {
    return <>{children}</>;
  }

  // 根据模式和页面类型选择布局
  let Layout;
  if (isH5 && useSimpleLayout) {
    Layout = H5SimpleLayout;
  } else if (isH5) {
    Layout = H5Layout;
  } else {
    Layout = AdminLayout;
  }
  
  return <Layout>{children}</Layout>;
};


// 登录页面路由组件 - 已登录则重定向到dashboard
const LoginRoute = () => {
  const authenticated = isLoggedIn();
  const navigate = useNavigate();
  
  useEffect(() => {
    if (authenticated) {
      // 使用 React Router 导航，避免无限跳转
      // 管理员进仪表板;车友进「我的订阅」(他只关心自己的订阅链接)
      navigate(isAdmin() ? '/dashboard' : '/my-sub', { replace: true });
    }
  }, [authenticated, navigate]);
  
  if (authenticated) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-100 dark:bg-black">
        <div className="text-lg text-gray-700 dark:text-gray-200"></div>
      </div>
    );
  }
  
  return <IndexPage />;
};

function App() {
  // 立即设置页面标题（使用已从缓存读取的配置）
  useEffect(() => {
    document.title = siteConfig.name;
    
    // 异步检查是否有配置更新
    const checkTitleUpdate = async () => {
      try {
        // 引入必要的函数
        const { getCachedConfig } = await import('@/config/site');
        const cachedAppName = await getCachedConfig('app_name');
        if (cachedAppName && cachedAppName !== document.title) {
          document.title = cachedAppName;
        }
      } catch (error) {
        console.warn('检查标题更新失败:', error);
      }
    };

    // 延迟检查，避免阻塞初始渲染
    const timer = setTimeout(checkTitleUpdate, 100);

    return () => clearTimeout(timer);
  }, []);

  return (
    <ErrorBoundary>
    <Suspense fallback={<div className="flex items-center justify-center min-h-[60vh] text-sm text-default-400">加载中...</div>}>
    <Routes>
      <Route path="/" element={<LoginRoute />} />
      <Route 
        path="/change-password" 
        element={
          <ProtectedRoute skipLayout={true}>
            <ChangePasswordPage />
          </ProtectedRoute>
        } 
      />
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            {/* 仪表板是账号级口径,车友一切按线路算 → 直接送他去「我的订阅」 */}
            {isAdmin() ? <DashboardPage /> : <Navigate to="/my-sub" replace />}
          </ProtectedRoute>
        }
      />
      <Route
        path="/forward"
        element={
          <ProtectedRoute>
            <ForwardPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/inbound"
        element={
          <ProtectedRoute>
            <InboundPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/relay"
        element={
          <ProtectedRoute>
            <RelayPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/landing"
        element={
          <ProtectedRoute>
            <LandingPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/guide"
        element={
          <ProtectedRoute>
            <GuidePage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/my-sub"
        element={
          <ProtectedRoute>
            <MySubPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/tunnel"
        element={
          <ProtectedRoute>
            <TunnelPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/node" 
        element={
          <ProtectedRoute>
            <NodePage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/user" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <UserPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/profile" 
        element={
          <ProtectedRoute>
            <ProfilePage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/limit" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <LimitPage />
          </ProtectedRoute>
        } 
      />
      <Route 
        path="/config" 
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <ConfigPage />
          </ProtectedRoute>
        } 
      />
      <Route
        path="/audit"
        element={
          <ProtectedRoute useSimpleLayout={true}>
            <AuditPage />
          </ProtectedRoute>
        }
      />
      <Route 
        path="/settings" 
        element={<SettingsPage />}
      />
      <Route
        path="/monitor"
        element={
          <ProtectedRoute>
            {isAdmin() ? <MonitorPage /> : <Navigate to="/my-sub" replace />}
          </ProtectedRoute>
        }
      />
      {/* 公开状态页:不登录也能看,面板里没打开时只显示「未开启」 */}
      <Route path="/status" element={<StatusPage />} />
    </Routes>
    </Suspense>
    </ErrorBoundary>
  );
}

export default App;
