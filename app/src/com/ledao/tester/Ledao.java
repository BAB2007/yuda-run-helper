package com.ledao.tester;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ledao —— 步道乐跑签名算法与跑步流程实现（Android 版）
 * 算法与 scripts/ledao_crypto.py、scripts/ledao_run.py 完全一致
 */
public class Ledao {

    // ---------------- 常量（全部由抓包 + libjni_utils.so 逆向社会）
    public static final String APP_KEY = "rDJiNB9j7vD2";        // 签名 A 的盐（后缀）
    public static final String APP_KEY_V2 = "eFoqdHOdDy6ViJ0i"; // 签名 B 的盐（前缀）
    public static final String REQ_KEY = "goMEzJ1pDpncNHb9";    // PARAMS_NEW_GAME 通道
    public static final String REQ_IV = "ZUrAS2lUidjyY2gK";
    public static final String NEW_KEY = "Wet2C8d34f62ndi3";    // NEW_GAME 通道（响应 + 结束跑步）
    public static final String NEW_IV = "K6iv85jBD8jgf32D";
    /** 响应体解密通道 —— 与 NEW_KEY 同一组（实测 226/226 响应可解） */
    public static final String RESP_KEY = "Wet2C8d34f62ndi3";
    public static final String RESP_IV = "K6iv85jBD8jgf32D";

    // ------------------------------------------------------------------
    // ★★★ lpi0 —— 风控令牌（2026-09-30 完整逆向，双向 8/8 逐字节验证）
    //
    // 真机每个「跑步中」的请求都带 lpi0，我们以前一个都没带 —— 这是最大的破绽。
    //
    // 真·构造：
    //     umid  = 真机 MMKV 里的 umid（34 字符，形如 ab82863e63d59e25 + 18 位尾码）
    //     P     = umid + md5hex(timestamp)                     // 66 字节 ASCII
    //     lpi0  = Base64.DEFAULT( AES-CBC-PKCS5(P, KEYS[i], IVS[i]) )
    //
    // 注意三点（都踩过坑）：
    //   ① 下面这张表是 **15 组配对的 (key, iv)**，不是两个独立列表 ——
    //      必须同下标取，配错了解不出来。
    //   ② 明文里 **没有** 前置 IV，别把密文第一块当 IV（一开始就是这么错的）。
    //   ③ Base64.DEFAULT 按 76 字符折行并补尾部换行，**这个换行也参与签名**。
    //
    // 实测：8 个真机样本，用配对 (key,iv) 全部一次解出 umid+MD5(ts)；
    //       反方向加密回去与真机原文逐字节一致。
    //       lpi0 必须放在算 sign 之前 —— 真机 signB 把它算在内（8/8 验证）。
    // ------------------------------------------------------------------
    public static final String[] LPI0_KEYS = {
            "L3xP7kQ2fD9sH5jR", "Z5tF8gS3dA7hJ2pQ", "Q2mK7jP3nL5iO9uY",
            "D9fG3hJ7kL2pM5rN", "P7bN2vF9cD4mK6jA", "H5jG8fD3sA6dF9gH",
            "X8rV3tB6yG9hN4kP", "M3dS5fG8hJ2kL7pR", "F6dG2hJ5kL8pM3rN",
            "V5bC3nM8vX4cZ6dF", "B8vN5cM2xZ9dF3gJ", "N3mK6lJ8pO5iU2yT",
            "T6rE2yU5iO8pJ3lK", "W9qE3sA6dF5gH7jK", "S4dF8aQ3wE6rT5yU",
    };
    /** 与 LPI0_KEYS 同下标配对的 IV —— 表本身就是 15 组 (key, iv) */
    public static final String[] LPI0_IVS = {
            "N8wB4mG6vK2rT9cE", "Y6rU3eW9qX4sC8vB", "W4sE8qR2tT5yU3iO",
            "B6vC8nX3mZ5dF7gH", "S3hG5jJ8dL7fT2wQ", "K7lJ2pO5iU8yT3rE",
            "E2sW5qA3dZ7fC8jM", "C4tY9rU6eW3qX5sZ", "Z7xC5vB3nA9sD2fH",
            "J9kL2jH5gD3fS7dA", "H4kL7jP2mQ5rT8sW", "G5hF7dS9fA3gH4jK",
            "J7kH3gF5dS8aQ2wE", "R5tY2eU7iO8pJ3lK", "I2uO5pJ8lK3jH7gF",
    };
    /** umid 兜底值（本机真机 MMKV 里读到的；提取身份时会用真实的覆盖它） */
    public static final String DEFAULT_UMID = "ab82863e63d59e250a3d836c44277484od";
    /** ★ 当前使用的 umid —— Identity 提取身份时覆盖。lpi0 明文的前 34 字符 */
    public volatile String umid = DEFAULT_UMID;
    /** 本次运行生成的 lpi0 个数 / 最近用的下标 —— 界面上要显示，方便核对 */
    public volatile int lpi0Count = 0;
    public volatile String lastLpi0Key = "-";

    public static final String BASE = "https://api2.lptiyu.com";
    public static final String API = "/v3/api.php/";
    public static final String UA =
            "Dalvik/2.1.0 (Linux; U; Android 10; MI 8 UD MIUI/V12.5.2.0.QECCNXN)";

    public interface Log {
        void log(String s);

        /**
         * ★ 静态信息更新：跑区打卡点 + 本次路线。**不含当前位置**，所以界面上
         * 绝不会往「实跑轨迹」里塞点。
         *
         * ★★★ 2026-10-08 修掉的真 bug：以前这些元数据也走 {@link #onRoute}，
         *   而 onRoute 的 (lat,lon) 是要进轨迹的 —— 于是每局开跑都会先塞进
         *   **三个不是 GPS 的点**：① 预选的打卡点 A、② 服务端指派的打卡点 A、
         *   ③ 路线起点。它们在校园里相距几百米，画出来就是两条笔直的长线，
         *   用户看到的现象正是「一点开始跑步，位置就瞬移」。
         *   顺带：那时还把「打卡点串起来的直连折线」当成路线推给地图，
         *   开跑后二十秒内地图上显示的就是一条横穿校园的直线 —— 同样是瞬移观感。
         *
         * @param allPoints 跑区全部打卡点（服务端点序）
         * @param route     本次实际要走的那条线；传 null = 只更新打卡点，别动路线
         */
        default void onPlan(java.util.List<double[]> allPoints, java.util.List<double[]> route) { }

        /**
         * ★ 位置变化回调（真·GPS 点，给界面上的轨迹和标注用）。
         * @param allPoints 跑区全部打卡点（服务端点序）
         * @param route     本次实际要走的那条线（从 allPoints 里挑出来的）
         * @param lat       当前纬度
         * @param lon       当前经度
         * @param hit       已打卡数量
         */
        default void onRoute(java.util.List<double[]> allPoints, java.util.List<double[]> route,
                             double lat, double lon, int hit) {
            onRoute(allPoints, route, lat, lon, hit, -1);
        }

        /**
         * ★ 2026-10-10 加了 speed：实跑轨迹要按「慢→快」上色。
         * 默认实现忽略它，老的实现类不用改。
         */
        default void onRoute(java.util.List<double[]> allPoints, java.util.List<double[]> route,
                             double lat, double lon, int hit, double speed) {
            onRoute(allPoints, route, lat, lon, hit);
        }

        /**
         * ★ 本次跑步「必须经过的那 2 个打卡点」—— 地图上要着重标注。
         * 步道乐跑一次跑步只给两个点，两个都过了才算数。
         */
        default void onKeyPoints(java.util.List<double[]> keys, java.util.List<String> ids) { }

        /**
         * ★★★ 2026-10-10（用户第 1 条）：**起跑前人脸核验的结论**。
         *
         * <p>用户原话：
         * <pre>
         *   跑前，如果人脸照片通过，就弹一个渐进窗口「人脸识别通过」，
         *   并在停留几毫秒后消失；如果没通过，就弹原因，并且终止跑步并提示
         * </pre>
         *
         * <p>所以这条回调**只在起跑前那一次**（type=0）发出来，中途那次走
         * {@link #onFlash}。注意 {@code ok=false} 时 {@code run()} 会**立刻收工**
         * （用户明确要求"没过就终止"），并且把 {@code Result.reported} 置位，
         * 界面**不要**再为同一个失败弹第二个窗。
         *
         * @param ok     true 只有一种情况：服务端 status==1
         * @param status 服务端 status（1=通过；0=没过；-1=这一枪没发出去，比如没设照片）
         * @param info   服务端原话（可能为空）
         */
        default void onFacePre(boolean ok, int status, String info) { }

        /**
         * ★ 2026-10-10：一闪而过的提示，不用点、不打断。
         * 用在「跑步途中出现了值得说一句、但不至于停下」的事情上
         * （目前只有中途人脸核验失败）。
         *
         * @param warn true=红卡片（出事了但继续跑），false=绿卡片
         */
        default void onFlash(String text, boolean warn) { }
    }

    private final TreeMap<String, String> ident = new TreeMap<>();
    private final Log log;
    private volatile boolean stopped = false;
    private final Random rnd = new Random();

    /** 学期 id（stopRunV278 要带），从 beforeRunV260 响应里取 */
    public String termId = "13119";
    /**
     * ★★★ 会话已死（101/102）。一旦置位，本次跑步**立刻中止**，不再往下跑。
     *
     * 实测教训（2026-09-30 20:37）：102 之后 refresh_token 也是 102，
     * 而旧代码每来一个请求就重试一次续期 —— 一秒钟能打十几次，
     * 既救不回来，还可能把服务端的会话状态搅得更死。
     */
    public volatile boolean sessionDead = false;
    public volatile int sessionDeadStatus = 0;
    /** 整个会话周期里最多只试一次自动续期 */
    private volatile boolean refreshTried = false;
    /** 上报的本机局域网 IP（App 也是报这个） */
    public String localIp = "10.0.0.1";
    /**
     * ★ 风控随机：每次挑不同的打卡点子集当路线。
     *   固定跑同一条线跑十几次，服务端曲线一看就是复读机。
     */
    public boolean randomRoute = false;

    /** 校园水面位图（由 MainActivity 注入）—— 路线生成时避湖 */
    public WaterGrid water = null;
    /** 校园可跑街道栅格（A* 寻路用）。为 null 时回落到旧的样条路线。 */
    public RoadGrid roadGrid = null;
    /**
     * ★★★ 手画路网（1683 街口 / 2871 街段 / 59.5 km）。
     *
     * 新版路线规划在它上面随机游走：两段式走法 + 空间自避让 + 靠右/横向游走/转角抹圆。
     * 见 {@link RoutePlan} 的类注释。为 null 时回落到旧的 {@link RouteGen}。
     */
    public RouteNet routeNet = null;
    /** 预览那条路线用的随机种子（{@link #routeCpN} 对得上时直接复用 → 逐点相同） */
    public long routeSeed = 0;
    /** 预览那条路线用的两个打卡点序号 n（1..14） */
    public int[] routeCpN = null;

    // ------------------------------------------------------------------
    //  ★★★ beforeRunV260 下发的 103 个规则参数里，以前我们只用了 8 个。
    //     下面这些是「服务端会拿来核轨迹」的那几个（docs/21 §四）。
    // ------------------------------------------------------------------
    /** 跑区多边形四角（来自 data.run_zone_latlng，形如 ["114.588111,36.660305", …]） */
    public final List<double[]> zoneRect = new ArrayList<>();
    /** 服务端要求的相邻点最大间距（米），android_gps_max_distance */
    public int gpsMaxDistance = 30;
    /** GPS 精度阈值，android_gps_start/process/log_precision */
    public int gpsPrecStart = 30, gpsPrecProcess = 100, gpsPrecLog = 100;
    /** 是否需要人脸验证：is_register_face / is_check_face / is_face */
    public boolean needFace = false;
    public String faceConfig = "";
    /** 本次是不是「自由跑」—— 服务端说 freerun_status=0 时校园跑不可用 */
    public String freeRunStatus = "";
    /** 抓拍策略 */
    public String captureType = "", captureInterval = "";
    /**
     * ★ 人脸验证凭据。真机是「起跑前弹框拍照 → 传 OSS → 服务端回一个 id」，
     *   18:51 那次拿到的是 `6abce8f040cfe`，stopRun 时原样上报。
     *   本客户端没有相机人脸模块，默认 "0"；外面若拿到真凭据可以注入进来。
     */
    public volatile String faceVerifyId = "0";
    /** 服务端 run_face_verify_config.middle_distance（真机是 600 m）—— 仅用于日志展示 */
    public volatile int faceMiddleDistance = 600;
    /**
     * 人脸核验时机：**起跑后多少秒**。
     *
     * ★ 2026-10-06 实测更正（见 docs/29）：真机 09-30 18:47 那一跑
     *   **从头到尾只做了一次核验**，出现在起跑后 18 秒（约 30 m 处）：
     *     18:47:50 clickRun:0
     *     18:48:06 randomFace:1033.0 … checkFace:-1
     *     18:48:08 showVerifyFaceDialog:1,0,0
     *     18:48:18 face_verify_id:6abce8f040cfe      ← 只有这一枪
     *   我上一版按「开始 25 m + 中途 600 m 各一次 + 链式带 id」改，
     *   是**过度解读**了服务端的 start_status/middle_status —— 那两个开关
     *   并没有让真机真的验两次。退回一次。
     */
    public static final int FACE_AT_SEC = 18;
    /** 服务端开关：start_status / middle_status / stop_status */
    public volatile boolean faceStartStatus = true;
    public volatile boolean faceMiddleStatus = true;
    public volatile boolean faceStopStatus = false;
    /**
     * 起跑时 `Run/getTimestampV278` 下发的服务端时间戳 —— 收尾 `stopRunV278` 的
     * `start_time` 用它（真机就是这个值：start_time == 那次 getTimestampV278 的 ts）。
     * ★ 2026-10-07 事故：把它存在局部变量里，字段裁剪时漏了 fin.put("start_time")，
     *   服务端收不到开始时间 → {"status":100,"info":"上传数据失败"}。现在提到成员，确保收尾一定带上。
     */
    public volatile String srvStartTime = "";
    /** 中途要核验几次（middle_num）—— 真机实测没用到，仅记录 */
    public volatile int faceMiddleNum = 1;
    /**
     * ★★★ 中途人脸核验是否已经做过（每场跑步重置）。
     *
     * 为什么必须有这个标志：服务端 `middle_status=1` + `middle_distance=600` +
     * `middle_num=1` 要求跑到 600 m 时**必须**再验一次脸。2026-10-07 之前，
     * `faceMiddleStatus` / `faceMiddleDistance` 只被赋值、从没被消费 ——
     * 中途那次核验压根没发生过，于是收尾被判
     * `status=59「人脸照片验证不合格」`。
     */
    public volatile boolean faceMiddleDone = false;

    /**
     * ★ 作废本机缓存的人脸核验凭据 —— 换照片、删照片之后必须调。
     *
     * <p>为什么要有它：{@code lastFaceId} 是「上一次核验拿到的 run_face_verify_id」，
     * 中途核验（type=2）会把它带上，服务端据此把 start→middle 串成一条链。
     * 照片换掉之后再用旧 id 去续链，等于拿 A 的脸去续 B 的核验。
     * 删照片的情况更严重 —— 没有照片就不该有任何 id 上报。
     */
    public void resetFaceState() {
        lastFaceId = "";
        lastFaceStatus = -1;
        lastFaceInfo = "";
        faceVerifyId = "0";
        faceMiddleDone = false;
        log.log("      [人脸] 已清空本机缓存的核验凭据（换/删照片后必须清）");
    }

    public Ledao(Log log) {
        this.log = log;
        // 默认环境参数（可被自动提取 / 粘贴的身份覆盖）
        // ★ mobileDeviceId 故意**不设默认值**：必须用本机真实值，
        //   写死一个假值会导致服务端一直返回 101/102。（血泪教训，见 Identity.java）
        ident.put("version", "229");
        ident.put("version_name", "4.2.9");
        ident.put("mobileModel", "MI_8_UD");
        ident.put("mobileOsVersion", "10");
        ident.put("ostype", "1");
        ident.put("school_id", "647");
    }

