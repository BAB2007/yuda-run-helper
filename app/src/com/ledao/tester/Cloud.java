package com.ledao.tester;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cloud —— 唯一一处「和作者自己的服务器说话」的代码。
 *
 * <p>它干两件事，共用一份清单（{@code update.json}）：
 * <ol>
 *   <li><b>版本闸门</b>：启动时拉一次清单，比 versionCode。新了就弹窗问用户
 *       「现在更新 / 以后再说」；低于清单里的 {@code min} 就不给「以后再说」。</li>
 *   <li><b>匿名心跳</b>：清单里带一个 {@code ping} 字段（作者自己部署的端点，
 *       可以是空的）。非空时上报「有人打开了 / 有人跑完了一局」，
 *       作者据此知道大概有多少人在用。</li>
 * </ol>
 *
 * <h3>为什么心跳要绕清单走一圈</h3>
 * <p>端点地址写在 {@code update.json} 里、不编译进 APK —— 这样作者以后才部署端点
 * 也不用重发一版。清单一拉不到（没网 / Gitee 抽风）就连心跳一起跳过，
 * 绝不阻塞启动，也绝不报错给用户看。
 *
 * <h3>上报的到底是什么（隐私边界，改代码前先读这段）</h3>
 * <ul>
 *   <li>只有三个字段：{@code id}（下面那个哈希）、{@code v}（versionCode）、
 *       {@code ev}（start / run）。</li>
 *   <li>{@code id} = SHA-256(本机随机 UUID + 固定盐) 取前 32 位十六进制。
 *       服务端拿不到原始 UUID，也没法从它反推是哪台手机 —— 同一个 {@code id}
 *       只能说明"还是这台设备"，说明不了"是谁"。</li>
 *   <li>不传 IMEI / OAID / ANDROID_ID / 手机号 / 位置 / 轨迹 / 里程数值。
 *       里程这类数据一律不进这条通道（理由见 docs/89）。</li>
 *   <li>侧栏「📊 匿名统计」可以关掉（{@link #setStatsOn}）；关掉之后一个字节都不发。</li>
 * </ul>
 *
 * <h3>哪些是纯函数（所以能离线回归）</h3>
 * <p>{@link #sha256Hex} {@link #hashId} {@link #encodeUrl} {@link #state}
 * {@link #today} {@link #parse} 一行 Android API 都不碰，{@code CloudTest} 直接断言。
 * 联网那一半（{@link #fetch} {@link #ping}）只能在真机上验，文档里不许写成"已验证"。
 */
public final class Cloud {

    private Cloud() { }

    // ==================================================================
    //  清单地址 —— 必须是**纯 ASCII 路径**的直链
    //  （仓库里那个目录叫「宇达步道乐跑助手」，中文进了 URL 会变乱码，
    //   所以 update.json 放在仓库根目录，不进中文目录。）
    // ==================================================================
    public static final String[] MANIFEST = {
            "https://gitee.com/BAB2007/yuda-run-helper/raw/main/update.json",
            "https://raw.githubusercontent.com/BAB2007/yuda-run-helper/main/update.json",
    };

    /** 哈希盐：换掉它等于换一代统计口径（老 id 全部对不上） —— 别随手改。 */
    public static final String SALT = "ledao-anon-v1";

    /** 连接/读取超时。清单只有几百字节，4 秒是"慢网也够、卡了也不拖启动"的折中。 */
    public static final int TIMEOUT_MS = 4000;

    static final String K_IID = "anon_id";       // 随机设备号（本机生成，从不上传原值）
    static final String K_LAST = "ping_day";     // 上次上报 start 的日期（yyyy-MM-dd）
    static final String K_STATS = "stats_on";    // 匿名统计开关（缺省=开）
    static final String K_SKIP = "upd_skip";     // 用户点过「以后再说」的那一版
    static final String K_CACHE = "upd_cache";   // 上一次成功拉到的清单原文
    static final String K_LASTOK = "upd_last";   // 上次成功的时间（只用于自检日志）

    // ==================================================================
    //  清单
    // ==================================================================

    /** 一个解析好的 {@code update.json}。字段名和仓库里那份一一对应。 */
    public static final class M {
        public int ver;                 // 最新 versionCode
        public int min;                 // 低于它就强制更新；0 = 不强制
        public long size;               // APK 字节数（进度条和「下完没下完」都看它）
        public String name = "";        // "1.0.47"
        public String sha = "";         // APK 的 sha256（小写十六进制，可空）
        public String note = "";        // 一句话更新内容
        public String page = "";        // 「去仓库看」打开的地址
        public String ping = "";        // 心跳端点；空 = 作者还没部署
        public final List<String> urls = new ArrayList<>();

        public boolean ok() {
            return ver > 0 && !urls.isEmpty();
        }

        /** 写回缓存用（和 {@link #parse} 读的字段一一对应）。 */
        public String json() {
            try {
                JSONObject o = new JSONObject();
                o.put("ver", ver);
                o.put("min", min);
                o.put("size", size);
                o.put("name", name);
                o.put("sha256", sha);
                o.put("note", note);
                o.put("page", page);
                o.put("ping", ping);
                JSONArray a = new JSONArray();
                for (String u : urls) a.put(u);
                o.put("urls", a);
                return o.toString();
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /**
     * 解析清单。任何缺字段/坏 JSON 都返回 null —— 不抛异常，
     * 调用方一律按"这次没有更新"处理。
     */
    public static M parse(String json) {
        if (json == null || json.trim().length() == 0) return null;
        try {
            JSONObject o = new JSONObject(json);
            M m = new M();
            m.ver = o.optInt("ver", 0);
            m.min = o.optInt("min", 0);
            m.size = o.optLong("size", 0);
            m.name = o.optString("name", "");
            m.sha = o.optString("sha256", "");
            m.note = o.optString("note", "");
            m.page = o.optString("page", "");
            m.ping = o.optString("ping", "");
            JSONArray a = o.optJSONArray("urls");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    String u = a.optString(i, "");
                    if (u.startsWith("http")) m.urls.add(u);
                }
            }
            return m.ok() ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 该不该提示更新。
     * @return 0=不用 · 1=可选（给「以后再说」）· 2=强制（不给退路）
     */
    public static int state(int myVer, M m) {
        if (m == null || m.ver <= 0 || myVer <= 0) return 0;
        if (m.ver <= myVer) return 0;
        if (m.min > 0 && myVer < m.min) return 2;
        return 1;
    }

    // ==================================================================
    //  纯工具
    // ==================================================================

    /** 今天（本机时区）。心跳按这个日期去重，一天最多一次 start。 */
    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 上传用的设备标识：SHA-256(随机 UUID + 盐) 前 32 位。见类注释的隐私边界。 */
    public static String hashId(String installId) {
        String h = sha256Hex(String.valueOf(installId) + SALT);
        return h.length() >= 32 ? h.substring(0, 32) : h;
    }

    /**
     * 把 URL 里 ≥0x80 的字符（中文路径）按 UTF-8 百分号编码，空格转 %20；
     * ASCII 原样保留（所以 {@code ?a=1&b=2} 不会被破坏）。
     *
     * <p>为什么非做不可：{@code new URL("…/宇达…/…apk")} 在 JVM 上不报错，
     * 但 {@link HttpURLConnection} 发请求行时按 ISO-8859-1 编，服务端收到的是乱码，
     * 结果是 404。真机第一次跑就会踩。
     */
    public static String encodeUrl(String u) {
        if (u == null) return "";
        StringBuilder sb = new StringBuilder(u.length() + 32);
        for (int i = 0; i < u.length(); i++) {
            char ch = u.charAt(i);
            if (ch < 0x80 && ch != ' ') {
                sb.append(ch);
                continue;
            }
            byte[] bs;
            try {
                bs = String.valueOf(ch).getBytes("UTF-8");
            } catch (Throwable t) {
                bs = new byte[]{(byte) '?'};
            }
            for (byte b : bs) sb.append('%').append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    // ==================================================================
    //  本地状态
    // ==================================================================

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE);
    }

    public static int myVer(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 本机随机设备号（首次调用生成并落盘）。只在本机参与哈希，原值不外发。 */
    public static String installId(Context c) {
        SharedPreferences p = sp(c);
        String id = p.getString(K_IID, "");
        if (id != null && id.length() >= 16) return id;
        id = UUID.randomUUID().toString();
        p.edit().putString(K_IID, id).apply();
        return id;
    }

    public static boolean statsOn(Context c) {
        return sp(c).getBoolean(K_STATS, true);
    }

    public static void setStatsOn(Context c, boolean on) {
        sp(c).edit().putBoolean(K_STATS, on).apply();
    }

    /** 上一次成功拉到的清单（没有就返回 null）。 */
    public static M cached(Context c) {
        return parse(sp(c).getString(K_CACHE, ""));
    }

    // ==================================================================
    //  联网那一半
    // ==================================================================

    private static String fetch(String url) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(encodeUrl(url)).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "ledao-helper");
            int code = c.getResponseCode();
            if (code != 200) return null;
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[2048];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > 64 * 1024) return null;   // 清单大过 64 KB 一定是打错了
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            try { if (c != null) c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 一条心跳。{@code base} 是清单里那个 ping 端点（可能带 query）。
     * @return 真发出去了（HTTP 2xx）才 true —— 失败时不记「今天已上报」，
     *         下次启动会再试。
     */
    static boolean ping(String base, Context c, String ev) {
        if (base == null || base.trim().length() == 0) return false;
        if (!statsOn(c)) return false;
        String url = base
                + (base.contains("?") ? "&" : "?")
                + "id=" + hashId(installId(c))
                + "&v=" + myVer(c)
                + "&ev=" + ev;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(encodeUrl(url)).openConnection();
            h.setConnectTimeout(TIMEOUT_MS);
            h.setReadTimeout(TIMEOUT_MS);
            h.setRequestProperty("User-Agent", "ledao-helper");
            int code = h.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (h != null) h.disconnect(); } catch (Throwable ignore) { }
        }
    }

    /** 拉清单：两个源依次试；全失败就用上次缓存。 */
    private static M load(Context c) {
        for (String u : MANIFEST) {
            String txt = fetch(u);
            M m = parse(txt);
            if (m != null) {
                sp(c).edit().putString(K_CACHE, m.json())
                        .putString(K_LASTOK, today()).apply();
                return m;
            }
            Log.d("ledao", "清单拉取失败：" + u);
        }
        return cached(c);
    }

    // ==================================================================
    //  对外的两个入口
    // ==================================================================

    /**
     * 启动时调：拉清单 → 上报 start（一天一次）→ 该更新就弹窗。
     * 全程后台线程，任何一步失败都静默。
     */
    public static void onStart(final android.app.Activity act) {
        if (act == null || act.isFinishing()) return;
        final Context app = act.getApplicationContext();
        final int my = myVer(app);
        new Thread(() -> {
            try {
                final M m = load(app);
                if (m == null) return;
                // 心跳：start 一天最多一次（run 那个另算，见 onRunDone）
                if (m.ping.length() > 0 && statsOn(app)
                        && !today().equals(sp(app).getString(K_LAST, ""))) {
                    if (ping(m.ping, app, "start")) {
                        sp(app).edit().putString(K_LAST, today()).apply();
                    }
                }
                final int st = state(my, m);
                if (st == 0) return;
                if (st == 1 && sp(app).getInt(K_SKIP, 0) >= m.ver) return;
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    Updater.prompt(act, m, st);
                });
            } catch (Throwable t) {
                Log.d("ledao", "更新检查出错：" + t);
            }
        }, "ledao-cloud").start();
    }

    /**
     * 一局真的跑成功了才调（失败 / 手动停止不算「有人在用」）。
     * 用上次缓存里的 ping 端点，不再拉清单 —— 这一步不该为了统计再花一次网络。
     */
    public static void onRunDone(final Context c) {
        if (!statsOn(c)) return;
        final M m = cached(c);
        if (m == null || m.ping.length() == 0) return;
        new Thread(() -> ping(m.ping, c, "run"), "ledao-cloud-run").start();
    }

    /** 侧栏那个开关按一下：返回切换后的状态。 */
    public static boolean toggleStats(Context c) {
        boolean on = !statsOn(c);
        setStatsOn(c, on);
        return on;
    }
}
