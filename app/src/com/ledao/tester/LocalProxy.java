package com.ledao.tester;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * LocalProxy —— App 内置的本地 HTTP/HTTPS 代理（带中间人解密能力）
 *
 * 只对 *.lptiyu.com 做 TLS 中间人解密（因为该 App 的 networkSecurityConfig
 * 对 api2.lptiyu.com 放开了【用户证书】信任），其它域名一律纯隧道转发，不影响手机正常上网。
 *
 * 抓到请求后交给 IdentitySniffer 解析出 uid / token / refresh_token。
 */
public class LocalProxy {

    public interface Sink {
        /** 每抓到一次明文 HTTP 请求就回调；返回 true 表示已经拿到需要的身份，可以停了 */
        boolean onHttp(String host, String path, String body);
        void onLog(String s);
        /**
         * ★ 抓到 *.lptiyu.com 的**响应体**时回调（已自动解 gzip）。
         * 为什么需要它：Login/RefreshToken 的**新令牌在响应里**，
         * 请求体里带的是旧令牌 —— 只抓请求会拿到一个马上就作废的令牌。
         */
        default void onResp(String host, String path, String body) { }
    }

    private static final int BUF = 32768;

    /** 本地代理监听端口（用户需在系统 WLAN 代理里填 127.0.0.1:该端口） */
    public static final int PORT = 8899;

    private final Context ctx;
    private final int port;
    /** ★ 可换绑：Activity 重建后要把回调指到新实例上（见 acquire） */
    private volatile Sink sink;
    private ServerSocket server;
    private volatile boolean running = false;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger conns = new AtomicInteger();
    private SSLContext mitmCtx;
    private final AtomicInteger hits = new AtomicInteger();

    public LocalProxy(Context ctx, int port, Sink sink) {
        this.ctx = ctx;
        this.port = port;
        this.sink = sink;
    }

    // ==================================================================
    //  ★★ 进程级单例 —— 血泪教训，千万别删 ★★
    //
    //  现象：第一次抓包一切正常，之后每次点「自动提取」都是
    //        BindException: EADDRINUSE，永远好不了。
    //
    //  原因：SniffGuard 是前台服务，它让**进程活着**（这是我们要的），
    //        但 Activity 被系统回收/重建后，MainActivity.proxy 字段变成 null，
    //        而旧 LocalProxy 的 ServerSocket 还死死占着 8899。
    //        于是新建的实例必然 bind 失败；更糟的是旧实例的回调还指向
    //        已经销毁的 Activity —— 就算抓到了数据也全丢进虚空。
    //
    //  修法：代理本身做成进程级单例。再次进入抓包模式时
    //        · 若已在监听 → 直接复用，只把 sink 换绑到新 Activity
    //        · 若没在监听 → 关掉旧的再起新的
    // ==================================================================
    private static volatile LocalProxy INSTANCE = null;

    /** 拿到（必要时启动）唯一的代理实例；已存在则只换绑回调 */
    public static synchronized LocalProxy acquire(Context ctx, int port, Sink s) throws Exception {
        LocalProxy p = INSTANCE;
        if (p != null && p.running && p.port == port) {
            p.sink = s;
            p.lg("[proxy] 复用已在监听的 127.0.0.1:" + port + "（端口占用自愈）");
            return p;
        }
        if (p != null) {
            try { p.stop(); } catch (Throwable ignore) { }
            INSTANCE = null;
        }
        LocalProxy np = new LocalProxy(ctx.getApplicationContext(), port, s);
        np.start();
        INSTANCE = np;
        return np;
    }

    /** 当前实例（可能为 null，也可能没在跑） */
    public static LocalProxy current() { return INSTANCE; }

    /** 现在有没有在监听 */
    public static boolean listening() {
        LocalProxy p = INSTANCE;
        return p != null && p.running;
    }

