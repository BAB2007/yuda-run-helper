package com.ledao.tester;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Stat —— 静默使用统计（v1.0.65 新增；数据契约与通用版同一份，见 `<工程>/stat/DATA.md`）。
 *
 * <p>要回答的问题只有一个：**有多少人在用、分布在哪些学校。** 别的都不问。
 *
 * <p>六条硬约束，一条都不能松：
 * <ul>
 *   <li><b>静默</b>：不写日志、不弹提示、不改任何界面状态。失败一律吞掉，下次启动再试。
 *       整个类里没有一处 log() / Toast / setStatus —— 用户永远看不到它在跑。</li>
 *   <li><b>只报五样</b>：设备指纹哈希、安装指纹哈希、school_id、学校名、版本号。
 *       uid / token / refresh_token / 学号 / 手机号 / mobileDeviceId / umid /
 *       经纬度 / 人脸 —— 一个字节都不出手机（那些是身份，跟"有多少人在用"无关）。</li>
 *   <li><b>指纹是哈希</b>：SHA-256(ANDROID_ID | 盐) 取前 24 位十六进制。服务端手里
 *       就是一串随机串，反推不出手机，也反推不出人。</li>
 *   <li><b>一天最多一条</b>：同设备同一天只成功发一条；跨天再发。
 *       身份是当天才提取出来的话，那一天会补发一条把学校补上（服务端按设备+日期合并，不多算人）。</li>
 *   <li><b>不阻塞</b>：永远在 daemon 线程里跑，最坏 6 秒超时，跟界面主线程无关。</li>
 *   <li><b>尊重老开关</b>：{@link Cloud#statsOn} 是老版本侧栏「📊 匿名统计」留下的偏好
 *       （v1.0.48 起界面上没这颗键了，缺省 true）。老用户当初关过，就一个字节都不发。</li>
 * </ul>
 *
 * <p>域名固定、路径隔离：{@code https://kadir.skin/stat/} 是独立服务（独立进程、独立目录、
 * 独立数据库），跟广告那套 {@code /api/} 不共用任何东西。换服务器只改 DNS，App 不用重发版。
 */
public final class Stat {

    /** 生产上报口。隔离目录 /stat/，与广告的 /api/ 完全分开 */
    public static final String BASE = "https://kadir.skin/stat/v1/report";

    /**
     * 联调开关：留空就是生产地址。
     *
     * <p>要验「报文到底长什么样」，把它填成本机监听地址（配 {@code adb reverse} +
     * {@code stat/tools/_stat_capture.py}）重打一版即可，**验完必须留空** ——
     * 正式包不允许指向任何本机地址。
     */
    private static final String BASE_OVERRIDE = "";

    /**
     * 指纹盐。跟通用版用同一把 —— 两边落在同一张看板上，盐不一致就等于两代口径。
     * <b>改这个值 = 全体设备指纹集体变一次，人数从 0 重新起算，没事别动。</b>
     */
    private static final String SALT = "kadir-stat-2026";

    /* 自己的三格状态（单独一个偏好文件，别跟主偏好混 —— 主偏好出事不能连累统计，
       统计出事也不该动到主偏好） */
    private static final String SP = "ledao_stat";
    private static final String K_DAY = "last";      /* 上次成功上报的日期 yyyy-MM-dd */
    private static final String K_SID = "last_sid";  /* 那次带上去的 school_id（判断要不要补发） */

    /** 学校名存放处：跟身份放同一个偏好文件（{@link AppPrefs#NAME}），键 {@code stat_zone} */
    static final String K_ZONE = "stat_zone";

    private static final int TIMEOUT_MS = 6000;

    /** kick() 时记下的 application context，只为了让 {@link #noteZone(String)} 能落盘 */
    private static volatile Context APP;

    private Stat() {
    }

    // ==================================================================
    //  入口
    // ==================================================================

    /**
     * 主入口：启动 / 回前台各调一次就行，随便调。
     *
     * <p>永远不抛异常、永远不阻塞调用线程（自己开 daemon 线程）。
     */
    public static void kick(Context context) {
        if (context == null) {
            return;
        }
        try {
            APP = context.getApplicationContext();
        } catch (Throwable ignored) {
        }
        try {
            final Context app = APP != null ? APP : context;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        report(app);
                    } catch (Throwable ignored) {
                        /* 静默：统计挂掉不影响任何功能 */
                    }
                }
            }, "ledao-stat");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记下本校的跑区名（= 学校名）。
     *
     * <p>学校名只有跑过一次才知道（{@code Run/GetAllZone} 的 {@code name}，或提取令牌时
     * MMKV 里的 {@code run_zone_name}），所以两处学到就存一次，之后每次上报都带着它 ——
     * 服务端就不用维护任何学校名单，看板上也不会全是"未命名"。
     *
     * <p>没有 context 的重载是给 {@link Ledao} 用的（它在跑动线程里，拿不到 Activity）。
     */
    public static void noteZone(String zone) {
        noteZone(APP, zone);
    }

    /** 同上，显式给 context（提取令牌那条线程用这个）。 */
    public static void noteZone(Context c, String zone) {
        try {
            if (c == null) {
                return;
            }
            String z = cleanSchool(zone);
            if (z.length() == 0) {
                return;
            }
            SharedPreferences sp = AppPrefs.sp(c);
            if (z.equals(sp.getString(K_ZONE, ""))) {
                return;
            }
            sp.edit().putString(K_ZONE, z).apply();
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    //  上报
    // ==================================================================

    private static void report(Context ctx) {
        /* 老用户当初在侧栏关过「匿名统计」→ 一个字节都不发 */
        try {
            if (!Cloud.statsOn(ctx)) {
                return;
            }
        } catch (Throwable ignored) {
        }

        SharedPreferences sp;
        try {
            sp = ctx.getSharedPreferences(SP, 0);
        } catch (Throwable th) {
            return;
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String lastDay = sp.getString(K_DAY, "");
        String lastSid = sp.getString(K_SID, "");

        String ident = "";
        try {
            ident = AppPrefs.sp(ctx).getString("ident", "");
        } catch (Throwable ignored) {
        }
        String sid = field(ident, "school_id");
        String school = schoolName(ctx, sid);

        /* 今天已经报过、而且那次已经把学校带上了 → 不再发。
           那次没带学校（身份是后来才提取的）→ 补发一条，服务端按（设备, 日期）合并。 */
        boolean reportedToday = today.equals(lastDay);
        if (reportedToday && (lastSid.length() > 0 || sid.length() == 0)) {
            return;
        }

        String base = BASE_OVERRIDE != null && BASE_OVERRIDE.trim().length() > 0
                ? BASE_OVERRIDE.trim() : BASE;

        StringBuilder u = new StringBuilder(base);
        u.append(base.indexOf('?') < 0 ? '?' : '&');
        u.append("d=").append(esc(deviceHash(ctx)));
        u.append("&i=").append(esc(installHash(ctx)));
        u.append("&sid=").append(esc(sid));
        u.append("&s=").append(esc(school));
        u.append("&v=").append(esc(ver(ctx)));

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u.toString()).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestMethod("POST");
            c.setDoOutput(false);                    /* 参数全在 query，body 为空 */
            c.setRequestProperty("User-Agent", "ledao-helper/" + ver(ctx));
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                sp.edit().putString(K_DAY, today).putString(K_SID, sid).apply();
            }
            /* 错误流也要读掉并关掉，否则这条连接会在 keep-alive 池里挂着 */
            try {
                InputStream es = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (es != null) {
                    byte[] buf = new byte[256];
                    int guard = 0;
                    while (es.read(buf) > 0 && guard++ < 16) {
                        /* 丢弃 */
                    }
                    es.close();
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            /* 静默：断网 / DNS 挂了都只是"这次没报成"，下次启动再说 */
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================================================================
    //  五样东西
    // ==================================================================

    /** 设备指纹：安卓 ID 优先（同签名重装不变），拿不到才退回安装 ID */
    static String deviceHash(Context ctx) {
        String aid = "";
        try {
            aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (aid != null && aid.trim().length() > 0) {
            return cut(sha256(aid.trim() + "|" + SALT), 24);
        }
        return cut(sha256("ins|" + installId(ctx) + "|" + SALT), 24);
    }

    /** 安装指纹：换一次安装换一次 —— 只用来区分「设备数」和「安装数」 */
    static String installHash(Context ctx) {
        return cut(sha256("ins|" + installId(ctx) + "|" + SALT), 16);
    }

    /** 复用 {@link Cloud#installId}：首次调用生成 UUID 并落盘，之后一直复用 */
    private static String installId(Context ctx) {
        try {
            return Cloud.installId(ctx);
        } catch (Throwable th) {
            return "";
        }
    }

    /** 版本号取 AndroidManifest 的 versionName（跟更新弹窗、关于页同源） */
    private static String ver(Context ctx) {
        try {
            android.content.pm.PackageInfo p =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName == null ? "" : p.versionName;
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 学校名：优先用学到过的跑区名（{@link #noteZone}），其次认身份串里若带的名字字段。
     * 取不到就返回空串 —— 服务端只按 school_id 归档，看板显示"未命名"等下次补上。
     */
    static String schoolName(Context ctx, String identSid) {
        try {
            String z = cleanSchool(AppPrefs.sp(ctx).getString(K_ZONE, ""));
            if (z.length() > 0) {
                return z;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 跑区名 → 学校名：「河北工程大学跑区」→「河北工程大学」；顺便清换行、限长 40 */
    private static String cleanSchool(String s) {
        if (s == null) {
            return "";
        }
        String r = s.replaceAll("[\\r\\n\\t]", " ").trim();
        r = r.replaceAll("跑区$", "").trim();
        return r.length() > 40 ? r.substring(0, 40) : r;
    }

    /** 从身份串里取一个字段（身份串是 k=v&k=v 的形式） */
    private static String field(String ident, String key) {
        if (ident == null || ident.length() == 0) {
            return "";
        }
        for (String part : ident.split("[&\\n]")) {
            int i = part.indexOf('=');
            if (i > 0 && key.equals(part.substring(0, i).trim())) {
                return part.substring(i + 1).trim();
            }
        }
        return "";
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable th) {
            return "";
        }
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String esc(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable th) {
            return "";
        }
    }
}
