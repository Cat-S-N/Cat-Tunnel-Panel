package com.admin.common.utils;

import com.alibaba.fastjson.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 轻量 SOCKS5 连通性探测工具（RFC 1928 + RFC 1929 用户名/密码认证）。
 * 不依赖第三方库，通过目标 SOCKS5 代理请求 api.ipify.org 返回延迟与出口 IP。
 */
public final class SocksProbeUtil {

    private static final int TIMEOUT_MS = 5000;
    private static final String PROBE_HOST = "api.ipify.org";
    private static final int PROBE_PORT = 80;

    private SocksProbeUtil() {
    }

    public static JSONObject probe(String host, int port, String username, String password) {
        JSONObject res = new JSONObject();
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 1. Greeting: VER=5, NMETHODS=1, METHOD=0x02 (Username/Password)
            out.write(new byte[]{0x05, 0x01, 0x02});
            out.flush();
            byte[] methodResp = readExact(in, 2);
            if (methodResp[0] != 0x05) {
                return fail(res, "非 SOCKS5 响应");
            }
            if ((methodResp[1] & 0xFF) == 0xFF) {
                return fail(res, "服务器拒绝用户名/密码认证方式");
            }

            // 2. Sub-negotiation (RFC 1929)
            if (methodResp[1] == 0x02) {
                byte[] u = (username == null ? "" : username).getBytes(StandardCharsets.UTF_8);
                byte[] p = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
                if (u.length > 255 || p.length > 255) {
                    return fail(res, "账号或密码过长");
                }
                ByteArrayOutputStream auth = new ByteArrayOutputStream();
                auth.write(0x01);
                auth.write(u.length);
                auth.write(u);
                auth.write(p.length);
                auth.write(p);
                out.write(auth.toByteArray());
                out.flush();
                byte[] authResp = readExact(in, 2);
                if (authResp[1] != 0x00) {
                    return fail(res, "SOCKS5 账号密码认证失败");
                }
            }

            // 3. CONNECT api.ipify.org:80 (ATYP=0x03 domain)
            byte[] target = PROBE_HOST.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(0x05);
            req.write(0x01); // CONNECT
            req.write(0x00); // RSV
            req.write(0x03); // DOMAINNAME
            req.write(target.length);
            req.write(target);
            req.write((PROBE_PORT >> 8) & 0xFF);
            req.write(PROBE_PORT & 0xFF);
            out.write(req.toByteArray());
            out.flush();

            byte[] head = readExact(in, 4);
            if (head[0] != 0x05 || head[1] != 0x00) {
                return fail(res, "SOCKS5 转发连接失败 (REP=" + (head[1] & 0xFF) + ")");
            }
            int atyp = head[3] & 0xFF;
            if (atyp == 0x01) {
                readExact(in, 4 + 2);
            } else if (atyp == 0x04) {
                readExact(in, 16 + 2);
            } else if (atyp == 0x03) {
                int len = readExact(in, 1)[0] & 0xFF;
                readExact(in, len + 2);
            }

            // 4. Plain HTTP GET / to obtain exit IP
            String httpReq = "GET / HTTP/1.1\r\nHost: " + PROBE_HOST + "\r\nUser-Agent: curl/8.0\r\nConnection: close\r\n\r\n";
            out.write(httpReq.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                bodyBuf.write(buf, 0, n);
                if (bodyBuf.size() > 8192) break;
            }
            long latencyMs = System.currentTimeMillis() - start;
            String raw = bodyBuf.toString(StandardCharsets.UTF_8);
            String exitIp = "";
            int idx = raw.indexOf("\r\n\r\n");
            if (idx >= 0) {
                exitIp = raw.substring(idx + 4).trim();
                int nl = exitIp.indexOf('\n');
                if (nl >= 0) exitIp = exitIp.substring(0, nl).trim();
            }

            res.put("ok", true);
            res.put("latencyMs", latencyMs);
            res.put("exitIp", exitIp);
            res.put("msg", exitIp.isEmpty() ? "连接成功" : ("连通 · 出口 " + exitIp));
            return res;
        } catch (Exception e) {
            return fail(res, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static byte[] readExact(InputStream in, int len) throws Exception {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) throw new java.io.EOFException("连接提前关闭");
            off += r;
        }
        return b;
    }

    private static JSONObject fail(JSONObject res, String msg) {
        res.put("ok", false);
        res.put("msg", msg);
        return res;
    }
}