    /** 用自动提取 / 手动粘贴的身份覆盖进来 */
    public void applyIdentity(Map<String, String> m) {
        if (m == null) return;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String v = e.getValue().trim();
            if (v.length() > 0) ident.put(e.getKey(), v);
        }
    }

    /**
     * ★★★ 这台机器上步道乐跑**自己发出去的** mobileDeviceId（2026-10-04 定案，docs/23）。
     *
     * 它长得像个坏 GUID（第 1 段和第 4 段整段全 F，即两个分量取了 -1）。
     * 以前我们据此把它定义成「旧抓包流传的占位设备号，服务端一律拒绝，必须当成没有」
     * —— **这个判断是错的，而且正是「一直抓不到个人标识」的根源**：
     *
     *   · 真机抓包（work/mitm + work/cap01 全量）里 `mobileDeviceId` 只有这**一个**取值，
     *     出现 **622 次**；
     *   · 我们自己 09-30 20:44 那次发的也是它，记录照样建出来了
     *     （work/lg.txt：「[设备号来自抓包] … （非标准 UUID，但抓包实测可用）」）；
     *   · 而当年拿去「纠正」它的 `.system_id.log` 值 `d69d4891-388e-44fc-90f2-167e9e626440`
     *     在真机请求里出现 **0 次** —— 真机根本不用它当 mobileDeviceId。
     *
     * 更要紧的是：它**不落盘**。`grep -rl` 扫遍 /data/data/com.lptiyu.tanke，
     * 只有 WebView 的缓存里带着它，任何配置文件、MMKV、SharedPreferences 里都没有
     * ⇒ 它是**运行时算出来的**，唯一拿得到的途径就是抓包。
     * 「从文件里读设备号」这条路本来就走不通。
     */
    public static final String KNOWN_DEVICE_ID = "ffffffff-af0e-5497-ffff-ffffef05ac4a";

    /** mobileDeviceId 是否是「能用的 36 位 UUID」。★ 不再排除 KNOWN_DEVICE_ID。 */
    public boolean deviceIdOK() {
        String d = ident.get("mobileDeviceId");
        return d != null && d.length() == 36 && d.indexOf('-') > 0;
    }

    public void stop() { stopped = true; }
    public TreeMap<String, String> ident() { return ident; }

    // ==================================================================
    //  加解密与签名
    // ==================================================================
    private static byte[] md5(byte[] b) throws Exception {
        return MessageDigest.getInstance("MD5").digest(b);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Python urllib.parse.unquote 的等价实现（注意：不把 + 变成空格） */
    public static String unquote(String s) {
        if (s == null || s.indexOf('%') < 0) return s == null ? "" : s;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                } catch (Exception e) {
                    out.write((byte) c);
                }
            } else {
                byte[] bb = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(bb, 0, bb.length);
            }
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 按 key 升序拼接 "key+value"（value 先 URL 解码），不含 sign */
    private static String sortedKv(Map<String, String> p, boolean dropDevice) {
        TreeMap<String, String> t = new TreeMap<>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            String k = e.getKey();
            if ("sign".equals(k)) continue;
            if (dropDevice && "mobileDeviceId".equals(k)) continue;
            t.put(k, unquote(e.getValue() == null ? "" : e.getValue()));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : t.entrySet()) sb.append(e.getKey()).append(e.getValue());
        return sb.toString();
    }

    /** 签名 A：MD5( sorted_kv + APP_KEY )，盐在后缀，含 mobileDeviceId */
    public static String sign(Map<String, String> p) throws Exception {
        return hex(md5((sortedKv(p, false) + APP_KEY).getBytes("UTF-8")));
    }

    /** 签名 B：MD5( APP_KEY_V2 + sorted_kv )，盐在前缀，排除 mobileDeviceId */
    public static String signV2(Map<String, String> p) throws Exception {
        return hex(md5((APP_KEY_V2 + sortedKv(p, true)).getBytes("UTF-8")));
    }

    public static byte[] aes(int mode, byte[] data, String key, String iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(iv.getBytes("UTF-8")));
        return c.doFinal(data);
    }

    public static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    /**
     * ★★★ 生成 lpi0 风控令牌（真·构造，与真机逐字节同构）。
     *
     *   P    = umid + md5hex(timestamp)                     // 66 字节 ASCII
     *   lpi0 = Base64.DEFAULT( AES-CBC-PKCS5(P, K[i], IV[i]) )
     *
     * 真机原文（capture_run.log L17）：
     *   lpi0=q4jgW%2FrqXNsGBMI8Oa7PZMXi%2BcXu7N0rdLl6HIFfNQCdxP1e8tptRTOuObH9hvM
     *        LnnnjdS5w2lZF%0Ac8ZXESfPIPMNdf7CResnGdVrt7el7Yo%3D%0A
     * 即 Base64.DEFAULT（76 字符折行 + 尾部换行），解码后 80 字节。
     *
     * @param timestamp 与参数表里的 timestamp **必须同一个值**（明文里是它的 MD5）
     */
    public String makeLpi0(String timestamp) throws Exception {
        int i = rnd.nextInt(LPI0_KEYS.length);          // ★ key 与 iv 同下标配对
        String key = LPI0_KEYS[i];
        String plain = umid + hex(md5(timestamp.getBytes("UTF-8")));
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(LPI0_IVS[i].getBytes("UTF-8")));
        byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
        lpi0Count++;
        lastLpi0Key = key;
        String out = Base64.encodeToString(ct, Base64.DEFAULT);   // ★ DEFAULT 才会折行
        // ★ 第一次生成时打自检：把刚生成的令牌解回来，必须是同一串明文。
        //   用户一眼就能看出 lpi0 到底有没有生效，不用等跑完。
        if (lpi0Count == 1 && log != null) {
            try {
                String back = verifyLpi0(out, key, LPI0_IVS[i]);
                log.log("      [lpi0] umid=" + umid);
                log.log("      [lpi0] key[" + i + "]=" + key + " iv=" + LPI0_IVS[i]
                        + " 明文=" + plain);
                log.log("      [lpi0] 自检 " + (plain.equals(back) ? "通过 ✅" : "失败 ❌ " + back)
                        + "   " + ct.length + " 字节密文");
            } catch (Throwable ignore) { }
        }
        return out;
    }

    /**
     * ★★★ 生成 lpi1 风控令牌 —— **与 lpi0 完全同一个算法，只是字段名不同**。
     *
     *   P    = umid + md5hex(timestamp)                     // 66 字节 ASCII
     *   lpi1 = Base64.NO_WRAP( AES-CBC-PKCS5(P, K[i], IV[i]) )   // 80 字节密文 → 110 字符
     *
     * 实证（2026-10-06 真机成功跑步，Reqable 会话 auto_20261006_212849.jsonl）：
     *   把真机 setRunLocationRecord（**明文表单，未加密**）里的 lpi1 用
     *   LPI0_KEYS 15 组密钥轮着解，4/4 全部命中，且明文恰好是 66 字节：
     *
     *     id=4618 ts=1791293757 key[12] → ab82863e63d59e250a3d836c44277484od
     *                                       a2031c9aa0eb026124d92ef2f5a9abfa
     *                                     └── umid (34) ──┘└─ md5hex(ts) (32) ─┘
     *     id=4707 ts=1791293956 key[1]  → …od 0eac8d7a050f45f6ef3e7cf0933a758c
     *     id=4769 ts=1791294090 key[0]  → …od a44adf45cfd8218370d85a0dea6f0a03
     *     id=4907 ts=1791294374 key[14] → …od e0c7bb9bb4a561963b1eeb418b936c7d
     *
     *   四次的「尾部 32 字符 == md5hex(timestamp)」**全部相等**（scripts/lpi_verify2.py）。
     *   所以 lpi1 不是新算法，就是旧 lpi0 换了名字。
     *
     * ⚠ 与 lpi0 的唯一差别：Base64 **不折行**（实机那 110 字符里没有 %0A）。
     *   另外注意：在 `Run/stopRunV278` 上 lpi1 的含义**不同** —— 那里它是
     *   `Run/getTimestampV278` 下发的 `str`（32 位 hex，= md5hex(start_time)），
     *   见收尾段的注释。
     *
     * @param timestamp 与参数表里的 timestamp **同一个值**
     */
    public String makeLpi1(String timestamp) throws Exception {
        int i = rnd.nextInt(LPI0_KEYS.length);          // ★ key 与 iv 同下标配对
        String key = LPI0_KEYS[i];
        String plain = umid + hex(md5(timestamp.getBytes("UTF-8")));
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(LPI0_IVS[i].getBytes("UTF-8")));
        byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
        lpi1Count++;
        lastLpi1Key = key;
        String out = Base64.encodeToString(ct, Base64.NO_WRAP);   // ★ 不折行
        if (lpi1Count == 1 && log != null) {
            try {
                String back = verifyLpi0(out, key, LPI0_IVS[i]);
                log.log("      [lpi1] umid=" + umid);
                log.log("      [lpi1] key[" + i + "]=" + key + " iv=" + LPI0_IVS[i]
                        + " 明文=" + plain);
                log.log("      [lpi1] 自检 " + (plain.equals(back) ? "通过 ✅" : "失败 ❌ " + back)
                        + "   " + ct.length + " 字节密文 → " + out.length() + " 字符");
            } catch (Throwable ignore) { }
        }
        return out;
    }

    /** lpi1 计数 / 最后一次用的 key —— 便于日志里核对 */
    public int lpi1Count = 0;
    private String lastLpi1Key = "";

    /** 自检：把刚生成的 lpi0 解回来，确认实现没写错 */
    public static String verifyLpi0(String lpi0, String key, String iv) throws Exception {
        byte[] ct = unb64(lpi0);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(iv.getBytes("UTF-8")));
        return new String(c.doFinal(ct), "UTF-8");
    }

    /**
     * ★★★ 启动自检 A：旧名 lpi0 的历史黄金向量（Base64.DEFAULT，会折行）。
     *
     * 这个向量证明的是**算法本身**没错，留着做回归。
     * 向量来源：work/capture_run.log L7/L8（真机 Run2/beforeRunV260，ts=1790676610）
     *   umid  = ab82863e63d59e250a3d836c44277484od
     *   明文  = umid + MD5("1790676610")
     *         = ab82863e63d59e250a3d836c44277484oddbdcf5ee6dd2569878b21a53207bd781
     *   配对  = KEYS[10] / IVS[10]
     *   令牌  = +boTNpI/qFOy.../06oeNSTwYmbJJfroar0sOd2uYm+lPI+KwmH0t6R7⏎ohPog2lIg5v21DyMjVdVjR8i9hXtlZ8=⏎
     */
    public static String selfTestLpi0() {
        final String TS = "1790676610";
        final String PLAIN = DEFAULT_UMID + "dbdcf5ee6dd2569878b21a53207bd781";
        final int IDX = 10;
        final String EXPECT =
                "+boTNpI/qFOy32hnB/f8iIeVYn2iIrk4E1x/06oeNSTwYmbJJfroar0sOd2uYm+lPI+KwmH0t6R7"
                        + "\n" + "ohPog2lIg5v21DyMjVdVjR8i9hXtlZ8=" + "\n";
        try {
            String plain = DEFAULT_UMID + hex(md5(TS.getBytes("UTF-8")));
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(LPI0_KEYS[IDX].getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(LPI0_IVS[IDX].getBytes("UTF-8")));
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            String got = Base64.encodeToString(ct, Base64.DEFAULT);
            boolean okPlain = PLAIN.equals(plain);
            boolean okTok = EXPECT.equals(got);
            String back = verifyLpi0(got, LPI0_KEYS[IDX], LPI0_IVS[IDX]);
            boolean okBack = plain.equals(back);
            if (okPlain && okTok && okBack) return "lpi0 自检通过 ✅ 与真机令牌逐字节一致";
            return "lpi0 自检失败 ❌ 明文" + (okPlain ? "正确" : "错误(" + plain + ")")
                    + " 令牌" + (okTok ? "正确" : "错误(" + got.replace("\n", "⏎") + ")")
                    + " 回解" + (okBack ? "正确" : "错误(" + back + ")");
        } catch (Throwable e) {
            return "lpi0 自检异常 ❌ " + e;
        }
    }

    /**
     * ★★★ 启动自检 B：**现役** lpi1 的真机黄金向量（Base64.NO_WRAP，不折行）。
     *
     * 向量来源：2026-10-06 真机成功跑步（Reqable 会话 auto_20261006_212849.jsonl）
     *   真机报文：Run/setRunLocationRecord（**明文表单，未加密**，可直接读原文）
     *     id=4618  timestamp=1791293757
     *     lpi1=AFGJ4CxRZIbIsHJxPBxywRqVlRDGYMMAkfWlwsy0Z0JUYF7ofT56zs%2Fosjd4mOyy
     *          uNpOpfZ9nT9eKKEAcTg1BcA0PYnFibYcl2xKbQDvqdw=
     *   用 LPI0_KEYS 15 组轮解 → 命中 key[12]，明文 66 字节 ASCII：
     *     ab82863e63d59e250a3d836c44277484od + a2031c9aa0eb026124d92ef2f5a9abfa
     *     └────────── umid (34) ──────────┘└────── md5hex(timestamp) (32) ──────┘
     *   「尾部 32 字符 == md5hex(timestamp)」在 4/4 个样本上全部成立
     *   （scripts/lpi_verify2.py 可复现）。
     *
     * 这个自检就是「我们发的 lpi1 和真机是不是同一个东西」的**离线证明**：
     * 同一 timestamp、同一密钥对，必须得到同一串令牌，一个字节都不能差。
     */
    public static String selfTestLpi1() {
        final String TS = "1791293757";
        final String PLAIN = DEFAULT_UMID + "a2031c9aa0eb026124d92ef2f5a9abfa";
        final int IDX = 12;
        final String EXPECT =
                "AFGJ4CxRZIbIsHJxPBxywRqVlRDGYMMAkfWlwsy0Z0JUYF7ofT56zs/osjd4mOyy"
                        + "uNpOpfZ9nT9eKKEAcTg1BcA0PYnFibYcl2xKbQDvqdw=";
        try {
            String plain = DEFAULT_UMID + hex(md5(TS.getBytes("UTF-8")));
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(LPI0_KEYS[IDX].getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(LPI0_IVS[IDX].getBytes("UTF-8")));
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            String got = Base64.encodeToString(ct, Base64.NO_WRAP);
            boolean okPlain = PLAIN.equals(plain);
            boolean okTok = EXPECT.equals(got);
            boolean okLines = got.indexOf('\n') < 0;
            String back = verifyLpi0(got, LPI0_KEYS[IDX], LPI0_IVS[IDX]);
            boolean okBack = plain.equals(back);
            if (okPlain && okTok && okBack && okLines)
                return "lpi1 自检通过 ✅ 与真机令牌逐字节一致（108 字符、不折行）";
            return "lpi1 自检失败 ❌ 明文" + (okPlain ? "正确" : "错误(" + plain + ")")
                    + " 令牌" + (okTok ? "正确" : "错误(len=" + got.length() + ")")
                    + " 不折行" + (okLines ? "正确" : "错误")
                    + " 回解" + (okBack ? "正确" : "错误(" + back + ")");
        } catch (Throwable e) {
            return "lpi1 自检异常 ❌ " + e;
        }
    }

    /** x-www-form-urlencoded 值编码 —— 与真机一致（逗号 %2C / 加号 %2B / 换行 %0A） */    private static final String UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~";

    public static String urlEnc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int v = x & 0xff;
            if (v < 128 && UNRESERVED.indexOf((char) v) >= 0) sb.append((char) v);
            else sb.append('%').append(String.format("%02X", v));
        }
        return sb.toString();
    }

    public static byte[] unb64(String s) {
        return Base64.decode(s, Base64.DEFAULT);
    }

    // ==================================================================
    //  HTTP
    // ==================================================================
    private String post(String path, String body, int timeoutSec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + API + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(timeoutSec * 1000);
        c.setReadTimeout(timeoutSec * 1000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        c.setRequestProperty("Connection", "close");
        byte[] d = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(d.length);
        OutputStream os = c.getOutputStream();
        os.write(d);
        os.flush();
        os.close();
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
        c.disconnect();
        String resp = new String(bo.toByteArray(), "UTF-8").trim();
        dump(path, body, resp);
        return resp;
    }

    /**
     * ★★★ 把每一次接口调用的**明文**报文落盘到 files/netlog.txt。
     *
     * 为什么需要它：助手的日志只走界面，跑完就没了。2026-10-07 那两单
     * （「上传数据失败」和「人脸照片验证不合格」）我们**一次都没看到自己发出去的
     * 报文长什么样**，只能在代码里猜，接连猜错两次。
     * 有这份记录，收尾那一枪就能和真机那份 37 字段的成功包**逐字段对照**。
     *
     * 密文体（body 是 key=…）会顺手解回明文；响应若 is_encrypt=1 也解回来。
     * 任何一步失败都不抛异常 —— 记录不能影响跑步。
     */
    private void dump(String path, String body, String resp) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n══════ ").append(new SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new Date())).append("  ").append(path).append(" ══════\n");
            // 请求体：密文体解回明文
            String reqShow = body;
            try {
                if (body != null && body.startsWith("key=")) {
                    String pt = new String(aes(Cipher.DECRYPT_MODE,
                            unb64(URLDecoder.decode(body.substring(4), "UTF-8")),
                            REQ_KEY, REQ_IV), "UTF-8");
                    reqShow = "（密文体 PARAMS_NEW_GAME，已解回明文）" + pt;
                }
            } catch (Throwable ignore) { }
            sb.append("→ 请求 ").append(reqShow == null ? "" : reqShow).append("\n");
            // 响应：is_encrypt=1 解回明文
            String respShow = resp;
            try {
                JSONObject rj = new JSONObject(resp);
                if (rj.optInt("is_encrypt") == 1 && rj.opt("data") instanceof String) {
                    String pt = new String(aes(Cipher.DECRYPT_MODE,
                            unb64(rj.getString("data")), RESP_KEY, RESP_IV), "UTF-8");
                    rj.put("data", new JSONObject(pt));
                    respShow = rj.toString();
                }
            } catch (Throwable ignore) { }
            sb.append("← 响应 ").append(respShow == null ? "" : respShow).append("\n");

            java.io.File f = new java.io.File(FACE_PHOTO).getParentFile();
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new java.io.File(f, "netlog.txt"), true);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignore) { }
    }

    /** 清空报文记录（每次开始跑步时调，保证 netlog.txt 只装本场） */
    public void resetNetLog() {
        try {
            java.io.File f = new java.io.File(FACE_PHOTO).getParentFile();
            java.io.File log = new java.io.File(f, "netlog.txt");
            if (log.exists() && !log.delete()) {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(log, false);
                fo.close();
            }
        } catch (Throwable ignore) { }
    }

    /** 组装带签名的参数表 */
    private TreeMap<String, String> params(Map<String, String> extra, boolean v2,
                                           boolean withLpi0) throws Exception {
        return params(extra, v2, withLpi0, null);
    }

    /**
     * ★★★ 真机 `Run/stopRunV278` 的**完整键集**（37 个，不含 sign）。
     *
     * 出处：2026-10-06 21:50 真机成功跑步（Reqable id=5252，record_id=364498，
     * `record_status=1`「自动确认有效」）的请求体逐字段抄录，见 work/session/real_stop.json。
     *
     * 为什么要有这个常量：签名算的是**整个参数表**，所以"发哪些键"和"签名口径"
     * 是同一件事。ident 里带着 `access_token` / `refresh_token`，真机在这条接口上
     * 从不发它们 —— 必须裁掉，否则我们用的就是另一个口径。
     * LpiTest 会拿这个集合做运行时断言（数出来的必须正好 37+sign）。
     */
    public static final String[] STOP_REAL_KEYS = {
            "distance", "end_time", "error_code", "file_img", "game_id", "gee_token",
            "ip", "is_running_area_valid", "is_timeout_zfb", "log_data", "lpi1",
            "mac", "mobileDeviceId", "mobileModel", "mobileOsVersion",
            "newMobileDeviceId", "nonce", "ostype", "pass_effective_num",
            "pass_total", "record_file", "record_img", "record_str",
            "run_check_res", "run_face_verify_id", "school_id", "start_time",
            "step_info", "step_num", "student_num", "term_id", "timestamp",
            "token", "uid", "used_time", "version", "version_name"};

    /** 收尾发出去的键集（去掉 sign）—— 给离线测试用来断言，别在生产路径调 */
    public java.util.TreeSet<String> stopKeysForTest(Map<String, String> extra)
            throws Exception {
        java.util.Set<String> keep = new java.util.HashSet<>(
                java.util.Arrays.asList(STOP_REAL_KEYS));
        TreeMap<String, String> p = params(extra, true, true, keep);
        p.remove("sign");
        return new java.util.TreeSet<>(p.keySet());
    }

    /**
     * 组装带签名的参数表。
     *
     * @param keepOnly 非空时，**只保留这些键**（白名单）。用于 stopRunV278 ——
     *   签名算的是整个参数表，而 ident 里带着 `access_token` / `refresh_token`
     *   （真机从来不在这条接口上发它们）。多发两个键就意味着**签名口径与真机不同**，
     *   所以收尾这一枪按真机基线做白名单裁剪。
     */
    private TreeMap<String, String> params(Map<String, String> extra, boolean v2,
                                           boolean withLpi0,
                                           java.util.Set<String> keepOnly) throws Exception {
        TreeMap<String, String> p = new TreeMap<>(ident);
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        p.put("timestamp", ts);
        p.put("nonce", String.valueOf(100000 + rnd.nextInt(900000)));
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet())
            p.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        p.remove("sign");
        // ★★★ 2026-10-06 真机成功基线（Reqable id=5252 / record_id=364498）实证：
        //   真机**从头到尾发的都是 `lpi1`，从来没发过 `lpi0`**。三个「跑步中」接口
        //   （beforeRunV260 / setRunLocationRecord / getOssSts）上的 lpi1 是
        //   Base64(AES(umid + md5hex(timestamp)))，密文 80 字节 → Base64 108 字符，
        //   没有折行。我们以前把同一个令牌挂在 `lpi0` 这个名字下面发——
        //   服务端按 `lpi1` 取值，取不到，于是风控那一路等于**完全没上报**。
        if (withLpi0) p.put("lpi1", makeLpi1(ts));
        // ★ 白名单裁剪必须放在**算 sign 之前** —— 否则签名覆盖的键集与实际发出的不一致。
        if (keepOnly != null) p.keySet().retainAll(keepOnly);
        p.put("sign", v2 ? signV2(p) : sign(p));
        return p;
    }

    /**
     * 调用接口
     * @param mode 0=明文体/签名A  1=明文体/签名B  2=加密PARAMS/签名B  3=加密NEW_GAME/签名A
     */
    public JSONObject call(String path, Map<String, String> extra, int mode) {
        return call(path, extra, mode, false, true);
    }

    /**
     * ★ 带风控令牌的调用。
     * 真机只有这三个「跑步中」的接口带 lpi0：
     *   Run2/beforeRunV260、Run/setRunLocationRecord、report/getOssSts
     * （不带 lpi0 的接口一律用签名A，带 lpi0 的一律用签名B —— 实测 8/8 吻合）
     */
    public JSONObject call(String path, Map<String, String> extra, int mode, boolean withLpi0) {
        return call(path, extra, mode, withLpi0, true, null);
    }

    /**
     * ★ 白名单版调用 —— 只发 keepOnly 里列出的键（连 ident 里的多余身份参数一起裁掉）。
     * 用于 `Run/stopRunV278`：真机那条请求只有 37 个键，而 ident 里还带着
     * `access_token` / `refresh_token`，多发会让签名口径与真机不同。
     */
    public JSONObject call(String path, Map<String, String> extra, int mode,
                           boolean withLpi0, java.util.Set<String> keepOnly) {
        return call(path, extra, mode, withLpi0, true, keepOnly);
    }

    private JSONObject call(String path, Map<String, String> extra, int mode,
                            boolean withLpi0, boolean allowRefresh) {
        return call(path, extra, mode, withLpi0, allowRefresh, null);
    }

    private JSONObject call(String path, Map<String, String> extra, int mode,
                            boolean withLpi0, boolean allowRefresh,
                            java.util.Set<String> keepOnly) {
        try {
            TreeMap<String, String> p = params(extra, mode == 1 || mode == 2, withLpi0, keepOnly);
            String body;
            if (mode == 0 || mode == 1) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : p.entrySet()) {
                    if (sb.length() > 0) sb.append('&');
                    // ★ 必须 URL 编码：lpi0 里含 + / = 和换行，裸拼会直接被服务端截断
                    sb.append(urlEnc(e.getKey())).append('=').append(urlEnc(e.getValue()));
                }
                body = sb.toString();
            } else {
                String k = mode == 2 ? REQ_KEY : NEW_KEY;
                String iv = mode == 2 ? REQ_IV : NEW_IV;
                JSONObject js = new JSONObject();
                for (Map.Entry<String, String> e : p.entrySet()) js.put(e.getKey(), e.getValue());
                byte[] enc = aes(Cipher.ENCRYPT_MODE, js.toString().getBytes("UTF-8"), k, iv);
                body = "key=" + URLEncoder.encode(b64(enc), "UTF-8");
            }
            JSONObject r = new JSONObject(post(path, body, 25));
            if (mode == 3 && r.optInt("is_encrypt") == 1 && r.opt("data") instanceof String) {
                try {
                    String pt = new String(aes(Cipher.DECRYPT_MODE, unb64(r.getString("data")),
                            NEW_KEY, NEW_IV), "UTF-8");
                    r.put("data", new JSONObject(pt));
                } catch (Exception ignore) { }
            } else {
                decryptResponse(r);
            }
            // ★ 令牌失效 -> 自动续期**最多一次**
            //   101 = 登录信息失效（拿到的 token 已经过期）
            //   102 = 该账号已在其他手机登录（App 又续期了一次，把我们顶掉）
            //   ★ 102 基本救不回来（refresh_token 也是 102），所以：
            //     ① 立刻把 sessionDead 置位，让 run() 中止本次跑步；
            //     ② 整个会话只试一次续期，不做无谓的狂刷。
            int st = r.optInt("status");
            if (st == 101 || st == 102) {
                sessionDead = true;
                sessionDeadStatus = st;
            }
            if (allowRefresh && (st == 101 || st == 102)
                    && !refreshTried && ident.containsKey("refresh_token")) {
                refreshTried = true;
                log.log("      [令牌失效 " + st + "] 尝试用 refresh_token 自动续期（只试这一次）…");
                JSONObject rr = refreshToken();
                if (rr != null && rr.optInt("status") == 1) {
                    log.log("      [续期成功] 新 token=" + shortTok(ident.get("token")));
                    sessionDead = false;
                    sessionDeadStatus = 0;
                    return call(path, extra, mode, withLpi0, false, keepOnly);
                }
                log.log("      [续期失败] " + trim(rr == null ? "null" : rr.toString(), 140));
            }
            return r;
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try { o.put("_err", String.valueOf(e)); } catch (Exception ignore) { }
            return o;
        }
    }

    /** Login/RefreshToken —— 加密 PARAMS_NEW_GAME + 签名B */
    public JSONObject refreshToken() {
        String at = ident.get("token");
        String rt = ident.get("refresh_token");
        if (at == null || rt == null) return null;
        TreeMap<String, String> ex = new TreeMap<>();
        ex.put("access_token", at);
        ex.put("token", at);
        ex.put("refresh_token", rt);
        JSONObject r = call("Login/RefreshToken", ex, 2, false, false);
        if (r.optInt("status") == 1) {
            JSONObject d = r.optJSONObject("data");
            if (d != null) {
                String nt = d.optString("access_token", at);
                ident.put("token", nt);
                ident.put("access_token", nt);
                String nrt = d.optString("refresh_token", "");
                if (nrt.length() > 0) ident.put("refresh_token", nrt);
                String uid = d.optString("uid", "");
                if (uid.length() > 0) ident.put("uid", uid);
            }
        }
        return r;
    }

    public static void decryptResponse(JSONObject r) {
        if (r == null) return;
        if (r.optInt("is_encrypt") == 1 && r.opt("data") instanceof String) {
            try {
                String pt = new String(aes(Cipher.DECRYPT_MODE, unb64(r.getString("data")),
                        NEW_KEY, NEW_IV), "UTF-8");
                try { r.put("data", new JSONObject(pt)); } catch (Exception e) { r.put("data", pt); }
            } catch (Exception e) {
                try { r.put("_decrypt_error", String.valueOf(e)); } catch (Exception ignore) { }
            }
        }
    }

    // ==================================================================
    //  OSS 上传（Signature V1 / HMAC-SHA1）
    // ==================================================================
    private static String hmacSha1B64(String key, String data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA1");
        m.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"));
        return Base64.encodeToString(m.doFinal(data.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private static String md5B64(byte[] d) throws Exception {
        return Base64.encodeToString(md5(d), Base64.NO_WRAP);
    }

    public int ossPut(JSONObject sts, String key, byte[] data) {
        return ossPut(sts, key, data, "application/octet-stream");
    }

    public int ossPut(JSONObject sts, String key, byte[] data, String ctype) {
        try {
            String bucket = sts.optString("bucket", "lptiyu-data");
            String ak = sts.getString("AccessKeyId");
            String sk = sts.getString("AccessKeySecret");
            String tok = sts.getString("SecurityToken");
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            String date = f.format(new Date());
            String contentMd5 = md5B64(data);
            String canon = "PUT\n" + contentMd5 + "\n" + ctype + "\n" + date
                    + "\nx-oss-security-token:" + tok + "\n/" + bucket + "/" + key;
            String sig = hmacSha1B64(sk, canon);
            String url = "https://" + bucket + ".oss-cn-hangzhou.aliyuncs.com/"
                    + uriEncodePath(key);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setConnectTimeout(40000);
            c.setReadTimeout(40000);
            c.setRequestProperty("Date", date);
            c.setRequestProperty("Content-Type", ctype);
            c.setRequestProperty("Content-MD5", contentMd5);
            c.setRequestProperty("Authorization", "OSS " + ak + ":" + sig);
            c.setRequestProperty("x-oss-security-token", tok);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            return code;
        } catch (Exception e) {
            log.log("      OSS 上传异常: " + e);
            return -1;
        }
    }

    /** 把流读干净（短读会继续读，直到 -1）。返回 null 只代表抛异常。 */
    static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int r;
        while ((r = in.read(b)) > 0) bo.write(b, 0, r);
        return bo.toByteArray();
    }

    /**
     * 把文件**整份**读进来；读不满就返回 null —— 绝不交出带 0x00 尾巴的残次品。
     *
     * ★ 为什么必须单独抽出来（2026-10-08 复盘）：
     *   `doFaceVerify` / `faceProbe` 原来各自手写了一段
     *       byte[] b = new byte[(int) f.length()];
     *       while (off &lt; b.length &amp;&amp; (r = in.read(b, off, …)) &gt; 0) off += r;
     *   只要 `read()` 提前返回 0 / -1（文件正在被改写、来源是云盘、
     *   磁盘写满、被别的进程截断），`off` 就停在半路，
     *   而那个数组**剩下的位置全是 0x00** —— 我们于是把
     *   「前半张真照片 + 后半段 NUL」当成一张完整 JPEG 传上 OSS。
     *
     *   这种残次品的头两字节仍然是 FF D8，所以旧自检（只查头两字节）放行；
     *   服务端一解码就抛异常，回的就是
     *       {"status":0,"info":"人脸验证错误，请稍后重试！"}
     *   —— 和 docs/29 里「1×1 像素」「纯文本」得到的那句话**一模一样**。
     *
     *   所以宁可在本地返 null（这一枪干脆不发），也不要让服务端替我们发现。
     */
    static byte[] readWhole(java.io.File f) {
        if (f == null || !f.exists()) return null;
        long n = f.length();
        if (n <= 0 || n > 64L * 1024 * 1024) return null;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] b = readAll(in);
            return b.length == n ? b : null;      // ★ 短读 ⇒ 这份文件不完整
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 传完立刻**把对象读回来逐字节比对** —— 回答「OSS 上躺着的到底是不是我们那串字节」。
     *
     * ★ 为什么值得多花一次往返：
     *   用户 2026-10-08 问的是「是不是人脸照片传输的时候出错」。
     *   我们当时的回答只有「PUT 带了 Content-MD5，OSS 校验过，所以字节没坏」——
     *   这话只证明了**写请求被受理**，证明不了**随后服务端读到的是同一串字节**
     *   （凭证过期、对象被覆盖、分片没合并、前缀写错，都在这条链上）。
     *
     *   把对象读回来比一次，「我们发错了东西」和「服务端那头抽风」就再也没法互相伪装
     *   —— 这正是 docs/29 那张实测表逼出来的分界线（服务端说「解码不了」时，
     *   到底是它的错还是我们的错，只有这一步能当场分清）。
     *
     * GET 的 V1 签名与 PUT 同规矩，只是 verb 换成 GET、Content-MD5 / Content-Type 留空：
     *   StringToSign = "GET\n\n\n" + Date + "\nx-oss-security-token:…\n/{bucket}/{key}"
     *
     * @return "" 表示读回来与本地**逐字节一致**；否则是一句人话原因
     *         （其中 "凭证没有读权限" 属正常情况，不影响上传本身）
     */
    public String ossGetBack(JSONObject sts, String key, byte[] expect) {
        try {
            String bucket = sts.optString("bucket", "lptiyu-data");
            String ak = sts.getString("AccessKeyId");
            String sk = sts.getString("AccessKeySecret");
            String tok = sts.getString("SecurityToken");
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            String date = f.format(new Date());
            String canon = "GET\n\n\n" + date
                    + "\nx-oss-security-token:" + tok + "\n/" + bucket + "/" + key;
            String sig = hmacSha1B64(sk, canon);
            String url = "https://" + bucket + ".oss-cn-hangzhou.aliyuncs.com/" + uriEncodePath(key);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Date", date);
            c.setRequestProperty("Authorization", "OSS " + ak + ":" + sig);
            c.setRequestProperty("x-oss-security-token", tok);
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return "读回 HTTP " + code + "（STS 常常只给写权限，这属正常，不代表上传失败）";
            }
            byte[] got = readAll(c.getInputStream());
            c.disconnect();
            if (got.length != expect.length) {
                return "读回来 " + got.length + " 字节，本地是 " + expect.length
                        + " 字节 —— OSS 上那份**不是**我们发的这份";
            }
            if (!md5B64(got).equals(md5B64(expect))) {
                return "字节数一样（" + got.length + "）但 MD5 不同 —— 内容被改过";
            }
            return "";
        } catch (Throwable t) {
            return "读回异常：" + t;
        }
    }

    /**
     * 现取一份 OSS STS，并把服务端给的过期时间打出来（排障用）。
     *
     * ★ 为什么要单独抽出来：2026-10-06 14:04 那一跑，我在跑到 569 m（13:48:49）
     *   取了一份 STS 做人脸核验，收尾上传时（14:04:52）**复用了同一份** —— 隔了
     *   16 分钟，OSS 直接回 403，整单没成。真机的做法是"要用的时候现取"。
     */
    public JSONObject fetchSts(String why) {
        // ★★★ 2026-10-08 加固（用户报「人脸识别模块上传不成功」）。
        //
        //   现场：用户手机上 getOssSts 直接回 {"status":0,"info":"系统错误"}，
        //   人脸那条链（取凭证 → 传 OSS → Run2/faceVerify）第一步就断了。
        //   用户说「前一个版本没问题」，所以我把 base.apk.1（v1.0）和当前 APK 都
        //   dexdump 出来做了**逐方法字节码对比**（work/dexdiff.py）：
        //     · fetchSts 的调用 `call("report/getOssSts", null, 2, true)` **完全一致**
        //     · params / makeLpi1 / faceProbe / doFaceVerify / ossPut **全部零差异**
        //   结论：这不是我们算错了请求，而是服务端对这个「带风控令牌」的组合
        //   条件性/偶发地拒绝。所以这里加一条**降级链**，让它自己绕过去：
        //     第 1 枪  真机口径：加密 + 签名B + lpi1
        //     第 2 枪  去掉 lpi1（docs/08 实证：这类接口 lpi0 缺省也能通过）
        //     第 3 枪  原样重试（服务端抖动）
        //   每一枪的原始响应都打进日志 —— 下次一眼就能看出是哪一层被拒。
        JSONObject last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean withLpi = (attempt != 1);
            try {
                JSONObject r = call("report/getOssSts", null, 2, withLpi);
                last = r;
                JSONObject d = r.optJSONObject("data");
                if (d != null && d.optString("SecurityToken").length() > 0) {
                    if (attempt > 0) {
                        log.log("      [OSS 凭证] 第 " + (attempt + 1) + " 枪成功（"
                                + (withLpi ? "原样重试" : "不带 lpi1") + "）");
                    }
                    stsFetchedAt = System.currentTimeMillis();
                    String exp = d.optString("Expiration", "");
                    log.log("      OSS 凭证（" + why + "）已取，服务端标称有效期至 " + exp
                            + (exp.length() == 0 ? "（没给 Expiration，只能当短命凭证用）" : ""));
                    return d;
                }
                log.log("      !! getOssSts(" + why + ") 第 " + (attempt + 1) + " 枪"
                        + (withLpi ? "（带 lpi1）" : "（不带 lpi1）")
                        + "没拿到凭证：" + trim(r.toString(), 200));
                int st = r.optInt("status");
                if (st == 101 || st == 102) {          // 令牌废了，再试也没用
                    log.log("      ⚠ 令牌已失效（status=" + st + "）—— 不再重试，先重新提取身份。");
                    break;
                }
            } catch (Throwable t) {
                log.log("      !! getOssSts(" + why + ") 第 " + (attempt + 1) + " 枪异常：" + t);
            }
            if (attempt < 2) {
                try { Thread.sleep(attempt == 0 ? 700 : 1500); } catch (InterruptedException ignore) { }
            }
        }
        if (last != null) {
            log.log("      ⚠ OSS 凭证三枪都没拿到（服务端不给）。人脸这一步只能跳过，"
                    + "**不影响跑步与计分**（服务端只在结算时看有没有 run_face_verify_id）。");
        }
        return null;
    }

    /**
     * 用已取好的凭证上传；**只有 403 才重新取一份重试一次**。
     * （真机一次跑步只取 1 次 getOssSts；我们别为了保险每段都取一遍，取多了反而显眼。）
     */
    public int ossPutRetry(JSONObject sts, String key, byte[] data, String ctype) {
        int code = ossPut(sts, key, data, ctype);
        if (code == 403) {
            long age = (System.currentTimeMillis() - stsFetchedAt) / 1000;
            log.log("      ⚠ OSS 403（这份凭证已经用了 " + age + " 秒）—— 重新取凭证再传一次");
            JSONObject sts2 = fetchSts("403 重试");
            if (sts2 != null) code = ossPut(sts2, key, data, ctype);
        }
        return code;
    }

    /** 最近一次取 STS 的时间（毫秒），用来算凭证用了多久 */
    public volatile long stsFetchedAt = 0;

    private static String uriEncodePath(String p) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    /** 轨迹打包：AES(new_game) -> Base64 -> 每行 76 字符（与 App 完全一致） */
    public static byte[] packRecord(JSONArray traj) throws Exception {
        return packRecord(traj.toString());
    }

    /** 同上，但直接吃 JSON 文本（buildTrackJson 的产物） */
    public static byte[] packRecord(String json) throws Exception {
        byte[] enc = aes(Cipher.ENCRYPT_MODE, json.getBytes("UTF-8"), NEW_KEY, NEW_IV);
        String s = b64(enc);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 76) {
            sb.append(s, i, Math.min(s.length(), i + 76)).append('\n');
        }
        return sb.toString().getBytes("UTF-8");
    }

    // ==================================================================
    //  ★★★ 轨迹序列化 —— 逐条对齐真机原件
    //  （work/tracks_plain/*.RESPONSE_CBC.txt + 金样本 work/today/track_1851.bin）
    //
    //  真机一个点的字段顺序固定是：o, a, [p], s, [b, c], l。三点关键：
    //   ① o / a / s 全是 **float32** —— 真机原文 114.6020736694336、2.930000066757202
    //      就是 float 的十进制展开。我们以前用 double 写 114.6020737，格式对不上。
    //   ② `b`/`c` **每满 1 公里一条**（b = 该段里程 km，c = 该段秒数），末尾再补一条
    //      收尾段。真机实测 2.53 km 的轨迹恰好 3 条（1.0034 / 1.0044 / 0.5193），
    //      而且 **Σb == 上报的 distance**（2.527 ≈ 2.53）。我们以前只在最后一个点
    //      写一条，还把总里程填进 b —— 段结构整个是错的。
    //   ③ `p` 只出现在**轨迹最后一个 GPS 点**上。金样本（2026-09-30 18:51 校园跑，
    //      打卡成功）141 个点里就 1 个 p，落在 len-2；3 份长轨迹各 2 个，也都含 len-2。
    //      docs/13 的「p:1 = 打卡点」被这份金样本证伪（p 点离打到的 28988 有两百多米）。
    //
    //  ★ 这里手工拼字符串而不用 org.json：一来字段顺序完全可控（真机就是这个顺序），
    //    二来它能脱离 Android 在桌面上单测 —— work/looptest/TrackTest.java 就是验它的。
    // ==================================================================

    /** float32 的十进制展开（真机就是这么存的，照抄精度） */
    public static String f32s(double v) { return String.valueOf((double) (float) v); }

    /**
     * @param samples {lat, lon, 速度 m/s, 累计米, 绝对秒} —— run() 里逐拍攒下来的 GPS 采样
     * @param totalM  全程米数（= Σb×1000，也是上报的 distance）
     * @param endLL   收尾点坐标 {lat, lon}；null 则用最后一个采样点
     */
    public static String buildTrackJson(List<double[]> samples, double totalM, double[] endLL) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append('[');
        if (samples == null || samples.isEmpty()) return sb.append(']').toString();
        double prevMarkM = 0, firstT = samples.get(0)[4], prevMarkT = firstT;
        int marks = 0;
        for (int i = 0; i < samples.size(); i++) {
            double[] sp = samples.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"o\":").append(f32s(sp[1]))
              .append(",\"a\":").append(f32s(sp[0]));
            if (i == samples.size() - 1) sb.append(",\"p\":1");   // ★ 金样本规则
            sb.append(",\"s\":").append(f32s(sp[2]));
            // 每满 1 公里切一段；离结尾不足 25 米就先不切，留给收尾段
            // （真机每段的 b 都略大于 1 而不是正好 1，就是这个道理）
            if (sp[3] >= (marks + 1) * 1000.0 && (totalM - sp[3]) > 25.0) {
                sb.append(",\"b\":").append(f32s((sp[3] - prevMarkM) / 1000.0))
                  .append(",\"c\":").append((long) Math.max(1, Math.round(sp[4] - prevMarkT)));
                prevMarkM = sp[3];
                prevMarkT = sp[4];
                marks++;
            }
            sb.append(",\"l\":1}");
        }
        double lastT = samples.get(samples.size() - 1)[4];
        double[] e = endLL != null ? endLL
                : new double[]{samples.get(samples.size() - 1)[0],
                               samples.get(samples.size() - 1)[1]};
        // 收尾汇总点：只带最后一段的 b/c，速度 ~0（真机是 0.009999999776482582）
        sb.append(",{\"o\":").append(f32s(e[1]))
          .append(",\"a\":").append(f32s(e[0]))
          .append(",\"s\":").append(f32s(0.01))
          .append(",\"b\":").append(f32s((totalM - prevMarkM) / 1000.0))
          .append(",\"c\":").append((long) Math.round(Math.max(1, lastT - prevMarkT)))
          .append(",\"l\":1}]");
        return sb.toString();
    }

    /** 轨迹里 b/c 分段的条数（= 满整公里的条数 + 1 条收尾），给日志用 */
    public static int trackMarks(List<double[]> samples, double totalM) {
        if (samples == null) return 0;
        int marks = 0;
        for (double[] sp : samples) {
            if (sp[3] >= (marks + 1) * 1000.0 && (totalM - sp[3]) > 25.0) marks++;
        }
        return marks + 1;
    }

    /**
     * 陀螺仪 / 加速度文件（type=8）
     * ★ 来自 App 真实上传文件的实证格式 —— **明文 JSON，不加密**：
     *   [{"n":"114.602066","a":"36.658878","t":1790681601464,
     *     "ax":"-0.00","ay":"0.00","az":"0.00","gx":"-2.07","gy":"-0.08","gz":"9.36"}, ...]
     *   n=经度 a=纬度（字符串）· t=毫秒时间戳 · 采样间隔实测 60~72ms · 偶发空档
     */
    public byte[] packSensor(JSONArray traj, long t0Sec, long usedSec) throws Exception {
        JSONArray out = new JSONArray();
        int n = traj.length();
        if (n < 2) return "[]".getBytes("UTF-8");
        double gx = -2.07, gy = -0.09, gz = 9.36;
        long tMs = t0Sec * 1000L, endMs = tMs + usedSec * 1000L;
        int[] gaps = {59, 60, 61, 66, 67, 68, 69, 70, 71, 72};
        int i = 0;
        while (tMs < endMs) {
            int k = (int) Math.min(n - 1, (tMs - t0Sec * 1000L) * (long) n
                    / Math.max(1L, usedSec * 1000L));
            JSONObject tp = traj.getJSONObject(k);
            gx = clamp(gx + (rnd.nextDouble() - 0.5) * 1.2, -9.0, 9.0);
            gy = clamp(gy + (rnd.nextDouble() - 0.5) * 1.2, -9.0, 9.0);
            gz = clamp(gz + (rnd.nextDouble() - 0.5) * 1.2, -9.0, 12.0);
            JSONObject o = new JSONObject();
            o.put("n", String.format(Locale.US, "%.6f", tp.optDouble("o", 0)));
            o.put("a", String.format(Locale.US, "%.6f", tp.optDouble("a", 0)));
            o.put("t", tMs);
            o.put("ax", String.format(Locale.US, "%.2f", (rnd.nextDouble() - 0.5) * 0.4));
            o.put("ay", String.format(Locale.US, "%.2f", (rnd.nextDouble() - 0.5) * 0.4));
            o.put("az", String.format(Locale.US, "%.2f", (rnd.nextDouble() - 0.5) * 0.4));
            o.put("gx", String.format(Locale.US, "%.2f", gx));
            o.put("gy", String.format(Locale.US, "%.2f", gy));
            o.put("gz", String.format(Locale.US, "%.2f", gz));
            out.put(o);
            i++;
            tMs += gaps[rnd.nextInt(gaps.length)];
            if (i % 97 == 0) tMs += 800 + rnd.nextInt(3200);
        }
        return out.toString().getBytes("UTF-8");
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * ★ 每分钟步频：App 实测 {"interval":60,"list":[138,117,...,148]}
     *   条数 = 跑步分钟数；总步数 ≈ 距离 / 步长（跑步约 1.55 m/步）
     */
    public String perStep(long usedSec, double distanceM) {
        int minutes = Math.max(1, (int) Math.round(usedSec / 60.0));
        int totalSteps = Math.max(50, (int) (distanceM / 1.55));
        double base = totalSteps / (double) minutes;
        StringBuilder sb = new StringBuilder("{\"interval\":60,\"list\":[");
        long sum = 0;
        for (int i = 0; i < minutes; i++) {
            int v = (int) Math.max(60, base * (0.88 + rnd.nextDouble() * 0.24));
            sum += v;
            if (i > 0) sb.append(',');
            sb.append(v);
        }
        return sb.append("]}").toString();
    }

    // ==================================================================
    //  身份解析（支持粘贴 JSON / query / 整段请求体）
    // ==================================================================
    private static final String[] ID_KEYS = {
            "uid", "user_id", "token", "access_token", "refresh_token", "school_id",
            "student_num", "card_id", "duid", "psid", "mobileDeviceId", "mobileModel",
            "mobileOsVersion", "ostype", "version", "version_name"};

    public int parseIdentity(String raw) {
        if (raw == null) return 0;
        String t = raw.replace("\ufeff", "").trim();
        int found = 0;
        // 1) 整段请求体 / query
        for (String seg : t.split("[&\\n\\r,]")) {
            int i = seg.indexOf('=');
            if (i < 0) i = seg.indexOf(':');
            if (i <= 0) continue;
            String k = seg.substring(0, i).replace("\"", "").replace("{", "").trim();
            String v = seg.substring(i + 1).replace("\"", "").replace("}", "").trim();
            if (v.length() == 0) continue;
            for (String kk : ID_KEYS) if (kk.equals(k)) { ident.put(kk, v); found++; break; }
        }
        // 2) 兼容 JSON
        if (found == 0) {
            try {
                JSONObject o = new JSONObject(t);
                for (String kk : ID_KEYS)
                    if (o.has(kk)) { ident.put(kk, o.getString(kk)); found++; }
            } catch (Exception ignore) { }
        }
        // 3) 正则兜底
        if (!ident.containsKey("token")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("token[\"']?\\s*[:=]\\s*[\"']?([0-9A-Fa-f]{16,})").matcher(t);
            if (m.find()) { ident.put("token", m.group(1)); found++; }
        }
        if (!ident.containsKey("uid")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("uid[\"']?\\s*[:=]\\s*[\"']?(\\d+)").matcher(t);
            if (m.find()) { ident.put("uid", m.group(1)); found++; }
        }
        if (ident.containsKey("access_token") && !ident.containsKey("token"))
            ident.put("token", ident.get("access_token"));
        if (ident.containsKey("token") && !ident.containsKey("access_token"))
            ident.put("access_token", ident.get("token"));
        return found;
    }

    // ==================================================================
    //  完整跑步流程
    // ==================================================================
    public static class Result {
        public boolean ok;
        public String recordId = "";
        public String distance = "";
        public String usedTime = "";
        public String detail = "";
        public String message = "";
        /**
         * ★ 2026-10-10：这次失败**界面已经单独弹过窗了**，别再弹第二个。
         *
         * <p>目前只有一种情况置位：起跑前人脸核验没过（走 {@link Log#onFacePre}）。
         * 那里弹的是"人脸专用"的窗（带 status 和原话，还能判读是服务端抽风还是
         * 照片不行），比这里通用那把「这一场没成功」有用得多。
         */
        public boolean reported = false;
    }

    /**
     * @param targetKm  目标里程（km）
     * @param speed     配速区间下限对应的速度（m/s，数值小 = 慢）
     * @param speed2    配速区间上限对应的速度（m/s，数值大 = 快）
     *                  跑步时每一段都在 [speed, speed2] 里随机取 —— 配速自然起伏
     */
    public Result run(double targetKm, double speed, double speed2,
                      double intervalSec, double lat0, double lon0) {
        stopped = false;
        faceMiddleDone = false;      // ★ 每场跑步重置「中途已核验」标志
        faceOk = false;              // ★ 每场跑步重置「本场真的验过脸」标志
        /* ★★★ 2026-10-10：还要重置身份证那张「上一次核验 id」。
         * 以前漏了这一句 —— 上一场遗留的 run_face_verify_id 会被当成"这条链的开头"
         * 带去服务端，而那个 id 属于**另一场跑步**。中途核验 type=2 最吃这一口。 */
        lastFaceId = "";
        lastFaceStatus = -1;
        lastFaceInfo = "";
        faceVerifyId = "0";
        resetNetLog();               // ★ 清空报文记录，保证 netlog.txt 只装本场
        Result res = new Result();
        try {
            // ---- 1) 服务器时间与位置签名串
            log.log("[1/6] 取服务器时间与位置签名串 …");
            JSONObject ts = call("Run/getTimestampV278", null, 0);
            if (ts.optInt("status") != 1) {
                res.message = "取时间失败: " + trim(ts.toString(), 200);
                log.log("      !! " + res.message);
                return res;
            }
            String locStr = ts.optJSONObject("data") != null
                    ? ts.optJSONObject("data").optString("str", "") : "";
            // ★ 服务端下发的时间戳 —— 真机的 start_time 用的就是这个值（不是本地时钟）。
            //   取 str 那一次与收尾上报的 start_time 是**同一个数**（id=5252 实证）。
            final String srvStartTs = ts.optJSONObject("data") != null
                    ? ts.optJSONObject("data").optString("timestamp", "") : "";
            // ★ start_time 要用的那个值 —— 收尾时 fin 里必须带上它。
            //   （2026-10-07 事故：改 t0 取值时把 fin.put("start_time") 一起删掉了，
            //    结果服务端收不到开始时间 → 直接 {"status":100,"info":"上传数据失败"}。）
            srvStartTime = srvStartTs;
            log.log("      status=1  str=" + locStr + "  服务端 ts=" + srvStartTs);
            // ★★ str 是「位置签名串」，docs/04 §三 明确写着 setRunLocationRecord **需回传**它，
            //    而返回里还带 location_septal=60 —— 有效期 60 秒。
            //    之前这里取到 str 就丢掉了，从没回传过。用数组是为了能在下面的循环里刷新。
            final String[] locRef = {locStr};

            // ---- 2) 跑区与打卡点
            log.log("[2/6] 取跑区与打卡点 …");
            JSONObject az = call("Run/GetAllZone", null, 0);
            JSONArray zl = az.optJSONObject("data") != null
                    ? az.optJSONObject("data").optJSONArray("zone_list") : null;
            String zoneId = null, zoneName = "";
            if (zl != null) {
                for (int i = 0; i < zl.length(); i++) {
                    JSONObject z = zl.getJSONObject(i);
                    if (z.optString("school_id").equals(ident.get("school_id"))) {
                        zoneId = z.optString("id");
                        zoneName = z.optString("name");
                        break;
                    }
                }
            }
            if (zoneId == null && zl != null && zl.length() > 0) {
                zoneId = zl.getJSONObject(0).optString("id");
                zoneName = zl.getJSONObject(0).optString("name");
            }
            if (zoneId == null) {
                res.message = "未找到跑区";
                log.log("      !! " + res.message);
                return res;
            }
            java.util.TreeMap<String, String> q = new java.util.TreeMap<>();
            q.put("zone_id", zoneId);
            JSONObject zp = call("Run/GetZonePoints", q, 0);
            JSONArray pts = zp.optJSONObject("data") != null
                    ? zp.optJSONObject("data").optJSONArray("point_list") : null;
            List<double[]> all = new ArrayList<>();
            List<String> allIds = new ArrayList<>();
            // ★ 跑区**总**打卡点数 —— 收尾时填 pass_total 用。
            //   真机实测 263（我校跑区全部打卡点），而我们以前填的是 keyPts.size()=2
            //   （"本次抽中的点数"）。这两个语义完全不同，服务端拿到 2 会以为跑区只有 2 个点。
            int zonePointTotal = 0;
            if (pts != null) {
                for (int i = 0; i < pts.length(); i++) {
                    JSONObject p = pts.getJSONObject(i);
                    String[] jw = p.optString("jingwei").split(",");
                    if (jw.length >= 2) {
                        try {
                            all.add(new double[]{Double.parseDouble(jw[0]), Double.parseDouble(jw[1])});
                            allIds.add(p.optString("point_id"));
                        } catch (Exception ignore) { }
                    }
                }
            }
            zonePointTotal = all.size();     // ★ 跑区总点数（真机实测 263）
            // ★★★ 打卡点怎么选 —— docs/15 定案：**手机自己抽的**，不是服务端分配的。
            //   真机算法在 selectPoints()（逐行复刻 ya/i0.f()）。这里先用「均匀随机」
            //   占个位给预览图用；等第 3 步 beforeRunV260 拿到规则参数后，再用真机
            //   算法**覆盖**一次，那次才是最终上报的分配对。
            List<double[]> cps = new ArrayList<>();
            List<String> cids = new ArrayList<>();
            int keyA = -1, keyB = -1;          // 「必过」两点在 all 里的下标（给街道版路线用）
            List<LogPt> keyPts = new ArrayList<>();   // ★ 最终分配对（上报用）
            if (all.size() >= 2) {
                int[] pair = randomRoute ? pickPairUniform(all.size(), rnd)
                        : new int[]{0, all.size() - 1};
                keyA = pair[0];
                keyB = pair[1];
                buildChain(all, allIds, keyA, keyB, cps, cids);
                log.log("      预选必过点：" + allIds.get(keyA) + " 和 " + allIds.get(keyB)
                        + "（相距 " + (int) dist(all.get(keyA), all.get(keyB))
                        + " 米）；另串 " + (cps.size() - 2) + " 个途经点，单程约 "
                        + (int) oneWayOf(cps) + " 米");
            }
            if (cps.size() < 2) {          // 极端情况兜底
                cps.clear();
                cids.clear();
                for (int i = 0; i < Math.min(2, all.size()); i++) {
                    cps.add(all.get(i));
                    cids.add(allIds.get(i));
                }
            }
            log.log("      跑区 " + zoneName + "(id=" + zoneId + ")，打卡点 " + (pts == null ? 0 : pts.length()));
            // ★ 把打卡点推给界面。
            //   ⚠ 这里**只更新打卡点，不推路线**（route 传 null）：
            //     ① cps 是「打卡点串起来的直连折线」，画出来是横穿校园的直线，
            //        正是用户说的「路线跳跃」；
            //     ② 真正的路线要等 buildLoop 沿街道 A* 出来，在下面 onPlan(all, rp.pts)。
            try { log.onPlan(all, null); } catch (Throwable ignore) { }

            // ---- 3) 前置校验
            log.log("[3/6] Run2/beforeRunV260 前置校验（加密 PARAMS + 签名B）…");
            JSONObject br = call("Run2/beforeRunV260", null, 2, true);
            log.log("      status=" + br.optInt("status"));

            // ★★★ 「自动签到」—— 真机进跑步页的顺序是 beforeRunV260 → My/getSignupPoints
            //   （抓包 work/cap01/raw：21:52:51 #13 beforeRunV260、#14 getSignupPoints，
            //     请求体 290 字节 = **只有公共参数、没有业务参数**）。
            //   它的响应是 {"status":0,"info":"已经自动签到过了"} —— 就是**签到**本身。
            //   我们一直没调过它；真机每次进跑步页都调。放在起跑前、非致命：失败只记日志。
            try {
                JSONObject su = call("My/getSignupPoints", null, 0);
                log.log("      My/getSignupPoints（自动签到）status=" + su.optInt("status")
                        + "  info=" + su.optString("info")
                        + (su.optInt("status") == 1 ? "  ★ 本次刚签到成功" : ""));
            } catch (Throwable t) {
                log.log("      ⚠ My/getSignupPoints 失败（不影响跑步）：" + t);
            }

            JSONObject bd = br.optJSONObject("data");
            if (bd != null) {
                String ti = bd.optString("term_id", "");
                if (ti.length() > 0) termId = ti;
                String rz = bd.optString("run_zone_id", "");
                if (rz.length() > 0 && !rz.equals(zoneId)) {
                    log.log("      注意: 服务端指定跑区 " + rz + "（本地取到 " + zoneId + "）");
                    zoneId = rz;
                }
                log.log("      authStatus=" + bd.optString("authStatus")
                        + "  term_id=" + termId
                        + "  累计 " + bd.optString("total_num") + " 次 / "
                        + bd.optString("total_distance") + " km");
                String tip = bd.optString("tips", "");
                if (tip.length() > 0) log.log("      tips: " + tip);

                // ★★★ 把服务端下发的规则参数真正读进来（以前这 103 个键我们只用了 8 个）。
                //   这些是服务端**核轨迹**时用的阈值，客户端自证清白要靠它们。
                gpsMaxDistance = bd.optInt("android_gps_max_distance", gpsMaxDistance);
                gpsPrecStart = bd.optInt("android_gps_start_precision", gpsPrecStart);
                gpsPrecProcess = bd.optInt("android_gps_process_precision", gpsPrecProcess);
                gpsPrecLog = bd.optInt("android_gps_log_precision", gpsPrecLog);
                freeRunStatus = bd.optString("freerun_status", "");
                captureType = bd.optString("capture_type", "");
                captureInterval = bd.optString("capture_interval", "");
                needFace = "1".equals(bd.optString("is_register_face", "0"))
                        || "1".equals(bd.optString("is_check_face", "0"))
                        || "1".equals(bd.optString("is_face", "0"));
                faceConfig = bd.optJSONObject("run_face_verify_config") != null
                        ? bd.optJSONObject("run_face_verify_config").toString() : "";
                zoneRect.clear();
                JSONArray zr0 = bd.optJSONArray("run_zone_latlng");
                if (zr0 != null) {
                    for (int i = 0; i < zr0.length(); i++) {
                        String[] jw = String.valueOf(zr0.opt(i)).split(",");
                        if (jw.length < 2) continue;
                        try {
                            zoneRect.add(new double[]{Double.parseDouble(jw[1]),
                                    Double.parseDouble(jw[0])});      // 存成 {lat, lon}
                        } catch (Exception ignore) { }
                    }
                }
                // ★ 把跑区多边形灌给路网与路线生成器：A* 直接在界内走，
                //   打卡点若压在边界外就拉回界内 —— is_running_area_valid 才能是 true。
                //   （真机数据里 28994 就超出北边界约 15 m，不处理必然被判界外。）
                try {
                    if (roadGrid != null) roadGrid.setZone(zoneRect);
                    RouteGen.setCampusZone(zoneRect);
                } catch (Throwable ignore) { }
                log.log("      规则：GPS 间距上限 " + gpsMaxDistance + " m；精度阈值 "
                        + gpsPrecStart + "/" + gpsPrecProcess + "/" + gpsPrecLog
                        + "；跑区多边形 " + zoneRect.size() + " 角；抓拍 " + captureType
                        + "/" + captureInterval + "；自由跑开关 " + freeRunStatus);
                if (needFace) {
                    log.log("      ⚠ 服务端要求人脸验证：is_face=" + bd.optString("is_face")
                            + "  config=" + faceConfig);
                    // ★★★ 2026-10-06 实测更正：以前这里写「本客户端没有相机人脸模块」——
                    //   现在有了（docs/28）：跑到 middle_distance 就用设置好的自拍去换
                    //   run_face_verify_id。服务端下发的正是这个：
                    //   {"middle_status":"1","middle_distance":"600","middle_num":"1",…}
                    //   （我们上一跑在 569 m 处核验，服务端返回「人脸验证成功！」）
                    JSONObject fc = bd.optJSONObject("run_face_verify_config");
                    if (fc != null) {
                        faceMiddleDistance = (int) parseIntSafe(fc.optString("middle_distance", ""));
                        int mn = (int) parseIntSafe(fc.optString("middle_num", ""));
                        if (mn > 0) faceMiddleNum = mn;
                        // ★ 三个开关照服务端说的来：start_status=1 → 起跑处要验一次；
                        //   middle_status=1 → 中途再验一次；stop_status=0 → 收尾不用。
                        //   （09-29 那条**计分**的真机记录正是 start=true, middle=true,
                        //     stop=false —— 和我们拿到的这套规则逐字对应。）
                        faceStartStatus = "1".equals(fc.optString("start_status", "1"));
                        faceMiddleStatus = "1".equals(fc.optString("middle_status", "1"));
                        faceStopStatus = "1".equals(fc.optString("stop_status", "0"));
                        log.log("      人脸核验规则：开始=" + (faceStartStatus ? "要" : "不要")
                                + "，中途=" + (faceMiddleStatus ? (faceMiddleNum + " 次，"
                                + "第 1 次在 " + faceMiddleDistance + " m 处") : "不要")
                                + "，收尾=" + (faceStopStatus ? "要" : "不要")
                                + "，超时=" + fc.optString("timeout_sec") + " s");
                    }
                }
            }

            // ---- 3.2) ★★★ 真正的选点：逐行复刻真机 ya/i0.f()（docs/15）
            //
            //   真相：打卡点是**手机在起跑那一刻自己抽的**，服务端只下发全部点位 + 规则。
            //   我校跑区 14 个点的 (type, point_type, is_online) 全是 (2,1,1)，没有必经点
            //   → A2 空 → n() 返回 null → 走「纯随机」分支 = 14 个里均匀抽 2 个
            //   （a1.b(0,14,2) + Collections.shuffle）。起跑点只影响两件**在本跑区不生效**
            //   的事（剔除 10 m 内的点、守卫第 2 近的点 ≤ 2 km），所以用「跑区质心附近」
            //   当仿真起跑点即可 —— 抽出来的分布与真机完全一致（scripts/rule_sim.py 已验证：
            //   91/91 种点对全可达、每点 14.31% vs 理论 14.29%）。
            //
            //   ★ 必须用 beforeRunV260 下发的 point_list 与 time_rule_arr，
            //     绝不能用 GetZonePoints 的（那份没有 type/point_type，也没规则）。
            StringBuilder selTrace = new StringBuilder();
            // ★ 仿真起跑点：跑区质心附近随机 0~300 米。
            //   两处都要用它 —— ① 真机选点算法要一个起跑坐标；② 闭环路线的起点/终点。
            //   所以提到 try 外面来算，保证两处用的是同一个位置。
            double clat = 0, clon = 0;
            for (double[] p : all) { clat += p[0]; clon += p[1]; }
            if (!all.isEmpty()) { clat /= all.size(); clon /= all.size(); }
            double sAng = rnd.nextDouble() * Math.PI * 2;
            double sRad = rnd.nextDouble() * 300.0;
            final double[] simStart = {
                    clat + (sRad * Math.cos(sAng)) / 111320.0,
                    clon + (sRad * Math.sin(sAng))
                            / (111320.0 * Math.cos(Math.toRadians(clat)))};
            try {
                if (bd != null && all.size() >= 2) {
                    double sLat = simStart[0], sLon = simStart[1];
                    List<LogPt> assigned = selectAssignedFromBeforeRun(bd, sLat, sLon, rnd, selTrace);
                    if (assigned != null && assigned.size() >= 2) {
                        int ia = allIds.indexOf(assigned.get(0).id);
                        int ib = allIds.indexOf(assigned.get(1).id);
                        if (ia >= 0 && ib >= 0 && ia != ib) {
                            keyA = ia;
                            keyB = ib;
                            keyPts = assigned;
                            buildChain(all, allIds, keyA, keyB, cps, cids);
                            log.log("      ★ 真机算法选点：" + assigned.get(0).tag() + "  +  "
                                    + assigned.get(1).tag() + "（相距 "
                                    + (int) dist(all.get(keyA), all.get(keyB)) + " 米）");
                            log.log("         " + selTrace + "仿真起跑点 "
                                    + String.format(Locale.US, "%.6f,%.6f", sLat, sLon));
                            // ★ 同前：只更新打卡点，不推直连折线，也不塞假 GPS 点
                            try { log.onPlan(all, null); }
                            catch (Throwable ignore) { }
                        } else {
                            log.log("      ⚠ 选点结果不在跑区点表里，沿用预选；" + selTrace);
                        }
                    } else {
                        log.log("      ⚠ 真机算法没选出点（" + selTrace + "），沿用预选");
                    }
                }
            } catch (Throwable t) {
                log.log("      !! 选点异常，沿用预选：" + t);
            }
            if (keyPts.size() < 2 && keyA >= 0 && keyB >= 0 && keyA != keyB) {
                // 兜底：真机算法没跑成，至少把预选的那一对按 LogPt 结构补齐，
                // 后面的「整对上报 + logPoints 固定 2 条」都依赖 keyPts 非空。
                List<LogPt> fb = new ArrayList<>();
                for (int idx : new int[]{keyA, keyB}) {
                    LogPt p = new LogPt();
                    p.id = allIds.get(idx);
                    p.lat = all.get(idx)[0];
                    p.lon = all.get(idx)[1];
                    p.type = 2;
                    p.pointType = 1;
                    fb.add(p);
                }
                keyPts = fb;
            }
            if (keyPts.size() >= 2) {
                List<double[]> keys = new ArrayList<>();
                List<String> kids = new ArrayList<>();
                for (LogPt p : keyPts) {
                    keys.add(new double[]{p.lat, p.lon});
                    kids.add(p.id);
                }
                try { log.onKeyPoints(keys, kids); } catch (Throwable ignore) { }
            }

            // ---- 3.5) ★★★ 选定跑区 —— 这才是真正的「开始跑步」，之前一直漏了这一步！
            //
            //   docs/04 §三 实测记录：`Run/setRunZone` =「选定跑区（= 开始跑步）」，
            //   参数名是 `run_zone_id`，而且**必须走明文 + 签名A**（加密通道反而报「参数错误」）。
            //
            //   漏掉它的后果（2026-09-30 13:24 那次实测）：
            //   服务端压根没有开局，后面每次 `Run/setRunLocationRecord` 虽然都回 status=1，
            //   但**打卡一个都不计入**。最终 stopRunV278 返回 log_num=0 / points=0，
            //   记录建出来了却不计分，理由是「打卡次数过少，未达到合格标准」。
            //   规则里 `point_num_online: 2` 说的就是这种「在线打卡」，必须是开着局报的。
            log.log("[3.5/6] Run/setRunZone 选定跑区 " + zoneId + "（明文 + 签名A = 真正的开始跑步）…");
            try {
                TreeMap<String, String> zx = new TreeMap<>();
                zx.put("run_zone_id", zoneId);
                JSONObject zr = call("Run/setRunZone", zx, 0);
                int zs = zr.optInt("status");
                String zi = zr.optString("info", "");
                log.log("      status=" + zs + (zi.length() > 0 ? "  " + zi : ""));
                if (zs != 1)
                    log.log("      ⚠ 选跑区没成功，本次打卡很可能一个都计不上！");
            } catch (Throwable t) {
                log.log("      !! setRunZone 异常：" + t);
            }

            // ---- 4) 轨迹上报
            // ★ 沿「样条 + 横向摆动 + 避湖」生成的路线走，不再是打卡点之间的直线。
            //   这样既像人跑的（弯弯曲曲），也不会穿湖。
            double vMin = Math.min(speed, speed2), vMax = Math.max(speed, speed2);
            if (vMax <= 0) { vMin = vMax = 2.5; }
            log.log("[4/6] 生成路线并逐点上报（明文体 + 签名B，配速 "
                    + (int) (1000 / vMax) + "~" + (int) (1000 / vMin) + " s/km）…");
            try {
                if (water == null || !water.isLoaded()) {
                    log.log("      ⚠ 水面数据缺失 —— 本次路线不避湖");
                } else {
                    log.log("      水面数据已加载，路线自动避湖");
                }
            } catch (Throwable ignore) { }
            RouteGen.Path rp = null;
            StringBuilder rnote = new StringBuilder();
            // ---- 首选：新版路线规划（手画路网上的随机游走，见 RoutePlan）
            //   ★ 打卡点要按 **point_id** 认，不能按序号 —— 序号是 GetZonePoints
            //     的返回顺序，换个跑区就全错位了。路网里存的就是 point_id。
            //   ★ 种子复用条件：服务端指派的就是预览那一对点。
            //     这时用同一个种子重生成，得到的是和地图上画的**逐点相同**的路线。
            if (routeNet != null && keyA >= 0 && keyB >= 0 && keyA < allIds.size() && keyB < allIds.size()) {
                String pidA = allIds.get(keyA), pidB = allIds.get(keyB);
                int ia = routeNet.indexOfPid(pidA), ib = routeNet.indexOfPid(pidB);
                if (ia >= 0 && ib >= 0 && ia != ib) {
                    int nA = routeNet.cpN[ia], nB = routeNet.cpN[ib];
                    boolean samePair = routeCpN != null && routeCpN.length >= 2
                            && ((routeCpN[0] == nA && routeCpN[1] == nB)
                             || (routeCpN[0] == nB && routeCpN[1] == nA));
                    // 预览时是 A→B 的方向；真跑这里也按同一个方向喂，才谈得上逐点相同
                    int uA = samePair ? routeCpN[0] : nA;
                    int uB = samePair ? routeCpN[1] : nB;
                    long seed = samePair && routeSeed != 0 ? routeSeed
                            : (rnd.nextLong() & 0x7FFFFFFFL);
                    RoutePlan.Opts o = new RoutePlan.Opts();
                    /* ★★★ 里程目标也要一起复用，光复用种子不够。
                     *   预览用的是**自动抽的**目标（Lfloor..Lceil 里按 lengthBias 抽），
                     *   而 planKm 是那条线**四舍五入到 0.1 km 之后**的长度 —— 两者不等。
                     *   若开跑时把 planKm 当 targetM 传进去，目标一变、游走就变，
                     *   同一对点同一种子也会得到另一条线（实测 2698.6 m / 2736 点
                     *   vs 2736.9 m / 2720 点）。
                     *   所以：**同一对点就原封不动重跑一遍**（种子 + 自动目标都不动），
                     *   得到逐点相同的一条线，"点开始跑步就跑这一条"才真的成立。
                     *   服务端换了一对点时，才退而求其次用 planKm 把里程大致对齐。 */
                    if (!samePair) o.targetM = targetKm * 1000.0;
                    long t0 = System.currentTimeMillis();
                    RoutePlan.Result rr = RoutePlan.generate(routeNet, uA, uB, seed, o);
                    long ms = System.currentTimeMillis() - t0;
                    if (rr.ok) {
                        rp = rr.path;
                        rnote.append(String.format(Locale.US,
                                "路网随机游走 起点街口#%d 掉头%d次 目标%.0fm 种子%s %dms",
                                rr.startNode, rr.backtracks, rr.targetM,
                                samePair ? "(复用预览)" : "(新抽)", ms));
                        log.log("      路网随机游走成功：" + rnote);
                        log.log(String.format(Locale.US,
                                "        打卡点 %s→%s = 路网序号 #%d/#%d（%s）",
                                pidA, pidB, uA, uB, samePair ? "与预览同一对 → 同一条线" : "服务端换了一对"));
                    } else {
                        log.log("      ⚠ 新版路线规划失败（" + rr.why + "），回落到旧版");
                    }
                } else {
                    log.log("      ⚠ 打卡点 " + pidA + "/" + pidB + " 不在本路网里，回落到旧版");
                }
            }
            if (rp == null && roadGrid != null && keyA >= 0 && keyB >= 0) {
                // 兜底：在校园可跑街道栅格上 A* 出一条**闭环**「只沿街道跑」的路线。
                //   真机三份长轨迹首末点相距 0 m / 4 m / 3.8 m —— 全是回到起点的环线；
                //   我们以前是 A→B 单程、首尾正好压在两个打卡点上，既不像人、也让
                //   打卡点落在轨迹的 idx 0 和最后一点（真机的 `p` 从不在 idx 0）。
                rp = RouteGen.buildLoop(roadGrid, all, keyA, keyB, simStart,
                        targetKm * 1000, rnd, rnote);
                if (rp != null) {
                    log.log("      路网闭环寻路成功：" + rnote);
                } else {
                    log.log("      ⚠ 路网闭环寻路失败，回落到样条路线");
                }
            }
            if (rp == null || rp.length < 50) {
                rp = RouteGen.build(cps, targetKm * 1000, water, rnd, randomRoute);
                rnote.setLength(0);
            }
            if (rp == null || rp.length < 50) {
                res.message = "路线生成失败（太短）";
                log.log("      !! " + res.message);
                return res;
            }
            // ★ 2026-10-08：服务端规则 min_distance = 2.00 km，短了这局就是白跑。
            //   RoutePlan 自己保证 ≥2.00 km（lenFloorFactor=1.10 留了抹圆的余量，
            //   越界还会换派生种子重试 8 次）；旧版把地板抬到 2.2 km。
            //   这里再兜一道：真出短路线就**当场中止**并说清原因，
            //   别让用户跑完才发现这一局不计分。
            if (rp.length < 2050) {
                res.message = String.format(Locale.US,
                        "路线只有 %.0f 米 —— 短于服务端最低里程 2.00 km，这一局会被判「里程不足」，已中止",
                        rp.length);
                log.log("      !! " + res.message);
                return res;
            }
            log.log(String.format(Locale.US, "      路线 %.0f 米，%d 个采样点（%s）",
                    rp.length, rp.pts.size(),
                    rnote.length() > 0 ? rnote.toString() : "样条+弯曲化"));
            // ★ 真路线出炉 → 推给地图。同样走 onPlan：**这一下不能动位置标注**，
            //   实跑轨迹的第一个点必须是下面跑出来的第一个真 GPS 点。
            try { log.onPlan(all, rp.pts); }
            catch (Throwable ignore) { }
            double[] pos = rp.at(0);
            double totalM = 0;
            // ★★★ 界面优化 #6：速度曲线模型（见 SpeedModel 的类注释，定标自
            //   真机 2026-09-29 17:39 那次计分跑）。三个正弦叠加 + 起停包络 +
            //   加速度限幅 —— 速度**连续可导**，且恒定落在 [vMin, vMax] 内。
            double paceLoMin = 1000.0 / vMax / 60.0;      // 快的一头（分/公里）
            double paceHiMin = 1000.0 / vMin / 60.0;      // 慢的一头
            double vMid = (vMin + vMax) / 2.0;
            /* ★ 不是 final：中途刷脸结束后要用 withResume() 挂一段复苏斜坡上去 */
            SpeedModel speedModel = SpeedModel.forPace(paceLoMin, paceHiMin, rnd);
            double lastV = Double.NaN;
            /* ---- 中途刷脸暂停的状态（详见下面触发处那段注释）---- */
            boolean faceEmitFrozen = false;   // 本拍要不要在暂停起点插一个 speed=0 的采样
            double facePauseM = 0;            // 暂停起点在路线上的里程
            double faceFrozenAt = -1;         // 已经插过冻结采样的里程（防止重复插）
            double facePauseUntil = -1;       // 暂停结束的"已跑时长"（秒）；<0 = 没有暂停
            double facePauseLeft = 0;         // 暂停还剩多少秒
            double faceMissedM = 0;           // 暂停期间"本该跑掉"的里程（解冻时一次性补上）
            double elapsedSec = 0;                        // 已跑时长（速度按时间推进）
            // 预计总时长：用区间中值速度估，只用于「收尾减速段」的定位
            double totalTSec = rp.length / Math.max(0.5, vMid);
            int n = (int) (totalTSec / intervalSec) + 4;
            log.log(String.format(Locale.US,
                    "      速度模型：区间 %.2f~%.2f m/s（配速 %.1f~%.1f 分/公里），"
                  + "加速度上限 %.2f m/s²，预计用时 %.0f 分",
                    vMin, vMax, paceHiMin, paceLoMin, speedModel.accelCap(), totalTSec / 60.0));
            List<String> doneIds = new ArrayList<>();
            long lastReport = 0;                          // ★ 上次位置心跳的时间（秒）
            List<MapShot.P> shot = new ArrayList<>();     // 画截图用
            // ★★★ GPS 采样缓冲：{lat, lon, 速度(m/s), 累计米, 绝对秒}。
            //   真机轨迹不是「跑完再拼」的，是逐点记下来的；末了再按公里切 b/c、
            //   给最后一个 GPS 点打 p:1。这里先把原始采样存下来，收尾时统一序列化。
            final List<double[]> samples = new ArrayList<>();
            long t0 = System.currentTimeMillis() / 1000;
            // ★ 真机的 start_time 严格等于它取 str 那一次 getTimestampV278 的服务端 ts
            //   （id=5252 实证：start_time == data.timestamp == 1791293667）。
            //   我们这边 t0 是本地时钟、且在取 str 之后几秒才赋值，会差几秒。
            //   这里优先用服务端下发的时间——两边时钟一致，才不会出现
            //   「lpi1 对不上 start_time」这种莫名失败。
            try {
                long srv = Long.parseLong(srvStartTs.trim());
                if (srv > 0) t0 = srv;
            } catch (Throwable ignore) { }
            // ★★★ 人脸核验（docs/28）—— 真机每次跑步都要做，我们现在照做：
            //   真机实证（work/ae.log 2026-09-30 18:48）：
            //     18:48:06 randomFace:1033.0, randomFaceList:[1033.0]   ← 服务端下发的核验要求
            //     18:48:08 showVerifyFaceDialog:1,0,0                    ← 弹窗让用户拍脸
            //     18:48:14 [path]=temp_file_…jpg
            //              [objectName]=Public/Upload/pic/face_verify/2026-09-30/477/….jpg
            //     18:48:15 uploadPhoto：Public/Upload/pic/face_verify/…/aa7b0d24-….jpg
            //     18:48:18 face_verify_id:6abce8f040cfe                  ← Run2/faceVerify 的返回
            //   反编译实证：CameraActivity 里 140 字节那个方法就是这一枪 ——
            //     params = zd.h.a(zd.o.S7 /* Run2/faceVerify */)
            //     params.addBodyParameter("face_file", <OSS key>)
            //     params.addBodyParameter("type", Integer.valueOf(this.c))
            //     if (photo 非空) params.addBodyParameter("run_face_verify_id", this.d)
            //   响应实体 com.lptiyu.tanke.entity.FaceVerifyResponse 的字段
            //     `run_face_verify_id` → 收尾时放进 stopRunV278 的 `run_face_verify_id`。
            //
            // ★ 2026-10-06 实测更正（docs/29）：
            //   1) 真机那一跑**只做一次**核验（起跑后 18 秒），不是两次 —— 退回一次。
            //   2) 我们传的照片被服务端连测 3 次全部 status=1「人脸验证成功！」
            //      → 「照片不合格」这条线被排除，别再在照片上折腾。
            //   3) ⚠ 服务端**验脸失败时也会下发 run_face_verify_id**，
            //      所以「拿到 id」不等于「验过了」，只有 status==1 才算。
            boolean faceTried = false;
            String faceResult = "";                      // 记下最后一次核验的结论
            // ★★★ 人脸核验搬到起跑**之前**（docs/31 抓包实证）。
            //   真机的动作序列是：
            //     beforeRunV260 → 扫脸 → Run2/faceVerify(type=0) → getTimestampV278 → 开跑
            //   也就是「进跑步页先验脸，验过了才允许开始」。我们以前是起跑后 18 秒才验，
            //   那是从 09-30 的日志时间线推出来的，抓包一到手就被推翻了。
            //   仍然每次现取 STS（人脸那步的凭证留到收尾会 403，实测过）。
            if (faceStartStatus) {
                faceTried = true;
                String fid = runFaceCheck("起跑前（真机时机：进跑步页就验）", "0");
                boolean ok = (lastFaceStatus == 1);
                /* ★★★ 2026-10-10 用户第 1 条：跑前人脸核验的结论要弹到界面上。
                 *
                 *   通过 → 界面上渐显一张小卡片「人脸识别通过」，停一会儿自己消失；
                 *   没通过 → 弹出原因，并且**就在这里终止**，不再往下跑。
                 *
                 *   为什么"没通过就终止"是对的：真机是"验过了才允许开始"，
                 *   我们继续跑下去的唯一结局是二十分钟后被服务端回一句
                 *   status=59「人脸照片验证不合格」—— 白跑一趟，用户还不知道
                 *   该去改哪儿（轨迹、配速、打卡点全是无辜的）。当场停下，
                 *   当场告诉他原因，才是省事的做法。 */
                try { log.onFacePre(ok, lastFaceStatus, lastFaceInfo); }
                catch (Throwable ignore) { }
                if (!ok) {
                    res.reported = true;      // ★ 界面已经弹过"人脸专用"的窗了，别再弹第二个
                    res.message = "跑前人脸核验没通过（服务端 status=" + lastFaceStatus
                            + (lastFaceInfo.length() > 0 ? "：" + lastFaceInfo : "") + "）";
                    log.log("      ❌ 起跑前人脸核验没过 —— 本场**不跑了**（用户要求：没过就终止）");
                    log.log("         没发出去的东西：登记令牌、位置心跳、三段上传，一个都没发。");
                    return res;
                }
                if (fid.length() > 0) {
                    faceVerifyId = fid;
                    faceResult = "拿到 id=" + fid;
                } else {
                    // status==1 却没给 id：照样往下跑（真机也是有什么传什么）
                    faceResult = "通过但没给 id";
                    log.log("      ⚠ 验脸通过了但服务端没给 id —— 收尾会带 face_verify_id="
                            + faceVerifyId + "，可能会被判 59");
                }
            } else {
                faceResult = "没做（start_status=0）";
            }

            // ============================================================================
            // ★★★ 2026-10-07 根因修复 —— 「登记本场跑步」
            //
            //   真机 id=4556（2026-10-06 21:34:27）实证：真机整场只调了 3 次
            //   `Run/getTimestampV278`，前两次**没有业务参数**（返回的 str 从没被用过），
            //   第三次带 `game_id` + `point_ids` —— 这一次**同时决定收尾的两个字段**：
            //
            //     请求（明文 + 签名A，已逐字节复刻验签通过，scripts/session_register.py）：
            //       Run/getTimestampV278
            //       + game_id=2562&point_ids=28991,28987
            //     响应：
            //       data.timestamp = 1791293667  ──►  stopRunV278.start_time
            //       data.str       = a39b2327…   ──►  stopRunV278.lpi1
            //
            //   两条等式在真机那份成功包（id=5252，record_status=1 计分）里**逐字符相等**。
            //
            //   我们以前的错：只发一个空的 getTimestampV278，服务端从来没有为本场跑步
            //   签发过 (start_time, str) 这一对。收尾却把那个 str 当 lpi1 报回去 ——
            //   服务端查不到这枚令牌属于哪场跑步，直接回
            //   `{"status":100,"info":"上传数据失败"}`（11:53 那次实测）。
            //
            //   ⇒ 必须带 game_id + point_ids 登记一次。`point_ids` 用**本次分配对**
            //     （真机的 28991,28987 正是 log_data 里打卡的那两个点）。
            //
            //   另外两个来自抓包的结论：
            //     · `location_septal=60` 在真机上形同虚设 —— 它唯一那枚 str 活了 940 秒
            //       （21:34:27 签发 → 21:49:53 收尾），且中途 setRunLocationRecord
            //       用的还是**别场遗留**的旧令牌，服务端照样收。所以这里登记一次就冻结，
            //       中途**不再**刷新（旧代码每 10 拍空刷一次 getTimestampV278，纯属噪声）。
            //     · `record_str` 不是登记给的 —— 真机它是另一个值（693e49ef…），
            //       起跑定下、收尾原样报回。我们沿用同一个 str 更安全，见下方收尾段。
            // ============================================================================
            // ★ 登记令牌（起跑前那次带 game_id+point_ids 的 getTimestampV278 下发）。
            //   收尾的 `lpi1` 必须原样报回它 —— 用数组是为了让下面的 try 块能写进来。
            final String[] regRef = {""};
            try {
                StringBuilder rids = new StringBuilder();
                for (LogPt kp : keyPts) {
                    if (rids.length() > 0) rids.append(',');
                    rids.append(kp.id);
                }
                TreeMap<String, String> rg = new TreeMap<>();
                rg.put("game_id", zoneId);
                rg.put("point_ids", rids.toString());
                log.log("[3.9/6] ★ 登记本场跑步 getTimestampV278"
                        + "（game_id=" + zoneId + " point_ids=" + rids + "）…");
                JSONObject rr = call("Run/getTimestampV278", rg, 0);   // 明文 + 签名A
                JSONObject rd = rr.optJSONObject("data");
                if (rr.optInt("status") == 1 && rd != null) {
                    String rt = rd.optString("timestamp", "");
                    String rs = rd.optString("str", "");
                    if (rt.length() > 0) { srvStartTime = rt; t0 = Long.parseLong(rt); }
                    // ★ 注意：这里**不能**动 locRef[0]。`lpi1` 这个键名在两条接口上
                    //   是两种完全不同的东西（真机实测）：
                    //     setRunLocationRecord → 108 字符 Base64(AES(umid+md5hex(请求时刻)))
                    //                            由客户端本地生成，locRef[0] 用第 1 步那份即可
                    //     stopRunV278          → 32 位 hex，就是这里下发的 str
                    //   覆盖 locRef[0] 会让跑步心跳发错令牌。
                    if (rs.length() > 0) regRef[0] = rs;
                    log.log("      status=1  start_time=" + srvStartTime
                            + "  lpi1(登记令牌)=" + trim(regRef[0], 16) + "…  长度=" + regRef[0].length()
                            + "  location_septal=" + rd.optString("location_septal"));
                    log.log("      ★ 收尾 start_time / lpi1 就用这一对（真机口径）");
                } else {
                    log.log("      ⚠ 登记失败 " + trim(rr.toString(), 200)
                            + " —— 收尾大概率会回「上传数据失败」");
                }
            } catch (Throwable t) {
                log.log("      !! 登记异常（继续跑，但收尾可能不过）");
                log.log("         ├ 位置：" + Err.where(t));
                log.log("         ├ 原因：" + Err.hint(t));
                log.log("         └ 原始：" + t);
            }

            for (int i = 0; i < n && !stopped; i++) {
                long tickStart = System.currentTimeMillis();
                // ★★★ 界面优化 #6（2026-10-07）：速度改成**连续可导**的随机曲线。
                //
                //   旧代码：double v = vMin + rnd.nextDouble() * (vMax - vMin);
                //           —— 每 6 秒在区间里独立抽一次，速度是阶梯，相邻拍能差 1.5 m/s。
                //   真机不是这样。定标依据（work/tracks_plain/2026-09-29_17-42，
                //   那次**计分成功**的 2.53 km / 336 点，scripts/ref_speed.py 实测）：
                //       相邻点 |Δs| 中位 0.030 m/s、p95 0.295 m/s
                //       |二阶差分| 中位 0.0100      ← 速度是连续可导的
                //       80% 的点落在 [3.5,4.5) m/s
                //       开头 1.22 → 升到 3.9（起跑斜坡），末点掉到 0.66 才停表
                //   SpeedModel 用「三个正弦叠加 + 软限幅 + 起停包络 + 加速度限幅」
                //   复刻这条曲线，并把实测统计打回日志，像不像人可以核对。
                //
                //   时间方向：按「已跑时长」推进速度（不是按里程），
                //   这样总时长可控、收尾减速能对上。
                /* ★★★ 中途刷脸暂停中：速度 0、位置不动、里程不涨。
                 * 真机就是这样 —— 人站在那儿举着手机等识别，GPS 不会往前跑。
                 * 缺口长度 = 解冻后第一截斜坡积分出来的那几十米，与真机同量级。
                 *
                 * 注意变量作用域：下面那一大段（打卡判定 / 轨迹采样 / 心跳）两种情形
                 * 都要走一遍，所以 v / stepM / lastTick 在这里先声明，暂停时取安全值。 */
                double v = 0;
                double stepM = 0;
                boolean lastTick = false;
                if (facePauseLeft > 0) {
                    double pSec = Math.min(intervalSec, facePauseLeft);
                    facePauseLeft -= pSec;
                    elapsedSec += pSec;
                    /* 暂停期间"本该跑掉"的里程：解冻时一次性补上。
                     *
                     * ★ 这就是真机轨迹里那条直线缺口的来源 —— 真机刷脸那几十秒
                     *   GPS 位置不动，解冻后第一次定位直接跳到"此时该在的地方"，
                     *   于是两点之间拉出一条几十到几百米的直线（实测 34.8 / 162.7 / 283.7 m）。
                     *   如果不补这一段，缺口只有解冻后第一拍的几米，轨迹上看不出停顿。 */
                    faceMissedM += speedModel.peekTemp(elapsedSec, totalTSec) * pSec;
                    /* 暂停起点插一个 speed=0 的采样：让轨迹在"冻结点"有个端点，
                     * 于是缺口读出来就是一条直线（真机也是这么看的）。 */
                    if (faceEmitFrozen && faceFrozenAt < 0) {
                        faceFrozenAt = totalM;
                        samples.add(new double[]{pos[0], pos[1], 0.0, totalM,
                                tickStart / 1000.0});
                        faceEmitFrozen = false;
                    }
                    if (facePauseLeft <= 1e-9) {
                        /* 解冻：① 补上暂停期间错过的里程（形成缺口）
                         *       ② 挂上复苏斜坡，从 0.3 m/s 上下爬回巡航 */
                        double jump = Math.max(8.0, Math.min(320.0, faceMissedM));
                        totalM += jump;
                        if (totalM > rp.length) totalM = rp.length;
                        pos = rp.at(totalM);
                        double rSec = 20 + rnd.nextDouble() * 15;
                        speedModel.withResume(elapsedSec, rSec);
                        lastV = Double.NaN;          // 重启：限幅不再拿暂停前的速度当基准
                        log.log(String.format(Locale.US,
                                "      ★ 刷脸结束，位置解冻 —— 补 %.0f m（暂停期间的位移，"
                                        + "轨迹上就是那段直线缺口）+ 恢复斜坡 %.0f 秒",
                                jump, rSec));
                    }
                    speedModel.record(0.0, pSec);
                } else {
                double want = speedModel.nextSpeed(elapsedSec, intervalSec, totalTSec);
                v = speedModel.limitAccel(want, lastV, intervalSec);
                lastV = v;
                speedModel.record(v, intervalSec);
                double runSec = intervalSec;
                stepM = v * runSec;
                lastTick = totalM + stepM >= rp.length;
                if (lastTick) {
                    // 最后一拍：只跑到末端，时长按实际里程折算（避免超跑一段）
                    stepM = Math.max(0, rp.length - totalM);
                    runSec = v > 0.05 ? stepM / v : intervalSec;
                }
                totalM += stepM;
                elapsedSec += runSec;
                // ★ 原来这里直接 break —— 末段被整段丢掉，正好压在终点上的那个
                //   「必过打卡点」就永远命中不了。改成夹到末端，并把这最后一拍跑完。
                if (totalM > rp.length) totalM = rp.length;
                pos = rp.at(totalM);
                StringBuilder hit = new StringBuilder();
                // ★★★ 打卡判定 —— **只认本次抽中的那 2 个点（keyPts）**。
                //   其余途经点路过不算：真机那次轨迹离 28989 只有 16 m 却没打卡，
                //   因为 28989 不是本次分配的点（docs/12 §1.2）。
                //   到达半径 **30 m**（真机实测 30.6 m 时 signUpCount 从 0→1）。
                double fromM = totalM - stepM;
                for (double mm = Math.max(0, fromM); mm <= totalM; mm += 4.0) {
                    double[] qp = rp.at(mm);
                    for (int k = 0; k < keyPts.size(); k++) {
                        LogPt kp = keyPts.get(k);
                        if (kp.isSignUp) continue;
                        if (dist(qp, new double[]{kp.lat, kp.lon}) >= 30) continue;
                        kp.isSignUp = true;
                        kp.timestamp = System.currentTimeMillis() / 1000;
                        kp.hitKm = mm / 1000.0;
                        // ★ 记下「踩到那一刻的实时 GPS」—— 收尾时 logPoints 要报这个坐标
                        kp.hitLat = qp[0];
                        kp.hitLon = qp[1];
                        if (hit.length() > 0) hit.append(',');
                        hit.append(kp.id);
                    }
                    // 途经点只用于界面显示「路过」，不参与打卡
                    for (int k = 0; k < cps.size(); k++) {
                        if (doneIds.contains(cids.get(k))) continue;
                        if (dist(qp, cps.get(k)) >= 30) continue;
                        doneIds.add(cids.get(k));
                    }
                }
                // ★★★ 轨迹点密度对齐真机：真机三个完整样本是
                //     240 点/2662 m、336 点/2688 m、356 点/3133 m
                //     —— 约 **8~11 米一个点（≈2.4~2.8 秒一点）**。
                //     （09-29 计分那次 336 点 / 2688 m：点距中位 7.67 m、
                //       采样间隔中位 2.5 s —— scripts/ref_speed.py 实测）
                //   我们以前一拍（6 秒）才落一个点，等于 18 米一点，比真机稀一半。
                //   服务端是拿上传的轨迹文件判「有没有经过打卡点」的，点太稀容易判漏，
                //   所以这里按 3 秒在拍内插值补点。
                //   ★ 速度现在是连续变化的，所以这里按**本拍均速**均匀插值，
                //     点距自然落在 8~11 m（v 3.5~4.5 m/s × 2.4~2.8 s）。
                double sampleSpan = Math.min(3.0, Math.max(1.5, runSec / 2.0));
                int nSub = Math.max(1, (int) Math.ceil(runSec / sampleSpan));
                for (int s2 = 1; s2 <= nSub; s2++) {
                    double frac = (double) s2 / nSub;
                    double mm = fromM + stepM * frac;
                    if (mm > totalM + 1e-6) mm = totalM;
                    if (mm < 0) mm = 0;
                    double[] sp = rp.at(mm);
                    samples.add(new double[]{sp[0], sp[1], v, mm,
                            tickStart / 1000.0 + runSec * frac});
                }
                shot.add(new MapShot.P(pos[0], pos[1], v));

                // ★★★ 位置心跳 —— 对齐真机实测节拍：
                //   docs/12 §1.4：真机 App 日志里那句「生成打卡json数据失败，runChecks为空」
                //   从 18:48:18 一直打到 18:53:25，**正好每 20 秒一次** —— 那就是
                //   setRunLocationRecord 的节拍。以前这里写 55 秒，是拿 run_flow_live 里
                //   两次「自由跑」心跳的间隔（59 秒）当依据，样本只有 2 个，判错了。
                //   现在：踩到新打卡点立刻报；否则每 **20** 秒报一次心跳。
                //
                // ★★★ point_ids —— 真机抓包（work/capture_run.log）原文：
                //     latitude=36.658928&longitude=114.60205&game_id=2562&point_ids=29001%2C28990
                //   而那个上报坐标离 29001 有 **1007 米**，30 米判定半径下根本不可能打到。
                //   ⇒ point_ids **不是「已打到的点」，而是「本次抽中的那一对」**，
                //     从起跑起就**整对**上报。（客户端是本地随机的，服务端唯一能知道
                //     分配对的渠道就是这个接口 —— docs/15 §5.2）
                //   ⇒ 所以这里改成：恒定上报 keyPts 的两个 id，与打到没打到无关。
                StringBuilder cum = new StringBuilder();
                for (LogPt kp : keyPts) {
                    if (cum.length() > 0) cum.append(',');
                    cum.append(kp.id);
                }
                long nowSec = System.currentTimeMillis() / 1000;
                if (hit.length() > 0 || nowSec - lastReport >= 20) {
                    lastReport = nowSec;
                    TreeMap<String, String> ex = new TreeMap<>();
                    ex.put("game_id", zoneId);              // ★ 校园跑必须带真实跑区 id（-1 会不计次）
                    ex.put("latitude", String.format(Locale.US, "%.7f", pos[0]));
                    ex.put("longitude", String.format(Locale.US, "%.7f", pos[1]));
                    ex.put("point_ids", cum.toString());
                    JSONObject r = call("Run/setRunLocationRecord", ex, 1, true);
                    // ★ 真机响应长这样：{"offlinePoints":[],"status":1,"info":""}
                    //   真机**在线时 offlinePoints 永远是空数组**（离线补传才非空），
                    //   所以它根本不是「认了几个点」的信号 —— 打卡是否生效只能看
                    //   收尾 stopRunV278 的 log_num。这里只把原始响应打出来备查。
                    JSONArray off = r.optJSONArray("offlinePoints");
                    int offN = off == null ? 0 : off.length();
                    if (hit.length() > 0)
                        log.log("      ★ 打卡上报 point_ids=" + cum + "（本拍新增 " + hit + "）离线补传点=" + offN);
                    log.log("        原始响应 " + trim(r.toString(), 200));
                }
                // ★★★ 会话已死就立刻收工。
                //   继续跑下去，stopRun 也是 102 —— 记录建不出来，还白占一次「当天已跑」。
                if (sessionDead) {
                    res.message = "会话已失效（status " + sessionDeadStatus
                            + " 该账号已在其他手机登录）—— 本次已中止，没有产生记录。"
                            + "请在步道乐跑里正常打开/重新登录一次，再回来重跑。";
                    log.log("      ❌ " + res.message);
                    return res;
                }
                if (i % 5 == 0) {
                    StringBuilder sg = new StringBuilder();
                    for (LogPt kp : keyPts) {
                        if (sg.length() > 0) sg.append(' ');
                        sg.append(kp.id).append(kp.isSignUp ? "✅" : "…");
                    }
                    log.log(String.format(Locale.US,
                            "      #%-3d 累计 %5.0f m  分配对 [%s]  lpi0×%d(%s)",
                            i, totalM, sg, lpi0Count, lastLpi0Key));
                }
                // ★★★ 2026-10-07 修正：**中途不再空刷 getTimestampV278**。
                //   旧代码每 10 拍（≈60 秒）空调一次（不带 game_id/point_ids），把
                //   locRef[0] 换成一个**不属于本场跑步**的 str。抓包实证真机整场只登记
                //   一次、那枚令牌活了 940 秒（21:34:27 → 21:49:53）都有效；中途
                //   setRunLocationRecord 用的甚至是别场遗留的旧令牌，服务端照样收。
                //   ⇒ 那个 60 秒是服务端给客户端的换签建议，不是硬性有效期；
                //     而多刷一次就会污染收尾要用的令牌，所以整段删掉。

                // ★★★ 2026-10-07 修复 —— **中途人脸核验（type=2）以前从来没做过**。
                //
                //   服务端 `run_face_verify_config` 下发的是：
                //     {"start_status":"1","stop_status":"0","middle_status":"1",
                //      "middle_distance":"600","face_verify_type":"1","middle_num":"1"}
                //   也就是**跑到 600 m 处必须再验一次脸**。
                //
                //   我们以前把这三个开关读出来、打印了日志，然后……`faceMiddleStatus`
                //   和 `faceMiddleDistance` 在全文件里**只有声明和赋值，没有任何消费点**
                //   —— 中途那次核验压根没发生。真机是实打实做了的（抓包实证）：
                //     21:34:24  Run2/faceVerify  type=0            → id=6ac4f8dfe6cf4
                //     21:48:29  Run2/faceVerify  type=2
                //               run_face_verify_id=6ac4f8dfe6cf4   → status=1
                //   而那一跑全程 2.06 km —— 599 m 处正是 middle_distance=600 的检查点。
                //
                //   后果：起跑后一直没有「中途已核验」的记录，服务端在收尾时认定核验链
                //   不完整，回 **status=59「人脸照片验证不合格」**（2026-10-07 13:0x 实测，
                //   门禁从「上传数据失败」推进到了这里）。
                //
                //   照抄真机：type=2，带上开始那次的 run_face_verify_id 把链串起来。
                //   非致命 —— 失败只记日志，收尾照跑（便于看服务端到底怎么判）。
                if (faceMiddleStatus && !faceMiddleDone && totalM >= faceMiddleDistance) {
                    faceMiddleDone = true;
                    log.log(String.format(Locale.US,
                            "      ★ 跑到 %.0f m（服务端要求 %d m 处）—— 中途人脸核验 type=2 …",
                            totalM, faceMiddleDistance));
                    /* ★★★ 2026-10-10：把"刷脸那一下"演成真人的样子。
                     *
                     * 真机跑到 middle_distance 弹出人脸核验时，人是**停下来**的：
                     * 掏手机、举到脸前、等识别。那几十秒 GPS 位置不动，回看轨迹
                     * 就是一段灰虚线 —— 三份真机长轨迹里都有这种直线缺口：
                     *   · 09-29 17-42（2688 m）第 ~1500 m 处 34.8 m
                     *   · 09-29 19-39（3133 m）第 ~1913 m 处 283.7 m
                     *   · 09-27 18-49（2662 m）第 ~1376 m 处 162.7 m
                     * 之后速度是**从接近 0 慢慢爬回巡航**的。
                     *
                     * 我们以前是"瞬时"完成（发一个 HTTP 就过去了），轨迹上看不出任何痕迹。
                     * 现在：① 在暂停起点插一个 speed=0 的采样（位置冻结，缺口就有端点）；
                     *       ② 真的停 9~20 秒（核验本身的耗时也算在里面）；
                     *       ③ 告诉 SpeedModel「从这一刻起按 20~35 秒的斜坡爬回来」。
                     *
                     * ⚠ 暂停期间**不推进虚拟位置、不缩短路线** —— distance / targetM
                     *   的口径完全不变；解冻后的位置由斜坡积分自然补齐，于是形成缺口。
                     *   这与真机一致（真机缺口也是几十米量级）。
                     */
                    facePauseM = fromM + (totalM - fromM) * 0.35;
                    if (facePauseM < fromM) facePauseM = fromM;
                    if (facePauseM > totalM) facePauseM = totalM;
                    if (facePauseM >= faceFrozenAt) faceEmitFrozen = true;
                    long faceT0ms = System.currentTimeMillis();
                    String mid = runFaceCheck("中途（type=2，真机 599 m 处）", "2");
                    long faceCallMs = System.currentTimeMillis() - faceT0ms;
                    if (mid.length() > 0) {
                        faceVerifyId = mid;
                        faceResult = "中途核验 id=" + mid;
                    } else {
                        log.log("      ⚠ 中途人脸核验没拿到 id —— 收尾大概率仍是 59");
                    }
                    /* ★ 2026-10-10 用户第 2 条：跑步途中出事要说一句，但**不能拦住跑步**。
                     * 中途核验失败属于"值得说、不至于停"——它只影响收尾可能被判 59，
                     * 而这时候人已经在路上了，停下来没有任何好处。
                     * 所以这里走的是一闪而过的红卡片，不是模态弹窗。 */
                    if (lastFaceStatus != 1) {
                        String why = lastFaceInfo.length() > 0 ? lastFaceInfo : "服务端没给原因";
                        try {
                            log.onFlash("⚠ 中途人脸核验没通过\n" + why
                                    + "\n（继续跑，但收尾可能被判「人脸照片验证不合格」）", true);
                        } catch (Throwable ignore) { }
                    }
                    /* 停顿：核验本身耗时 + 一点"人对屏幕"的时间。
                     * 真机 18:48:06 弹窗 → 18:48:18 拿到 id，约 12 秒。 */
                    long faceWaitMs = 5000 + (long) (rnd.nextDouble() * 12000);
                    long faceSleepMs = Math.max(4000, faceWaitMs - faceCallMs);
                    double facePauseSec = (faceCallMs + faceSleepMs) / 1000.0;
                    log.log(String.format(Locale.US,
                            "      ★ 刷脸暂停 %.1f 秒（核验 %d ms + 停顿 %d ms）——"
                                    + "轨迹上会留一段直线缺口，之后从低速爬回巡航",
                            facePauseSec, faceCallMs, faceSleepMs));
                    if (!sleep(faceSleepMs)) break;
                    facePauseUntil = elapsedSec + facePauseSec;
                    facePauseLeft = facePauseSec;
                }

                // ★★★ 人脸核验 —— 起跑前那次已挪到循环**之前**（见循环外那一段，docs/31）。
                //   抓包实证：真机是进跑步页就验（type=0），验完才 getTimestampV278，
                //   然后才开跑；中途那次由上面这段负责。
                // ★ 实时刷新界面上的「跑步路线」小地图
                try { log.onRoute(all, rp.pts, pos[0], pos[1], doneIds.size(), v); }
                catch (Throwable ignore) { }
                // ★ 把本次 HTTP 耗时从间隔里扣掉，否则实际配速会被网络延迟拖慢
                //   （实测：不扣的话 2.2 m/s 会跑成 511 s/km，超出 480 的规则上限）
                long spent = System.currentTimeMillis() - tickStart;
                long wait = (long) (intervalSec * 1000) - spent;
                if (!sleep(Math.max(0, wait))) break;
                if (lastTick) break;        // 末拍已经处理完（含终点打卡），收工
                }                           // ← 对应上面那个 else（刷脸暂停时不做推进）
            }
            long t1 = System.currentTimeMillis() / 1000;
            long used = t1 - t0;
            double distKm = totalM / 1000.0;
            int signUpCount = 0;
            StringBuilder sgAll = new StringBuilder();
            for (LogPt kp : keyPts) {
                if (kp.isSignUp) signUpCount++;
                if (sgAll.length() > 0) sgAll.append(' ');
                sgAll.append(kp.id).append(kp.isSignUp ? "✅" : "❌");
            }
            log.log(String.format(Locale.US, "      合计 %.3f km / %d 秒 / 配速 %d s/km",
                    distKm, used, (int) (used / Math.max(0.001, distKm))));
            log.log("      分配对 [" + sgAll + "]  signUpCount=" + signUpCount + "/" + keyPts.size()
                    + "（真机 signUpCount < min_log_num(2) 就是「打卡次数过少」）");
            log.log("      人脸核验：" + (faceTried ? faceResult : "没做（start_status=0）")
                    + "，stopRun 上报 face_verify_id=" + faceVerifyId);
            // ★★★ 2026-10-08 新增：把「有没有真的验过」如实说出来。
            //   服务端**验脸失败时也给 id**，所以「上报了 id」≠「验过了」。
            //   这一行是给下一次复盘用的：看到它就别再怀疑别的地方。
            if (faceTried && !faceOk) {
                log.log("      ❌ 本场**没有任何一次**人脸核验 status==1 ——"
                        + " 上面那些 id 全是失败件的编号。");
                // ★ 2026-10-08 更正：以前这里一口咬定「这单会被 59 拒掉」。
                //   现在知道了：服务端那句「请稍后重试」是它自己没跑通（真机原图同样被拒），
                //   所以这里必须把两种可能都摆出来，别让人再去翻轨迹和配速。
                log.log("         先看上面那几枪的**耗时**：6~13 s 且回「请稍后重试」= 服务端那头没跑通，"
                        + "换照片没用，过一阵重跑；");
                log.log("         若是几十毫秒就被拒、且原话是「未拍到完整人脸」= 那才是照片的问题，去重设一张原图。");
                log.log("         无论哪种，收尾都可能被 59「人脸照片验证不合格」拒掉 —— 别从轨迹/配速/打卡点上找原因。");
            } else if (faceOk) {
                log.log("      ✅ 本场人脸核验真的通过过（至少一次 status==1）");
            }
            // ★★★ 生成 logPoints —— **固定 point_num 条**，与真机 i0.b() 逐字段对齐。
            //   金样本实证（docs/21 §3.2 + docs/22）：
            //     打到的点：time = 打卡时刻(epoch 秒)，distance = 打卡那刻的累计里程(km)，
            //               **经纬度 = 踩到那一刻的实时 GPS**（离点位自身坐标 45.8 m）
            //     没打到的点：time = 0，distance = 0.0，**经纬度 = 打卡点自身坐标**
            //   真机 18:51 那次就是 [{29001,time:0,点位坐标},{28988,time:...,实时GPS}]。
            //   服务端数的是 time>0 的条数 → 就是 log_num。坐标给错等于自证「没到过」。
            JSONArray logPts = new JSONArray();
            for (LogPt kp : keyPts) {
                JSONObject lp = new JSONObject();          // 字段名照抄 App（含 longtitude 拼写）
                if (kp.isSignUp) {
                    lp.put("distance", Math.round(kp.hitKm * 100.0) / 100.0);
                    lp.put("latitude", round6(kp.hitLat));
                    lp.put("longtitude", round6(kp.hitLon));
                    lp.put("point_id", parseIntSafe(kp.id));
                    lp.put("time", kp.timestamp);
                    log.log(String.format(Locale.US,
                            "      [logPoints] %s 打到：实时 (%.6f,%.6f) 距点位 %.1f m  %.2f km  t=%d",
                            kp.id, kp.hitLat, kp.hitLon,
                            dist(new double[]{kp.hitLat, kp.hitLon},
                                 new double[]{kp.lat, kp.lon}),
                            kp.hitKm, kp.timestamp));
                } else {
                    lp.put("distance", 0.0);
                    lp.put("latitude", round6(kp.lat));
                    lp.put("longtitude", round6(kp.lon));
                    lp.put("point_id", parseIntSafe(kp.id));
                    lp.put("time", 0L);
                    log.log("      [logPoints] " + kp.id + " 没打到：报点位自身坐标 "
                            + String.format(Locale.US, "(%.6f,%.6f)", kp.lat, kp.lon)
                            + "  time=0  distance=0.0");
                }
                logPts.put(lp);
            }
            log.log("      logPoints(" + logPts.length() + " 条) = " + trim(logPts.toString(), 300));
            if (stopped) { res.message = "已手动停止"; return res; }
            // ★ 界面优化 #6：把速度曲线的实测统计打出来 —— 「像不像人」要可核对
            log.log("      " + speedModel.stats(distKm, used));
            log.log(String.format(Locale.US,
                    "      轨迹采样 %d 点 / %.0f m（点距中位 %.1f m），真机基线 336 点 / 2688 m / 7.67 m",
                    samples.size(), distKm * 1000.0,
                    samples.isEmpty() ? 0.0 : medianStep(samples)));

            // ★★★ 轨迹序列化 —— 见 buildTrackJson() 上方的说明（对齐真机原件）
            String trackJson = buildTrackJson(samples, totalM, pos);
            int marks = trackMarks(samples, totalM);
            double firstT = samples.isEmpty() ? 0 : samples.get(0)[4];
            double lastT = samples.isEmpty() ? used : samples.get(samples.size() - 1)[4];
            JSONArray traj;
            try {
                traj = new JSONArray(trackJson);
            } catch (Exception e) {
                res.message = "轨迹序列化失败：" + e;
                log.log("      !! " + res.message);
                return res;
            }
            log.log(String.format(Locale.US,
                    "      轨迹 %d 个 GPS 点 + 1 收尾点；b/c 分段 %d 条；"
                    + "Σb≈%.4f km（=上报里程 %.4f）；跨度 %.0f 秒;  末尾带 p:1",
                    samples.size(), marks, distKm, distKm, lastT - firstT));

            // ★★★ 把轨迹落到自己的私有目录，方便 `adb shell su -c cat` 直接 pull 下来核对
            //   （不用去 OSS 拉 —— OSS 要活会话的 STS，会话一死就查不了了）。
            //   同时打首尾点，方便一眼看出「始/终」位置对不对。
            try {
                java.io.File dir = new java.io.File("/data/data/com.ledao.tester/files");
                if (!dir.exists()) dir.mkdirs();
                java.io.FileOutputStream fo =
                        new java.io.FileOutputStream(new java.io.File(dir, "last_track.txt"));
                fo.write(packRecord(trackJson));
                fo.close();
                JSONObject f0 = traj.optJSONObject(0);
                JSONObject fl = traj.optJSONObject(traj.length() - 1);
                log.log("      [轨迹落盘] /data/data/com.ledao.tester/files/last_track.txt  "
                        + traj.length() + " 点");
                if (f0 != null && fl != null)
                    log.log(String.format(Locale.US,
                            "      首点 (%.6f,%.6f)  末点 (%.6f,%.6f)  路线全长 %.0f m",
                            f0.optDouble("a"), f0.optDouble("o"),
                            fl.optDouble("a"), fl.optDouble("o"), rp.length));
            } catch (Throwable t) {
                log.log("      [轨迹落盘] 失败：" + t);
            }

            // ---- 5) OSS 上传三段文件
            if (sessionDead) {          // ★ 会话死了就别浪费 OSS 流量了
                res.message = "会话已失效（status " + sessionDeadStatus + "）—— 未上传、未成单";
                log.log("      ❌ " + res.message);
                return res;
            }
            log.log("[5/6] 取 OSS 凭证，上传 轨迹 + 截图 + 陀螺仪 …");
            // ★★★ 2026-10-06 14:04 实测踩的坑：**OSS 凭证不能复用太久**。
            //   那一跑我在人脸核验时（13:48:49，跑到 569 m）取了一份 STS，
            //   收尾上传时（14:04:52）复用它 —— 隔了 16 分钟，OSS 直接 403。
            //   真机是"要用的时候现取"。所以这里**必须重新取一份**，不复用早期那份。
            JSONObject sts = fetchSts("上传前");
            if (sts == null) {
                res.message = "拿不到 OSS STS";
                log.log("      !! " + res.message);
                return res;
            }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

            // ① type=1 轨迹
            String key = "Public/Upload/file/run_record/" + day + "/"
                    + (1 + rnd.nextInt(999)) + "/" + UUID.randomUUID() + ".txt";
            int code = ossPutRetry(sts, key, packRecord(trackJson), "application/octet-stream");
            log.log("      ① 轨迹   HTTP " + code + "  " + key);
            if (code != 200) { res.message = "轨迹上传失败 HTTP " + code; return res; }

            // ② type=18 跑步截图（真实高德底图 + 彩色轨迹，和 App 的观感一致）
            String imgKey = "Public/Upload/pic/run_photo_screenshotImage/" + day + "/"
                    + (1 + rnd.nextInt(999)) + "/" + UUID.randomUUID() + ".png";
            byte[] img = MapShot.render(shot, 540, 1059, log);
            if (img == null) { log.log("      !! 底图没拉到，改用兜底图"); img = MapShot.fallback(540, 1059); }
            int icode = ossPutRetry(sts, imgKey, img, "image/png");
            log.log("      ② 截图   HTTP " + icode + "  " + imgKey);
            if (icode != 200) { res.message = "截图上传失败 HTTP " + icode; return res; }

            // ③ type=8 陀螺仪 / 加速度（明文 JSON）
            String senKey = "Public/Upload/file/run_gyroscope/" + day + "/"
                    + (1 + rnd.nextInt(999)) + "/" + UUID.randomUUID() + ".txt";
            byte[] sen = packSensor(traj, t0, used);
            int scode = ossPutRetry(sts, senKey, sen, "application/octet-stream");
            log.log("      ③ 陀螺仪 HTTP " + scode + "  " + sen.length + " 字节  " + senKey);
            if (scode != 200) { res.message = "陀螺仪上传失败 HTTP " + scode; return res; }

            String stepInfo = perStep(used, totalM);
            int stepNum = 0;
            try { JSONArray sl = new JSONObject(stepInfo).getJSONArray("list");
                  for (int i = 0; i < sl.length(); i++) stepNum += sl.getInt(i); } catch (Exception ignore) { }
            log.log("      步频: " + stepInfo);

            // ---- 6) 结束跑步（★ 校园跑走 Run/stopRunV278 + 加密 PARAMS + 签名B）
            //
            // ★★★ 报文按真机 `RunRecordEntity` 的 43 个字段重建（docs/21 §三）。
            //   真机原件（work/ae.log 19:17:05，两次记录逐字一致）：
            //     RunRecordEntity{ id, game_id, type, start_time, end_time, time_type,
            //       new_time_type, run_type, point_type_str, file_img, distance, max_distance,
            //       original_distance, title, cover, tag, star_level, record_status, isUpload,
            //       schoolId, fileName, sensor_path, termId, usedTime, logPoints, uid, step_num,
            //       isLastLocalRecord, perStep, is_running_area_valid, validLatLngCount,
            //       totalLatLngCount, needCheckGeeTest, needCheckGeeDp, needCheckGeeDevice,
            //       path, record_str, capture_num, face_auth_status, face_verify_id,
            //       is_timeout_zfb, runCheck, showType }
            //
            //   ✅ 2026-10-05 更新：真机的**请求键名**已经通过离线反编译拿到了（docs/27）。
            //     构造该方法就是 `school_run_record_detail.d` 里体长 4186 字节的那个方法，
            //     它读 `zd.o.M3`（= "Run/stopRunV278"）并逐条 addBodyParameter。完整键表见下方。
            //     凡是「实体名 ≠ 真机请求键名」的，仍然两种拼写都发一份 —— 多余键会被忽略，
            //     少发一个键就少一条信息。真机请求体里没有的键（pass_total 之外的
            //     point_list / log_points / sign_up_num …）不要自己造：
            //     `point_list` 是 **recordDetailV270 的响应字段**。
            //     （旧清单里点名的 9 个键现在看错了一半：record_img / pass_total /
            //       pass_effective_num 真机**确实发**，已按实证补回。）
            log.log("[6/6] Run/stopRunV278 结束跑步（加密 PARAMS + 签名B）…");

            // ★★★ 2026-10-06 22:5x 修正（真机 id=5252 实证）——
            //   **`lpi1` 必须用「起跑那一刻」那份 `str`，不是收尾前现取的。**
            //
            //   真机三个值严格相等，这是铁证：
            //     getTimestampV278 第3次  ts = 1791293667
            //     stopRunV278.start_time     = 1791293667
            //     stopRunV278.lpi1           = a39b2327…  == md5hex(1791293667)
            //   而 stopRunV278 自己的 timestamp = 1791294592 —— 与上面**差 925 秒**
            //   （正好是整场跑步时长）。也就是说：真机起跑时取一次 str 揣着，
            //   收尾原样报回；**不是**从"我现在发请求"的时间点取。
            //
            //   旧实现有两个错，叠加起来让 lpi1 和 start_time 差了一整个跑步时长：
            //     ① 跑步循环里每 10 拍刷新 locRef[0]（≈60 秒一张），收尾时它早已不是起跑那份
            //     ② 收尾前又重取一次（"location_septal=60 所以要新鲜"——那个 60 秒是
            //        服务端给客户端换签名的建议值，**不是**收尾上报的有效期）
            //
            //   改法：把起跑的 str 冻结到 runStartStr，收尾原样用它。
            //   `lpi1` 与 `record_str` 用**同一个**值 —— 真机就是同一个（a39b2327…）。
            final String runStartStr = locStr;
            log.log("      lpi1/record_str 用**起跑前登记**换来的令牌（真机 id=4556→5252 口径）："
                    + trim(regRef[0].length() > 0 ? regRef[0] : runStartStr, 16)
                    + "…  长度=" + (regRef[0].length() > 0 ? regRef[0].length() : runStartStr.length())
                    + "  start_time=" + srvStartTime);
            if (regRef[0].length() == 0) {
                log.log("      ⚠ 没有登记令牌，退回起跑那次 getTimestampV278 的 str ——"
                        + " 服务端多半会回「上传数据失败」");
            }
            String freshStr = regRef[0].length() > 0 ? regRef[0] : runStartStr;

            boolean areaValid = inZone(samples, zoneRect);
            int totalFix = samples.size();
            String dist2 = String.format(Locale.US, "%.2f", distKm);
            TreeMap<String, String> fin = new TreeMap<>();
            // ★★★ 2026-10-06 真机成功基线（Reqable id=5252，record_id=364498，
            //   record_status=1「自动确认有效」）——真机那次**只发了 37 个字段**。
            //   而我们以前发 60+ 个。签名算的是**整个参数表**，多一个键就多一段
            //   参与 MD5；就算服务端能容忍未知键，字段集不同也意味着我们在赌它
            //   按同一口径算 sign。这里改成**照抄真机字段集**：一个不多，一个不少。
            //
            //   真机 37 个字段（实测原样，按字母序）：
            //     distance end_time error_code file_img game_id gee_token ip
            //     is_running_area_valid is_timeout_zfb log_data lpi1 mac
            //     mobileDeviceId mobileModel mobileOsVersion newMobileDeviceId nonce
            //     ostype pass_effective_num pass_total record_file record_img
            //     record_str run_check_res run_face_verify_id school_id start_time
            //     step_info step_num student_num term_id timestamp token uid
            //     used_time version version_name (+ sign)
            //   其中 uid/token/version/…/nonce/timestamp 由 params() 自动注入，
            //   这里不再重复 put —— 重复 put 会让字段集偏移。
            fin.put("distance", dist2);
            fin.put("end_time", String.valueOf(t1));
            // ★★★ start_time —— 2026-10-07 补回。真机必发此字段；漏了它服务端直接
            //   回 {"status":100,"info":"上传数据失败"}（不是签名错、也不是字段名错）。
            //   值取起跑那次 getTimestampV278 的服务端 ts（真机口径），拿不到才退回本地 t0。
            fin.put("start_time", srvStartTime.length() > 0 ? srvStartTime : String.valueOf(t0));
            // 真机实测值就是 3009（不是空串！）。它是「截图/照片」那条链路的返回码，
            // 发空等于宣称那一步没发生过。
            fin.put("error_code", "3009");
            fin.put("file_img", imgKey);
            fin.put("game_id", zoneId);
            fin.put("gee_token", "");
            fin.put("ip", localIp);
            // 真机发数字 1（不是字符串 "true"）。类型在 JSON 里可见，服务端按 int 解。
            fin.put("is_running_area_valid", areaValid ? "1" : "0");
            fin.put("is_timeout_zfb", "0");
            fin.put("log_data", logPts.toString());
            // ★★★ 收尾这里的 lpi1 与跑步中那三个接口**含义不同** —— 实测证据：
            //   · 跑步中（beforeRunV260 / setRunLocationRecord / getOssSts）：
            //       lpi1 = 108 字符 Base64(AES(umid + md5hex(请求里那个 timestamp)))
            //              （真机用 Base64.DEFAULT 折行 → 解出来 110 字符含 2 个 \n；
            //                本体是 108。scripts/lpi1_len_check.py 已逐字节证实）
            //   · 收尾（stopRunV278）：lpi1 = **32 位 hex**，等于**带 game_id+point_ids
            //       那次** `Run/getTimestampV278` 下发的 `str`（见上面 [3.9/6] 那段）。
            //     真机 id=4556 → id=5252 实证：
            //       登记 data.str = a39b2327075e1d4c0c356a2e86b4d3a9
            //       收尾 lpi1     = a39b2327075e1d4c0c356a2e86b4d3a9   ← 同一个值
            //   而 record_str 是**另一个值**（真机那次 693e49ef…），起跑定下、原样报回。
            fin.put("lpi1", freshStr);
            fin.put("mac", "02:00:00:00:00:00");            // 真机实测值（Android 10 起固定）
            fin.put("newMobileDeviceId", "");               // 真机两次都是空串
            fin.put("pass_effective_num", "0");             // 真机实测 0，照样计分
            fin.put("pass_total", String.valueOf(zonePointTotal));   // 跑区**总**打卡点数
            fin.put("record_file", key);
            fin.put("record_img", "");                      // 真机实测空串，照样计分
            fin.put("record_str", freshStr);
            fin.put("run_check_res", "[]");
            fin.put("run_face_verify_id", faceVerifyId);
            fin.put("step_info", stepInfo);
            fin.put("step_num", String.valueOf(stepNum));
            fin.put("term_id", termId);
            fin.put("used_time", String.valueOf(used));
            log.log("      stopRun 字段 " + fin.size() + " 个（真机基线 37）；log_data="
                    + logPts.length() + " 条；lpi1=" + trim(freshStr, 12) + "…；"
                    + "is_running_area_valid=" + (areaValid ? "1" : "0")
                    + "；pass_total=" + zonePointTotal);
            // ★★★ 白名单：真机 stopRunV278 只有这 37 个键（+sign）。签名覆盖整个参数表，
            //   而 ident 里多带着 access_token / refresh_token（真机从不在这条接口发它们）。
            //   不裁掉就等于用了一个和真机不同的签名口径。
            java.util.Set<String> realKeys = new java.util.HashSet<>(
                    java.util.Arrays.asList(STOP_REAL_KEYS));
            log.log("      stopRun 将发出 " + realKeys.size() + " 个字段（真机基线 37）+ sign；"
                    + "裁掉的多余键（若有）= access_token/refresh_token");
            JSONObject r = call("Run/stopRunV278", fin, 2, true, realKeys);   // mode2 加密 + 签名B + lpi1
            log.log("      " + trim(r.toString(), 400));
            if (r.optInt("status") == 1) {
                JSONObject d = r.optJSONObject("data");
                res.ok = true;
                res.recordId = d != null ? d.optString("record_id") : "";
                res.distance = d != null ? d.optString("distance") : "";
                res.usedTime = d != null ? d.optString("time") : "";
                String why = d != null ? d.optString("record_failed_reason", "") : "";
                res.message = "跑步记录创建成功" + (why.length() > 0 ? "（未计分：" + why + "）" : "");
                // ★★★ 更正（2026-09-30 20:55 实测）：`Run/stopRunV278` 的响应里
                //   **确实有 log_num / points**：
                //     {"record_id":"355710",...,"log_num":0,"points":0,"record_status":0,
                //      "record_failed_reason":"打卡次数过少，未达到合格标准", ...}
                //   之前误判成"幻影值"，是因为看的是 `Run/stopFreeRunV220`（自由跑）的响应
                //   —— 那个接口没有这两个字段，而且它**本来就不算打卡**。
                //   ⇒ 判分只用 stopRunV278，直接看 log_num 即可（≥ min_log_num=2 才合格）。
                if (d != null) res.detail = "log_num=" + d.optInt("log_num")
                        + "  points=" + d.optInt("points")
                        + "  record_status=" + d.optInt("record_status")
                        + (why.length() > 0 ? "  ⚠reason=" + why : "");
                log.log("      ✅ record_id=" + res.recordId + "  " + res.distance + " km  " + res.detail);
                if (d != null && d.optInt("log_num") < 2)
                    log.log("      ⚠ log_num < 2 —— 服务端一个打卡都没认。"
                            + "本次已报 logPoints " + logPts.length()
                            + " 条（time>0 的 " + signUpCount + " 条）");

                // ★★★ 7) Run2/gyroscope —— 真机跑完必调，我们**以前从来没调过**。
                //   真机原文（artifacts/tls/run_flow_live.log L2404）：
                //     gyroscope_file = Public%2FUpload%2Ffile%2Frun_gyroscope%2F<date>%2F<n>%2F<uuid>.txt
                //     record_id      = 353146        ← 上一步 stop 返回的 record_id
                //     明文 form + 签名B + lpi0
                //   规则里 gyroscope_type="1"，这个接口又专门加了 lpi0 保护 ——
                //   八成就是「防代步/防模拟器」那条流水线，缺了它成绩可能不算数。
                if (res.recordId.length() > 0) {
                    log.log("[7/7] Run2/gyroscope 上传陀螺仪关联（明文 + 签名B + lpi0）…");
                    TreeMap<String, String> gy = new TreeMap<>();
                    gy.put("gyroscope_file", senKey);
                    gy.put("record_id", res.recordId);
                    JSONObject gr = call("Run2/gyroscope", gy, 1, true);
                    log.log("      " + trim(gr.toString(), 260));
                } else {
                    log.log("      ⚠ 没拿到 record_id，跳过 Run2/gyroscope");
                }

                // ★★★ 8) Run/recordDetailV270 —— 把服务端**存下来的那份**记录拉回来验收。
                //   真机 App 跑完会自动调它，我们一直没调过（docs/21 §一：缺失接口里
                //   只有 `Report/appLog` 和它值得补）。这是唯一能直接看到服务端
                //   `log_num` / `record_status` / `record_failed_reason` / `point_list`
                //   的地方 —— 判分就靠这几个字段，不用再猜。
                if (res.recordId.length() > 0) {
                    log.log("[8/8] Run/recordDetailV270 拉服务端存下来的那份记录（明文 + 签名A）…");
                    TreeMap<String, String> rd = new TreeMap<>();
                    rd.put("record_id", res.recordId);
                    JSONObject dr = call("Run/recordDetailV270", rd, 0);
                    JSONObject dd = dr.optJSONObject("data");
                    if (dr.optInt("status") == 1 && dd != null) {
                        JSONArray pl2 = dd.optJSONArray("point_list");
                        String reason = dd.optString("record_failed_reason", "");
                        log.log("      服务端记录：log_num=" + dd.optInt("log_num")
                                + "  record_status=" + dd.optInt("record_status")
                                + "  point_list=" + (pl2 == null ? 0 : pl2.length()) + " 条"
                                + (reason.length() > 0 ? "  ⚠reason=" + reason : "  ✅无失败原因"));
                        if (pl2 != null) log.log("      point_list=" + trim(pl2.toString(), 280));
                        if (dd.optInt("log_num") >= 2)
                            log.log("      ✅✅ log_num ≥ min_log_num(2) —— 本次打卡**计入成绩**");
                        else
                            log.log("      ❌ log_num=" + dd.optInt("log_num")
                                    + " < 2 —— 仍不计分，需要继续定位");
                    } else {
                        log.log("      ⚠ 拉取失败：" + trim(dr.toString(), 200));
                    }
                }
            } else {
                res.message = "未成单: " + trim(r.toString(), 200);
                log.log("      ❌ " + res.message);
            }
        } catch (Exception e) {
            res.message = "异常: " + Err.one(e);
            log.log("      ❌ 收尾阶段异常，这一段没走完");
            log.log("         ├ 位置：" + orDash(Err.where(e)));
            log.log("         ├ 原因：" + Err.hint(e));
            log.log("         └ 原始：" + e);
            log.log("         （收尾要发的关键项：start_time / lpi1 / run_face_verify_id"
                    + " —— 缺任何一个服务端都会回 status=100 或 59）");
        }
        return res;
    }

    private static String orDash(String s) {
        return s == null || s.length() == 0 ? "(不在本工程代码里)" : s;
    }

    /**
     * 采样点的相邻点距中位数 —— 用来和真机基线（7.67 m）对照，
     * 确认轨迹密度没跑偏。给日志用，失败不抛。
     */
    static double medianStep(List<double[]> samples) {
        try {
            if (samples == null || samples.size() < 3) return 0;
            double[] d = new double[samples.size() - 1];
            for (int i = 1; i < samples.size(); i++) {
                double[] a = samples.get(i - 1), b = samples.get(i);
                d[i - 1] = dist(new double[]{a[0], a[1]}, new double[]{b[0], b[1]});
            }
            java.util.Arrays.sort(d);
            return d[d.length / 2];
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 跑一次人脸核验（现取 OSS 凭证 → 传照片 → Run2/faceVerify）。
     *
     * ★ 2026-10-06 更正（docs/29）：真机 09-30 18:47 那一跑**只验一次**，
     *   起跑后 18 秒。我上一版以为要 start+middle 两次，是把服务端的
     *   `start_status/middle_status` 过度解读了。现在只调用一次。
     *   仍然每次**现取凭证**（人脸那步取的凭证留到收尾会 403，实测过）。
     *
     * @param phase 日志里显示这次核验的时机
     * @return run_face_verify_id；失败返回 ""
     */
    private String runFaceCheck(String phase) {
        return runFaceCheck(phase, "0");
    }

    /**
     * @param phase 日志里显示这次核验的时机
     * @param type  服务端要的核验类型（见 facesType 注释）
     * @return run_face_verify_id；失败返回 ""
     */
    private String runFaceCheck(String phase, String type) {
        try {
            log.log("      [人脸] " + phase + " …");
            JSONObject sts = fetchSts("人脸核验/" + phase);
            if (sts == null) return "";
            String id = doFaceVerify(sts, lastFaceId, type);
            // ★ 只有 status==1 才算「本场验过脸」。id 照样往下传（真机
            //   `if (!TextUtils.isEmpty(this.d)) addBodyParameter("run_face_verify_id", this.d)`
            //   也是有什么传什么），但**是否真的通过**必须单独记下来。
            if (lastFaceStatus == 1) {
                faceOk = true;
            } else if (id.length() > 0) {
                log.log("      [人脸] ⚠ 这次没通过（status=" + lastFaceStatus + "），"
                        + "id=" + id + " 只是这次请求的编号 —— 别当成「验过了」");
            }
            if (id.length() > 0) lastFaceId = id;
            return id;
        } catch (Throwable t) {
            log.log("      ⚠ 人脸核验异常（" + phase + "，继续跑）：" + t);
            return "";
        }
    }

    /**
     * 单独跑一次人脸核验并把原始响应交出来（**不参与跑步**）。
     *
     * ★ 用途：首页那个「人脸照片」按钮，选完照片立刻拿服务端当裁判 ——
     *   当场就知道这张照片行不行，不用等跑完 20 分钟才发现被拒。
     *   实测这条路完全成立（docs/29）：人脸核验本来就是「传 OSS + Run2/faceVerify」
     *   两个独立动作，不需要跑步上下文。
     *
     * @param photo 要传的照片
     * @return Run2/faceVerify 的原始响应；失败返回 null
     */
    public JSONObject faceProbe(java.io.File photo) {
        try {
            if (photo == null || !photo.exists() || photo.length() == 0) {
                log.log("      ⚠ 照片不存在：" + photo);
                return null;
            }
            JSONObject sts = fetchSts("人脸照片自检");
            return faceProbe(photo, sts);
        } catch (Throwable t) {
            log.log("      ⚠ 人脸自检异常：" + t);
            return null;
        }
    }

    /**
     * 会话预检 —— 一条最便宜的请求问服务端「我这份身份还活着吗」。
     *
     * ★ 2026-10-08 事故（用户报「重新上传昨天的照片依旧会报错」）：
     *     13:31:58  Run2/faceVerify → status=1 人脸验证成功！   ← 照片是好的
     *     13:36:29  User/User       → status=102 该账号已在其他手机登录
     *     13:37:39  Run2/faceVerify → SocketException / UnknownHostException
     *   会话死在 13:36，可「设人脸照片」这条路在 13:37 还是先把十个候选
     *   挨个传了一遍 OSS，全部失败后界面给出的却是
     *   「这些候选服务端一个都没认 —— 这张照片本身可能就不合格」
     *   —— 照片一次都没被否定过。用户于是反复换照片，而真正该做的是重新提取身份。
     *
     *   所以：只要 101/102 就把结论摆到台面上，**不浪费一次上传**。
     *
     * @param strict true 时「连不上/答不上来」也算不通过（预检宁可误报，
     *               也别让人白等十个候选）；false 时只有 101/102 才判死。
     */
    public boolean sessionAlive(boolean strict) {
        try {
            // ★ 只能用 3 参重载：call 的 allowRefresh 重载是 private，
            //   而且会话真死时 refresh_token 也是 102，续期本来就没救
            //   （Ledao.call 内部会把 sessionDead 置位、只试一次）。
            JSONObject r = call("User/User", null, 0);
            if (r == null) return !strict;
            int st = r.optInt("status");
            if (st == 1) return true;
            String info = r.optString("info");
            if (st == 101 || st == 102) {
                log.log("      ❌ 会话已失效（status " + st + "  " + info
                        + "）—— 这份身份已被服务端作废，先重新提取，再谈照片。");
                return false;
            }
            if (r.opt("_err") != null) {
                log.log("      ⚠ 会话预检没答上来：" + trim(String.valueOf(r.opt("_err")), 160));
                return !strict;
            }
            log.log("      ⚠ 会话预检异常响应：status=" + st + " " + info);
            return !strict;
        } catch (Throwable t) {
            log.log("      ⚠ 会话预检异常：" + t);
            return !strict;
        }
    }

    /**
     * 同上，但复用外面已经拿到的 OSS 凭证。
     *
     * ★ 2026-10-08：首页设人脸照片时会**一次问好几个候选**
     *   （见 FacePhoto / MainActivity.installFacePhoto），
     *   每个候选都去 getOssSts 一次太浪费，凭证又是通用的，所以拆出这个重载。
     */
    public JSONObject faceProbe(java.io.File photo, JSONObject sts) {
        try {
            if (photo == null || !photo.exists() || photo.length() == 0) {
                log.log("      ⚠ 照片不存在：" + photo);
                return null;
            }
            if (sts == null) return null;
            // ★ 同 doFaceVerify：改用 readWhole，避免短读留下 0x00 尾巴
            byte[] jpg = readWhole(photo);
            if (jpg == null) {
                log.log("      ⚠ 照片读不完整（" + photo.length() + " 字节）—— 不拿它去问服务端");
                return null;
            }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String key = "Public/Upload/pic/face_verify/" + day + "/"
                    + (1 + rnd.nextInt(999)) + "/" + UUID.randomUUID() + ".jpg";
            int code = ossPut(sts, key, jpg, "image/jpeg");
            log.log("      照片上传 HTTP " + code + "  " + jpg.length + " 字节  " + key);
            if (code != 200) return null;
            TreeMap<String, String> p = new TreeMap<>();
            p.put("face_file", key);
            p.put("type", "0");          // 真机扫脸实测就是 0（docs/31）
            // ★ 2026-10-08：原来这里会**连发两次**（第二次带上第一次的 id），
            //   纯粹是为了让抓包工具里出现两组一样的报文方便肉眼比对。
            //   现在首页设照片要一次问好几个候选，发两次等于把请求数翻倍，
            //   而且没有任何功能价值，所以去掉。
            return call("Run2/faceVerify", p, 0);
        } catch (Throwable t) {
            log.log("      ⚠ 人脸自检异常：" + t);
            return null;
        }
    }

    /**
     * 人脸核验（docs/28）—— 真机 `CameraActivity` 里 140 字节那个方法的等价实现：
     *
     *   1) 把用户设置好的自拍（files/face.jpg）上传到 OSS
     *      路径照抄真机：Public/Upload/pic/face_verify/&lt;yyyy-MM-dd&gt;/&lt;1-999&gt;/&lt;uuid&gt;.jpg
     *      （真机日志实证：work/ae.log 18:48:15 uploadPhoto：Public/Upload/pic/face_verify/…）
     *   2) POST `Run2/faceVerify`，body = { face_file: &lt;OSS key&gt;, type: &lt;facesType&gt; }
     *      ★ 抓包实证（docs/31）：真机扫脸发 `type=0`，时机在**起跑之前**。
     *   3) 响应 `data.run_face_verify_id` 就是收尾要带的那个 id（真机形如 6abce8f040cfe）
     *
     * @param facesType `type` 字段的值；抓包见到的真机值是 "0"
     * @return 拿到的 id；失败返回 ""（不抛异常，跑不影响）
     */
    private String doFaceVerify(JSONObject sts, String prevId, String facesType) {
        try {
            java.io.File f = new java.io.File(FACE_PHOTO);
            if (!f.exists() || f.length() == 0) {
                lastFaceInfo = "";
                lastFaceStatus = -1;
                log.log("      ⚠ 还没设置人脸照片（首页「🧑 设置人脸照片（自拍）」）——跳过核验");
                return "";
            }
            // ★★★ 2026-10-08 复核：这里原来是手写的定长读，**不检查是否读满** ——
            //   read() 提前收尾时，数组里剩下的 0x00 会被当成照片的一部分传上去。
            //   现在走 readWhole()：读不满就返 null，这一枪干脆不发。
            byte[] jpg = readWhole(f);
            if (jpg == null) {
                lastFaceInfo = "本地那张人脸照片读不完整";
                lastFaceStatus = -1;
                log.log("      ❌ 人脸照片读不完整（文件 " + f.length()
                        + " 字节，读回来的字节数对不上）—— 这一枪不发。");
                log.log("         去首页点「🧑 设置人脸照片」重新选一张原图。");
                return "";
            }
            // ★★★ 2026-10-08 19:23 事故逼出来的「照片体检」——
            //   原来只看头两字节 FF D8 就放行，而**写到一半的 JPEG 同样带 FF D8**。
            //
            //   那天另一台手机（MTN-AN80 / Android 16）起跑前核验回
            //       {"status":0,"info":"人脸验证错误，请稍后重试！"}
            //   这句话在 docs/29 里出现过两次，两次的输入都是**服务端根本没法当图解码**的东西：
            //       1×1 像素        → status=0 人脸验证错误，请稍后重试！
            //       纯文本不是图片   → status=0 人脸验证错误，请稍后重试！
            //   而「图能解码、只是没脸」是**另一句**：未拍到完整人脸，请离镜头稍远一点重拍…
            //   ⇒ 服务端那句话的真实含义是「我拿到的不是一张能用的图」。
            //
            //   铁证就在本仓库里：work/facedbg/src.jpg 是 SOI=True / EOI=False 的半截图。
            String bad = jpegDiag(jpg);
            // ② 只读边界解码一遍（inJustDecodeBounds 不分配像素）：连宽高都报不出来就是真解不了
            android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            try {
                android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length, bo);
            } catch (Throwable t) {
                bad = "本机解码器抛异常：" + t;
            }
            if (bad.length() == 0 && (bo.outWidth <= 0 || bo.outHeight <= 0)) {
                bad = "本机解码器报不出宽高（BitmapFactory 判为非法图）";
            }
            if (bad.length() > 0) {
                lastFaceInfo = "本地那张人脸照片本身不完整：" + bad;
                lastFaceStatus = -1;
                log.log("      ❌ 人脸照片文件本身就不完整：" + bad);
                // ★ 2026-10-08 更正：原来这里写「服务端只会回人脸验证错误…」——
                //   那句话当晚被正对照推翻了（真机自己的好图也被同一句话顶回来），
                //   所以这里不再拿服务端的话当理由。本地这份文件就是坏的，不发，仅此而已。
                log.log("         本地这份就读不出完整的一帧图，**这一枪不发**（免得白挨一次上传）。");
                log.log("         去首页点「🧑 设置人脸照片」重新选一张原图。");
                return "";
            }
            log.log(String.format(Locale.US,
                    "      [人脸] 照片体检通过：%d×%d / %d 字节 / 尾标记 EOI 正常",
                    bo.outWidth, bo.outHeight, jpg.length));
            if (jpg.length > 900 * 1024) {
                log.log(String.format(Locale.US,
                        "      ⚠ 人脸照片偏大（%.1f MB，真机约 0.2 MB）——"
                        + "首页重新选一次会自动归一化到 1296×1296", jpg.length / 1048576.0));
            }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String key = "Public/Upload/pic/face_verify/" + day + "/"
                    + (1 + rnd.nextInt(999)) + "/" + UUID.randomUUID() + ".jpg";
            int code = ossPut(sts, key, jpg, "image/jpeg");
            log.log("      [人脸] 照片上传 HTTP " + code + "  " + key
                    + "  " + jpg.length + " 字节");
            if (code != 200) return "";
            // ★★★ 2026-10-08 新增：传完立刻读回来比对一次。
            //   用户问的正是「是不是照片传输的时候加密格式或者其他什么东西错了」——
            //   从此这一步当场给答案，而不是靠「PUT 返回 200」去推论。
            String back = ossGetBack(sts, key, jpg);
            if (back.length() == 0) {
                log.log("      [人脸] ✅ OSS 回读一致：" + jpg.length + " 字节 / MD5 相同"
                        + " —— 服务端将来取到的就是这一串字节");
            } else {
                log.log("      [人脸] ⚠ OSS 回读：" + back);
            }
            TreeMap<String, String> p = new TreeMap<>();
            p.put("face_file", key);
            // ★★★ 2026-10-06 抓包实证（docs/31）：真机扫脸发的是 **type=0**，
            //   而且时机是**起跑之前**（进入跑步页就验，验完才 getTimestampV278）。
            //   以前我们发 type=1、起跑后 18 秒 —— 那条"真机时机"是从 09-30 的
            //   日志时间线推断的，抓包一到手就被推翻了。现在照抓包来。
            p.put("type", facesType == null ? "0" : facesType);
            // ★★★ 真机 verifyPhoto 里那句 `if (!TextUtils.isEmpty(this.d))
            //     addBodyParameter("run_face_verify_id", this.d)` —— 已经验过一次时，
            //     下一次要把**上一次的 id 带回去**（服务端把几次核验串成一条链：
            //     start → middle → stop）。我们第一次做「开始+中途」两次核验，
            //     中途那次必须带上开始那次的 id，否则链是断的。
            if (prevId != null && prevId.length() > 0)
                p.put("run_face_verify_id", prevId);
            // ★★★ 2026-10-08 19:23 加固：这一步以前**只给服务端一次机会**。
            //   那次它耗了 6 秒（客户端全程没重试 —— call() 只在 101/102 时才重试，
            //   日志里也没有续期行），然后回「人脸验证错误，请稍后重试！」。
            //   服务端自己都在说"稍后重试"，我们却把这一枪当终局写进结论。
            //   同一天早上 getOssSts 也是"偶发被拒"，那边早就有三枪降级链了，这边补上。
            //   顺带把每一枪的**耗时**打出来 —— 下次一眼就能分清：
            //     几十毫秒就被拒 = 服务端当场判这张图不行；
            //     好几秒才被拒   = 服务端自己那头卡住了（取 OSS / 调人脸服务超时）。
            JSONObject r1 = null;
            int shots = 0;
            long msTotal = 0;
            // ★★★ 2026-10-08 20:58：退避窗口从 0.8/1.8 s 拉长到 2/5/10 s。
            //   实测服务端现在每枪要 6~13 s 才回（真机顺利时是 412 ms），
            //   而且回的是「请稍后重试」——服务端自己都在说「稍后」，那就该给足间隔再打，
            //   而不是隔 0.8 秒连着捅三下。三次都失败就认了，不硬耗。
            final long[] backoff = {2000L, 5000L, 10000L};
            for (int shot = 0; shot < 4; shot++) {
                long t0 = System.currentTimeMillis();
                JSONObject rr = call("Run2/faceVerify", p, 0);
                long ms = System.currentTimeMillis() - t0;
                msTotal += ms;
                shots = shot + 1;
                r1 = rr;
                log.log("      [人脸] Run2/faceVerify 第 " + (shot + 1) + " 枪 " + ms + " ms → "
                        + trim(rr.toString(), 300));
                int s = (int) parseIntSafe(rr.optString("status", "0"));
                if (s == 1 || s == 101 || s == 102) break;   // 成了 / 会话死了，都没必要再打
                if (shot < backoff.length) {
                    try { Thread.sleep(backoff[shot]); } catch (InterruptedException ignore) { }
                }
            }
            if (shots > 1) log.log("      [人脸] 上面共打了 " + shots + " 枪");
            // ★★★ 2026-10-06 实测（docs/29）：服务端**验脸失败时也会下发
            //     run_face_verify_id**（纯黑图那次：status=0「未拍到完整人脸…」
            //     照样给了 id）。所以「拿到 id」不等于「验过了」——
            //     只有 status==1 才算真通过，这里必须分开报。
            int st = (int) parseIntSafe(r1.optString("status", "0"));
            String info = r1.optString("info", "");
            lastFaceStatus = st;
            lastFaceInfo = info;                 // ★ 界面要拿它当「原因」弹给用户看
            log.log("      [人脸] 服务端结论：status=" + st + "  info="
                    + info + (st == 1 ? "  ✅ 验脸通过" : "  ❌ 验脸没过"));
            // ★★★ 2026-10-08 20:58 更正 —— 这几句话到底是谁的错，终于用**正对照**定死了。
            //
            //   以前这里写的是「『请稍后重试』= 服务端没能把这张图当图解码，多半是图不完整」。
            //   那句话来自 docs/29 的推理（1×1 像素和纯文本也回这句）。**推理错了。**
            //
            //   当晚用 10-06 真机那张 264394 字节的原图（服务端当天**亲口接受过**的那张）
            //   当场重打，连续 3 枪全是：
            //       9763 ms → status=0 系统错误，请稍后重试！
            //      10539 ms → status=0 系统错误，请稍后重试！
            //       9624 ms → status=0 系统错误，请稍后重试！
            //   而同一时刻我们的 259150 字节图（13:31:58 也被接受过）同样打不进去。
            //   真机自己的图都过不去 ⇒ **这两句话说的是服务端那头的事，不是我们照片的事。**
            //
            //   而且注意耗时：真机成功那次全程 412 ms，现在每次 6~13 s —— 差一个数量级，
            //   这就是「上游在超时/重试」的指纹，不是「图片不合格」的指纹。
            //
            //   所以现在的判读是：
            //     系统错误 / 人脸验证错误 + 请稍后重试  ⇒ 服务端那头挂了或超时（**换照片没用**）
            //     未拍到完整人脸                        ⇒ 图能解码但没找到完整人脸（**这才该换照片**）
            if (st != 1) {
                long avg = msTotal / Math.max(1, shots);
                if (info.contains("请稍后重试")) {
                    log.log("         ↳ 判读：**服务端那头没跑通**（原话「" + info + "」），不是照片的问题。");
                    log.log("           依据（2026-10-08 20:58 正对照）：真机 10-06 那张 264394 字节的原图，"
                            + "服务端当天接受过，当晚连打 3 枪同样被拒；每枪 6~13 s，"
                            + "而真机成功那次只要 412 ms。");
                    log.log("           ⇒ 换照片、重设人脸都没用。过一阵再跑，或先拿别的接口试试服务端活没活。");
                } else if (info.contains("未拍到完整人脸")) {
                    log.log("         ↳ 判读：图能解码，但画面里没找到一张完整的人脸 —— "
                            + "裁的位置不对（脸被切掉/离得太远），**这次才该换一张原图重设**。");
                } else {
                    log.log("         ↳ 判读：服务端回了一句没见过的：" + info + "（平均 " + avg + " ms）");
                }
            }
            JSONObject d = r1.optJSONObject("data");
            String id = d != null ? d.optString("run_face_verify_id", "") : "";
            if (id.length() == 0) id = r1.optString("run_face_verify_id", "");
            if (id.length() == 0 && d != null) {
                // 兜底：万一服务端换个名字（face_verify_id / id / verify_id）
                for (String k : new String[]{"face_verify_id", "id", "verify_id"}) {
                    String v = d.optString(k, "");
                    if (v.length() > 0) { id = v; break; }
                }
            }
            return id;
        } catch (Throwable t) {
            log.log("      [人脸] 异常：" + t);
            return "";
        }
    }

    /**
     * 照片体检 —— 判断"这个文件到底是不是一张完整的 JPEG"。
     *
     * ★ 为什么需要它（2026-10-08 19:23 事故）：服务端那句
     *   `{"status":0,"info":"人脸验证错误，请稍后重试！"}` 看着像"照片不合格"，
     *   其实它的判据是**服务端根本没法把收到的东西当图解码**（docs/29 实测表：
     *   1×1 像素、纯文本，回的都是这一句）。而"图能解码、只是画面里没脸"是另一句
     *   「未拍到完整人脸，请离镜头稍远一点重拍…」。
     *
     *   既然服务端分得这么细，我们发之前就该先分一次 —— 否则一张半截文件
     *   会把"我们发错了东西"伪装成"服务端间歇性抽风"，白白浪费一整天。
     *
     * 判据（本地几百微秒）：
     *   ① 头必须是 FF D8；
     *   ② 尾必须是 FF D9 —— **少了 EOI 就是没写完**，而带 FF D8 的半截文件
     *      恰恰能骗过原来那句只查头两字节的自检。
     *
     * @return "" 表示健康；否则是一句人话原因
     */
    static String jpegDiag(byte[] jpg) {
        if (jpg == null || jpg.length < 128) {
            return "文件只有 " + (jpg == null ? 0 : jpg.length) + " 字节（太小，不可能是照片）";
        }
        if ((jpg[0] & 0xFF) != 0xFF || (jpg[1] & 0xFF) != 0xD8) {
            return String.format(Locale.US, "开头不是 JPEG 标记（%02X %02X）",
                    jpg[0] & 0xFF, jpg[1] & 0xFF);
        }
        int n = jpg.length;
        if ((jpg[n - 2] & 0xFF) != 0xFF || (jpg[n - 1] & 0xFF) != 0xD9) {
            return String.format(Locale.US,
                    "JPEG 没有结尾标记 EOI（尾部是 %02X %02X %02X %02X）—— 这个文件写了一半",
                    jpg[n - 4] & 0xFF, jpg[n - 3] & 0xFF, jpg[n - 2] & 0xFF, jpg[n - 1] & 0xFF);
        }
        return "";
    }

    /** 上一次核验拿到的 id —— 真机在 `this.d` 非空时会把它带回下一次请求 */
    private volatile String lastFaceId = "";

    /**
     * 上一次 `Run2/faceVerify` 的 status（1 = 真通过）。
     *
     * ★ 为什么必须单独记：服务端**验脸失败时也会下发 run_face_verify_id**
     *   （docs/29 实测：纯黑图 status=0 照样给 id），所以
     *   「拿到了 id」和「验过了」是两件事。旧代码只看 id 非空就往下走，
     *   于是一次失败的核验会被一路当成通过 —— 连收尾上报的
     *   `run_face_verify_id` 都是那个失败件的编号。
     */
    private volatile int lastFaceStatus = -1;

    /**
     * ★★★ 2026-10-10：上一次核验时**服务端的原话**（响应里的 info）。
     *
     * <p>以前这句话只在日志里打一遍就丢了，界面要弹「没通过的原因」时手上只有
     * 一个 status=0 —— 那是开发话，普通用户看不懂。用户第 1 条要的是「弹原因」，
     * 而这两句话的含义天差地别（docs/50 的正对照）：
     * <pre>
     *   系统错误，请稍后重试！  ⇒ 服务端自己没跑通，换照片没用
     *   未拍到完整人脸…        ⇒ 图能解码但没找到脸，这才该换照片
     * </pre>
     * 判读逻辑见 {@link Err#faceReason}。
     */
    private volatile String lastFaceInfo = "";

    /** 本场跑步里**至少有一次**人脸核验真的 status==1。收尾时如实报出来。 */
    public volatile boolean faceOk = false;

    private static final String FACE_PHOTO = "/data/data/com.ledao.tester/files/face.jpg";

    private boolean sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (stopped) return false;
            try { Thread.sleep(200); } catch (InterruptedException e) { return false; }
        }
        return !stopped;
    }

    /** 近似距离（米）：纬度 111320 m/度，经度按 cos(纬度) 收缩 */
    private static double dist(double[] p, double[] q) {
        double dy = (q[0] - p[0]) * 111320.0;
        double dx = (q[1] - p[1]) * 111320.0 * Math.cos(Math.toRadians(p[0]));
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double round6(double v) { return Math.round(v * 1e6) / 1e6; }

    /**
     * ★★★ 跑区多边形判定 —— 对应报文里的 `is_running_area_valid`。
     *
     * 真机实体两次都是 `true`，而跑区四角来自 beforeRunV260 的 `run_zone_latlng`：
     *   ["114.588111,36.660305","114.587887,36.649753",
     *    "114.605873,36.649419","114.605393,36.660356"]
     * 服务端显然会拿轨迹去核这个多边形，我们以前从不发这个字段、也从不检查。
     * 这里用射线法逐点判，任何一个点出界就算 false（宁可如实报 false 也不要撒谎）。
     *
     * @param samples  {lat, lon, …} 的采样列表
     * @param rect     {lat, lon} 的角点列表（顺序即多边形顺序）
     */
    public static boolean inZone(List<double[]> samples, List<double[]> rect) {
        if (rect == null || rect.size() < 3 || samples == null || samples.isEmpty()) return true;
        for (double[] s : samples) {
            if (!pointInPoly(s[0], s[1], rect)) return false;
        }
        return true;
    }

    /** 射线法：点 (lat,lon) 是否在多边形 poly（{lat,lon} 顶点）内 */
    public static boolean pointInPoly(double lat, double lon, List<double[]> poly) {
        boolean in = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = poly.get(i)[0], xi = poly.get(i)[1];
            double yj = poly.get(j)[0], xj = poly.get(j)[1];
            if (((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                in = !in;
            }
        }
        return in;
    }

    /** 一条 via 链的单程长度（米） */
    private static double oneWayOf(java.util.List<double[]> chain) {
        double s = 0;
        for (int i = 1; i < chain.size(); i++) s += dist(chain.get(i - 1), chain.get(i));
        return s;
    }

    // =====================================================================
    //  打卡点选择 —— 逐行复刻真机 ya/i0.java（RunLogManager，classes4.dex）
    //
    //  ★ 真相（docs/15）：打卡点是**手机自己在起跑那一刻抽的**，不是服务端分配的。
    //    服务端只下发「全部 N 个点 + 一套规则参数」；App 用当前 GPS 做一次
    //    **均匀随机抽样**抽 point_num 个。起跑点只影响两件事：
    //      · 剔除 type!=1 且 距起跑点 < point_min_distance*1000 的点
    //      · 守卫：第 point_num 近的点 > point_max_distance2*1000 → 一个点都不给
    //
    //  回归验证（scripts/rule_sim.py，N=200000）：复刻版 91/91 种点对全部可达、
    //  每个点被选中概率 14.31%（理论 2/14=14.29%）；而旧实现（锚点取自普通点组 +
    //  强制第 2 点在锚点 1km 内）只有 77/91 可达，且**永远抽不出真机 9/30 抽到的
    //  「29001+28988」**。
    // =====================================================================

    /** 服务端打卡点（对应 App 的 RunLogPoint / LocalRunLogPoint 实体，22 字段的常用子集）。 */
    public static final class LogPt {
        public String id = "";
        public String address = "";
        public String isOnline = "1";
        public int type = 0;          // 1 = 必经点；其余 = 普通点
        public int pointType = 1;     // 1 = 在线
        public double lat, lon;
        public double distM;          // 距起跑点直线距离（米）
        public boolean isSignUp;      // 是否已打卡
        public boolean pMarked;       // （已废弃）docs/13 的 p:1 假设已被金样本证伪
        public long timestamp;        // 打卡时刻（epoch 秒）；0 = 没打到
        public double hitKm;          // 打卡那一刻的累计里程（km）
        // ★★★ 打卡那一刻的**实时 GPS 位置** —— 这才是 logPoints 里要上报的坐标。
        //   金样本（work/today/track_1851.bin，2026-09-30 18:51 校园跑，打卡成功）实证：
        //     logPoints = [{"distance":0.0,   ...,"point_id":29001,"time":0},
        //                  {"distance":0.56,  "latitude":36.65712,
        //                   "longtitude":114.60427,"point_id":28988,"time":1790765481}]
        //   打到的那个点报的是**踩到那一刻的实时 GPS**（离点位自身坐标 45.8 m），
        //   不是点位坐标；没打到的那个点才报点位自身坐标 + time=0 + distance=0.0。
        //   我们以前把两者都吸附到点位坐标 —— 方向正好反了（docs/21 §3.2）。
        public double hitLat, hitLon;

        public String tag() { return id + "(" + address + ")"; }
    }

    /** i0.j()：注意 type==1 的必经点**无条件入选，不看距离**。 */
    private static void jAdd(List<LogPt> cands, double limitM, double[] anchor,
                             double minM, List<LogPt> out) {
        for (LogPt p : cands) {
            double d = dist(anchor, new double[]{p.lat, p.lon});
            if (p.type == 1) {
                if (!out.contains(p)) out.add(p);
            } else if (d > minM && d < limitM && !out.contains(p)) {
                out.add(p);
            }
        }
    }

    /** i0.l()：入参为空表时返回 null（原代码 return null）。 */
    private static List<LogPt> jList(List<LogPt> cands, double limitM,
                                     double[] anchor, double minM) {
        if (cands == null || cands.isEmpty()) return null;
        List<LogPt> out = new ArrayList<>();
        jAdd(cands, limitM, anchor, minM, out);
        return out;
    }

    /** i0.n()：1km 池随机 → 2km 池随机 → 全表随机；**入参池为空直接返回 null**。 */
    private static LogPt nPick(List<LogPt> pool, double[] anchor, double max1,
                               double max2, double minM, Random rnd) {
        if (pool == null || pool.isEmpty()) return null;
        List<LogPt> l1 = jList(pool, max1, anchor, minM);
        if (l1 != null && !l1.isEmpty()) return l1.get(rnd.nextInt(l1.size()));
        List<LogPt> l2 = jList(pool, max2, anchor, minM);
        if (l2 != null && !l2.isEmpty()) return l2.get(rnd.nextInt(l2.size()));
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** i0.m()：同 n()，但 1km/2km 都空时返回 null（不回退全表）。 */
    private static LogPt mPick(List<LogPt> pool, double[] anchor, double max1,
                               double max2, double minM, Random rnd) {
        if (pool == null || pool.isEmpty()) return null;
        List<LogPt> l1 = jList(pool, max1, anchor, minM);
        if (l1 != null && !l1.isEmpty()) return l1.get(rnd.nextInt(l1.size()));
        List<LogPt> l2 = jList(pool, max2, anchor, minM);
        if (l2 != null && !l2.isEmpty()) return l2.get(rnd.nextInt(l2.size()));
        return null;
    }

    /** a1.b(0, n, k)（utils/a1.java:21）：均匀无放回抽 k 个不同下标。 */
    private static List<Integer> a1b(int n, int k, Random rnd) {
        List<Integer> out = new ArrayList<>();
        if (k <= 0 || k > n) return out;
        while (out.size() < k) {
            int v = rnd.nextInt(n);
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    /**
     * 逐行复刻 i0.e() → i0.f()（学生角色），即真机的选点算法。
     *
     * @return 抽中的 point_num 个点（顺序已 shuffle）；失败返回 null（真机也是 return null）
     */
    public static List<LogPt> selectPoints(List<LogPt> pts, double startLat, double startLon,
                                           int pointNum, int online, int offline,
                                           double max1M, double max2M, double minM,
                                           Random rnd, StringBuilder trace) {
        if (pts == null || pts.isEmpty()) return null;
        if (pointNum <= 0 || pointNum > pts.size()) return null;
        if (trace == null) trace = new StringBuilder();

        // ②③ 算到起跑点的直线距离并升序排序
        final double[] start = new double[]{startLat, startLon};
        for (LogPt p : pts) p.distM = dist(start, new double[]{p.lat, p.lon});
        Collections.sort(pts, new Comparator<LogPt>() {
            public int compare(LogPt a, LogPt b) { return Double.compare(a.distM, b.distM); }
        });

        // ④ 剔除 D()：type!=1 且 距起跑点 < point_min_distance*1000
        List<LogPt> kept = new ArrayList<>();
        for (LogPt p : pts) {
            if (p.type != 1 && p.distM < minM) {
                trace.append("剔除(<").append((int) minM).append("m)").append(p.id).append(" ");
                continue;
            }
            if (!kept.contains(p)) kept.add(p);
        }
        if (kept.size() < pointNum) return null;

        // ⑤ 分四组
        List<LogPt> A2 = new ArrayList<>(), A3 = new ArrayList<>(),
                A4 = new ArrayList<>(), A5 = new ArrayList<>();
        for (LogPt p : kept) {
            if (p.type == 1) {
                if (p.pointType == 1) A2.add(p); else A3.add(p);
            } else {
                if (p.pointType == 1) A4.add(p); else A5.add(p);
            }
        }
        trace.append("分组 必经在线=").append(A2.size())
                .append(" 必经离线=").append(A3.size())
                .append(" 普通在线=").append(A4.size())
                .append(" 普通离线=").append(A5.size()).append("; ");

        List<LogPt> chosen = new ArrayList<>();

        // ⑥ 只要 1 个点
        if (online + offline == 1) {
            LogPt p = nPick(online == 1 ? A2 : A3, start, max1M, max2M, minM, rnd);
            if (p == null) p = mPick(online == 1 ? A4 : A5, start, max1M, max2M, minM, rnd);
            if (p == null) return null;
            chosen.add(p);
            return chosen;
        }

        // ⑦ 守卫
        LogPt nth = kept.get(pointNum - 1);
        if (nth.distM > max2M) {
            trace.append("★守卫拦截：第 ").append(pointNum).append(" 近的 ").append(nth.id)
                    .append(" 距起跑点 ").append((int) nth.distM).append("m > ")
                    .append((int) max2M).append("m; ");
            return null;
        }
        trace.append("守卫OK(第").append(pointNum).append("近=").append(nth.id)
                .append(" ").append((int) nth.distM).append("m); ");

        // ⑧ 锚点池 = 必经点组 A2/A3（**不是** A4！）
        LogPt anchor = nPick(online > 0 ? A2 : A3, start, max1M, max2M, minM, rnd);

        if (anchor != null) {
            // ⑨-a 锚点分支
            trace.append("锚点=").append(anchor.tag()).append("; ");
            chosen.add(anchor);
            int needOn = online > 0 ? online - 1 : online;
            int needOff = online > 0 ? offline : offline - 1;
            double[] a = new double[]{anchor.lat, anchor.lon};
            List<LogPt> restOn = new ArrayList<>(), restOff = new ArrayList<>();
            for (LogPt p : A2) if (p != anchor) restOn.add(p);
            for (LogPt p : A4) if (p != anchor) restOn.add(p);
            for (LogPt p : A3) if (p != anchor) restOff.add(p);
            for (LogPt p : A5) if (p != anchor) restOff.add(p);
            if (needOn > 0) {
                List<LogPt> pool = jList(restOn, max1M, a, minM);
                if (pool == null || pool.size() < needOn) pool = jList(restOn, max2M, a, minM);
                if (pool == null || pool.size() < needOn) return null;
                for (int i : a1b(pool.size(), needOn, rnd)) chosen.add(pool.get(i));
            }
            if (needOff > 0) {
                List<LogPt> pool = jList(restOff, max1M, a, minM);
                if (pool == null || pool.size() < needOff) pool = jList(restOff, max2M, a, minM);
                if (pool == null || pool.size() < needOff) return null;
                for (int i : a1b(pool.size(), needOff, rnd)) chosen.add(pool.get(i));
            }
        } else {
            // ⑨-b 纯随机分支（我校跑区 14 个点全是 type=2/point_type=1 → 永远走这里）
            trace.append("必经点池为空→纯随机 a1.b(0,").append(A4.size()).append(",")
                    .append(online).append("); ");
            if (online > 0) {
                if (A4.size() < online) return null;
                for (int i : a1b(A4.size(), online, rnd)) chosen.add(A4.get(i));
            }
            if (offline > 0) {
                if (A5.size() < offline) return null;
                for (int i : a1b(A5.size(), offline, rnd)) chosen.add(A5.get(i));
            }
        }

        Collections.shuffle(chosen, rnd);   // ⑩ i0.f() 505 行
        return chosen;
    }

    /**
     * 由「必过两点」构建途经点链：A → （落在 A→B 连线中段的若干点）→ B。
     *
     * 只用 2 个点的话，2 公里得在原地来回折返好几趟，轨迹叠成一条编织线一眼假；
     * 串上中间几个点，单程就变长，跑起来是一次正常的绕校园跑。
     */
    public static void buildChain(List<double[]> all, List<String> allIds,
                                  int bestA, int bestB,
                                  List<double[]> cps, List<String> cids) {
        cps.clear();
        cids.clear();
        if (all == null || all.size() < 2) return;
        if (bestA < 0 || bestB < 0 || bestA >= all.size() || bestB >= all.size()) return;
        List<Integer> order = new ArrayList<>();
        order.add(bestA);
        final double[] A = all.get(bestA), B = all.get(bestB);
        double vLat = B[0] - A[0], vLon = B[1] - A[1];
        double vv = vLat * vLat + vLon * vLon;
        List<double[]> mid = new ArrayList<>();   // {投影t, 离连线距离, 下标}
        for (int i = 0; i < all.size(); i++) {
            if (i == bestA || i == bestB) continue;
            double[] p = all.get(i);
            double t = vv < 1e-12 ? 0
                    : ((p[0] - A[0]) * vLat + (p[1] - A[1]) * vLon) / vv;
            if (t <= 0.12 || t >= 0.88) continue;          // 只取连线中段的
            double off = Math.abs((p[0] - A[0]) * vLon - (p[1] - A[1]) * vLat) / Math.sqrt(vv);
            if (off > 0.0016) continue;                    // 偏离连线太远的不要（约 140 米）
            mid.add(new double[]{t, off, i});
        }
        Collections.sort(mid, new Comparator<double[]>() {
            public int compare(double[] x, double[] y) { return Double.compare(x[0], y[0]); }
        });
        int take = Math.min(4, mid.size());
        for (int i = 0; i < take; i++) order.add((int) mid.get(i)[2]);
        order.add(bestB);
        for (int idx : order) {
            cps.add(all.get(idx));
            cids.add(allIds.get(idx));
        }
    }

    /**
     * 从 beforeRunV260 的响应里取「规则 + 点位」，跑一次真机选点算法。
     *
     * @return 抽中的分配对（List&lt;LogPt&gt;）；拿不到规则/点位时返回 null（调用方保留预选）
     */
    public static List<LogPt> selectAssignedFromBeforeRun(JSONObject bd,
                                                          double startLat, double startLon,
                                                          Random rnd, StringBuilder trace) {
        if (bd == null) return null;
        JSONObject rli = bd.optJSONObject("run_line_info");
        if (rli == null) return null;
        JSONArray pl = rli.optJSONArray("point_list");
        if (pl == null || pl.length() < 2) return null;

        List<LogPt> list = new ArrayList<>();
        for (int i = 0; i < pl.length(); i++) {
            JSONObject o = pl.optJSONObject(i);
            if (o == null) continue;
            String[] jw = o.optString("jingwei", "").split(",");
            if (jw.length < 2) continue;
            LogPt p = new LogPt();
            p.id = o.optString("id", o.optString("point_id", ""));
            p.address = o.optString("address", "");
            p.type = o.optInt("type", 0);
            p.pointType = o.optInt("point_type", 1);
            p.isOnline = o.optString("is_online", "1");
            try {
                p.lat = Double.parseDouble(jw[0]);
                p.lon = Double.parseDouble(jw[1]);
            } catch (Exception e) {
                continue;
            }
            if (p.id.length() > 0) list.add(p);
        }
        if (list.size() < 2) return null;

        int pointNum = rli.optInt("point_num", 2);
        // ★ point_num_online/offline 在 time_rule_arr[i] 里（按里程分段），不在顶层！
        //   （旧代码读顶层 data.point_num_online 永远得到 0，会静默走错分支 —— docs/15 §四）
        int online = bd.optInt("point_num_online", -1);
        int offline = bd.optInt("point_num_offline", -1);
        if (online < 0 || offline < 0) {
            JSONArray arr = bd.optJSONArray("time_rule_arr");
            JSONObject t = null;
            if (arr != null && arr.length() > 0) t = arr.optJSONObject(0);
            if (t != null) {
                online = t.optInt("point_num_online", 2);
                offline = t.optInt("point_num_offline", 0);
            } else {
                online = 2;
                offline = 0;
            }
        }
        double max1 = rli.optDouble("point_max_distance1", 1.0) * 1000.0;
        double max2 = rli.optDouble("point_max_distance2", 2.0) * 1000.0;
        double minM = rli.optDouble("point_min_distance", 0.01) * 1000.0;
        if (trace != null) {
            trace.append("point_num=").append(pointNum)
                    .append(" online=").append(online)
                    .append(" offline=").append(offline)
                    .append(" max1=").append((int) max1)
                    .append(" max2=").append((int) max2)
                    .append(" min=").append((int) minM).append("; ");
        }
        return selectPoints(list, startLat, startLon, pointNum, online, offline,
                max1, max2, minM, rnd, trace);
    }

    /** 兜底用的「均匀随机抽 2 个不同点」——只在拿不到 beforeRun 规则参数时使用。
     *  （正常路径必须走 selectPoints()。） */
    public static int[] pickPairUniform(int n, Random rnd) {
        if (n < 2) return new int[]{0, Math.max(0, n - 1)};
        int a = rnd.nextInt(n), b = rnd.nextInt(n);
        while (b == a) b = rnd.nextInt(n);
        return new int[]{a, b};
    }

    /** 取本机 WLAN IPv4（App 上报的 ip 就是这个） */
    public static String wifiIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (n == null || !n.startsWith("wlan")) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address)
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignore) { }
        return "10.0.0.1";
    }

    /** point_id 在 App 里是 long；这里容错解析（拿不到就给 0）。 */
    public static long parseIntSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    public static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String shortTok(String t) {
        if (t == null) return "(无)";
        return t.length() <= 12 ? t : t.substring(0, 12) + "…";
    }
}
