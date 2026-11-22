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

    /**
     * 轻量 HTTP / HTTPS 代理连通性探测（HTTP CONNECT 隧道 + Basic 鉴权，HTTPS 跳过自签证书校验）。
     */
    public static JSONObject probeHttp(String host, int port, String username, String password, boolean useTls) {
        JSONObject res = new JSONObject();
        long start = System.currentTimeMillis();
        Socket rawSocket = null;
        Socket socket = null;
        try {
            rawSocket = new Socket();
            rawSocket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            rawSocket.setSoTimeout(TIMEOUT_MS);
            if (useTls) {
                javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                        }
                };
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new java.security.SecureRandom());
                javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) sc.getSocketFactory()
                        .createSocket(rawSocket, host, port, true);
                sslSocket.setSoTimeout(TIMEOUT_MS);
                sslSocket.startHandshake();
                socket = sslSocket;
            } else {
                socket = rawSocket;
            }

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            StringBuilder connectReq = new StringBuilder();
            connectReq.append("CONNECT ").append(PROBE_HOST).append(":").append(PROBE_PORT).append(" HTTP/1.1\r\n");
            connectReq.append("Host: ").append(PROBE_HOST).append(":").append(PROBE_PORT).append("\r\n");
            if (username != null && !username.isEmpty()) {
                String cred = java.util.Base64.getEncoder().encodeToString(
                        (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
                connectReq.append("Proxy-Authorization: Basic ").append(cred).append("\r\n");
            }
            connectReq.append("Proxy-Connection: Keep-Alive\r\n\r\n");
            out.write(connectReq.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String connectResp = readHttpHeaders(in);
            if (!connectResp.startsWith("HTTP/1.1 200") && !connectResp.startsWith("HTTP/1.0 200")) {
                String firstLine = connectResp.split("\r\n", 2)[0];
                return fail(res, "代理握手失败: " + (firstLine.isEmpty() ? "空响应" : firstLine));
            }

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
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            try { if (rawSocket != null && rawSocket != socket) rawSocket.close(); } catch (Exception ignored) {}
        }
    }

    private static String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prev3 = 0, prev2 = 0, prev1 = 0;
        int b;
        while ((b = in.read()) != -1) {
            out.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                break;
            }
            if (out.size() > 8192) break;
            prev3 = prev2;
            prev2 = prev1;
            prev1 = b;
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }
}