    /** 彻底关掉（停止抓包时调用） */
    public static synchronized void shutdown() {
        LocalProxy p = INSTANCE;
        INSTANCE = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignore) { }
        }
    }

    // ---------------------------------------------------------------- 安全回调
    private void lg(String s) {
        Sink k = sink;
        if (k == null) return;
        try { k.onLog(s); } catch (Throwable ignore) { }
    }

    private boolean http(String host, String path, String body) {
        Sink k = sink;
        if (k == null) return false;
        try { return k.onHttp(host, path, body); } catch (Throwable t) { return false; }
    }

    private void resp(String host, String path, String body) {
        Sink k = sink;
        if (k == null) return;
        try { k.onResp(host, path, body); } catch (Throwable ignore) { }
    }

    /** 换绑回调（Activity 重建后重新接管） */
    public void setSink(Sink s) { this.sink = s; }
    public int getPort() { return port; }
    public int getHits() { return hits.get(); }
    public boolean isRunning() { return running; }

    // ---------------------------------------------------------------- 证书
    /**
     * 从 assets 里的 PEM（PKCS#8 私钥 + X.509 证书）载入服务器证书，构造"服务端模式"的 SSLContext。
     *
     * 不用 PKCS12：Android 的 PKCS12 实现不支持 PBKDF2(PBES2, OID 1.2.840.113549.1.5.12) 加密，
     * 读取用 cryptography 生成的 p12 会抛 NoSuchAlgorithmException。
     * 改为直接解析 PEM 并手工构造 X509KeyManager，绕开 KeyStore 的算法兼容问题。
     */
    private SSLContext buildMitmContext() throws Exception {
        java.security.PrivateKey pk = loadPkcs8PrivateKey(readAsset("ledao-key.pem"));
        final X509Certificate cert = loadCert(readAsset("ledao-cert.pem"));

        javax.net.ssl.X509KeyManager km = new javax.net.ssl.X509KeyManager() {
            public String[] getClientAliases(String t, java.security.Principal[] i) { return null; }
            public String chooseClientAlias(String[] t, java.security.Principal[] i, Socket s) { return null; }
            public String[] getServerAliases(String t, java.security.Principal[] i) {
                return new String[]{"ledao"};
            }
            public String chooseServerAlias(String t, java.security.Principal[] i, Socket s) {
                return "ledao";
            }
            public X509Certificate[] getCertificateChain(String a) {
                return new X509Certificate[]{cert};
            }
            public java.security.PrivateKey getPrivateKey(String a) { return pk; }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(new javax.net.ssl.KeyManager[]{km}, null, new java.security.SecureRandom());
        return sc;
    }

    private String readAsset(String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static byte[] pemBody(String pem, String type) {
        String head = "-----BEGIN " + type + "-----";
        String tail = "-----END " + type + "-----";
        int i = pem.indexOf(head);
        int j = pem.indexOf(tail);
        if (i < 0 || j < 0) return null;
        String b64 = pem.substring(i + head.length(), j).replaceAll("\\s", "");
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
    }

    private static java.security.PrivateKey loadPkcs8PrivateKey(String pem) throws Exception {
        byte[] der = pemBody(pem, "PRIVATE KEY");
        return java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
    }

    private static X509Certificate loadCert(String pem) throws Exception {
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(pem.getBytes("UTF-8")));
    }

    /** 连接真实服务器时不做证书校验（我们只做转发，不做安全检查） */
    private SSLContext buildLooseClientContext() throws Exception {
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{tm}, new java.security.SecureRandom());
        return sc;
    }

    // ---------------------------------------------------------------- 启停
    public void start() throws Exception {
        mitmCtx = buildMitmContext();
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 100);
        } catch (java.net.BindException be) {
            // 把「端口被占」翻译成人话，并提示自愈办法
            throw new IllegalStateException("端口 " + port + " 被占用（EADDRINUSE）。"
                    + "通常是上一轮抓包没退干净 —— 已尝试自愈，若仍失败请"
                    + "「强制停止」本应用后重开。", be);
        }
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket c = server.accept();
                    conns.incrementAndGet();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    if (running) lg("[proxy] accept 异常: " + e);
                }
            }
        });
        lg("[proxy] 已启动，监听 127.0.0.1:" + port);
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignore) { }
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------- 连接处理
    private void handle(Socket client) {
        String host = null;
        try {
            client.setSoTimeout(60000);
            // ★ 必须用【无缓冲】流读 CONNECT 请求：
            //   SSLSocketFactory.createSocket(Socket,...) 要求底层流不能再有已缓冲的额外数据，
            //   否则 Android 会抛 UnsupportedOperationException。readLine 是逐字节读的，不会多读。
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            String reqLine = readLine(cin);
            if (reqLine == null) { close(client); return; }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) { close(client); return; }
            String method = parts[0], target = parts[1];

            // 读请求头
            StringBuilder hdr = new StringBuilder();
            String line;
            while ((line = readLine(cin)) != null && line.length() > 0) {
                hdr.append(line).append("\r\n");
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                // 建立隧道
                host = target.split(":")[0].toLowerCase(Locale.US);
                int hport = 443;
                try { hport = Integer.parseInt(target.split(":")[1]); } catch (Exception ignore) { }

                if (!isTarget(host)) {
                    // 非目标域名：纯隧道，不解密
                    tunnel(client, cin, cout, host, hport);
                    return;
                }

                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
                cout.flush();

                // 中间人：用我们的证书跟 App 握手（4 参数版本，服务端模式）
                SSLSocket ssl = (SSLSocket) mitmCtx.getSocketFactory()
                        .createSocket(client, host, hport, false);
                ssl.setUseClientMode(false);
                ssl.setSoTimeout(60000);
                ssl.startHandshake();
                lg("[proxy] 中间人已解密 " + host);

                // 连真实服务器
                Socket up = connectReal(host, hport);
                SSLSocket upSsl = (SSLSocket) buildLooseClientContext().getSocketFactory()
                        .createSocket(up, host, hport, true);
                upSsl.setSoTimeout(60000);
                upSsl.startHandshake();

                // 在解密后的通道上做 HTTP 转发 + 抓取
                serveHttp(ssl.getInputStream(), ssl.getOutputStream(),
                          upSsl.getInputStream(), upSsl.getOutputStream(), host);
                close(ssl);
                close(upSsl);
                return;
            }

            // 普通 HTTP 代理请求（http:// 明文）
            try {
                java.net.URL u = new java.net.URL(target);
                host = u.getHost().toLowerCase(Locale.US);
                int hport = u.getPort() > 0 ? u.getPort() : 80;

                // ★★ 关键修复 1：明文请求必须【真的转发】，绝不能回 502。
                //    这个 App 大量用明文 HTTP（fdl/upc.zztfly.com、cfgc、httpdns、
                //    Mob/友盟统计、高德定位 …，实测 526 个请求）。原代码对非 lptiyu
                //    域名直接回 502 Bad Gateway，注释写着「让系统回退直连」——
                //    但设了代理之后 HTTP 客户端【不会】自动回退直连，
                //    这些请求全部失败，表现就是各种「上传时客户端错误」。
                // ★★ 关键修复 2：必须把请求体一起转发。
                //    原代码只写请求头就等响应，服务器还在等 Content-Length 个字节，
                //    于是挂到超时 —— 所有明文 POST/PUT（即所有上传）必死。

                int clen = -1;
                boolean reqChunked2 = false;
                for (String h : hdr.toString().split("\r\n")) {
                    String lc = h.toLowerCase(Locale.US);
                    if (lc.startsWith("content-length:")) {
                        try {
                            clen = Integer.parseInt(h.substring(h.indexOf(':') + 1).trim());
                        } catch (Exception ignore) { }
                    } else if (lc.startsWith("transfer-encoding:") && lc.contains("chunked")) {
                        reqChunked2 = true;
                    }
                }
                byte[] reqBody = new byte[0];
                if (reqChunked2) reqBody = readChunkedBody(cin);
                else if (clen > 0) reqBody = readN(cin, clen);

                // 顺便记录（只记目标域名，避免把统计/定位的垃圾也写进日志）
                if (isTarget(host) && reqBody.length > 0) {
                    try {
                        String b = new String(reqBody, "UTF-8");
                        if (b.length() > 0) http(host, method + " " + target, b);
                    } catch (Exception ignore) { }
                }

                Socket up = new Socket();
                up.connect(new InetSocketAddress(host, hport), 15000);
                try { up.setSoTimeout(0); } catch (Exception ignore) { }
                String pathQ = u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : "");
                OutputStream uo = up.getOutputStream();
                StringBuilder nh = new StringBuilder();
                nh.append(method).append(' ').append(pathQ).append(" HTTP/1.1\r\n");
                for (String h : hdr.toString().split("\r\n")) {
                    if (h.trim().isEmpty()) continue;
                    String lc = h.toLowerCase(Locale.US);
                    if (lc.startsWith("proxy-connection:")) continue;
                    if (lc.startsWith("connection:")) continue;
                    if (lc.startsWith("transfer-encoding:")) continue;
                    if (lc.startsWith("content-length:")) continue;
                    nh.append(h).append("\r\n");
                }
                if (reqBody.length > 0) {
                    nh.append("Content-Length: ").append(reqBody.length).append("\r\n");
                }
                // 我们靠读到上游 EOF 来结束转发，所以必须让上游关连接
                nh.append("Connection: close\r\n\r\n");
                uo.write(nh.toString().getBytes("UTF-8"));
                if (reqBody.length > 0) uo.write(reqBody);
                uo.flush();
                relay(up.getInputStream(), cout);
                close(client);
                close(up);
            } catch (Exception e) {
                close(client);
            }
        } catch (Exception e) {
            lg("[proxy] 处理异常(" + host + "): " + e);
        } finally {
            close(client);
        }
    }

    /** 是否是我们要解密的域名 */
    private static boolean isTarget(String host) {
        return host != null && (host.endsWith(".lptiyu.com") || host.equals("lptiyu.com"));
    }

    private Socket connectReal(String host, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 15000);
        return s;
    }

    /** 纯隧道（不解密），双向搬运 */
    private void tunnel(Socket client, InputStream cin, OutputStream cout,
                        String host, int port) throws IOException {
        Socket up = new Socket();
        up.connect(new InetSocketAddress(host, port), 15000);
        // ★★ 关键修复：隧道必须把两边的读超时都设成「无限」。
        //    handle() 为了读 CONNECT 头设过 setSoTimeout(60000)，这个超时是挂在
        //    socket 上的，会一路带进隧道。隧道里客户端只要 60 秒不发数据
        //    （典型场景：App 提前跟 OSS 建好连接，用户对着镜头慢慢调整人脸），
        //    relay() 就抛 SocketTimeoutException，finally 把两个 socket 一起关掉。
        //    等 App 真正开始上传时，用的是连接池里这条已经死掉的连接 ——
        //    表现就是「上传时客户端错误」，人脸验证永远过不去。
        try { client.setSoTimeout(0); } catch (Exception ignore) { }
        try { up.setSoTimeout(0); } catch (Exception ignore) { }
        cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
        cout.flush();
        final Socket u = up, c = client;
        pool.execute(() -> { try { relay(cin, u.getOutputStream()); } catch (Exception ignore) { } finally { close(u); close(c); } });
        pool.execute(() -> { try { relay(u.getInputStream(), cout); } catch (Exception ignore) { } finally { close(u); close(c); } });
    }

    /** 在解密通道上做 HTTP 转发，并抓取请求体 */
    private void serveHttp(InputStream cin, OutputStream cout,
                           InputStream uin, OutputStream uout, String host) throws IOException {
        while (running) {
            String reqLine = readLine(cin);
            if (reqLine == null) return;
            if (reqLine.trim().isEmpty()) continue;

            StringBuilder hdr = new StringBuilder();
            String line;
            int contentLength = -1;
            boolean reqChunked = false;
            while ((line = readLine(cin)) != null && line.length() > 0) {
                String lc = line.toLowerCase(Locale.US);
                if (lc.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    } catch (Exception ignore) { }
                }
                // ★ 请求体也可能是 chunked（图片/文件上传常用），必须识别
                if (lc.startsWith("transfer-encoding:") && lc.contains("chunked")) reqChunked = true;
                if (lc.startsWith("proxy-connection:")) continue;
                hdr.append(line).append("\r\n");
            }
            byte[] body = new byte[0];
            if (reqChunked) {
                // ★ 关键修复：完整读取 chunked 请求体（原来只等 400ms 就截断，
                //   导致带 Transfer-Encoding: chunked 的图片上传整个失败 —— 人脸识别就是这么挂的）
                body = readChunkedBody(cin);
            } else if (contentLength > 0) {
                body = readN(cin, contentLength);
            }
            // 注意：请求没有 Content-Length 也没有 chunked 时就是没有 body，
            //       绝不能去“读一会儿”，否则会吃掉下一个请求的字节。

            // 检查是否有我们要的东西
            try {
                String b = new String(body, "UTF-8");
                if (b.length() > 0 && host != null) {
                    hits.incrementAndGet();
                    boolean done = http(host, reqLine, b);
                    if (done) {
                        // 已拿到身份：继续转发完就退出
                    }
                }
            } catch (Exception ignore) { }

            // 转发给真实服务器
            uout.write((reqLine + "\r\n").getBytes("UTF-8"));
            if (reqChunked) {
                // 我们已经把 chunked 还原成裸字节，所以要丢掉 Transfer-Encoding、补上 Content-Length
                for (String hl : hdr.toString().split("\r\n")) {
                    if (hl.trim().isEmpty()) continue;
                    if (hl.toLowerCase(Locale.US).startsWith("transfer-encoding:")) continue;
                    if (hl.toLowerCase(Locale.US).startsWith("content-length:")) continue;
                    uout.write((hl + "\r\n").getBytes("UTF-8"));
                }
                uout.write(("Content-Length: " + body.length + "\r\n").getBytes("UTF-8"));
            } else {
                uout.write(hdr.toString().getBytes("UTF-8"));
            }
            uout.write("\r\n".getBytes("UTF-8"));
            if (body.length > 0) uout.write(body);
            uout.flush();

            // 回传响应
            String status = readLine(uin);
            if (status == null) return;
            StringBuilder rh = new StringBuilder();
            int rlen = -1;
            boolean chunked = false;
            boolean gz = false;
            while ((line = readLine(uin)) != null && line.length() > 0) {
                String lc = line.toLowerCase(Locale.US);
                if (lc.startsWith("content-length:")) {
                    try { rlen = Integer.parseInt(line.split(":")[1].trim()); } catch (Exception ignore) { }
                }
                if (lc.startsWith("transfer-encoding:") && lc.contains("chunked")) chunked = true;
                if (lc.startsWith("content-encoding:") && lc.contains("gzip")) gz = true;
                if (lc.startsWith("connection:")) continue;
                rh.append(line).append("\r\n");
            }
            // ★ 修复 3：响应既没有 Content-Length 也不是 chunked 时，
            //   body 只能靠「读到上游关闭连接」来判断。这时【绝对不能】对客户端
            //   宣称 Connection: keep-alive —— 否则客户端会一直等 body 等到超时，
            //   这正是「上传时客户端错误」的典型成因之一。
            boolean untilClose = (!chunked && rlen < 0);
            cout.write((status + "\r\n").getBytes("UTF-8"));
            cout.write(rh.toString().getBytes("UTF-8"));
            cout.write((untilClose ? "Connection: close\r\n\r\n"
                                   : "Connection: keep-alive\r\n\r\n").getBytes("UTF-8"));
            cout.flush();

            if (chunked) {
                // 简单转发 chunked（逐块）
                while (true) {
                    String sz = readLine(uin);
                    if (sz == null) return;
                    cout.write((sz + "\r\n").getBytes("UTF-8"));
                    int n;
                    try { n = Integer.parseInt(sz.trim().split(";")[0], 16); } catch (Exception e) { n = 0; }
                    if (n == 0) {
                        String t;
                        while ((t = readLine(uin)) != null && t.length() > 0)
                            cout.write((t + "\r\n").getBytes("UTF-8"));
                        cout.write("\r\n".getBytes("UTF-8"));
                        cout.flush();
                        break;
                    }
                    byte[] chunk = readN(uin, n);
                    cout.write(chunk);
                    cout.write("\r\n".getBytes("UTF-8"));
                    readLine(uin); // 块尾 CRLF
                }
            } else if (rlen > 0) {
                byte[] rb = readN(uin, rlen);
                cout.write(rb);
                // ★ 把 lptiyu 的响应体交给上层 —— 新令牌就在这里
                try {
                    if (host != null && host.contains("lptiyu")) {
                        resp(host, reqLine, decodeMaybeGzip(rb, gz));
                    }
                } catch (Exception ignore) { }
            } else if (untilClose) {
                // 没有长度信息：原样搬到上游关闭为止
                relay(uin, cout);
            }
            cout.flush();
            if (untilClose) return;   // 上游已经关了，这条解密连接到此结束
        }
    }

    // ---------------------------------------------------------------- IO 工具

    /** 响应体可能是 gzip，解一下；解不开就原样当字符串返回 */
    static String decodeMaybeGzip(byte[] b, boolean gz) {
        byte[] out = b;
        if (gz || (b.length > 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B)) {
            try {
                java.util.zip.GZIPInputStream g =
                        new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(b));
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = g.read(buf)) > 0) bo.write(buf, 0, n);
                g.close();
                out = bo.toByteArray();
            } catch (Exception ignore) { }
        }
        try { return new String(out, "UTF-8"); } catch (Exception e) { return ""; }
    }
    /** 读取 chunked 请求体并还原成裸字节（转发时改写成 Content-Length，最稳妥） */
    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sz = readLine(in);
            if (sz == null) break;
            sz = sz.trim();
            if (sz.isEmpty()) continue;
            int n;
            try { n = Integer.parseInt(sz.split(";")[0].trim(), 16); } catch (Exception e) { break; }
            if (n == 0) {
                // 读掉 trailer
                String t;
                while ((t = readLine(in)) != null && t.length() > 0) { }
                break;
            }
            byte[] chunk = readN(in, n);
            out.write(chunk, 0, chunk.length);
            readLine(in);   // 块尾 CRLF
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 65536) break;
        }
        if (c == -1 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), "ISO-8859-1");
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) break;
            off += r;
        }
        if (off == n) return b;
        byte[] t = new byte[off];
        System.arraycopy(b, 0, t, 0, off);
        return t;
    }

    /** 没有 Content-Length 时，尽力读一小段（够解析 key= 体即可） */
    private static byte[] readUntilClose(InputStream in, int ms) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end && bo.size() < 8192) {
                if (in.available() > 0) {
                    int c = in.read();
                    if (c < 0) break;
                    bo.write(c);
                } else {
                    Thread.sleep(20);
                }
            }
            return bo.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void relay(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[BUF];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            out.flush();
        }
    }

    private static void close(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignore) { }
    }
}
