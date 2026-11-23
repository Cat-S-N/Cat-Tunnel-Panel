package com.admin.common.aop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.admin.common.lang.R;
import com.admin.common.utils.AuditLogStore;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * LogAspect (@LogAnnotation) 正常记录、脱敏、quiet 级别门控与异常捕获传播单元测试
 */
@ExtendWith(MockitoExtension.class)
class LogAspectTest {

    @Mock
    private AuditLogStore auditLogStore;

    private LogAspect logAspect;
    private Logger aspectLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    static class DummyController {
        @LogAnnotation
        public R normalAction(String username, String password) {
            Map<String, Object> out = new HashMap<>();
            out.put("username", username);
            out.put("secret", "RETURN_SECRET_123");
            return R.ok(out);
        }

        @LogAnnotation(quiet = true)
        public R quietReport(String rawJson) {
            return R.ok("reported");
        }

        @LogAnnotation
        public R failingAction(String username, String token) {
            throw new IllegalStateException("db failed secret=BOOM_SECRET_999");
        }
    }

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "log-aspect-test-secret");
        jwtUtil.init();

        logAspect = new LogAspect();
        ReflectionTestUtils.setField(logAspect, "auditLogStore", auditLogStore);

        aspectLogger = (Logger) LoggerFactory.getLogger(LogAspect.class);
        previousLevel = aspectLogger.getLevel();
        aspectLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(appender);
        aspectLogger.setLevel(previousLevel);
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest(Long uid, String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/test");
        if (uid != null) {
            User u = new User();
            u.setId(uid);
            u.setUser("u" + uid);
            u.setRoleId(0);
            req.addHeader("Authorization", JwtUtil.generateToken(u));
        }
        if (xff != null) {
            req.addHeader("x-forwarded-for", xff);
        }
        req.setRemoteAddr("198.51.100.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private DummyController proxiedTarget() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new DummyController());
        factory.setProxyTargetClass(true);
        factory.addAspect(logAspect);
        return factory.getProxy();
    }

    @Test
    void logAnnotation_hasExpectedDefaults() throws Exception {
        Target target = LogAnnotation.class.getAnnotation(Target.class);
        Retention retention = LogAnnotation.class.getAnnotation(Retention.class);
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
        assertEquals(RetentionPolicy.RUNTIME, retention.value());

        LogAnnotation normal = DummyController.class.getDeclaredMethod("normalAction", String.class, String.class)
                .getAnnotation(LogAnnotation.class);
        LogAnnotation quiet = DummyController.class.getDeclaredMethod("quietReport", String.class)
                .getAnnotation(LogAnnotation.class);
        assertFalse(normal.quiet());
        assertTrue(quiet.quiet());
    }

    @Test
    void afterReturning_loggedWithRedactionAndAuditStoreCallback() {
        bindRequest(42L, "203.0.113.9, 10.0.0.1");
        DummyController proxy = proxiedTarget();

        R res = proxy.normalAction("alice", "PLAIN_PWD_888");

        assertEquals(0, res.getCode());
        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        String msg = event.getFormattedMessage();
        assertTrue(msg.contains("【请求日志】"));
        assertTrue(msg.contains("用户ID:[42]"));
        assertTrue(msg.contains("IP地址:[203.0.113.9]"));
        assertTrue(msg.contains("DummyController.normalAction"));
        assertFalse(msg.contains("PLAIN_PWD_888"), msg);
        assertFalse(msg.contains("RETURN_SECRET_123"), msg);
        verify(auditLogStore).recordIfAuditable(anyString(), eq("203.0.113.9"),
                contains("DummyController.normalAction"), anyString(), eq(res));
    }

    @Test
    void afterReturning_anonymousNoArgsNullReturnAndAuditFailureSafe() throws Throwable {
        bindRequest(null, null);
        doThrow(new RuntimeException("audit down")).when(auditLogStore)
                .recordIfAuditable(any(), any(), any(), any(), any());

        JoinPoint jp = mock(JoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        Method m = DummyController.class.getDeclaredMethod("normalAction", String.class, String.class);
        when(jp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(m);
        when(sig.getName()).thenReturn("normalAction");
        when(jp.getTarget()).thenReturn(new DummyController());
        when(jp.getArgs()).thenReturn(new Object[0]);

        logAspect.log(jp, null);

        assertEquals(1, appender.list.size());
        String msg = appender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("用户ID:[未登录]"));
        assertTrue(msg.contains("IP地址:[198.51.100.7]"));
        assertTrue(msg.contains("请求参数:[无参数]"));
        assertTrue(msg.contains("返回参数:[无返回值]"));
    }

    @Test
    void quietAnnotation_skippedAtInfoAndLoggedAtDebug() {
        bindRequest(1L, null);
        DummyController proxy = proxiedTarget();

        aspectLogger.setLevel(Level.INFO);
        proxy.quietReport("{\"secret\":\"Q1\"}");
        assertTrue(appender.list.isEmpty());
        verifyNoInteractions(auditLogStore);

        aspectLogger.setLevel(Level.DEBUG);
        proxy.quietReport("{\"secret\":\"Q1\"}");
        assertEquals(1, appender.list.size());
        assertEquals(Level.DEBUG, appender.list.get(0).getLevel());
        assertFalse(appender.list.get(0).getFormattedMessage().contains("Q1"));
        verify(auditLogStore).recordIfAuditable(any(), any(), contains("quietReport"), any(), any());
    }

    @Test
    void afterThrowing_logsRedactedExceptionAndPropagatesOriginalException() {
        bindRequest(99L, "203.0.113.50");
        DummyController proxy = proxiedTarget();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> proxy.failingAction("bob", "RAW_JWT_SECRET_777"));
        assertEquals("db failed secret=BOOM_SECRET_999", thrown.getMessage());

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        String msg = event.getFormattedMessage();
        assertTrue(msg.contains("【异常日志】"));
        assertTrue(msg.contains("用户ID:[99]"));
        assertFalse(msg.contains("RAW_JWT_SECRET_777"), msg);
        assertFalse(msg.contains("BOOM_SECRET_999"), msg);
        assertNotNull(event.getThrowableProxy());
        assertFalse(String.valueOf(event.getThrowableProxy().getMessage()).contains("BOOM_SECRET_999"));
        verifyNoInteractions(auditLogStore);
    }

    @Test
    void recordLog_nullExceptionOrMissingRequestHandledSafely() throws Exception {
        bindRequest(null, null);
        JoinPoint jp = mock(JoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        Method m = DummyController.class.getDeclaredMethod("normalAction", String.class, String.class);
        when(jp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(m);
        when(sig.getName()).thenReturn("normalAction");
        when(jp.getTarget()).thenReturn(new DummyController());
        when(jp.getArgs()).thenReturn(new Object[]{Collections.singletonMap("token", "T1")});

        logAspect.recordLog(jp, null);
        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("异常信息:[未知异常]"));

        appender.list.clear();
        RequestContextHolder.resetRequestAttributes();
        assertDoesNotThrow(() -> logAspect.recordLog(jp, new RuntimeException("boom")));
        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("记录异常日志时出错"));
    }
}
