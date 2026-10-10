package com.ledao.tester;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;

/**
 * MainActivity —— 步道乐跑跑步测试客户端 v2.2
 *
 * 用法（老师那边三步）：
 *   ① 装 APK  ② 点「🔐 安装证书」→ 系统弹窗点确定  ③ 点「▶ 开始跑步」
 *
 * v2.2 修掉的三个坑（都在 2026-09-29 的真机日志里现形过）：
 *   1. 端口 8899 被上一轮遗留的 socket 占死 → 之后每次抓包都 EADDRINUSE。
 *      修法：LocalProxy 改成进程级单例，重入时复用而不是重 bind。
 *   2. VPN 模式下还去写系统代理 → 没 WRITE_SECURE_SETTINGS 就抛 SecurityException，
 *      日志里一片红，其实是纯噪音。修法：VPN 通了就完全不碰系统代理。
 *   3. 抓身份的时序反了：先杀步道乐跑再用**旧**令牌去校验，必然 102。
 *      修法：先抓（自动打开步道乐跑）→ 抓到立刻杀 → 再用新令牌开跑。
 */
public class MainActivity extends Activity implements Ledao.Log {

    private static final String PREF = "ledao";
    private static final String PKG_LEDAO = "com.lptiyu.tanke";
    private static final int REQ_VPN = 8801;
    private static final int REQ_PICK_MARKER = 8802;
    private static final int REQ_PICK_BG = 8803;
    private static final int REQ_FACE = 8804;      // 人脸核验用的自拍（docs/28）
    /**
     * 人脸照片的体积上限。
     *
     * <p>照片是**原字节直存**的（用户要求不做任何检测/裁剪/压缩），所以要防一手
     * 「相册里误选了 40 MB 的 RAW 或全景图」—— 那种上传不稳，也没必要。
     * 真机自拍量级是 170~290 KB，8 MB 已经是二十多倍的余量，正常照片碰不到。
     */
    private static final long FACE_MAX_BYTES = 8L * 1024 * 1024;
    private static final int REQ_PICK_AVATAR = 8805;  // ★ #8：水滴定位图标里的头像
    /** 自动打开步道乐跑后停留多久（毫秒）——够它把 User/User + RefreshToken 发完 */
    private static final long LEDAO_DWELL_MS = 8000;

    /** 所有「卡片」容器 —— 换主题时统一刷背景 */
    private final List<View> cards = new ArrayList<>();
    /**
     * ★ 2026-10-10 第 5 条（按钮要半透明）：所有由 {@link #mkBtn} 造出来的按钮。
     * 各自的**原色记在 tag 上**，换主题 / 铺背景照片时统一重刷 ——
     * 这样地图那排「＋ － 全校 定位 路网」、侧边栏按钮、开始/停止按钮一个都不会漏。
     */
    private final List<Button> tinted = new ArrayList<>();
    /** 按钮当前的不透明度：铺了背景照片 → 0.70，否则 1.0（原样） */
    private float btnAlpha = 1f;

    /** 校园水面位图（路线避湖用），启动时加载一次 */
    private WaterGrid water;
    private RoadGrid roadGrid;
    /**
     * ★★★ 手画路网：1683 个街口 / 2871 条街段 / 59.5 km，从 assets/routenet.bin 读。
     * 新版路线规划（work/route/route_lib.js → {@link RoutePlan}）就在这张图上
     * 随机游走。读不到时返回 null，那时自动回落到旧的 {@link RouteGen}。
     */
    private RouteNet routeNet;

    // ---------------- UI
    private EditText etIdent;
    private TextView tvLog, tvStatus, tvIdentBrief, tvRouteHead, tvRouteInfo, tvTitle, tvAutoInfo;
    private Button btnRun, btnSniff, btnClear, btnProxyHelp, btnCopyLog, btnCa, btnEdit, btnRealId, btnFace, btnBg;
    private Button btnMenu;
    /** 地图卡片的容器 */
    private CampusMapView map;
    private LinearLayout routeBody, drawerPanel, markGrid;
    private View identEditor;
    private ScrollView scLog;
    /** 侧边栏 */
    private FrameLayout rootFrame;
    private LinearLayout contentRoot;
    private View scrim, drawerHolder;
    private boolean drawerOpen = false;
    private int drawerW = 292;

    // ---------------- 状态
    private Thread worker;
    private Thread extractThread;
    /** 「载入步道乐跑取标识」的后台线程 */
    private Thread realIdThread;
    private volatile Ledao ledao;
    private LocalProxy proxy;
    private int sniffHits = 0;
    private boolean pendingAutorun = false;
    private int autorunRound = 0;

    // ==================================================================
    //  ★★★ 进程级状态 —— 必须 static，不能是实例字段！
    //
    //  用户实测（2026-09-30 20:37）：「跑着跑着就跳转、重开页面」，
    //  日志里同时有 3 个跑步线程在跑（分配对在 [28993…]/[28987…]/[28994…] 之间来回切），
    //  结果三个线程抢同一个账号的会话 → 服务端一路回 102「已在其他手机登录」。
    //
    //  根因：助手为了抢身份会反复「打开步道乐跑 → 切回助手」。切回来时
    //  **MainActivity 被重建**（AndroidManifest 里没写 launchMode，默认 standard），
    //  新实例的 worker = null，旧实例的跑步线程却还活着 → 互斥形同虚设。
    //
    //  所以互斥量、Ledao 句柄、日志落点全部改成进程级。
    // ==================================================================
    /** 进程级「有跑步在跑」互斥量 */
    private static volatile boolean RUN_ACTIVE = false;    /** 当前正在跑的 Ledao 句柄（停止按钮要用） */
    private static volatile Ledao RUN_LEDAO = null;
    /** 跑步线程本身 */
    private static volatile Thread RUN_WORKER = null;
    /** 当前活着的 Activity —— 旧线程的日志要打到它上面，而不是已经销毁的那个 */
    private static volatile MainActivity LIVE = null;

    /**
     * ★ v1.0.25：这个界面已经被销毁了（划掉 / 系统回收）。
     *
     * <p>跑步线程还在跑，它照样会调 {@code log()} / {@code onRoute()} —— 那些方法
     * 不要再往一个没有窗口的 Activity 上贴文字（会白干活，个别 ROM 还会报警告）。
     * 日志照旧写 logcat 和 files/run.log，收尾也照旧。
     */
    private volatile boolean dead = false;

    /** 「只抓到 uid+token」时的快速兜底是否已排上（别重复排定时器） */
    private boolean quickFallbackArmed = false;
    private final TreeMap<String, String> sniffKv = new TreeMap<>();
    private boolean vpnMode = false;
    private boolean sniffAfterVpnGrant = false;
    private boolean recordMode = false;
    private boolean sysProxyOwned = false;
    private boolean routeOpen = true;
    private long logHoldUntil = 0;
    /** 标题栏文案用的两个小状态（打卡点对 / 已打卡数） */
    private String routePair = null;
    private int routeKpHit = 0;
    /** ★ 界面优化 #1（2026-10-07）：手动设置框已删。走 Intent 的自动化还可以指定里程，
     *  0 表示「不指定，交给系统自动随机」。 */
    private static volatile double forcedKm = 0;
    /**
     * ★★★ 2026-10-08：本次要跑的里程（公里，一位小数）。
     *
     * 存在的唯一理由：让**白框「本次预览里程」和绿框「目标 N km」永远是同一个数**。
     * 以前这两处各自随机：预览自己抽一个 2.5，startRun 又抽一个 3.5，
     * 于是用户一眼就看到「上面说 3.5 公里，下面说 2.5 公里」。
     * 现在只在这里抽一次：预览按它生成路线并把数字显示出来，开跑时也用它。
     */
    private double planKm = 0;
    /** 预览用的那两个打卡点在 14 个内置点里的下标（显示成 1-based 序号） */
    private int[] planPair = null;
    /** 服务端真正指派的两个打卡点 id —— 一旦拿到，白框就改说这两个 */
    private String[] planIds = null;
    /**
     * ★★★ 预览那条路线的随机种子 + 它在路网里的打卡点序号对。
     *
     * 开跑时把这两个一起交给 {@link Ledao}：**如果服务端指派的还是同一对点，
     * 就用同一个种子重生成 —— 结果是逐点相同的一条线**，
     * 也就是界面上那句「点『开始跑步』就跑这一条」真的成立了。
     * 点对不一样（服务端换人了）才重新抽一个种子。
     */
    private long planSeed = 0;
    private int[] planCpN = null;
    private final Random rnd = new Random();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    // ==================================================================
    //  生命周期
    // ==================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LIVE = this;                     // ★ 让还在跑的旧线程把日志打到这个新实例上
        drawerW = dp(280);               // ★ 一定要用 dp —— 直接写 292 会变成 292 像素
        water = WaterGrid.load(this);    // ★ 校园水面位图，路线避湖要用（必须在 buildUI 之前）
        roadGrid = RoadGrid.load(this);  // ★ 校园可跑街道栅格 —— 「只沿街道跑」靠它
        routeNet = RouteNet.load(this);  // ★★★ 手画路网（街口/街段图）—— 新版路线规划就靠它
        setContentView(buildUI());
        applyTheme();

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        etIdent.setText(sp.getString("ident", ""));
        routeOpen = sp.getBoolean(AppPrefs.K_ROUTE_OPEN, true);
        applyRouteOpen();

        log("宇达步道乐跑助手 " + getString(R.string.app_version));
        // ★★★ 路网加载结果 —— 新版路线规划成不成，全看这一行
        if (routeNet != null) {
            log("✅ " + routeNet + "（路线规划已启用：街口/街段图上随机游走）");
            if (map != null) {
                map.setNetwork(routeNet.netLatLon(), routeNet.netBreaks());
            }
        } else {
            log("⚠ 路网资源 routenet.bin 读不到 —— 路线回落到旧版（街道栅格 A*）");
        }
        Err.installCrashHandler(this, this);   // ★ #7：未捕获异常也要能看见位置和原因
        for (String line : Err.crashSummary(this)) log(line);   // ★ #7：上次崩过就先说清楚
        log("签名A=MD5(sorted_kv+rDJiNB9j7vD2) · 签名B=MD5(eFoqdHOdDy6ViJ0i+sorted_kv[-mobileDeviceId])");
        log("lpi0=风控令牌 base64(AES-CBC(umid+MD5(timestamp), KEYS[i], IVS[i]))  15组配对表");
        log(Ledao.selfTestLpi0());          // ★ 每次启动自查：必须与真机令牌逐字节一致
        log("------------------------------------------------------------");
        log("★ 直接点「▶ 开始跑步」：会自动提取身份 → 自动开跑 → 自动收尾。");
        log("  计分条件：≥2.00 km、配速 3'00\"~8'00\"/km、经过 ≥2 个打卡点、当天未跑过");
        log("");

        if (isCaInstalled()) {
            log("✅ CA 证书已就绪 —— 「自动提取」可用");
        } else {
            log("⚠ 还没装抓包证书 —— 点「🔐 安装证书」，系统弹窗里点「确定」即可");
            if (!sp.getBoolean("ca_prompted", false)) {
                sp.edit().putBoolean("ca_prompted", true).apply();
                ui.postDelayed(this::installCa, 1500);
            }
        }
        log("");

        healStaleProxy();
        handleIntent(getIntent());
        refreshIdentBox();
        askNotifyPermission();

        // ★ 上次的抓包如果还活着（前台服务保住了进程），直接重新接管 —— 不重开端口
        if (LocalProxy.listening()) {
            proxy = LocalProxy.current();
            if (proxy != null) proxy.setSink(mkSink());
            log("[代理] 上一轮抓包仍在运行 —— 已就地接管（不再重 bind，端口冲突自愈）");
            setStatus("⏳ 抓包进行中 —— 请打开步道乐跑");
        }
        updateSniffBtn();
        // ★ 先把 14 个打卡点和一条预览路线画到地图上（真正开跑时会被服务端返回的覆盖）
        map.setCheckpoints(defaultCps());
        map.post(() -> {
            map.fitRoute();
            drawPreviewRoute();
        });

        // ★ 界面优化 #9（2026-10-07）：进软件默认弹「开发者留言」。
        //   首次停留 30 秒才能关；之后每次进来都弹，但可勾「未来均不弹出」。
        ui.postDelayed(() -> {
            if (isFinishing()) return;
            if (DevNote.shouldShow(this)) DevNote.show(this, null);
        }, 700);

        if (etIdent.getText().toString().trim().length() == 0) {
            log("（身份是空的，先自动提取一次）");
            autoExtract(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LIVE = this;
        /* ★★★ 2026-10-10 用户第 2 条：助手被切到后台时，出错是发通知提醒的；
         *   用户点通知回来，就要把同一个弹窗补上 —— 否则他只看到一句
         *   "跑步出错了"，回来却不知道错在哪。 */
        Alerts.setForeground(true);
        Alerts.flushPending(this, this::copyLog);
        // ★ 如果跑步线程还在跑，按钮要恢复成「停止跑步」，别显示成可再开一局
        if (RUN_ACTIVE) setRunBtn(true);
        updateFaceBtn();          // 人脸照片按钮状态（docs/28）
        updateBgBtn();            // ★ v1.0.25：后台运行白名单状态（docs/59）
        showBgProbeResultIfAny();  // ★ v1.0.30：后台体检有结论了就把结论给用户（docs/62）
        restoreRunLog();          // ★ v1.0.25：把上一次（或被系统杀掉那次）的日志从磁盘捞回来
        /* ★ v1.0.25：如果本进程里跑步还在跑（用户把界面划掉又打开），
         *   把通知里的进度接着刷起来 —— notiAt 归零，下一拍就会刷一次。 */
        if (RUN_ACTIVE) {
            notiAt = 0;
            SniffGuard.runMode = true;
            log("★ 跑步还在跑（进程没死）—— 界面只是重新接管显示；日志、唤醒锁、通知都在。");
        }
        // 从步道乐跑切回来时，把代理回调重新挂到当前 Activity 上
        if (LocalProxy.listening()) {
            LocalProxy p = LocalProxy.current();
            if (p != null) {
                proxy = p;
                p.setSink(mkSink());
                updateSniffBtn();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ★ 前台/后台的唯一判据 —— Alerts 靠它决定"弹窗"还是"发通知"
        Alerts.setForeground(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        /* ★★★ v1.0.25（docs/59）：界面被销毁**不再**停掉正在跑的跑步线程。
         *
         *   以前这里是"不管界面为什么被销毁，都先把跑步线程停掉"（拿 RUN_LEDAO
         *   那个句柄调一次 stop）。于是「从最近任务里把助手划掉」等于点了
         *   「■ 停止跑步」：这一场不生成记录，而那条常驻通知还挂着
         *   （SniffGuard 有 stopWithTask="false"），看着像还在跑，其实早就停了。
         *   用户根本分不清。
         *
         *   现在把所有权摆正：跑步归**进程**管（RUN_ACTIVE / RUN_LEDAO / RUN_WORKER
         *   都是静态的），界面只是"看它的一扇窗"。划掉窗口：
         *     · 跑步继续跑、继续收尾上报（前台服务 + 唤醒锁撑着，见 SniffGuard）；
         *     · 日志继续往 files/run.log 里写（进程被杀也留得住）；
         *     · 重新打开 App，按钮直接显示「■ 停止跑步」，日志把断掉那一段从磁盘捞回来。
         *   想停就跑回来点「停止跑步」，或者点通知回到界面 —— 不再是"划掉即停止"。 */
        dead = true;
        if (LIVE == this) LIVE = null;
        // ★ 抓包守护还活着时不能停 —— 那正是要它继续抓的时候（进程还在，端口还占着，
        //   但因为有单例，下次进来会复用，不会再 EADDRINUSE）
        if (LocalProxy.listening() && !SniffGuard.alive) {
            LocalProxy.shutdown();
            restoreProxy();
        }
    }

    /**
     * ★ 2026-10-10：Android 13 起「发通知」是一个要用户点头的运行时权限。
     *
     * <p>本 App 的 targetSdk 还是 28，所以在 13/14 上系统会在**建渠道那一下**
     * 自动弹一次授权框；但 15 以后这条豁免没了，targetSdk 低也不再自动给。
     * 这里显式问一次，问不到也不影响跑步（只是后台提醒会静默）。
     */
    private void askNotifyPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 33) return;
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8810);
        } catch (Throwable ignore) { }
    }

    @Override
    protected void onNewIntent(android.content.Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        // ★ 已经在跑了就不要再被 Intent 触发一次自动开跑（那会开出第二个会话）
        if (RUN_ACTIVE && it != null && it.getBooleanExtra("autorun", false)) {
            log(">> 已有跑步在进行中，忽略本次 autorun");
            return;
        }
        handleIntent(it);
    }

    private void handleIntent(android.content.Intent it) {
        if (it == null) return;
        // ★ 通知栏那两个按钮（复制日志 / 看看详情）点进来的
        if (it.getBooleanExtra("copyLog", false)) {
            it.removeExtra("copyLog");
            ui.postDelayed(this::copyLog, 600);
        }
        if (it.getBooleanExtra("showError", false)) {
            it.removeExtra("showError");
            ui.postDelayed(() -> Alerts.flushPending(this, this::copyLog), 300);
        }
        String selftest = it.getStringExtra("selftest");
        if (selftest != null && selftest.length() > 0) {
            final String k = selftest;
            log("[自检] selftest=" + k);
            ui.postDelayed(() -> runSelfTest(k), 900);
        }
        String ident = it.getStringExtra("ident");
        String dist = it.getStringExtra("distance");
        String spd = it.getStringExtra("speed");
        if (ident != null && ident.length() > 0) {
            etIdent.setText(ident);
            refreshIdentBox();
            log("[Intent] 已注入身份参数 (" + ident.length() + " 字符)");
        }
        // ★ 2026-10-07 界面优化 #1：里程框已删，走 Intent 时记到进程级强制值上
        if (dist != null && dist.length() > 0) {
            forcedKm = parse(dist, 0);
            log("[Intent] 本次强制里程 " + forcedKm + " 公里（手动设置框已移除）");
        }
        if (it.getBooleanExtra("autorun", false)) {
            log("[Intent] autorun=true —— 2 秒后自动开始");
            ui.postDelayed(this::startRun, 2000);
        }
        if (it.getBooleanExtra("record", false)) {
            log("[Intent] record=true —— 完整记录模式");
            ui.postDelayed(() -> startRecordMode(true), 300);
        } else if (it.getBooleanExtra("sniff", false)) {
            log("[Intent] sniff=true —— 自动开始抓取身份");
            ui.postDelayed(() -> autoExtract(false), 300);
        }
        if (it.getBooleanExtra("realid", false)) {
            // ★ 调试/自动化用：载入步道乐跑现场取 mobileDeviceId（docs/24）
            log("[Intent] realid=true —— 载入步道乐跑取标识");
            ui.postDelayed(() -> captureRealId(it.getBooleanExtra("thenrun", false)), 500);
        }
    }

    // ==================================================================
    //  界面
    // ==================================================================
    private View buildUI() {
        rootFrame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(6), dp(10), dp(8));
        root.setBackgroundColor(Color.parseColor("#F2F5F3"));
        contentRoot = root;
        cards.add(root);
        rootFrame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 标题行：三条杠 + 标题（系统那条黑色标题栏已经在主题里去掉了）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        btnMenu = new Button(this);
        btnMenu.setText("☰");
        btnMenu.setTextSize(20f);
        btnMenu.setPadding(0, 0, 0, 0);
        btnMenu.setMinWidth(dp(48));
        btnMenu.setMinimumWidth(dp(48));
        btnMenu.setBackgroundColor(Color.TRANSPARENT);
        btnMenu.setOnClickListener(v -> toggleDrawer());
        bar.addView(btnMenu, new LinearLayout.LayoutParams(dp(50), dp(42)));
        tvTitle = new TextView(this);
        tvTitle.setText("宇达步道乐跑助手");
        tvTitle.setTextSize(17f);
        tvTitle.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvVer = new TextView(this);
        // ★ 2026-10-09 修：这里原来写死 "v1.0.9" —— 一路发到 v1.0.16 都没跟着走，
        //   标题栏显示 v1.0.9、日志里却是 getString(app_version) 的 v1.0.17，
        //   自己看自己都以为是装错了。现在两处同源（strings.xml 的 app_version）。
        tvVer.setText(getString(R.string.app_version));
        tvVer.setTextSize(10.5f);
        tvVer.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bar.addView(tvVer);
        root.addView(bar);

        // ---- ① 身份小框（不大，两行）
        LinearLayout idBox = new LinearLayout(this);
        idBox.setOrientation(LinearLayout.VERTICAL);
        idBox.setBackground(round(Color.WHITE, "#D6E2DA", 8));
        idBox.setPadding(dp(9), dp(7), dp(9), dp(7));

        tvIdentBrief = new TextView(this);
        tvIdentBrief.setTextSize(11.5f);
        tvIdentBrief.setTextColor(Color.parseColor("#2C3A31"));
        tvIdentBrief.setLineSpacing(dp(1), 1f);
        idBox.addView(tvIdentBrief);

        btnEdit = new Button(this);
        btnEdit.setText("✎ 手动粘贴 / 修改身份");
        btnEdit.setTextSize(10.5f);
        btnEdit.setTextColor(Color.parseColor("#1565C0"));
        btnEdit.setBackgroundColor(Color.TRANSPARENT);
        btnEdit.setPadding(0, dp(2), 0, 0);
        btnEdit.setMinHeight(0);
        btnEdit.setMinimumHeight(0);
        idBox.addView(btnEdit);

        // ★★ 「载入步道乐跑 → 现场取标识」（docs/24）：
        //   真机的 mobileDeviceId 不落盘，但助手可以把它拉起来、当场从它进程内存里读回来。
        //   不需要抓包 / 不需要装 CA / 不需要设代理。
        btnRealId = new Button(this);
        btnRealId.setText("📥 载入步道乐跑并取标识");
        btnRealId.setTextSize(10.5f);
        btnRealId.setTextColor(Color.parseColor("#1B7A4B"));
        btnRealId.setBackgroundColor(Color.TRANSPARENT);
        btnRealId.setPadding(0, dp(2), 0, 0);
        btnRealId.setMinHeight(0);
        btnRealId.setMinimumHeight(0);
        btnRealId.setOnClickListener(v -> captureRealId(false));
        idBox.addView(btnRealId);

        // ★★★ 「人脸照片」—— 真机每次跑步都要做人脸核验（docs/28）：
        //   拍照 → 上传 OSS Public/Upload/pic/face_verify/… → Run2/faceVerify
        //        → 响应 data.run_face_verify_id → 收尾时放进 stopRunV278 的
        //          `run_face_verify_id`。没有这一步，服务端会判「人脸照片验证不合格」。
        //   这里只需要**设置一次**照片（助手每次跑步自动复用、自动上传）。
        btnFace = new Button(this);
        btnFace.setTextSize(10.5f);
        btnFace.setTextColor(Color.parseColor("#B26A00"));
        btnFace.setBackgroundColor(Color.TRANSPARENT);
        btnFace.setPadding(0, dp(2), 0, 0);
        btnFace.setMinHeight(0);
        btnFace.setMinimumHeight(0);
        btnFace.setOnClickListener(v -> facePhotoMenu());
        idBox.addView(btnFace);

        /* ★★★ v1.0.25（docs/59）：「后台运行」这一行。
         *   跑步期间可以息屏 / 切走 —— 但前提是系统别把它清掉：
         *     · AOSP 那一层是"电池优化白名单"（点这里申请，走系统弹窗）；
         *     · MIUI 还有自己的一套「省电策略 / 自启动」，那个只能用文字引导。
         *   这一行同时当**状态显示**：进没进白名单一眼可见。 */
        btnBg = new Button(this);
        btnBg.setTextSize(10.5f);
        btnBg.setTextColor(Color.parseColor("#00695C"));
        btnBg.setBackgroundColor(Color.TRANSPARENT);
        btnBg.setPadding(0, dp(2), 0, 0);
        btnBg.setMinHeight(0);
        btnBg.setMinimumHeight(0);
        btnBg.setOnClickListener(v -> askBackgroundPermission());
        idBox.addView(btnBg);

        root.addView(idBox);
        /* ★★★ 2026-10-10 第 5 条踩到的老 bug：
         *   `cards` 这个列表以前**只装了 contentRoot**，而 applyTheme 里那句
         *   `if (c == contentRoot) continue;` 又把它跳过了 —— 也就是说这个列表
         *   一次都没生效过。于是「夜间黑」主题下，身份框和下面两个框仍然是
         *   写死的白色，跟主题完全对不上。
         *   现在把三个真正的卡片都装进来（顺带让它们能被调成半透明）。 */
        cards.add(idBox);

        // 身份编辑框（默认收起 —— 平时用不到，别占地方）
        identEditor = buildIdentEditor();
        identEditor.setVisibility(View.GONE);
        root.addView(identEditor);

        // ---- ② 状态条
        tvStatus = new TextView(this);
        tvStatus.setText("就绪");
        tvStatus.setTextSize(12.5f);
        tvStatus.setTextColor(Color.parseColor("#1B5E20"));
        tvStatus.setBackground(round("#E8F5E9", "#C8E6C9", 6));
        tvStatus.setPadding(dp(8), dp(5), dp(8), dp(5));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(6), 0, 0);
        root.addView(tvStatus, slp);

        // ---- ③ 主按钮：开始/停止（同一个键切换）+ 自动提取
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1lp.setMargins(0, dp(7), 0, 0);
        btnRun = mkBtn("▶  开始跑步", "#1B7A4B", 15f);
        btnRun.setMinHeight(dp(46));
        btnSniff = mkBtn("📲  自动提取", "#1565C0", 15f);
        btnSniff.setMinHeight(dp(46));
        row1.addView(btnRun, weight());
        row1.addView(btnSniff, weight());
        root.addView(row1, r1lp);

        // ---- ④ 次按钮一排（安装证书 / 设置代理 已按要求移除，挪进侧边栏）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.setMargins(0, dp(5), 0, 0);
        btnClear = mkBtn("🧹  清空日志", "#616161", 12f);
        btnCopyLog = mkBtn("📋  复制日志", "#37474F", 12f);
        row2.addView(btnClear, weight());
        row2.addView(btnCopyLog, weight());
        root.addView(row2, r2lp);

        // ---- ⑤ 跑步参数：系统自动 / 手动 两种模式
        LinearLayout param = new LinearLayout(this);
        param.setOrientation(LinearLayout.VERTICAL);
        param.setBackground(round(Color.WHITE, "#D6E2DA", 8));
        param.setPadding(dp(9), dp(7), dp(9), dp(7));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, dp(6), 0, 0);

        // ★ 2026-10-07 界面优化 #1：删除「手动设置」。
        //   跑步参数一律由系统自动设置 —— 不再给按钮、也不再给输入框，
        //   点「▶ 开始跑步」就是自动模式。里程/配速的硬约束仍在 Ledao 里兜底。
        tvAutoInfo = new TextView(this);
        tvAutoInfo.setTextSize(10.5f);
        tvAutoInfo.setTextColor(Color.parseColor("#37474F"));
        tvAutoInfo.setLineSpacing(dp(2), 1f);
        tvAutoInfo.setPadding(dp(2), dp(2), dp(2), dp(2));
        param.addView(tvAutoInfo);
        root.addView(param, plp);
        cards.add(param);          // ★ 第 5 条：参数框也要跟着主题/背景照片走

        // ---- ⑥ 跑步路线（可收起）
        LinearLayout routeBox = new LinearLayout(this);
        routeBox.setOrientation(LinearLayout.VERTICAL);
        routeBox.setBackground(round(Color.WHITE, "#D6E2DA", 8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(6), 0, 0);

        tvRouteHead = new TextView(this);
        tvRouteHead.setTextSize(12f);
        tvRouteHead.setTextColor(Color.parseColor("#1B5E20"));
        tvRouteHead.setPadding(dp(9), dp(7), dp(9), dp(7));
        routeBox.addView(tvRouteHead);

        routeBody = new LinearLayout(this);
        routeBody.setOrientation(LinearLayout.VERTICAL);
        // ★ 换成真的地图控件：可捏合缩放、可拖动，位置标注可点击
        map = new CampusMapView(this);
        map.setListener(new CampusMapView.Listener() {
            @Override
            public void onMarkerClick(double lat, double lon) {
                map.animateTo(lat, lon, Math.max(17.8, map.getZoom()));
                log("[地图] 已跳到当前位置并放大");
            }

            @Override
            public void onMapTap(double lat, double lon) { }
        });
        routeBody.addView(map, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(148)));

        LinearLayout mapBar = new LinearLayout(this);
        mapBar.setOrientation(LinearLayout.HORIZONTAL);
        mapBar.setPadding(0, dp(4), 0, 0);
        Button bIn = mkBtn("＋", "#455A64", 15f);
        Button bOut = mkBtn("－", "#455A64", 15f);
        Button bFit = mkBtn("全校", "#455A64", 11.5f);
        Button bMe = mkBtn("定位", "#1565C0", 11.5f);
        // ★ 路网底纹开关（2026-10-09）：路线是在这张图上随机游走出来的，
        //   开着它才看得懂"为什么这条线要绕这么一下"。嫌乱就关掉。
        Button bNet = mkBtn("路网", "#1B7A4B", 11.5f);
        bIn.setOnClickListener(v -> map.zoomBy(0.7));
        bOut.setOnClickListener(v -> map.zoomBy(-0.7));
        bFit.setOnClickListener(v -> map.fitRoute());
        bNet.setOnClickListener(v -> {
            if (routeNet == null) {
                Toast.makeText(this, "路网资源没加载，无法显示", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean on = !map.isShowNetwork();
            map.setShowNetwork(on);
            Toast.makeText(this, on ? "显示路网底纹" : "隐藏路网底纹", Toast.LENGTH_SHORT).show();
        });
        bMe.setOnClickListener(v -> {
            if (map.hasPosition()) map.animateTo(map.getCurLat(), map.getCurLon(), 18.0);
            else Toast.makeText(this, "还没开始跑步，暂无当前位置", Toast.LENGTH_SHORT).show();
        });
        mapBar.addView(bIn, weight());
        mapBar.addView(bOut, weight());
        mapBar.addView(bFit, weight());
        mapBar.addView(bMe, weight());
        mapBar.addView(bNet, weight());
        routeBody.addView(mapBar);

        tvRouteInfo = new TextView(this);
        tvRouteInfo.setTextSize(9.5f);
        tvRouteInfo.setTextColor(Color.parseColor("#66756A"));
        tvRouteInfo.setPadding(dp(2), dp(4), 0, dp(2));
        tvRouteInfo.setText("两指缩放 · 单指拖动 · 点蓝色标注跳回当前位置并放大");
        routeBody.addView(tvRouteInfo);
        routeBox.addView(routeBody);
        root.addView(routeBox, rlp);
        cards.add(routeBox);       // ★ 第 5 条：路线框也要跟着主题/背景照片走

        tvRouteHead.setOnClickListener(v -> {
            routeOpen = !routeOpen;
            applyRouteOpen();
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putBoolean("routeopen", routeOpen).apply();
        });

        // ---- ⑦ 日志框（占满剩余空间）
        LinearLayout logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.setMargins(0, dp(6), 0, 0);

        tvLog = new TextView(this);
        tvLog.setTextSize(10.5f);
        tvLog.setTextColor(Color.parseColor("#1F2A22"));
        tvLog.setBackgroundColor(Color.WHITE);
        tvLog.setPadding(dp(8), dp(6), dp(8), dp(6));
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        // 用户往上翻的时候别自动滚到底
        tvLog.setOnTouchListener((v, e) -> {
            logHoldUntil = System.currentTimeMillis() + 6000;
            return false;
        });
        scLog = new ScrollView(this);
        scLog.setBackground(round(Color.WHITE, "#D6E2DA", 8));
        scLog.addView(tvLog);
        logBox.addView(scLog, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(logBox, llp);

        // ---- 事件
        btnRun.setOnClickListener(v -> toggleRun());
        btnSniff.setOnClickListener(v -> {
            if (LocalProxy.listening()) stopSniff("用户手动停止");
            else autoExtract(false);
        });
        btnCopyLog.setOnClickListener(v -> copyLog());   // 全量复制，仅脱敏个人标识
        btnClear.setOnClickListener(v -> {
            logBuf.setLength(0);
            tvLog.setText("");
        });
        btnEdit.setOnClickListener(v -> {
            boolean show = identEditor.getVisibility() != View.VISIBLE;
            identEditor.setVisibility(show ? View.VISIBLE : View.GONE);
            btnEdit.setText(show ? "▲ 收起编辑框" : "✎ 手动粘贴 / 修改身份");
        });

        // ---- 侧边栏（放在最后，盖在主界面之上）
        buildDrawer();
        return rootFrame;
    }

    // ==================================================================
    //  侧边栏
    // ==================================================================
    private void buildDrawer() {
        scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> toggleDrawer());
        rootFrame.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView sc = new ScrollView(this);
        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setPadding(dp(13), dp(2), dp(13), dp(24));
        sc.addView(drawerPanel);
        drawerHolder = sc;

        // ===== 主题
        drawerPanel.addView(section("🎨 主题"));
        for (final String[] th : AppPrefs.THEMES) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            View chip = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.parseColor(th[3]));
            g.setCornerRadius(dp(7));
            chip.setBackground(g);
            row.addView(chip, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView t = new TextView(this);
            t.setText("   " + th[0]);
            t.setTextSize(13.5f);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t2 = new TextView(this);
            t2.setText("应用 ▸");
            t2.setTextSize(12.5f);
            row.addView(t2);
            row.setOnClickListener(v -> {
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putInt(AppPrefs.K_BG, Color.parseColor(th[1]))
                        .putInt(AppPrefs.K_FG, Color.parseColor(th[2]))
                        .putInt(AppPrefs.K_ACCENT, Color.parseColor(th[3]))
                        .putString(AppPrefs.K_BGIMG, "")
                        .apply();
                applyTheme();
                log("[主题] 已切换到「" + th[0] + "」");
            });
            drawerPanel.addView(row);
        }
        Button bgPick = drawerBtn("🖼  上传本地照片当背景");
        bgPick.setOnClickListener(v -> pickImage(REQ_PICK_BG));
        drawerPanel.addView(bgPick);
        Button bgClear = drawerBtn("清除背景照片 / 恢复纯色");
        bgClear.setOnClickListener(v -> {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putString(AppPrefs.K_BGIMG, "").apply();
            applyTheme();
            log("[主题] 已清除背景照片");
        });
        drawerPanel.addView(bgClear);
        TextView fgTip = new TextView(this);
        fgTip.setTextSize(11f);
        fgTip.setPadding(0, dp(6), 0, 0);
        fgTip.setText("字体颜色随主题自动匹配（深色主题用浅字，浅色主题用深字）。");
        drawerPanel.addView(fgTip);

        // ===== 运动图标（★ 2026-10-08：内置图片已并入下面「定位标注 / 头像」，这里只留颜色）
        drawerPanel.addView(section("🏃 水滴颜色"));
        TextView s1 = new TextView(this);
        s1.setTextSize(12f);
        s1.setText("定位标注是水滴状的，这里是水滴的颜色（描边和底色，选了图片也保留这一圈）：");
        s1.setLineSpacing(dp(2), 1f);
        drawerPanel.addView(s1);
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        final String[] cols = {"#1B7A4B", "#1565C0", "#D81B60", "#EF6C00", "#6A1B9A", "#212121"};
        for (final String c : cols) {
            View chip = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.parseColor(c));
            g.setCornerRadius(dp(20));
            chip.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMargins(dp(3), dp(6), dp(3), dp(6));
            chip.setOnClickListener(v -> {
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putString(AppPrefs.K_MARKER, "c:" + c).apply();
                map.setMarkerSpec("c:" + c);
                log("[图标] 已改为纯色 " + c);
            });
            colors.addView(chip, lp);
        }
        drawerPanel.addView(colors);

        // ★ 2026-10-08 用户第 3 条：「内置图片图标就是定位图像，整合给它整合到定位图像中」。
        //   原来这 40 张内置图挂在「🏃 运动图标」下面，而且点一下写的是 K_MARKER ——
        //   可 CampusMapView.drawMarker() 只认 "c:" 开头的颜色值，图片一律被忽略：
        //   **点那 40 张图其实没有任何效果**（水滴既不换图也不变色）。
        //   现在整块并进「🙂 定位标注 / 头像」：一套图、一个入口，统一写 K_AVATAR，
        //   走 avatarSpec 那条真正会画出来的路径。
        drawerPanel.addView(section("🙂 定位标注 / 头像"));
        TextView avTip = new TextView(this);
        avTip.setTextSize(12f);
        avTip.setText("定位标注是水滴状的，图片就嵌在水滴里。点一下即用：");
        avTip.setLineSpacing(dp(2), 1f);
        drawerPanel.addView(avTip);

        TextView s2 = new TextView(this);
        s2.setTextSize(12f);
        s2.setPadding(0, dp(4), 0, dp(2));
        s2.setText("内置图片（" + (assetMarkers().length + assetAvatars().length)
                + " 张，点一下即用）：");
        drawerPanel.addView(s2);

        markGrid = new LinearLayout(this);
        markGrid.setOrientation(LinearLayout.VERTICAL);
        List<String> allPic = new ArrayList<>();
        for (String s : assetMarkers()) allPic.add("markers/" + s);
        for (String s : assetAvatars()) allPic.add("avatars/" + s);
        for (int i = 0; i < allPic.size(); i += 5) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(allPic.size(), i + 5); j++) {
                final String path = allPic.get(j);
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                try {
                    InputStream in = getAssets().open(path + ".png");
                    iv.setImageBitmap(BitmapFactory.decodeStream(in));
                    in.close();
                } catch (Throwable ignore) { }
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0x22000000);
                bg.setCornerRadius(dp(50));          // 圆形预览（水滴是圆的）
                iv.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
                lp.setMargins(dp(3), dp(4), dp(3), dp(4));
                iv.setOnClickListener(v -> {
                    String spec = "a:" + path;
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putString(AppPrefs.K_AVATAR, spec).apply();
                    map.setAvatarSpec(spec);
                    log("[图标] 定位标注已改为内置图片 " + path);
                });
                row.addView(iv, lp);
            }
            markGrid.addView(row);
        }
        drawerPanel.addView(markGrid);

        LinearLayout avRow2 = new LinearLayout(this);
        avRow2.setOrientation(LinearLayout.HORIZONTAL);
        Button upA = drawerBtn("📁  上传自己的图片");
        upA.setOnClickListener(v -> pickImage(REQ_PICK_AVATAR));
        Button noA = drawerBtn("🚫  不用图片（纯色水滴）");
        noA.setOnClickListener(v -> {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putString(AppPrefs.K_AVATAR, "").apply();
            map.setAvatarSpec("");
            log("[图标] 已关闭图片，改用纯色水滴");
        });
        avRow2.addView(upA, weight());
        avRow2.addView(noA, weight());
        drawerPanel.addView(avRow2);

        // ===== 使用说明
        drawerPanel.addView(section("📖 使用说明"));
        TextView help = new TextView(this);
        help.setTextSize(12f);
        help.setLineSpacing(dp(2), 1f);
        help.setText(HELP_TEXT);
        drawerPanel.addView(help);

        // ===== 开发者留言
        drawerPanel.addView(section("💬 开发者留言"));
        TextView dev = new TextView(this);
        dev.setTextSize(12f);
        dev.setLineSpacing(dp(2), 1f);
        dev.setText(DEV_TEXT);
        drawerPanel.addView(dev);
        // ★ 第 3 条（2026-10-10）：把留言里的网址 / QQ 号 / 邮箱做成可复制的小按键。
        //   和启动时那个留言弹窗**共用同一份**（DevNote.copyRow），两处永远一致 ——
        //   很多用户第一次就把弹窗勾成「不再弹出」，之后只能从侧边栏找。
        drawerPanel.addView(DevNote.copyRow(this));
        // ★ 2026-10-10 用户第 6 条：侧边栏**最后**放煮波的收款码（一句话 + 图）。
        //   只放这儿、不放启动弹窗 —— 那边首次要停够 30 秒，小屏再加一张 230dp 的图
        //   会把「关闭」挤出屏幕。文案与文件名都在 DevNote 里，测试盯着那一处。
        drawerPanel.addView(DevNote.tipBlock(this));

        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                drawerW, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.START;
        sc.setVisibility(View.GONE);          // ★ 初始就藏起来，toggle 时再滑进来
        rootFrame.addView(sc, dlp);
        sc.setTranslationX(-drawerW);
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13.5f);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    private Button drawerBtn(String s) {
        Button b = mkBtn(s, "#455A64", 12f);
        b.setMinHeight(dp(40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void toggleDrawer() {
        drawerOpen = !drawerOpen;
        if (scrim != null) scrim.setVisibility(drawerOpen ? View.VISIBLE : View.GONE);
        if (drawerHolder == null) return;
        if (drawerOpen) {
            drawerHolder.setVisibility(View.VISIBLE);
            drawerHolder.setTranslationX(-drawerW);
            drawerHolder.animate().translationX(0).setDuration(200).start();
        } else {
            drawerHolder.animate().translationX(-drawerW).setDuration(170)
                    .withEndAction(() -> drawerHolder.setVisibility(View.GONE)).start();
        }
    }

    /**
     * ★ 界面优化 #1（2026-10-07）：不再有「系统自动 / 手动」两个按钮。
     *   跑步参数永远由系统自动设置，这里只负责把「本次会怎么设」讲清楚。
     */
    private void applyMode() {
        if (tvAutoInfo == null) return;
        tvAutoInfo.setVisibility(View.VISIBLE);
        tvAutoInfo.setText(
                "跑步参数：系统自动设置（无需选择）\n"
              + "· 里程 2.0~5.0 公里、配速 3.5~6.0 分/公里，每次随机\n"
              + "· 路线沿校园道路生成，开头结尾都在路上，不穿楼不穿湖\n"
              + "· 速度连续变化（不是每段跳一个值），加减速限幅，像真人");
    }

    /** 侧边栏文字全部跟着主题走 */
    private void recolorDrawer(View v, int fg) {
        if (v instanceof TextView && !(v instanceof Button)) ((TextView) v).setTextColor(fg);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolorDrawer(g.getChildAt(i), fg);
        }
    }

    private void applyTheme() {
        int bg = AppPrefs.bg(this), fg = AppPrefs.fg(this), acc = AppPrefs.accent(this);
        String bgImg = AppPrefs.bgImage(this);
        /* ★★★ 2026-10-10 用户第 5 条：「这个按键还有框有点挡背景，
         *   能不能在设置背景后改成半透明的。」
         *   —— 判据只有这一个：**铺了背景照片**。没铺照片时一切都是原来的不透明，
         *   免得纯色主题下反而变得发灰、字发虚。 */
        final boolean hasBg = bgImg != null && bgImg.length() > 0;

        contentRoot.setBackgroundColor(bg);
        if (hasBg) {
            try {
                Bitmap bm = BitmapFactory.decodeFile(bgImg);
                if (bm != null) contentRoot.setBackground(new BitmapDrawable(getResources(), bm));
            } catch (Throwable ignore) { }
        }
        tvTitle.setTextColor(acc);
        btnMenu.setTextColor(fg);
        tvIdentBrief.setTextColor(fg);
        btnEdit.setTextColor(acc);
        tvLog.setTextColor(fg);
        tvRouteHead.setTextColor(acc);
        if (scLog != null) {
            scLog.setBackground(round(AppPrefs.panelStrong(bg, hasBg), null, 10));
        }
        // ---- 卡片（身份框 / 参数框 / 路线框）
        int panelCol = AppPrefs.panel(bg, hasBg);
        int lineCol = AppPrefs.alpha(AppPrefs.darken(bg, 0.88f), hasBg ? 0.45f : 1f);
        for (View c : cards) {
            if (c == contentRoot) continue;
            c.setBackground(round(panelCol, AppPrefs.hex(lineCol), 10));
        }
        if (etIdent != null) {
            etIdent.setBackground(round(AppPrefs.panelStrong(bg, hasBg), AppPrefs.hex(lineCol), 8));
            etIdent.setTextColor(fg);
        }
        tvStatus.setBackground(round(String.format("#%06X",
                AppPrefs.blend(acc, bg, 0.16f) & 0xFFFFFF),
                String.format("#%06X", AppPrefs.blend(acc, bg, 0.36f) & 0xFFFFFF), 7));
        tvStatus.setTextColor(AppPrefs.onColor(AppPrefs.blend(acc, bg, 0.16f)));

        /* ---- 按钮：★ 第 5 条就是冲着它们来的（"这个按键…有点挡背景"）。
         *   颜色不是写死在这里，而是 mkBtn 时记在 tag 上的原色 —— 这样地图
         *   「＋/－/全校/定位/路网」、侧边栏按钮、开始/停止按钮全都一起变，
         *   不会漏掉哪一个。 */
        btnAlpha = hasBg ? 0.70f : 1f;
        repaintButtons();
        if (drawerHolder != null) {
            // 侧边栏是一整块贴边面板，透太多会跟底下的卡片叠成一团，留 88% 就够
            drawerHolder.setBackgroundColor(hasBg ? AppPrefs.alpha(bg, 0.88f) : bg);
            recolorDrawer(drawerPanel, fg);
        }
        applyMode();
        String spec = AppPrefs.marker(this);
        if ("custom".equals(spec)) {
            String p = AppPrefs.customMarker(this);
            map.setMarkerSpec(p != null && p.length() > 0 ? "f:" + p : "c:#1B7A4B");
        } else if (spec != null && spec.startsWith("a:")) {
            // ★ 2026-10-08 迁移：旧版那 40 张「内置图片图标」把选择写进了 K_MARKER，
            //   而 drawMarker() 只认 "c:" 开头的颜色 —— 等于用户当时白点了一下。
            //   现在把它搬到 K_AVATAR（真正会画出来的那条路径），并把旧的图片值清掉、
            //   颜色回落到默认，用户之前选的那张图就"活"过来了。
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putString(AppPrefs.K_MARKER, "c:#1B7A4B").apply();
            if (av0IsEmpty(this)) {
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putString(AppPrefs.K_AVATAR, spec).apply();
            }
            map.setMarkerSpec("c:#1B7A4B");
            log("[图标] 已把旧版选中的内置图片并入定位标注：" + spec);
        } else {
            map.setMarkerSpec(spec);
        }
        // ★ #8：水滴里嵌的头像
        String av = AppPrefs.avatar(this);
        map.setAvatarSpec(av == null ? "" : av);
        if (routeBody != null) applyRouteOpen();
    }

    /** 迁移用：K_AVATAR 是不是还没设过 */
    private static boolean av0IsEmpty(android.content.Context c) {
        String v = AppPrefs.avatar(c);
        return v == null || v.length() == 0;
    }

    private View buildIdentEditor() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, 0);
        etIdent = new EditText(this);
        etIdent.setHint("uid=…&token=…&refresh_token=…&mobileDeviceId=…\n"
                + "（留空即可：点「自动提取」或直接「开始跑步」会自动抓）");
        etIdent.setTextSize(11f);
        etIdent.setMinLines(3);
        etIdent.setMaxLines(5);
        etIdent.setGravity(Gravity.TOP | Gravity.START);
        etIdent.setBackground(round(Color.WHITE, "#D6E2DA", 8));
        etIdent.setPadding(dp(8), dp(6), dp(8), dp(6));
        etIdent.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) { refreshIdentBox(); }
        });
        box.addView(etIdent);
        return box;
    }

    private void applyRouteOpen() {
        if (routeBody == null || tvRouteHead == null) return;
        routeBody.setVisibility(routeOpen ? View.VISIBLE : View.GONE);
        refreshRouteHead();
    }

    // ==================================================================
    //  图片选择：自定义图标 / 自定义背景
    // ==================================================================
    private void pickImage(int req) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择图片"), req);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开图片选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 人脸照片按钮的文案：已设置就显示尺寸，没设置就提示去拍一张。
     *
     * <p>★ v1.0.24（docs/58）：顺手把结构上的毛病**写在按钮上**。以前照片坏了
     * 按钮照样写"已设置"，用户要到起跑前那次核验才知道 —— 而那时候人已经在跑道上，
     * 一整场就废了。现在哪怕只是"尾部有 0 填充"也会挂个 ⚠。
     */
    private void updateFaceBtn() {
        if (btnFace == null) return;        File f = new File(getFilesDir(), "face.jpg");
        if (f.exists() && f.length() > 0) {
            String warn = "";
            byte[] b = readFileBytes(f);
            if (b != null) {
                if (Ledao.jpegEoi(b) < 0) warn = "  ⚠ 不完整";
                else if (Ledao.jpegTailAllZero(b)) warn = "  ⚠ 尾部有 0 填充";
            }
            btnFace.setText("🧑 人脸照片已设置（" + (f.length() / 1024) + " KB" + warn
                    + "，点击预览 / 更换）");
        } else {
            btnFace.setText("🧑 设置人脸照片（随便一张自拍就行）");
        }
    }

    /** 人脸照片路径 */
    private File faceFile() { return new File(getFilesDir(), "face.jpg"); }

    // ==================================================================
    //  后台运行（v1.0.25，docs/59）
    // ==================================================================

    /**
     * ★★★ v1.0.25：把磁盘上那份运行日志捞回界面。
     *
     * <p>三种情况都会走到这里，而且都值得让用户看见：
     * <ol>
     *   <li>上次被系统清掉了（MIUI 省电策略太狠 / 内存不够）—— 界面日志本来就随进程
     *       一起没了，只有这份落盘的还在，用户点「复制日志」就能发出来；</li>
     *   <li>跑步**还在跑**（用户把界面划掉又打开）—— 断掉那一段接回来；</li>
     *   <li>上一场正常跑完，想回头看看（这份会一直留到下一场开始）。</li>
     * </ol>
     *
     * <p>读完就归档改名（{@link RunLog#archive}），所以**不会每次开 App 都重复显示**。
     * 正在跑的那种情况不归档 —— 文件还得接着写。
     */
    private void restoreRunLog() {
        try {
            boolean writing = RunLog.active();
            String tail = RunLog.tail(getFilesDir(), 60000);
            if (tail.length() > 0) {
                log("");
                log("══════ 下面是从磁盘捞回来的日志（files/" + RunLog.NAME
                        + (writing ? "，这一场还在写" : "，上一次留下的") + "）══════");
                for (String ln : tail.split("\n")) {
                    if (ln.length() > 0) logLine(ln, false);   // ★ 不再写回磁盘，免得越滚越多
                }
                log("══════ 磁盘日志到此为止 ══════");
                log("");
            }
            if (!writing) RunLog.archive(getFilesDir());
        } catch (Throwable ignore) {
            /* 捞不回来也不影响任何事 */
        }
    }

    /**
     * 「🔋 后台运行设置」那一行的文案 —— 顺便当状态显示。
     *
     * <p>进没进"电池优化白名单"一眼可见；没进就提示点一下申请。
     * 国产 ROM 那套「省电策略 / 自启动 / 后台运行」读不出来（不是公开 API），
     * 但 v1.0.30 起向导里能**一键跳到那台机器自己的那个页面**（docs/62）。
     */
    private void updateBgBtn() {
        if (btnBg == null) return;
        boolean ign = ignoringBatteryOpt();
        btnBg.setText(ign
                ? "🔋 后台运行：白名单已加 ✅（点这里做后台体检 / 系统自启动设置）"
                : "🔋 后台运行：白名单还没加 —— 点这里设置（不然切走会被系统冻住）");
    }

    /** 现在是否已经在"电池优化白名单"里（拿不到就当作没有，反正点了会走兜底） */
    private boolean ignoringBatteryOpt() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    //  ★★★ v1.0.30（docs/62）：后台运行向导 + 后台体检
    //
    //  起因（用户 2026-10-10 报的）：「切到别的 app 后跑步就停了，只有屏幕在
    //  助手才能跑」，用的是荣耀 X80、白名单还没加。查下来机理很清楚：
    //  前台服务活着、常驻通知还挂着（所以看着像在跑），但**整进程被系统冻住**，
    //  一条指令都不执行 —— 日志里那段时间就是空白。
    //
    //  原来那行「🔋 后台运行设置」只有一个 AOSP 白名单弹窗，而荣耀/华为/小米
    //  真正管用的是它们**自己**的「应用启动管理 / 自启动 / 后台运行」，
    //  那个只能用户手点。所以这里做三件事：
    //    ① 状态说清楚（白名单 / 前台服务 / 唤醒锁 / 机型）；
    //    ② 一键跳到这台机器自己的那个页面（各家组件名不一样，逐个试）；
    //    ③ **后台体检**：切走 90 秒，回来直接告诉你系统有没有把助手冻住 ——
    //       把"到底有没有放开后台"从猜测变成可测的数（用户和开发者都能看）。
    // ==================================================================

    /** 体检时长（秒）。90 秒足够跨过大多数 ROM 的"切后台 N 秒后冻结"阈值 */
    private static final int BG_PROBE_SEC = 90;

    private static volatile long bgProbeStartWall = 0;
    private static volatile long bgProbeFrozenMs = 0;
    private static volatile int bgProbeFreezeTimes = 0;
    private static volatile long bgProbeMaxFreezeMs = 0;
    private static volatile boolean bgProbeRunning = false;
    private static volatile String bgProbeReport = null;
    private static volatile boolean bgProbeShown = true;   // 结果弹过一次就不反复弹

    /** 当前后台状态那几行（向导、日志、自检共用同一份口径） */
    private String bgStateText() {
        StringBuilder sb = new StringBuilder();
        sb.append(ignoringBatteryOpt()
                ? "· 系统电池优化白名单：已加 ✅\n"
                : "· 系统电池优化白名单：还没加 ❌（点上面第 ① 个按钮）\n");
        sb.append(SniffGuard.alive
                ? "· 前台服务：在跑 ✅（通知栏那条常驻通知就是它）\n"
                : "· 前台服务：现在没起（跑步时会自动起）\n");
        sb.append(SniffGuard.awake()
                ? "· 唤醒锁：已持有 ✅（息屏后节拍不会被拉长）\n"
                : "· 唤醒锁：现在没持（跑步时会自动持）\n");
        sb.append("· 这台机器：").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append(" / Android ")
          .append(android.os.Build.VERSION.RELEASE).append('\n');
        sb.append("· 它的自启动页：").append(romPageName() == null
                ? "没找到专用入口（会退到「应用详情」页）" : romPageName());
        return sb.toString();
    }

    /**
     * 这台机器的「自启动 / 后台运行」页叫什么（只影响按钮文案，能跳才算数）。
     *
     * <p>组件名是从各家 ROM 长期稳定沿用下来的（荣耀/华为 systemmanager、
     * 小米 securitycenter、OPPO safecenter、vivo permissionmanager）；
     * 跳之前还会用 {@code resolveActivity} 探一次，探不到就换下一个、最后退到
     * 系统「应用详情」页 —— 那一页每个 ROM 都有，用户自己在里面翻。
     */
    private String romPageName() {
        String m = android.os.Build.MANUFACTURER == null ? ""
                : android.os.Build.MANUFACTURER.toUpperCase(Locale.US);
        if (m.contains("HONOR")) return "应用启动管理（荣耀：应用 → 应用启动管理）";
        if (m.contains("HUAWEI")) return "应用启动管理（华为：应用 → 应用启动管理）";
        if (m.contains("XIAOMI") || m.contains("REDMI") || m.contains("POCO"))
            return "自启动管理（小米：应用设置 → 授权管理 → 自启动）";
        if (m.contains("OPPO") || m.contains("REALME") || m.contains("ONEPLUS")
                || m.contains("ONEPLUS"))
            return "自启动管理（OPPO/一加：手机管家 → 权限隐私 → 自启动）";
        if (m.contains("VIVO") || m.contains("IQOO"))
            return "自启动（vivo/iQOO：i 管家 → 应用管理 → 权限管理 → 自启动）";
        if (m.contains("MEIZU")) return "后台管理（魅族：手机管家 → 权限管理 → 后台管理）";
        if (m.contains("SAMSUNG")) return "电池 → 后台使用限制（三星）";
        return null;
    }

    /**
     * 打开这台机器自己的「自启动 / 后台运行」页。
     *
     * <p>逐条试，全都不认就退到系统「应用详情」页（{@code ACTION_APPLICATION_DETAILS_SETTINGS}），
     * 至少把用户送到能翻到那一项的地方。
     *
     * @return 打开成功返回那一页的名字，全失败返回 null
     */
    private String openRomBackgroundPage() {
        String[][] tries = {
            // 荣耀 / 华为：应用启动管理（自启动 + 关联启动 + 后台活动 三个开关都在这一页）
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.hihonor.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            // 小米 / 红米：自启动管理
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            // OPPO / 一加 / realme
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
            // vivo / iQOO
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            // 魅族
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
        };
        for (String[] c : tries) {
            try {
                Intent i = new Intent();
                i.setComponent(new android.content.ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(i, 0) == null) continue;
                startActivity(i);
                log("[后台] 已打开系统页面：" + c[0] + "/" + c[1]);
                return romPageName() != null ? romPageName() : (c[0] + " 的自启动页");
            } catch (Throwable ignore) {
                /* 试下一个 */
            }
        }
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            log("[后台] 这台机器没认出自启动页，已打开「应用详情」页 ——"
                    + "在里面找「自启动 / 后台运行 / 省电策略 / 电池」，全部放开。");
            return "应用详情页";
        } catch (Throwable t) {
            logFail("打开应用详情页", t);
            return null;
        }
    }

    /**
     * ★★★ v1.0.30：「🔋 后台运行设置」点开的东西 —— 一个向导，不再是一个弹窗。
     */
    private void askBackgroundPermission() {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);

            /* ★ 顺序有讲究：**先按钮、后状态**。
             *   2026-10-11 装机第一版是反过来的（五行状态在最上面），MI 8 上
             *   一屏只装得下 ①②，③④ 被切在下面（uiautomator dump 里
             *   ③ 的高度只剩 44px、④ 干脆没排上）—— 用户根本不知道下面还有东西。
             *   现在：动作先给，状态文本挪到最后当"详情"，小屏上也点得到。 */
            Button b1 = new Button(this);
            b1.setText("① 让系统别省我：申请「电池优化白名单」");
            b1.setOnClickListener(v -> askIgnoreBatteryOpt());
            box.addView(b1);

            Button b2 = new Button(this);
            b2.setText("② 打开系统的「自启动 / 后台活动」页（最关键的一层）");
            b2.setOnClickListener(v -> {
                String p = openRomBackgroundPage();
                log(p == null ? "[后台] 没打开任何系统页面" : "[后台] 打开的是：" + p);
                Toast.makeText(this, "把助手的「自启动 / 后台活动」全部打开，再回来做一次体检",
                        Toast.LENGTH_LONG).show();
            });
            box.addView(b2);

            Button b3 = new Button(this);
            b3.setText("③ 后台体检：切走 " + BG_PROBE_SEC + " 秒，回来看后台通不通");
            b3.setOnClickListener(v -> startBgProbe());
            box.addView(b3);

            Button b4 = new Button(this);
            b4.setText("④ 把下面的状态写进日志（再点「📋 复制日志」发我）");
            b4.setOnClickListener(v -> {
                log("[后台] ── 状态快照 ──");
                for (String ln : bgStateText().split("\n")) log("   " + ln);
                log("   白名单=" + (ignoringBatteryOpt() ? "已加" : "没加")
                        + "  前台服务=" + (SniffGuard.alive ? "在跑" : "没起")
                        + "  唤醒锁=" + (SniffGuard.awake() ? "已持有" : "没持"));
                Toast.makeText(this, "已写进日志", Toast.LENGTH_SHORT).show();
            });
            box.addView(b4);

            TextView st = new TextView(this);
            st.setTextSize(12.5f);
            st.setLineSpacing(dp(3), 1.15f);
            st.setPadding(dp(2), dp(10), dp(2), dp(2));
            st.setText("—— 现在的状态 ——\n" + bgStateText());
            box.addView(st);

            /* 内容比一屏高时必须能滚，而且**高度要自己框住**（AlertDialog 不会）。
             * 取屏高 72% 与 470dp 的较小值：①②③ 一定在第一屏里。 */
            android.widget.ScrollView sc = new android.widget.ScrollView(this);
            sc.addView(box);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.72);
            sc.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, dp(470))));

            android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                    .setTitle("🔋 后台运行向导")
                    .setView(sc)
                    .setNegativeButton("关闭", null)
                    .create();
            dlg.show();
        } catch (Throwable t) {
            logFail("打开后台运行向导", t);
        }
    }

    /** ① 那一步：AOSP 那层白名单（国产 ROM 还会再拦一层，所以②也要做） */
    private void askIgnoreBatteryOpt() {
        boolean ign = ignoringBatteryOpt();
        log(ign ? "[后台] 电池优化白名单：已经在里面 ✅" : "[后台] 电池优化白名单：还没进 —— 现在申请");
        if (ign) {
            Toast.makeText(this, "已经在白名单里了；②那层（系统自启动/后台活动）也别忘了",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            log("   系统弹窗里点「允许」。");
        } catch (Throwable t) {
            log("   这台机器不给直接申请（" + Err.one(t) + "），改成打开电池优化列表 ——"
                    + "在里面找到「跑步测试助手」并允许。");
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                logFail("打开电池优化设置", t2);
            }
        }
    }

    /**
     * ★★★ v1.0.30：**后台体检** —— 用 90 秒把"系统有没有冻住助手"量出来。
     *
     * <p>原理很朴素：起一根后台线程，每睡 1 秒醒一次；醒来发现"这一觉睡了
     * 远不止 1 秒"，那多出来的时间就是**没被调度**的时间（被冻住了）。
     * 90 秒里真正被调度了多久，直接就是"后台通不通"的答案：
     * <ul>
     *   <li>≥ 80%：通 ✅ —— 切走、息屏都能接着跑；</li>
     *   <li>40% ~ 80%：断断续续 ⚠ —— 跑得完，但会被冻几段（用时会被自动剔除，配速不受影响）；</li>
     *   <li>&lt; 40%：基本没跑 ❌ —— 这台机器把助手冻住了，必须去②那个页面放开。</li>
     * </ul>
     *
     * <p>为什么要做它：用户报"切走就停"的时候，肉眼只能看到"日志中间是空的"，
     * 到底是网络、是代码、还是系统，谁也说不清。有了这个数，
     * 用户自己能验，开发者也不用猜（结果会写进 logcat 和日志框）。
     */
    private void startBgProbe() {
        if (bgProbeRunning) {
            Toast.makeText(this, "体检已经在跑了：再切走一会儿，回来再点这里看结果",
                    Toast.LENGTH_LONG).show();
            return;
        }
        bgProbeStartWall = System.currentTimeMillis();
        bgProbeFrozenMs = 0;
        bgProbeFreezeTimes = 0;
        bgProbeMaxFreezeMs = 0;
        bgProbeReport = null;
        bgProbeShown = false;
        bgProbeRunning = true;
        new Thread(() -> {
            long last = System.currentTimeMillis();
            while (bgProbeRunning) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
                long now = System.currentTimeMillis();
                long gap = now - last;
                last = now;
                if (gap > 2500) {
                    long over = gap - 1000;          // 计划睡 1 秒，多出来的就是被冻掉的
                    bgProbeFrozenMs += over;
                    bgProbeFreezeTimes++;
                    if (over > bgProbeMaxFreezeMs) bgProbeMaxFreezeMs = over;
                }
                if (now - bgProbeStartWall >= BG_PROBE_SEC * 1000L) break;
            }
            bgProbeRunning = false;
            bgProbeReport = bgProbeVerdict();
            android.util.Log.i("LedaoTester", bgProbeReport.replace("\n", " / "));
        }, "bg-probe").start();
        log("[体检] 后台体检开始（" + BG_PROBE_SEC + " 秒）—— 现在把助手切到后台，或者直接息屏；"
                + "到点回来点「🔋 后台运行设置」看结论。");
        setStatus("🔬 后台体检中：请切走 / 息屏 " + BG_PROBE_SEC + " 秒");
    }

    /** 体检结论（人话版，给用户看；界面文字不带 markdown 记号） */
    private String bgProbeVerdict() {
        long wall = Math.max(1, System.currentTimeMillis() - bgProbeStartWall);
        double sec = wall / 1000.0;
        double frozen = bgProbeFrozenMs / 1000.0;
        double awake = Math.max(0, sec - frozen);
        double rate = awake / sec * 100.0;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "切走 %.0f 秒：助手真正被调度了 %.1f 秒（%.0f%%）", sec, awake, rate));
        if (bgProbeFreezeTimes > 0)
            sb.append(String.format(Locale.US, "，被冻住 %.1f 秒（%d 段，最长 %.1f 秒）",
                    frozen, bgProbeFreezeTimes, bgProbeMaxFreezeMs / 1000.0));
        sb.append("。\n\n");
        if (rate >= 80) {
            sb.append("结论：后台是通的 ✅ 这台机器没有冻助手 —— 跑步时可以放心切走、息屏。");
        } else if (rate >= 40) {
            sb.append("结论：后台断断续续 ⚠ 系统把助手冻了几段。\n")
              .append("这一场还是跑得完（被冻住的时间会自动从用时里剔除，配速不会被拖坏），\n")
              .append("但想让后台顺畅，请点第 ② 个按钮，把助手的「自启动 / 后台活动」全部打开。");
        } else {
            sb.append("结论：后台基本被冻住了 ❌ 这就是「切走就停」的原因 ——\n")
              .append("前台服务和通知都还在（所以看着像在跑），但进程被系统冻住，一条指令都不执行，\n")
              .append("所以那段时间既没有日志、也没有里程。\n")
              .append("请点第 ② 个按钮，在系统页面里把助手的「自启动 / 关联启动 / 后台活动」\n")
              .append("全部打开（有的机型还要把「省电策略」设成「无限制」），回来再做一次体检。");
        }
        return sb.toString();
    }

    /** 回到前台时，如果体检已经有结论了，就把结论弹给用户（只弹一次） */
    private void showBgProbeResultIfAny() {
        if (bgProbeReport == null || bgProbeShown) return;
        bgProbeShown = true;
        String r = bgProbeReport;
        log("[体检] " + r.replace("\n", " "));
        // 状态条别再挂着"体检中" —— 结论已经在弹窗里了
        setStatus("🔋 后台体检完成，见弹窗结论");
        try {
            Alerts.dialog(this, "后台体检结果", r,
                    "① 点首页「🔋 后台运行设置」→ 第 ② 个按钮，把系统那层的"
                  + "「自启动 / 后台活动」全部打开（不同机型叫法不同，页面已经帮你跳过去）；\n"
                  + "② 回来再做一次体检，看到「后台是通的 ✅」就可以放心跑了。",
                    this::copyLog);
        } catch (Throwable t) {
            logFail("弹后台体检结果", t);
        }
    }

    /** 选人脸照片（走 ACTION_GET_CONTENT 而不是相机，助手没有相机权限） */
    private void pickFacePhoto() {
        if (RUN_ACTIVE) { log("⚠ 跑步进行中，先停掉再换照片"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选一张正脸自拍"), REQ_FACE);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开图片选择器：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 人脸照片按钮：没设置就选图，已设置就弹预览框（里面能更换 / 删除）。
     */
    private void facePhotoMenu() {
        File f = faceFile();
        if (f.exists() && f.length() > 0) previewFacePhoto();
        else pickFacePhoto();
    }

    /**
     * 人脸照片预览框 —— 看一眼存下来的到底是什么，再决定留不留。
     *
     * <p>为什么要它：照片是**原字节直存**的（见 {@link #installFacePhoto}），
     * 选错了自己不知道 —— 服务端要到跑前那次核验才会说「未拍到完整人脸」。
     * 这里至少能让人在跑之前就看清自己选的是哪张。
     */
    private void previewFacePhoto() {
        final File f = faceFile();
        if (!f.exists() || f.length() == 0) { pickFacePhoto(); return; }
        try {
            final Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            final byte[] fb = readFileBytes(f);            // ★ v1.0.24：结构诊断要用它
            final String diag = fb == null ? "" : Ledao.jpegDiag(fb);
            final int[] sof = fb == null ? null : Ledao.jpegSofSize(fb);
            final File okF = new File(getFilesDir(), Ledao.FACE_OK_NAME);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(12), dp(16), dp(4));

            if (bm != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageBitmap(bm);
                int maxH = dp(260);
                float sc = Math.min(1f, (float) maxH / Math.max(1, bm.getHeight()));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (int) (bm.getHeight() * sc));
                lp.bottomMargin = dp(8);
                box.addView(iv, lp);
            } else {
                TextView bad = new TextView(this);
                bad.setTextSize(13f);
                bad.setTextColor(Color.parseColor("#C62828"));
                bad.setText("⚠ 这张图解不出来（可能不是图片，或者已经损坏）。\n"
                        + "跑步时上传它大概率会失败，建议点「更换」。");
                box.addView(bad);
            }

            TextView info = new TextView(this);
            info.setTextSize(12.5f);
            info.setLineSpacing(dp(2), 1f);
            info.setText("文件  " + f.getAbsolutePath() + "\n"
                    + "大小  " + (f.length() / 1024) + " KB"
                    + (bm != null ? "　尺寸  " + bm.getWidth() + "×" + bm.getHeight() : "")
                    + (sof != null ? "　图里声明 " + sof[0] + "×" + sof[1] : "")
                    + "\n结构  " + (diag.length() == 0
                        ? ("JPEG 完整，" + Ledao.jpegTailDesc(fb))
                        : ("⚠ " + diag))
                    + "\n原样存下来的，没有裁剪、没有压缩、没有检测人脸。\n"
                    + "服务端要的是「脸占满画面」的近照，跑前那次核验才会给结论。"
                    + (okF.exists() && okF.length() > 0
                        ? "\n上次通过核验的那张还留着备份（" + (okF.length() / 1024) + " KB）。"
                        : ""));
            box.addView(info);

            /* ★ v1.0.24（docs/58）：照片有毛病时，就地给一条走得通的路。
             *   以前这里只有「更换这张」—— 而同一个文件再选一次结果一模一样，
             *   用户会以为软件坏了（12:22、13:11 两次事故都是这么卡住的）。 */
            final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];
            if (diag.length() > 0) {
                Button fix = new Button(this);
                fix.setText("🔧 试试修复这张（能修就修，原图留一份）");
                fix.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    repairFacePhoto();
                });
                box.addView(fix);
            }
            if (okF.exists() && okF.length() > 0) {
                Button useOk = new Button(this);
                useOk.setText("↩ 用回上次通过核验的那张");
                useOk.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    useFaceOkPhoto();
                });
                box.addView(useOk);
            }

            android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                    .setTitle("人脸照片预览")
                    .setView(box)
                    .setPositiveButton("更换这张", (d, w) -> pickFacePhoto())
                    .setNeutralButton("删除", (d, w) -> deleteFacePhoto())
                    .setNegativeButton("关闭", null)
                    .create();
            holder[0] = dlg;
            dlg.show();
        } catch (Throwable t) {
            logFail("预览人脸照片", t);
        }
    }

    /**
     * ★ v1.0.24（docs/58）：把当前的人脸照片**就地修一次**，修完写回 files/face.jpg。
     *
     * <p>两种修法（{@link Ledao#faceJpegFix} 里的顺序就是优先级）：
     * <ol>
     *   <li><b>无损</b>：EOI 之后是纯 0 填充 → 裁掉那截（图片本体一个字节不动）</li>
     *   <li><b>有损</b>：结构上写坏了（缺 EOI）→ 本地解码 → 按解出来的部分重编码成完整 JPEG</li>
     * </ol>
     * 原图另存一份 {@code files/face_orig.jpg}：不动用户原始数据，想删随时能删。
     * 修完清掉本机缓存的核验凭据（照片换了，凭据必须作废）。
     */
    private void repairFacePhoto() {
        final File f = faceFile();
        if (!f.exists() || f.length() == 0) { pickFacePhoto(); return; }
        log("🧑 开始修复人脸照片…（原图会留一份 face_orig.jpg，不会丢）");
        new Thread(() -> {
            try {
                byte[] cur = readFileBytes(f);
                if (cur == null) {
                    log("!! 这张照片读不完整，没动它 —— 重新选一张更稳");
                    return;
                }
                Ledao.FaceFix fix = Ledao.faceJpegFix(cur);
                if (fix == null) {
                    log(Ledao.jpegDiag(cur).length() == 0
                            ? "🔧 这张照片结构上没毛病（没有可修的地方）"
                            : "🔧 这张照片本地修不了：连解码器都解不出一帧图 —— 只能换一张");
                    log("   " + Ledao.jpegDiag(cur));
                    ui.post(() -> Toast.makeText(MainActivity.this,
                            "这张修不了，换一张吧", Toast.LENGTH_LONG).show());
                    return;
                }
                File orig = new File(getFilesDir(), "face_orig.jpg");
                if (!orig.exists() || orig.length() == 0) copyFile(f, orig);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(fix.bytes, 0, fix.bytes.length);
                fo.close();
                if (ledao != null) ledao.resetFaceState();   // 照片变了，旧凭据作废
                log(String.format(Locale.US,
                        "✅ 人脸照片已修复：%d KB → %d KB%s",
                        cur.length / 1024, fix.bytes.length / 1024,
                        fix.lossy ? "（重新编码过，画质略有损失）" : "（无损，图片本体没动）"));
                log("   修的内容：" + fix.note);
                log("   原图留在 files/face_orig.jpg（想删就用「删除」那颗，一起删掉）。");
                ui.post(this::updateFaceBtn);
            } catch (Throwable e) {
                logFail("修复人脸照片", e);
            }
        }, "face-repair").start();
    }

    /**
     * ★ v1.0.24（docs/58）：把**上次通过服务端核验**的那张（files/face_ok.jpg）
     * 拿回来当当前照片 —— 当前这张救不回来时的最后一条路。
     */
    private void useFaceOkPhoto() {
        final File ok = new File(getFilesDir(), Ledao.FACE_OK_NAME);
        if (!ok.exists() || ok.length() == 0) {
            log("🧑 还没有「上次通过核验」的备份（得先有一场核验通过才会有）");
            return;
        }
        try {
            copyFile(ok, faceFile());
            new File(getFilesDir(), "face_pick.tmp").delete();
            if (ledao != null) ledao.resetFaceState();
            byte[] b = readFileBytes(faceFile());
            log(String.format(Locale.US,
                    "✅ 已换回上次通过核验的那张照片（%d KB）%s",
                    ok.length() / 1024,
                    b == null ? "" : "；结构 " + (Ledao.jpegDiag(b).length() == 0
                            ? "完整（" + Ledao.jpegTailDesc(b) + "）" : "⚠ " + Ledao.jpegDiag(b))));
            updateFaceBtn();
        } catch (Throwable t) {
            logFail("换回上次通过核验的照片", t);
        }
    }

    /** 删掉人脸照片，并把 Ledao 里缓存的核验凭据一起清掉 */
    private void deleteFacePhoto() {
        File f = faceFile();
        boolean had = f.exists();
        if (had && !f.delete()) {
            Toast.makeText(this, "删不掉：" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        // 顺手清掉选图时留下的临时文件
        new File(getFilesDir(), "face_pick.tmp").delete();
        new File(getFilesDir(), "face_new.jpg").delete();
        /* ★ v1.0.24：备份也要一起删 —— 用户点「删除」的意思就是"别留我的自拍"，
         *   留着 face_ok.jpg / face_orig.jpg 等于偷偷藏了两张（这是隐私，不是缓存）。 */
        new File(getFilesDir(), Ledao.FACE_OK_NAME).delete();
        new File(getFilesDir(), "face_orig.jpg").delete();
        if (ledao != null) ledao.resetFaceState();
        updateFaceBtn();
        if (had) log("🧑 人脸照片已删除 —— 下次跑步不会做刷脸核验了"
                + "（连 files/face_ok.jpg、face_orig.jpg 一起删了，不留备份）");
        else log("🧑 本来就没有人脸照片");
        Toast.makeText(this, had ? "已删除人脸照片" : "没有可删的人脸照片",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 把选中的照片**原字节**存成 files/face.jpg —— 不做任何处理。
     *
     * <h3>★ 2026-10-10 按用户要求改简单了</h3>
     * 原来是条重管线：解码 → {@code android.media.FaceDetector} 找脸 →
     * 按「眼距占画幅 ≥26.5%」生成 4 档人脸裁方 + 3 档盲裁 + 老流水线 + 原样，
     * 最多 9 个候选**逐个上传 {@code Run2/faceVerify} 问服务端**，谁 status=1 用谁
     * （那 194 KB 就是这么来的）。
     *
     * <p>用户原话：「关于人脸照片上传，上传的时候不要做任何检测，直接存进去，
     * 判断人脸照片是否正确的事等到跑前那次人脸验证就可以」。
     * 所以现在就是**抄字节**这一件事。
     *
     * <h3>取舍（写在 docs/52 里）</h3>
     * 代价是真存在的：`docs/44` 量出来服务端要「眼距占画幅 ≥ 26.5%」，
     * 一张远处拍的全身照会被 {@code status=59} 拒掉，而那时候人已经在跑道上了。
     * 好处是选照片**立刻完成**、不再需要"先杀掉乐跑、再问服务端"那套预检
     * （以前换照片必须保证会话还活着），也不再拿服务端当裁图顾问。
     *
     * <p>仍然保留一道**不做任何修改**的体检：{@code doFaceVerify} 上传前会看
     * SOI/EOI 是否完整、体积是否异常 —— 那只读不写。
     */
    private void installFacePhoto(final Uri src) {
        final File dst = faceFile();
        log("🧑 保存人脸照片…（原字节直存，不做检测 / 不裁剪 / 不压缩）");
        new Thread(() -> {
            try {
                /* 先落到临时文件再原子改名 —— 中途失败不会把已有照片毁掉 */
                final File tmp = new File(getFilesDir(), "face_new.jpg");
                long total = 0;
                java.io.InputStream in = getContentResolver().openInputStream(src);
                if (in == null) {
                    log("!! 打不开这个 Uri：" + src);
                    return;
                }
                FileOutputStream fo = new FileOutputStream(tmp);
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > FACE_MAX_BYTES) {          // 见常量注释
                        fo.close(); in.close(); tmp.delete();
                        log(String.format(Locale.US,
                                "!! 这张图 %.1f MB，超过 %d MB 上限 —— 拒绝保存（原有照片未改动）。",
                                total / 1048576.0, FACE_MAX_BYTES / 1048576));
                        ui.post(() -> Toast.makeText(MainActivity.this,
                                "照片太大（超 " + (FACE_MAX_BYTES / 1048576) + " MB），换一张",
                                Toast.LENGTH_LONG).show());
                        return;
                    }
                    fo.write(b, 0, n);
                }
                fo.close();
                in.close();

                if (total <= 0) {
                    tmp.delete();
                    log("!! 读到 0 字节 —— 这个 Uri 给不出内容，照片未改动");
                    return;
                }
                /* ★★★ v1.0.24（docs/58）：存进来的时候就做一次**只读**体检，顺手裁掉尾部 0 填充。
                 *   为什么要在这里做：2026-10-10 13:11，用户那台 MTN-AN80 上选中的
                 *   1156 KB 自拍，尾部最后四字节是 {@code 00 00 00 00}（写了一半 /
                 *   从云端只下了一半），被当时那条「最后两字节必须是 FF D9」的判据
                 *   当场判成坏图 ⇒ 那一场没做刷脸核验 ⇒ 收尾被判
                 *   「人脸照片验证不合格」，白跑 2.34 km。
                 *   裁 0 填充是**无损**的：EOI 之后不属于图片，而一长串 0 更不可能是内容。
                 *   ★ 这里只用纯字节函数：不解码、不裁剪、不缩放、不压缩（用户第 1 条要求）。 */
                String jpegNote = "";
                try {
                    byte[] got = readFileBytes(tmp);
                    if (got != null && got.length >= 128) {
                        byte[] t = Ledao.jpegTrimZeroTail(got);
                        if (t.length < got.length) {
                            FileOutputStream fo2 = new FileOutputStream(tmp);
                            fo2.write(t, 0, t.length);
                            fo2.close();
                            total = t.length;
                            jpegNote = "；尾部 " + (got.length - t.length)
                                     + " 字节 0 填充已裁掉（图片本体一字节没动）";
                        } else if (Ledao.jpegEoi(got) < 0) {
                            jpegNote = "；⚠ 这张图 JPEG 不完整（没有结尾标记 EOI）——"
                                     + "跑步时我会先本地修一次（按能解出来的部分重编码），"
                                     + "修不好会明确提醒你换一张";
                        } else if (Ledao.jpegTailBytes(got) > 0) {
                            jpegNote = "；EOI 后有 " + Ledao.jpegTailBytes(got)
                                     + " 字节附加数据（JPEG 允许，原样保留）";
                        }
                    }
                } catch (Throwable ignore) {
                    /* 体检失败绝不影响保存本身 —— 照片已经在 tmp 里了 */
                }
                long before = dst.exists() ? dst.length() : 0;
                if (dst.exists() && !dst.delete()) log("   （旧照片没删掉，直接覆盖）");
                if (!tmp.renameTo(dst)) { copyFile(tmp, dst); tmp.delete(); }
                new File(getFilesDir(), "face_pick.tmp").delete();
                if (ledao != null) ledao.resetFaceState();     // 换了照片，旧的核验凭据作废
                log(String.format(Locale.US,
                        "✅ 人脸照片已保存：%s  %d KB（原 %d KB）—— 未经任何处理%s",
                        dst.getAbsolutePath(), dst.length() / 1024, before / 1024, jpegNote));
                log("   点首页那行「🧑 人脸照片」可以预览 / 更换 / 删除。");
                ui.post(this::updateFaceBtn);
            } catch (Throwable e) {
                logFail("保存人脸照片", e);
            }
        }, "face-photo").start();
    }


    private static void copyFile(File a, File b) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(a);
        FileOutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    /**
     * ★ v1.0.24：把整份文件读进来（读不满返回 null）—— 只给"看结构"用。
     *
     * <p>为什么不用 {@code Files.readAllBytes}：这里跑在 UI 线程上（按钮文案、
     * 预览框），必须自己设一个上限。人脸照片本身就有 8 MB 上限，再加一点余量。
     * 失败一律返回 null —— 调用方全部按"看不出问题"处理，绝不因此拦住用户。
     */
    private static byte[] readFileBytes(File f) {
        try {
            long n = f == null ? 0 : f.length();
            if (n <= 0 || n > FACE_MAX_BYTES + 65536) return null;
            byte[] b = new byte[(int) n];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r <= 0) break;
                off += r;
            }
            in.close();
            return off == b.length ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String copyToFiles(Uri u, String name) throws Exception {        InputStream in = getContentResolver().openInputStream(u);
        File f = new File(getFilesDir(), name);
        FileOutputStream fo = new FileOutputStream(f);
        byte[] b = new byte[8192];
        int n;
        while (in != null && (n = in.read(b)) > 0) fo.write(b, 0, n);
        fo.close();
        if (in != null) in.close();
        return f.getAbsolutePath();
    }

    /** 列出 assets/markers 下内置的图标文件名 */
    private String[] assetMarkers() {
        try {
            String[] all = getAssets().list("markers");
            if (all == null) return new String[0];
            List<String> out = new ArrayList<>();
            java.util.Arrays.sort(all);
            for (String s : all) if (s.endsWith(".png")) out.add(s.substring(0, s.length() - 4));
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /** 列出 assets/avatars 下内置的头像文件名 */
    private String[] assetAvatars() {
        try {
            String[] all = getAssets().list("avatars");
            if (all == null) return new String[0];
            List<String> out = new ArrayList<>();
            java.util.Arrays.sort(all);
            for (String s : all) if (s.endsWith(".png")) out.add(s.substring(0, s.length() - 4));
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /**
     * 预览：用内置的 14 个点挑一条路线画到地图上。
     *
     * ★★★ 2026-10-09：换成新版路线规划（{@link RoutePlan}，即 work/route/route_lib.js
     * 的 Java 移植）。和旧版 {@link RouteGen} 的区别：
     *   旧版在「可跑街道栅格」上 A* 找一条闭环 —— 栅格是 2 m 一格的位图，
     *   只能保证"压在街道上"；新版在一张**真正的图**（1683 街口 / 2871 街段）
     *   上随机游走，于是才有"起点附近几米随机起跑、允许返程、
     *   类似路口抹圆、靠右行驶、横向游走"这些像人的东西，
     *   也才谈得上"随机筛路线但尽量靠近里程下限"。
     */
    private void drawPreviewRoute() {
        try {
            List<double[]> cps = new ArrayList<>();
            for (double[] p : RouteView.CAMPUS) cps.add(new double[]{p[0], p[1]});
            // ★ 以前这里写死 cps.get(0) 和 cps.get(size/2) —— 每次预览永远是同两个点，
            //   用户看到的「点位不随机」多半就是这个。
            //   现在和真跑的规则一致：**14 个点里均匀随机抽 2 个**（docs/15）。
            //   开跑后会用 Run2/beforeRunV260 下发的规则 + 真机算法 ya/i0.f() 覆盖一次。
            int[] pair = Ledao.pickPairUniform(cps.size(), new Random());
            List<double[]> sel = new ArrayList<>();
            sel.add(cps.get(pair[0]));
            sel.add(cps.get(pair[1]));
            map.setKeyPoints(sel, null);
            planPair = new int[]{pair[0], pair[1]};
            planIds = null;                      // 还没开跑，服务端还没指派

            // ---- 首选：新版路线规划（路网随机游走）
            RouteGen.Path p = null;
            String how = "";
            double wantKm = 0;
            if (routeNet != null) {
                // RouteView.CAMPUS 的顺序就是路网里打卡点的 n 顺序（1..14），
                // 两边是同一份点表 —— 用 id 串核对过（见 app/assets/routenet.json）。
                int nA = pair[0] + 1, nB = pair[1] + 1;
                planSeed = (rnd.nextLong() & 0x7FFFFFFFL);
                RoutePlan.Opts o = new RoutePlan.Opts();
                // ★ 里程只在这里定一次：自动化脚本给了就听脚本的，
                //   否则让生成器自己按 lengthBias=1.7 在 [下限, 上限] 里抽
                //   —— 这个偏置指数就是「虽然随机但要尽量靠近下限」那条要求。
                if (forcedKm >= 2.0 && forcedKm <= 10.0) {
                    o.targetM = forcedKm * 1000.0;
                    wantKm = forcedKm;
                }
                long t0 = System.currentTimeMillis();
                RoutePlan.Result r = RoutePlan.generate(routeNet, nA, nB, planSeed, o);
                long ms = System.currentTimeMillis() - t0;
                if (r.ok) {
                    p = r.path;
                    planCpN = new int[]{nA, nB};
                    how = String.format(Locale.CHINA,
                            "路网随机游走 · 起点街口 #%d · 掉头 %d 次 · %d ms", r.startNode, r.backtracks, ms);
                } else {
                    log("[地图] 新版路线规划失败（" + r.why + "），回落到旧版");
                }
            }

            // ---- 兜底：旧版（可跑街道栅格 + A*）
            if (p == null || p.length < 50) {
                double km = wantKm > 0 ? wantKm
                        : 2.0 + Math.round(rnd.nextDouble() * 30) / 10.0;
                if (km < 2.0) km = 2.0;
                if (km > 5.0) km = 5.0;
                Random r2 = new Random();
                double clat = 0, clon = 0;
                for (double[] q : cps) { clat += q[0]; clon += q[1]; }
                clat /= cps.size(); clon /= cps.size();
                double ang = r2.nextDouble() * Math.PI * 2, rad = r2.nextDouble() * 300.0;
                double[] start = {clat + rad * Math.cos(ang) / 111320.0,
                        clon + rad * Math.sin(ang)
                                / (111320.0 * Math.cos(Math.toRadians(clat)))};
                planCpN = null;
                planSeed = 0;
                if (roadGrid != null)
                    p = RouteGen.buildLoop(roadGrid, cps, pair[0], pair[1],
                            start, km * 1000, r2, null);
                if (p == null || p.length < 50)
                    p = RouteGen.build(sel, km * 1000, water, r2, true);
                how = roadGrid != null ? "沿街道闭环（旧版）" : "样条（旧版）";
                wantKm = km;
            }

            map.setRoute(p.pts);
            map.fitRoute();
            // ★★★ 白框里显示的、以及开跑时绿框里显示的那个里程，取**这条预览路线
            //   真实的长度**（一位小数），不是生成目标。生成器只能做到「贴着目标」，
            //   显示真实长度才能白框↔绿框↔地图三处一致；开跑按这个数去生成，落点就贴得住。
            //   forcedKm（自动化脚本指定）时不覆盖 —— 脚本说要跑多少就跑多少。
            if (forcedKm < 2.0 || forcedKm > 10.0) {
                planKm = Math.round(p.length / 100.0) / 10.0;
                if (planKm < 2.0) planKm = 2.0;
                if (planKm > 10.0) planKm = 10.0;
            } else {
                planKm = forcedKm;
            }
            log(String.format(Locale.CHINA,
                    "[地图] 预览路线 %d 米（%s）→ 本次按 %.1f 公里算，打卡点 %d 和 %d",
                    (int) p.length, how, planKm, pair[0] + 1, pair[1] + 1));
            refreshPlanInfo();
        } catch (Throwable t) {
            log("[地图] 预览路线生成失败：" + t);
        }
    }

    /**
     * 参数卡片（白框）文案 —— 全工程唯一一处。
     *
     * 里程永远取 {@link #planKm}：预览时是「准备跑的里程」，开跑后就是绿框里那个目标里程。
     * 打卡点分两种说法：还没开跑时说预览抽到的内置序号；服务端指派下来之后改说真实 id。
     */
    private void refreshPlanInfo() {
        if (tvAutoInfo == null) return;
        tvAutoInfo.setVisibility(View.VISIBLE);
        if (planIds != null && planIds.length >= 2 && planIds[0] != null && planIds[1] != null) {
            tvAutoInfo.setText(String.format(Locale.CHINA,
                    "本次跑步 %.1f 公里 · 必过打卡点 %s 和 %s（服务端指派 → 地图上红 1 / 蓝 2）",
                    planKm, planIds[0], planIds[1]));
            return;
        }
        if (planPair != null && planPair.length >= 2) {
            tvAutoInfo.setText(String.format(Locale.CHINA,
                    "本次预览里程 %.1f 公里 · 必过打卡点 %d 和 %d（%s；点「开始跑步」就跑这一条）",
                    planKm, planPair[0] + 1, planPair[1] + 1,
                    routeNet != null ? "路网随机游走" : (roadGrid != null ? "沿街道闭环" : "样条")));
            return;
        }
        applyMode();
    }

    /** 身份摘要小框 —— 只显示人看得懂的几项，令牌永远只露 6 位 */
    private void refreshIdentBox() {
        if (tvIdentBrief == null || etIdent == null) return;
        TreeMap<String, String> kv = new TreeMap<>();
        String raw = etIdent.getText().toString().trim();
        for (String seg : raw.split("[&\\n]")) {
            int i = seg.indexOf('=');
            if (i > 0) kv.put(seg.substring(0, i).trim(), seg.substring(i + 1).trim());
        }
        StringBuilder sb = new StringBuilder();
        if (kv.isEmpty()) {
            sb.append("身份：未提取\n点「▶ 开始跑步」或「📲 自动提取」自动获取");
        } else {
            sb.append("uid ").append(or(kv.get("uid"), "?"));
            if (kv.containsKey("student_num")) sb.append("   学号 ").append(kv.get("student_num"));
            sb.append("   校区 ").append(or(kv.get("school_id"), "?"));
            sb.append('\n');
            sb.append("令牌 ").append(shortTok(kv.get("token")));
            sb.append(kv.containsKey("refresh_token") ? "   续期✓" : "   续期✗");
            String dev = kv.get("mobileDeviceId");
            if (dev != null && dev.length() >= 8) sb.append("   设备 ").append(dev.substring(0, 8)).append("…");
            String mdl = kv.get("mobileModel");
            if (mdl != null && mdl.length() > 0) sb.append("   ").append(mdl);
        }
        tvIdentBrief.setText(sb.toString());
    }

    private static String or(String s, String d) { return s == null || s.length() == 0 ? d : s; }

    // ==================================================================
    //  「开始跑步 / 停止跑步」同一个键
    // ==================================================================
    private void toggleRun() {
        // ★ 用进程级 RUN_ACTIVE 判断，不用实例字段 worker —— Activity 重建后 worker 会归零
        if (RUN_ACTIVE) {
            if (RUN_LEDAO != null) RUN_LEDAO.stop();
            log(">> 用户点击停止");
            setStatus("正在停止…");
            btnRun.setEnabled(false);
            return;
        }
        startRun();
    }

    private void setRunBtn(boolean running) {
        if (running) {
            btnRun.setText("■  停止跑步");
            paintBtn(btnRun, "#B00020");
        } else {
            btnRun.setText("▶  开始跑步");
            paintBtn(btnRun, "#1B7A4B");
        }
        btnRun.setEnabled(true);
    }

    private void startRun() {
        // ★★★ 进程级互斥：同一时间只允许一个跑步线程。
        //   实测踩坑：Activity 被重建后新实例的 worker=null，旧线程还在跑，
        //   于是同时开了 3 局 → 抢会话 → 服务端一路 102。
        if (RUN_ACTIVE) {
            Toast.makeText(this, "已有跑步在进行中", Toast.LENGTH_SHORT).show();
            log(">> 已有跑步在进行中，忽略本次「开始跑步」");
            return;
        }
        String raw = etIdent.getText().toString().trim();
        if (raw.length() == 0) { autoExtract(true); return; }

        // ★ 界面优化 #1（2026-10-07）：手动设置已删除，**只有系统自动一种模式**。
        //   里程 2.0~5.0 随机（步进 0.1，看起来更像随手定的目标）；
        //   配速 3.5~6.0 里随机取一个宽度 0.6~1.2 分的区间，跑起来在区间内连续起伏。
        //   自动化脚本仍可用 Intent 的 distance 强制里程（forcedKm>0）。
        //
        // ★★★ 2026-10-08：这里**不再自己抽里程**，直接用预览算好的 {@link #planKm}。
        //   以前预览抽一个、这里再抽一个，白框和绿框的公里数就对不上了。
        // ★★★ 2026-10-09：上界从 5.0 放宽到 10.0（服务端 max_distance）。
        //   新版生成器按 lengthBias=1.7 抽，实测 1000 条落在 2.19~4.60 km，
        //   5.0 这个自设上限本来碰不到；但留着它只会在边界上悄悄把预览里程丢掉、
        //   退回"现抽一个"，白框和绿框又对不上。用服务端真正的区间更省事。
        double km, paceLo, paceHi;
        if (forcedKm >= 2.0 && forcedKm <= 10.0) {
            km = forcedKm;
            log("[参数] 本次里程由 Intent 指定：" + km + " 公里");
        } else if (planKm >= 2.0 && planKm <= 10.0) {
            km = planKm;
        } else {
            km = 2.0 + Math.round(rnd.nextDouble() * 30) / 10.0;
            planKm = km;
            log("[参数] 没有预览里程（地图还没生成），现抽一个：" + km + " 公里");
        }
        double w = 0.6 + rnd.nextDouble() * 0.6;
        paceLo = 3.5 + rnd.nextDouble() * (6.0 - 3.5 - w);
        paceHi = paceLo + w;
        // ---- 硬约束（服务端规则：里程 2.00~10.00 km，配速 3'00"~8'00"/km）
        if (km < 2.0) km = 2.0;
        if (km > 10.0) km = 10.0;
        if (paceLo < 3.5) paceLo = 3.5;
        if (paceHi > 6.0) paceHi = 6.0;
        if (paceHi <= paceLo) paceHi = paceLo + 0.3;
        km = Math.round(km * 100) / 100.0;

        // 配速(分/公里) -> 速度(m/s)
        final double fKm = km;
        final double vSlow = 1000.0 / (paceHi * 60.0);   // 慢的一头
        final double vFast = 1000.0 / (paceLo * 60.0);   // 快的一头

        AppPrefs.sp(this).edit()
                .putString("ident", raw)
                .putFloat(AppPrefs.K_DIST, (float) km)
                .putFloat(AppPrefs.K_PACE_LO, (float) paceLo)
                .putFloat(AppPrefs.K_PACE_HI, (float) paceHi)
                .apply();

        ledao = new Ledao(this);
        RUN_LEDAO = ledao;             // ★ 进程级句柄，Activity 重建后停止按钮还找得到它
        int found = ledao.parseIdentity(raw);
        if (found == 0 || !ledao.ident().containsKey("uid") || !ledao.ident().containsKey("token")) {
            log("!! 解析失败：至少需要 uid 与 token。当前解析到 " + found + " 个字段");
            Toast.makeText(this, "缺少 uid 或 token", Toast.LENGTH_LONG).show();
            return;
        }
        Ledao L = ledao;
        L.randomRoute = true;                 // 路线永远弯曲化 + 随机起点
        L.water = water;                      // 水面位图 —— 路线自动避湖
        L.roadGrid = roadGrid;                // 路网 —— 路线只走街道（穿楼问题就靠它）
        // ★★★ 新版路线规划（2026-10-09）：把路网和预览那两个数一起交过去。
        //   如果服务端指派的就是预览那一对点，真跑会用同一个种子重生成 ——
        //   结果和地图上画的**逐点相同**；点对变了才重新抽种子。
        L.routeNet = routeNet;
        L.routeSeed = planSeed;
        L.routeCpN = planCpN;
        if (routeNet != null) {
            log("   [路线] 路网 " + routeNet.nNodes + " 街口 / " + routeNet.nEdges
                    + " 街段；预览种子 " + planSeed
                    + (planCpN != null ? "（打卡点 #" + planCpN[0] + "/#" + planCpN[1] + "）" : ""));
        }

        // ★★★ umid —— lpi0 明文的前 34 字符（设备绑定），从真机 MMKV 里读
        try {
            String um = Identity.readUmid();
            if (um != null && um.length() >= 30) {
                L.umid = um;
                log("   [lpi0] umid = " + um);
            } else {
                log("   [lpi0] 读不到 umid，用内置值 " + Ledao.DEFAULT_UMID);
            }
        } catch (Throwable e) {
            log("   [lpi0] 读 umid 异常：" + e + "（用内置值）");
        }

        // ★★★ mobileDeviceId（2026-10-04 定案，docs/23）：
        //   真机自己发的是 ffffffff-af0e-5497-ffff-ffffef05ac4a —— 一个看着像「坏 GUID」
        //   的值，但它**就是**服务端认的那个，而且只存在于抓包里（全目录 grep 只有
        //   WebView 缓存带着它，没落盘，是运行时算出来的）。
        //   所以规则只有一条：**已经有一个 36 位的值，就绝不要动它。**
        //   以前这里写的是「能 root 读到 .system_id.log 就覆盖」，等于把服务端认得的值
        //   换成真机从来没用过的 d69d4891-…（真机请求里出现 0 次）——
        //   这就是「一直抓不到个人标识」的真相。
        String dev = readDeviceId();
        String cur = L.ident().get("mobileDeviceId");
        if (cur != null && cur.length() == 36) {
            log("   [设备号] " + cur + "（保留现有值；不用 .system_id.log 覆盖）");
        } else if (dev.length() == 36) {
            L.ident().put("mobileDeviceId", dev);
            log("   [设备号] 现有值缺失，退回 .system_id.log：" + dev);
            log("            ⚠ 这不是真机的 mobileDeviceId，只是兜底；"
                    + "有条件请粘贴抓包里的 " + Ledao.KNOWN_DEVICE_ID);
        } else {
            // ★★ 身份里完全没有设备号：不再拿 .system_id.log 的兜底值硬开跑
            //    （那个值真机从没用过），而是让助手载入真机 App 现场取一个，
            //    取到后自动接着开跑（captureRealId 里 thenRun 分支）。
            log("   ⚠ 身份里没有 mobileDeviceId —— 先载入步道乐跑现场取一个，取到就自动开跑");
            setStatus("⏳ 正在载入步道乐跑取标识…");
            pendingAutorun = true;
            captureRealId(true);
            return;
        }

        log("");
        log("=== 本次参数（系统自动设置） ===");
        log("   目标 " + String.format(Locale.US, "%.2f", fKm) + " km");
        log("   配速 " + String.format(Locale.US, "%.1f", paceLo) + " ~ "
                + String.format(Locale.US, "%.1f", paceHi) + " 分/公里"
                + "   → 速度 " + String.format(Locale.US, "%.2f", vSlow) + " ~ "
                + String.format(Locale.US, "%.2f", vFast) + " m/s"
                + "   每 6 秒上报一点");
        for (String k : new String[]{"uid", "school_id", "student_num", "mobileDeviceId",
                "mobileModel", "mobileOsVersion"}) {
            String v = L.ident().get(k);
            if (v != null) log("   " + pad(k, 15) + " = " + v);
        }
        if (!L.ident().containsKey("refresh_token")) log("   ⚠ 无续期令牌：只能跑一次");

        L.localIp = Ledao.wifiIp();
        L.termId = "13119";
        // ★ 新的一局开始了 —— 把上一场还没弹的那条错误丢掉，别在开跑时才冒出来
        Alerts.clearPending(this);
        setRunBtn(true);
        // ★ 绿框里的目标里程用 %.1f —— 和白框（refreshPlanInfo）显示的位数一致，
        //   两个框里就是一模一样的数字（2026-10-08 用户要求「改成一致的」）。
        setStatus("准备中… 目标 " + String.format(Locale.US, "%.1f", fKm) + " km");
        planIds = null;                       // 服务端还没指派，白框先说预览的两个点
        refreshPlanInfo();
        map.clearTrace();
        map.setCheckpoints(defaultCps());

        worker = new Thread(() -> {
            RUN_ACTIVE = true;                 // ★ 立刻置位（放线程里也行，但要保证早于任何网络调用）
            RUN_LEDAO = L;
            /* ★★★ v1.0.25（docs/59）：后台运行的三件套，在**任何网络调用之前**挂上。
             *
             *   ① 前台服务：进程优先级提到"前台服务"，切走/息屏不会被顺手清掉。
             *      （抓包那条路早就起了它；这里兜底 —— 用户可能没走「自动提取」，
             *       或者提取完那一下就把它停了。）
             *   ② PARTIAL_WAKE_LOCK：**前台服务不保证 CPU 不睡**，这把锁才保证。
             *      息屏后节拍被拉长 → 配速被拖慢 → 可能撞上服务端 480 s/km 的上限。
             *      45 分钟是兜底超时（一场撑死 30 分钟），防止哪条路径忘了释放。
             *   ③ files/run.log：日志同步落盘并 flush，进程被系统清掉也能捞回来。
             */
            try {
                if (!SniffGuard.alive) SniffGuard.start(MainActivity.this);
                SniffGuard.runMode = true;
                SniffGuard.holdAwake(MainActivity.this, 45L * 60 * 1000);
                SniffGuard.setText(MainActivity.this, "准备起跑…（可以息屏 / 切到别的应用）");
                RunLog.start(getFilesDir(), String.format(Locale.US,
                        "══════ 本场开始 %s  目标 %.2f km ══════",
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()), fKm));
                /* ★★★ v1.0.30（docs/62）：前台服务是**异步**起的（startService →
                 *   onCreate → onStartCommand），紧接着查 alive 必然是 false ——
                 *   v1.0.28 的自检就在 MI 8 上这么误报过一次。这里最多等 3 秒，
                 *   然后把"到底起没起"如实写进日志：没起来就等于没有前台保命，
                 *   切走以后随时会被系统冻住或者清掉。 */
                long svcT0 = System.currentTimeMillis();
                while (!SniffGuard.alive && System.currentTimeMillis() - svcT0 < 3000) {
                    try { Thread.sleep(200); } catch (InterruptedException ignore) { }
                }
                long svcMs = System.currentTimeMillis() - svcT0;
                log("★ 后台运行已就绪：");
                log("   · 前台服务：" + (SniffGuard.alive
                        ? "在跑 ✅（等了 " + svcMs + " ms，异步起的属正常）"
                        : "没起来 ❌ —— 切走以后系统随时可能冻住 / 清掉助手"));
                log("   · 唤醒锁：" + (SniffGuard.awake()
                        ? "已持有 ✅（息屏后节拍不会被拉长）"
                        : "没拿到 ❌ —— 息屏后每一拍可能被拉长，配速会被拖慢"));
                log("   · 日志落盘：files/" + RunLog.NAME + "（"
                        + (RunLog.active() ? "可写 ✅" : "写不了 ❌") + "）");
                log("   · 白名单：" + (ignoringBatteryOpt() ? "已加 ✅" : "还没加 ❌")
                        + "　机型 " + android.os.Build.MANUFACTURER + " "
                        + android.os.Build.MODEL + " / Android " + android.os.Build.VERSION.RELEASE);
                log("   跑步期间可以息屏、可以切到别的应用；唯独别从最近任务里把助手划掉 ——"
                        + "划掉只是关掉这扇窗，跑步照跑（想停就点回来按「■ 停止跑步」）。");
                /* ★ v1.0.30：把新增的兜底说清楚 —— 系统真冻了我们，也不至于白跑一场。 */
                log("   ★ 万一系统把助手冻住了（日志里会出现「⚠ 系统把助手冻住了 X 秒」）："
                        + "那段时间会自动从用时里剔除，配速不会被拖坏，回来接着跑完就行。");
            } catch (Throwable t) {
                log("⚠ 后台运行三件套没挂全（不影响跑步）：" + t);
            }
            try {
            // ★ 先抢会话：结束步道乐跑 → 用手上令牌确认可用
            if (!prepareSession(L)) {
                final String msg = lastPrepareError;
                ui.post(() -> {
                    // ★ 先放开互斥，才允许「自动重试」再开一局
                    RUN_ACTIVE = false;
                    RUN_LEDAO = null;
                    setRunBtn(false);
                    log("");
                    log("⚠ 手上的令牌已作废（" + msg + "）");
                    autorunRound++;
                    if (autorunRound > 2) {
                        log("   已经自动重试 " + (autorunRound - 1) + " 次仍失败 —— 先停下。");
                        log("   请在步道乐跑里正常打开一次，再回来点「开始跑步」。");
                        setStatus("❌ 令牌一直失效，请先在步道乐跑里打开一次");
                        final int rounds = autorunRound - 1;
                        autorunRound = 0;
                        reportError("身份被顶掉了，自动重抓也没成功",
                                "手上的令牌已经被服务端判失效（" + msg + "）。\n"
                              + "助手自己重抓了 " + rounds + " 次，都没换回一份能用的身份。\n"
                              + "最常见的原因：步道乐跑在后台把会话刷走了"
                              + "—— 它是单会话登录，两边同时在线就会互相顶。",
                                "① 打开一次步道乐跑（正常登录、进到首页就行）\n"
                              + "② 切回助手，点「📲 自动提取」\n"
                              + "③ 抓完再点「▶ 开始跑步」");
                        return;
                    }
                    log("   → 自动进入抓取模式：会自己打开步道乐跑、抓完自动切回来接着跑");
                    setStatus("⏳ 正在重新获取身份（全自动，请稍候）…");
                    // ★ 必须传 true：autoExtract 内部会把 pendingAutorun 赋成入参
                    autoExtract(true);
                });
                return;
            }
            persistIdent(L);
            Ledao.Result r = L.run(fKm, vSlow, vFast, 6.0, 36.658704, 114.604740);
            persistIdent(L);
            ui.post(() -> {
                log("");
                log("========== 结果 ==========");
                log(r.ok ? "✅ " + r.message : "❌ " + r.message);
                if (r.recordId.length() > 0) log("record_id = " + r.recordId);
                if (r.distance.length() > 0) log("里程/用时 = " + r.distance + " km / " + r.usedTime + " 秒");
                if (r.detail.length() > 0) log("明细: " + r.detail);
                setStatus(r.ok ? "✅ 完成，record_id=" + r.recordId : "❌ " + r.message);
                setRunBtn(false);
                autorunRound = 0;
                Toast.makeText(LIVE != null ? LIVE : this,
                        r.ok ? "跑步记录创建成功" : "未成功，请看日志", Toast.LENGTH_LONG).show();
                /* ★★★ 2026-10-10 用户第 2 条：跑步没跑成 → 弹窗说清楚原因和下一步。
                 *   两种情况不弹：
                 *     · 用户自己点的停止（"已手动停止"）—— 那不是错误，别吓人
                 *     · Result.reported —— 跑前人脸那次已经弹过"人脸专用"的窗了，
                 *       同一个失败弹两个窗比不弹还烦 */
                if (!r.ok && !r.reported && !r.message.contains("已手动停止")) {
                    String[] a = Err.advise(r.message);
                    Alerts.error(LIVE != null ? LIVE : this, a[0], a[1], a[2], this::copyLog);
                }
                /* ★★★ v1.0.30（docs/62）：这一场被系统冻过 —— 必须让用户知道，
                 *   否则他会以为"是助手自己停了"，下次还会在同一个地方栽。
                 *   说得准确一点：用时已经把冻住的那几段剔掉了，所以这不是
                 *   "这一场废了"，而是"这台机器没放开助手的后台"。 */
                if (r.frozenSec >= 10) {
                    log("★ 这一场系统冻了 " + r.frozenSec + " 秒 —— 已全部从用时里剔除，"
                            + "配速不受影响（见上面那几行「⚠ 系统把助手冻住了」）");
                    if (r.ok)
                        Alerts.error(LIVE != null ? LIVE : this,
                                "后台被系统冻住了 " + r.frozenSec + " 秒（这一场照常跑完了）",
                                "跑步期间系统把助手整进程冻住了 " + r.frozenSec + " 秒："
                              + "前台服务和常驻通知都还在（所以看着像在跑），"
                              + "但那段时间一条指令都没执行 —— 既没有日志，也没有里程。\n"
                              + "冻住的时间已经自动从「用时」里剔除，这一场的配速和记录不受影响。",
                                "① 点首页「🔋 后台运行设置」；\n"
                              + "② 点第 ② 个按钮，把助手的「自启动 / 关联启动 / 后台活动」"
                              + "全部打开（不同机型叫法不同，页面会直接跳过去）；\n"
                              + "③ 回来点第 ③ 个按钮做一次 " + BG_PROBE_SEC + " 秒后台体检，"
                              + "看到「后台是通的 ✅」就稳了。",
                                this::copyLog);
                }
                // ★ 这一局结束了 —— 清掉实跑轨迹、重新抽下一局的预览
                //   （里程/打卡点/路线都会换新的，白框和下一次绿框仍然一致）。
                map.clearTrace();
                drawPreviewRoute();
            });
            } catch (Throwable t) {
                /* ★★★ 2026-10-10 用户第 2 条：跑步线程整个挂掉 —— 以前这里**没有 catch**，
                 *   异常直接冒到进程级兜底，界面上只剩一句日志，弹窗/通知一个都没有，
                 *   用户看到的就是"点开始跑步，然后没有然后了"。
                 *   现在：中文原因 + 出错位置 + 下一步，前台弹窗、后台发消息。 */
                final String msg = Err.one(t);
                final String stack = Err.full(t);
                log("");
                log("❌ 跑步线程整个挂掉了：" + msg);
                for (String ln : stack.split("\n")) log(ln);
                ui.post(() -> setStatus("❌ 跑步中途出错，已停止"));
                /* ★ 弹给用户的「原因」用 hint()/where() 而不是 one()：
                 *   one() 是给开发者看的一行式（含类名.方法:行号 | 原始异常），
                 *   hint() 是中文人话，where() 指出出事的位置 —— 普通用户读这两条
                 *   就够判断"要不要重跑"，剩下的交给「复制日志」。 */
                reportError("跑步中途出错了，已经停下",
                        "出错位置：" + (Err.where(t).length() > 0 ? Err.where(t) : "（不在本工程代码里）")
                      + "\n原因：" + Err.hint(t),
                        "先原样再点一次「▶ 开始跑步」—— 有些错误是服务端偶发的，重跑就好。\n"
                      + "如果每次都停在同一个地方，点下面的按钮复制日志，"
                      + "发到 " + DevNote.EMAIL + "。");
            } finally {
                RUN_ACTIVE = false;
                RUN_LEDAO = null;
                RUN_WORKER = null;
                /* ★ v1.0.25：这一场结束了（正常 / 出错 / 用户点停止都是这里）——
                 *   放掉唤醒锁、收掉日志文件、把通知改回"抓包"那句话。
                 *   必须放在 finally：不然出错退出会把锁一直攥着耗电。 */
                try {
                    SniffGuard.dropAwake();
                    RunLog.stop();
                    SniffGuard.runMode = false;
                    SniffGuard.setText(MainActivity.this, null);
                    log("★ 后台运行已收尾：唤醒锁已释放（"
                            + (SniffGuard.awake() ? "还持有 ❌" : "已释放 ✅")
                            + "），日志留在 files/" + RunLog.NAME);
                } catch (Throwable ignore) { }
            }
        }, "ledao-run");
        RUN_WORKER = worker;
        worker.start();
    }

    private String lastPrepareError = "";

    /**
     * 开跑前把会话抢过来。
     * ★ 顺序很重要：先杀步道乐跑（防止它在跑步途中刷令牌把我们顶掉），再校验令牌。
     */
    private boolean prepareSession(Ledao L) {
        killLedaoApp("防止它中途刷令牌把我们顶下线");
        try { Thread.sleep(1200); } catch (InterruptedException ignore) { }
        JSONObject chk = L.call("User/User", null, 0);
        int st = chk.optInt("status");
        if (st == 1) {
            log("   [准备] 令牌可用 ✅  token=" + shortTok(L.ident().get("token")));
            log("   [准备] " + (L.ident().containsKey("refresh_token")
                    ? "已持有续期令牌，途中被顶下线能自动救回来" : "⚠ 无续期令牌"));
            return true;
        }
        lastPrepareError = "status=" + st + " " + chk.optString("info");
        return false;
    }

    /** 结束步道乐跑的后台进程（普通权限即可，不需要 root） */
    private boolean killLedaoApp(String why) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            am.killBackgroundProcesses(PKG_LEDAO);
            log("   [准备] 已结束步道乐跑的进程（" + why + "）");
            return true;
        } catch (Throwable t) {
            log("   [准备] 结束进程失败（请手动把它从多任务里划掉）：" + t);
            return false;
        }
    }

    private void persistIdent(Ledao L) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String k : new String[]{"uid", "token", "access_token", "refresh_token",
                    "school_id", "student_num", "mobileDeviceId", "mobileModel",
                    "mobileOsVersion", "ostype", "version", "version_name"}) {
                String v = L.ident().get(k);
                if (v == null || v.length() == 0) continue;
                if (sb.length() > 0) sb.append('&');
                sb.append(k).append('=').append(v);
            }
            final String txt = sb.toString();
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString("ident", txt).apply();
            ui.post(() -> { etIdent.setText(txt); refreshIdentBox(); });
        } catch (Exception ignore) { }
    }

    /** 从身份串里抠出 mobileDeviceId（没有就返回空串）—— 纯字符串处理，任何线程都能调 */
    private static String devidFrom(String raw) {
        if (raw == null) return "";
        for (String seg : raw.split("[&\\n]")) {
            int i = seg.indexOf('=');
            if (i > 0 && "mobileDeviceId".equals(seg.substring(0, i).trim())) {
                String v = seg.substring(i + 1).trim();
                if (v.length() == 36) return v;
            }
        }
        return "";
    }

    /**
     * 把身份串里的某一项就地改掉（保留其它字段，不动用户粘贴过的内容），
     * 同时落进 SharedPreferences，并更新正在跑的那个 Ledao 会话。
     */
    private void setIdentField(String k, String v) {
        TreeMap<String, String> kv = new TreeMap<>();
        for (String seg : etIdent.getText().toString().trim().split("[&\\n]")) {
            int i = seg.indexOf('=');
            if (i > 0) kv.put(seg.substring(0, i).trim(), seg.substring(i + 1).trim());
        }
        kv.put(k, v);
        StringBuilder sb = new StringBuilder();
        for (String kk : kv.keySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(kk).append('=').append(kv.get(kk));
        }
        final String txt = sb.toString();
        etIdent.setText(txt);
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString("ident", txt).apply();
        if (ledao != null) ledao.ident().put(k, v);
        refreshIdentBox();
    }

    // ==================================================================
    //  ★★ 载入步道乐跑 → 现场取 mobileDeviceId（docs/24）
    // ==================================================================
    /**
     * 为什么要这么做：真机的 mobileDeviceId
     * （ffffffff-af0e-5497-ffff-ffffef05ac4a）不落盘 —— MMKV / SharedPreferences /
     * 数据库里都找不到，只有 WebView 缓存里的 H5 页面 URL 带着它。
     * 以前只能抓包；现在助手自己把它拉起来，直接读它进程内存（ART 主堆第 1 个 rw 段
     * 里就有，实测 0 秒命中），当场取回。
     *
     * @param thenRun 取到之后是否接着开跑
     */
    private void captureRealId(final boolean thenRun) {
        if (RUN_ACTIVE) {
            Toast.makeText(this, "正在跑步中", Toast.LENGTH_SHORT).show();
            return;
        }
        if (realIdThread != null && realIdThread.isAlive()) {
            log("（上一次取标识还在进行中，等它跑完）");
            return;
        }
        log("");
        log("========== 载入步道乐跑 · 现场取标识 ==========");
        setStatus("⏳ 正在载入步道乐跑并取标识…");
        realIdThread = new Thread(() -> {
            final RealId.Result r = RealId.capture(this, true, this);
            ui.post(() -> {
                if (r.trusted()) {
                    String cur = etIdent.getText().toString();
                    String old = null;
                    for (String seg : cur.split("[&\\n]")) {
                        int i = seg.indexOf('=');
                        if (i > 0 && "mobileDeviceId".equals(seg.substring(0, i).trim())) {
                            old = seg.substring(i + 1).trim();
                        }
                    }
                    setIdentField("mobileDeviceId", r.id);
                    if (old != null && old.length() == 36 && !old.equals(r.id)) {
                        log("   ⚠ 身份里的设备号已由 " + old + " 换成现场取到的 " + r.id);
                    }
                    setStatus("✅ 标识已就绪");
                    // ★★ 取标识要「载入步道乐跑」，而它启动一次就会刷一次令牌
                    //    （refresh_token 一次性）—— 所以这里必须重新提取一次身份，
                    //    把 MMKV 里它刚写过的新令牌拿过来，否则一开跑就是 102。
                    //    RealId.capture 收尾时已经把乐跑结束掉了。
                    log("   → 重新提取一次身份（取回它刚刷过的那份令牌）");
                    if (thenRun) {
                        try {
                            android.content.Intent it = new android.content.Intent(
                                    this, MainActivity.class);
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(it);
                        } catch (Throwable ignore) { }
                    }
                    ui.postDelayed(() -> autoExtract(thenRun), 300);
                } else {
                    // ★★ 不可信的值绝不写进身份（2026-10-04 真出过事故：
                    //    内存没扫到时回落到 files 里的 .system_id.log，
                    //    把原本正确的设备号覆盖成了真机从来没用过的那个）。
                    if (r.ok()) {
                        log("   ⚠ 只在 files/shared_prefs 里找到 " + r.id
                                + " —— 那是 .system_id.log 一类「像设备号但不是」的值，**不采用**，身份保持原样");
                    }
                    // 退路只给「没有 root」的情况：这时没别的办法，用 .system_id.log
                    // 兜底至少能试着跑一次（值多半不认，但比直接卡住强）。
                    // 有 root 时不给退路 —— 重试一次就能取到真值，不该拿假值去浪费一局。
                    if (thenRun && !Identity.hasRoot()) {
                        String dev = readDeviceId();
                        if (devidFrom(etIdent.getText().toString()).length() != 36
                                && dev.length() == 36) {
                            setIdentField("mobileDeviceId", dev);
                            log("   ⚠ 没有 root，退回 .system_id.log 兜底值 " + dev
                                    + "（真机请求里出现 0 次，多半不认）");
                            setStatus("⚠ 用的是兜底设备号");
                            pendingAutorun = true;
                            ui.postDelayed(this::startRun, 300);
                            return;
                        }
                    }
                    pendingAutorun = false;
                    setStatus("⚠ 没取到可信标识 —— 先手动打开步道乐跑进一个页面再试");
                }
            });
        }, "realid");
        realIdThread.start();
    }

    // ==================================================================
    //  自动提取身份
    // ==================================================================
    private void updateSniffBtn() {
        boolean on = LocalProxy.listening();
        btnSniff.setText(on ? "⏹  停止提取" : "📲  自动提取");
        paintBtn(btnSniff, on ? "#B00020" : "#1565C0");   // 走 paintBtn：原色要留在 tag 上
    }

    private void autoExtract(final boolean thenRun) {
        if (RUN_ACTIVE) {
            Toast.makeText(this, "正在跑步中", Toast.LENGTH_SHORT).show();
            return;
        }
        if (LocalProxy.listening()) {
            pendingAutorun = pendingAutorun || thenRun;
            log("（抓包已经在跑了，就在这等 —— 身份一抓到就自动开跑）");
            setStatus("⏳ 抓包中 —— 请打开步道乐跑");
            return;
        }
        if (extractThread != null && extractThread.isAlive()) return;
        pendingAutorun = thenRun;
        // ★ 身份串只能在 UI 线程读（后台线程碰 TextView 是不允许的），先取出来
        final String identRaw = etIdent.getText().toString().trim();
        log("");
        log("========== 自动提取身份 ==========");
        setStatus("⏳ 正在启动抓取…");
        extractThread = new Thread(() -> {
            // root 可用就走文件直读（最快）；否则走本地代理抓取（不需要 root）
            if (!Identity.hasRoot()) {
                ui.post(() -> {
                    log("   本机无 root —— 走「本地代理抓取」方式");
                    log("   " + Identity.rootDiagnosis());
                    startSniff();
                });
                return;
            }
            try {
                ui.post(() -> log("   检测到 root —— 直接读步道乐跑的数据文件…"));
                final Identity id = Identity.extract(this);
                if (id.uid.length() == 0) {
                    ui.post(() -> { log("   root 读取没拿到 uid —— 改用抓包方式"); startSniff(); });
                    return;
                }
                final Ledao probe = new Ledao(this);
                TreeMap<String, String> base = new TreeMap<>();
                base.put("uid", id.uid);
                // ★★ 校验令牌时也必须用**当前那个** mobileDeviceId（抓包里那个），
                //    不能用 .system_id.log 的兜底值 —— 服务端把会话和设备绑定，
                //    换一个它没见过的设备号去问 User/User，多半直接 101/102，
                //    于是「root 里的令牌都失效了」→ 白白回落到抓包方式。
                // ★★ 优先用**身份串里已有的**设备号（那才是抓包/上次采集来的真值）。
                //    以前这里只看 ledao.ident()，Activity 一重建 ledao 就是 null，
                //    于是每次都当成「没有设备号」，回落到 .system_id.log 的假值。
                String keepDev = devidFrom(identRaw);
                if (keepDev.length() != 36 && ledao != null) {
                    String v = ledao.ident().get("mobileDeviceId");
                    if (v != null && v.length() == 36) keepDev = v;
                }
                if (keepDev.length() != 36) {
                    // 身份里确实没有 —— 载入步道乐跑现场取（docs/24）。
                    // 千万别再用 .system_id.log 那个「真机请求里出现 0 次」的值。
                    ui.post(() -> log("   身份里没有 mobileDeviceId —— 载入步道乐跑现场取一个…"));
                    String live = RealId.harvest(this, this);
                    if (live.length() == 36) {
                        keepDev = live;
                    } else {
                        keepDev = id.deviceId;
                        if (keepDev.length() == 36) {
                            final String fb = keepDev;
                            ui.post(() -> log("   ⚠ 现场没取到，退回 .system_id.log 兜底值 "
                                    + fb + "（真机请求里出现 0 次，多半不认）"));
                        }
                    }
                }
                if (keepDev.length() == 36) base.put("mobileDeviceId", keepDev);
                base.put("school_id", id.schoolId);
                if (id.studentNum.length() > 0) base.put("student_num", id.studentNum);
                probe.applyIdentity(base);
                String goodTok = null, goodRt = "";
                for (String t : id.tokens) {
                    probe.ident().put("token", t);
                    probe.ident().put("access_token", t);
                    JSONObject r = probe.call("User/User", null, 0);
                    if (r.optInt("status") == 1) { goodTok = t; break; }
                }
                if (goodTok == null) {
                    for (String rt : id.refreshTokens) {
                        probe.ident().put("refresh_token", rt);
                        JSONObject rr = probe.refreshToken();
                        if (rr != null && rr.optInt("status") == 1) {
                            goodTok = probe.ident().get("token");
                            goodRt = probe.ident().get("refresh_token");
                            break;
                        }
                    }
                }
                if (goodTok == null) {
                    ui.post(() -> { log("   root 里的令牌都失效了 —— 改用抓包方式"); startSniff(); });
                    return;
                }
                if (goodRt.length() == 0 && id.refreshTokens.size() > 0) goodRt = id.refreshTokens.get(0);
                final String txt = "uid=" + id.uid + "&token=" + goodTok
                        + (goodRt.length() > 0 ? "&refresh_token=" + goodRt : "")
                        + "&school_id=" + id.schoolId
                        + (id.studentNum.length() > 0 ? "&student_num=" + id.studentNum : "")
                        + (keepDev.length() == 36 ? "&mobileDeviceId=" + keepDev : "");
                ui.post(() -> {
                    etIdent.setText(txt);
                    refreshIdentBox();
                    log("   ✅ root 提取成功：uid=" + id.uid);
                    setStatus("✅ 身份已就绪");
                    if (pendingAutorun) { pendingAutorun = false; startRun(); }
                });
            } catch (final Exception e) {
                ui.post(() -> { log("   root 提取异常（" + e + "）—— 改用抓包方式"); startSniff(); });
            }
        }, "extract");
        extractThread.start();
    }

    private void startSniff() {
        log("");
        log("--- 开始抓取身份 ---");
        if (ProxyVpn.supported()) {
            android.content.Intent prep = null;
            try { prep = android.net.VpnService.prepare(this); } catch (Throwable ignore) { }
            if (prep != null) {
                log("★ 需要授权一次 VPN（系统弹窗点「确定」，以后不再问）");
                log("  好处：不用改 WLAN 代理，步道乐跑那边 isWifiProxy 仍然是 false");
                sniffAfterVpnGrant = true;
                try { startActivityForResult(prep, REQ_VPN); }
                catch (Throwable e) { log("!! 拉起 VPN 授权失败：" + e); startSniffViaSystemProxy(); }
                return;
            }
            startVpnAndProxy();
            return;
        }
        log("（系统低于 Android 10，没有 VPN 代理接口 —— 退回「系统代理」方式）");
        startSniffViaSystemProxy();
    }

    private void startVpnAndProxy() {
        try {
            android.content.Intent it = new android.content.Intent(this, ProxyVpn.class);
            it.setAction(ProxyVpn.ACTION_START);
            startService(it);
        } catch (Throwable e) {
            log("!! 启动 VPN 服务失败：" + e);
            startSniffViaSystemProxy();
            return;
        }
        ui.postDelayed(() -> {
            if (!ProxyVpn.running) {
                log("!! VPN 没能建立（" + ProxyVpn.lastError + "）");
                startSniffViaSystemProxy();
                return;
            }
            vpnMode = true;
            log("★ VPN 已建立 —— 步道乐跑的流量会走到本机代理");
            log("  系统代理栏位保持「无」→ isWifiProxy 仍是 false ✅");
            if (!startProxyCore()) return;
            maybeAutoOpenLedao();
        }, 1200);
    }

    private void startSniffViaSystemProxy() {
        vpnMode = false;
        boolean ok = false;
        if (hasSecureSettings()) {
            saveOldProxy(readProxy());
            ok = setProxy(true);
            if (ok) {
                sysProxyOwned = true;
                log("★ 已自动把系统代理设为 127.0.0.1:" + LocalProxy.PORT + "（用完自动恢复）");
            }
        }
        if (!ok) {
            // ★ 用户要求：代理这块不要写到明面上。
            //   能自动设就自动设；设不了（没有 WRITE_SECURE_SETTINGS）就静默走 VPN，
            //   实在都不行才给一句最简提示，不再往日志里倒一堆操作步骤。
            log("   正在切换到抓包通道…（若手机低于 Android 10，会需要一次 WLAN 代理授权）");
        }
        if (!startProxyCore()) return;
        maybeAutoOpenLedao();
    }

    /** 两条路共用的部分：把本地代理跑起来 */
    private boolean startProxyCore() {
        try {
            proxy = LocalProxy.acquire(this, LocalProxy.PORT, mkSink());
            SniffGuard.start(this);
            updateSniffBtn();
            setStatus("⏳ 抓包中 —— 等步道乐跑的请求");
            log("   代理就绪：127.0.0.1:" + LocalProxy.PORT);
            // 90 秒还没拿到续期令牌，就先用手上的 uid+token 收工
            ui.postDelayed(() -> {
                if (LocalProxy.listening() && sniffHits > 0 && !sniffKv.containsKey("refresh_token")) {
                    log("⏱ 已等 90 秒仍没拿到续期令牌，先用现有 uid+token 收工。");
                    onIdentityReady("超时，只有 uid+token");
                }
            }, 90000);
            return true;
        } catch (Throwable t) {
            log("!! 本地代理启动失败：" + t.getMessage());
            log("   处理办法：设置 → 应用 → 跑步测试助手 → 强行停止，然后重开本应用。");
            setStatus("❌ 代理启动失败，请强行停止本应用后重开");
            // ★ 注意：这里绝不能去写系统代理 —— VPN 模式下没这个权限，
            //   写了就是一条 SecurityException 假警报（v2.1 的日志里全是它）
            updateSniffBtn();
            return false;
        }
    }

    private LocalProxy.Sink mkSink() {
        return new LocalProxy.Sink() {
            @Override
            public boolean onHttp(String host, String path, String body) {
                return onSniffed(host, path, body);
            }

            @Override
            public void onLog(String s) {
                log(s);
            }

            @Override
            public void onResp(String host, String path, String body) {
                onSniffedResp(host, path, body);
            }
        };
    }

    /**
     * ★ 一键到底：自动打开步道乐跑 → 停 8 秒（让它把登录/续期请求发出来）
     *   → 自动切回助手。本应用 targetSdk=28，不受 Android 10 后台启动 Activity 限制。
     */
    private void maybeAutoOpenLedao() {
        // ★ 「一键到底」是固定行为（用户要求不用再显示开关）：
        //   自动打开步道乐跑停 8 秒，让它把登录/令牌请求发出来，然后自动切回。
        ui.postDelayed(() -> {
            if (!LocalProxy.listening()) return;
            android.content.Intent li;
            try {
                li = getPackageManager().getLaunchIntentForPackage(PKG_LEDAO);
            } catch (Throwable t) { li = null; }
            if (li == null) {
                log("!! 手机上没找到步道乐跑 —— 请手动打开它");
                return;
            }
            log("→ 自动打开步道乐跑，停留 " + (LEDAO_DWELL_MS / 1000) + " 秒…");
            try {
                li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(li);
            } catch (Throwable t) {
                log("!! 打开步道乐跑失败：" + t + " —— 请手动打开");
                return;
            }
            ui.postDelayed(() -> {
                log("→ 自动切回助手");
                try {
                    android.content.Intent back = new android.content.Intent(this, MainActivity.class);
                    back.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(back);
                } catch (Throwable ignore) { }
                ui.postDelayed(() -> {
                    if (sniffKv.containsKey("uid") && sniffKv.containsKey("token"))
                        log("✅ 身份已抓回：" + shortTok(sniffKv.get("token")));
                    else
                        log("⚠ 还没抓到 —— 请手动切到步道乐跑点两个页签再回来");
                }, 2500);
            }, LEDAO_DWELL_MS);
        }, 700);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FACE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                installFacePhoto(data.getData());
            } else {
                log("⚠ 没选照片");
            }
        }
        // ★ #8：水滴定位图标里的头像
        if (req == REQ_PICK_AVATAR) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try {
                    String p = copyToFiles(data.getData(), "avatar.png");
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putString(AppPrefs.K_AVATAR, "f:" + p).apply();
                    map.setAvatarSpec("f:" + p);
                    log("[头像] 已用本地图片 " + p);
                } catch (Throwable t) {
                    logFail("保存头像", t);
                }
            } else {
                log("⚠ 没选头像图");
            }
        }
        if (req == REQ_PICK_MARKER) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try {
                    String p = copyToFiles(data.getData(), "marker.png");
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putString(AppPrefs.K_MARKER, "f:" + p)
                            .putString(AppPrefs.K_CUSTOM, p).apply();
                    map.setMarkerSpec("f:" + p);
                    log("[图标] 已用本地图片 " + p);
                } catch (Throwable t) {
                    logFail("保存图标", t);
                }
            } else {
                log("⚠ 没选图标图");
            }
        }
        if (req == REQ_PICK_BG) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try {
                    String p = copyToFiles(data.getData(), "bg.png");
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putString(AppPrefs.K_BGIMG, p).apply();
                    applyTheme();
                    log("[主题] 背景图已换成本地图片");
                } catch (Throwable t) {
                    logFail("保存背景图", t);
                }
            } else {
                log("⚠ 没选背景图");
            }
        }
        if (req == REQ_VPN && sniffAfterVpnGrant) {
            sniffAfterVpnGrant = false;
            if (res == RESULT_OK) {
                log("✅ VPN 已授权");
                startVpnAndProxy();
            } else {
                log("⚠ 你拒绝了 VPN 授权 —— 改用「系统代理」方式");
                startSniffViaSystemProxy();
            }
        }
    }

    private void stopVpnIfAny() {
        if (!vpnMode) return;
        vpnMode = false;
        try {
            android.content.Intent it = new android.content.Intent(this, ProxyVpn.class);
            it.setAction(ProxyVpn.ACTION_STOP);
            startService(it);
            stopService(new android.content.Intent(this, ProxyVpn.class));
            log("[VPN] 已关闭");
        } catch (Throwable ignore) { }
    }

    private void stopSniff(String why) {
        final String reason = why;
        quickFallbackArmed = false;
        ui.post(() -> {
            LocalProxy.shutdown();          // ★ 单例，彻底关掉才不会再占 8899
            proxy = null;
            /* ★ v1.0.25：跑步进行中不关保命服务 —— 唤醒锁和前台优先级都靠它
             *   （抓包那条路结束了不影响跑步；跑步自己会在收尾时改通知文案）。 */
            if (!RUN_ACTIVE) SniffGuard.stop(this);
            else log("[抓包] 跑步进行中 —— 保命服务继续留着（唤醒锁挂在它上面）");
            stopVpnIfAny();
            restoreProxy();
            updateSniffBtn();
            log("[抓包] 已停止（" + reason + "）");
        });
    }

    // ==================================================================
    //  抓到的请求 / 响应
    // ==================================================================
    private boolean onSniffed(String host, String path, String body) {
        String plain = body;
        if (body != null && body.startsWith("key=")) {
            plain = decryptAny(body.substring(4));
            if (plain == null) plain = "(解密失败) " + body.substring(0, 200);
        }
        if (recordMode) appendCapture(host, path, plain);
        if (plain == null || plain.indexOf("uid") < 0) return false;

        TreeMap<String, String> kv = new TreeMap<>();
        try {
            JSONObject o = new JSONObject(plain);
            for (String k : new String[]{"uid", "token", "access_token", "refresh_token",
                    "school_id", "student_num", "mobileDeviceId", "mobileModel",
                    "mobileOsVersion", "ostype", "version", "version_name"}) {
                String v = o.optString(k, "");
                if (v.length() == 0) continue;
                if ("uid".equals(k) && ("1".equals(v) || "0".equals(v))) continue;
                if ("school_id".equals(k) && "0".equals(v)) continue;
                kv.put(k, v);
            }
        } catch (Exception e) {
            for (String seg : plain.split("[&\\n]")) {
                int i = seg.indexOf('=');
                if (i > 0) kv.put(seg.substring(0, i).trim(), seg.substring(i + 1).trim());
            }
        }
        if (!kv.containsKey("uid") || !kv.containsKey("token")) return false;

        // ★ token / refresh_token 后来者为准：能走到这里的都是 App 刚发出的请求，
        //   带的就是它当前的令牌。响应里拿到的更新，会由 onSniffedResp 再盖一次。
        String txt;
        synchronized (sniffKv) {
            for (java.util.Map.Entry<String, String> e : kv.entrySet()) {
                String k = e.getKey();
                if ("access_token".equals(k)) continue;        // 用 token 字段就够，别重复存
                sniffKv.put(k, e.getValue());
            }
            if (sniffKv.containsKey("token")) sniffKv.put("access_token", sniffKv.get("token"));
            txt = toQuery(sniffKv);
        }
        ui.post(() -> { etIdent.setText(txt); refreshIdentBox(); });

        boolean full;
        synchronized (sniffKv) {
            full = sniffKv.containsKey("refresh_token") && sniffKv.containsKey("uid");
        }
        if (full) {
            if (recordMode) return false;      // 记录模式：代理绝不能停
            onIdentityReady("已拿到 uid + token + refresh_token");
            return true;
        }
        sniffHits++;
        final int n = sniffHits;
        ui.post(() -> {
            log("  [第 " + n + " 次] 已拿到 uid+token（" + path + "），继续等续期令牌…");
            setStatus("⏳ 已拿到 token，再等 refresh_token…");
        });
        // ★ 要开跑的时候不能干等 90 秒：刚抓到的 token 一定是有效的（步道乐跑正拿它在用），
        //   给 12 秒等续期令牌，等到就走，等不到就用 uid+token 直接开跑。
        if (pendingAutorun && !quickFallbackArmed) {
            quickFallbackArmed = true;
            ui.postDelayed(() -> {
                quickFallbackArmed = false;
                boolean have;
                synchronized (sniffKv) {
                    have = sniffKv.containsKey("uid") && sniffKv.containsKey("token")
                            && !sniffKv.containsKey("refresh_token");
                }
                if (have && pendingAutorun && LocalProxy.listening()) {
                    log("⏱ 12 秒没等到续期令牌 —— 用刚抓到的 uid+token 直接开跑。");
                    onIdentityReady("已拿到 uid+token（本轮没有续期令牌）");
                }
            }, 12000);
        }
        return false;
    }

    /** 响应体里的新令牌（Login/RefreshToken 的新令牌只在响应里） */
    private void onSniffedResp(String host, String path, String body) {
        if (body == null || body.length() < 8 || body.charAt(0) != '{') return;
        try {
            JSONObject o = new JSONObject(body);
            Ledao.decryptResponse(o);
            JSONObject d = o.optJSONObject("data");
            if (d == null) return;
            String at = d.optString("access_token", "");
            String rt = d.optString("refresh_token", "");
            String uid = d.optString("uid", "");
            if (at.length() < 16 && rt.length() < 16) return;
            StringBuilder got = new StringBuilder();
            synchronized (sniffKv) {
                if (uid.length() > 0) { sniffKv.put("uid", uid); got.append("uid "); }
                if (at.length() >= 16) {
                    sniffKv.put("token", at);
                    sniffKv.put("access_token", at);
                    got.append("token ");
                }
                if (rt.length() >= 16) { sniffKv.put("refresh_token", rt); got.append("refresh_token "); }
            }
            final String txt;
            final String what = got.toString().trim();
            synchronized (sniffKv) { txt = toQuery(sniffKv); }
            ui.post(() -> {
                etIdent.setText(txt);
                refreshIdentBox();
                log("  ★ 响应里拿到新令牌（" + path + "）：" + what);
                setStatus("✅ 已拿到最新令牌（" + what + "）");
            });
            boolean full;
            synchronized (sniffKv) {
                full = sniffKv.containsKey("refresh_token") && sniffKv.containsKey("uid");
            }
            if (!recordMode && full) onIdentityReady("响应里拿到完整令牌");
        } catch (Throwable ignore) { }
    }

    /**
     * 身份齐了之后怎么办：
     *   · 带 pendingAutorun → 立刻杀掉步道乐跑（免得它再刷一次把令牌顶掉）→ 收抓包 → 开跑
     *   · 否则 → 收抓包，把身份留在框里
     */
    private void onIdentityReady(String why) {
        final boolean go = pendingAutorun;
        pendingAutorun = false;
        ui.post(() -> {
            log("");
            log("★★★ 身份已就绪（" + why + "）");
            String uid, tok; boolean hasRt;
            synchronized (sniffKv) {
                uid = or(sniffKv.get("uid"), "?");
                tok = shortTok(sniffKv.get("token"));
                hasRt = sniffKv.containsKey("refresh_token");
            }
            log("    uid=" + uid + "  令牌=" + tok + "  续期=" + (hasRt ? "有" : "无"));
            setStatus(go ? "✅ 身份已就绪，马上开跑" : "✅ 身份已就绪");
        });
        if (recordMode) return;
        if (go) {
            killLedaoApp("身份已拿到，立刻结束它，防止它再刷令牌");
            ui.postDelayed(() -> {
                stopSniff("身份已更新，准备开跑");
                ui.postDelayed(this::startRun, 900);
            }, 900);
        } else {
            stopSniff("已拿到完整身份");
        }
    }

    private String decryptAny(String b64url) {
        String[][] ch = {
                {"goMEzJ1pDpncNHb9", "ZUrAS2lUidjyY2gK"},
                {"Wet2C8d34f62ndi3", "K6iv85jBD8jgf32D"},
                {"S9rl6L76eBloH5a3", "s8J6j7L74u5Kdf5S"},
                {"J98huiy7EWF78S4a", "p02J7G53rfsJy7yH"},
                {"HX7jeq!@kA8X4c$5", "2cNe10vz5P9V6Tm$"},
        };
        try {
            byte[] ct = android.util.Base64.decode(
                    java.net.URLDecoder.decode(b64url, "UTF-8"), android.util.Base64.DEFAULT);
            for (String[] c : ch) {
                for (int sw = 0; sw < 2; sw++) {
                    try {
                        byte[] pt = Ledao.aes(javax.crypto.Cipher.DECRYPT_MODE, ct,
                                sw == 0 ? c[0] : c[1], sw == 0 ? c[1] : c[0]);
                        String s = new String(pt, "UTF-8").trim();
                        if (s.startsWith("{")) return s;
                    } catch (Throwable ignore) { }
                }
            }
        } catch (Throwable ignore) { }
        return null;
    }

    private static String toQuery(java.util.Map<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : kv.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void appendCapture(String host, String path, String body) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "capture.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write("=== " + host + path + " @" + (System.currentTimeMillis() / 1000) + "\n");
            w.write((body == null ? "(空)" : body) + "\n\n");
            w.close();
        } catch (Exception e) {
            log("写 capture.log 失败: " + e);
        }
    }

    private void startRecordMode(boolean setSysProxy) {
        log("");
        log("=== 📝 完整记录模式 ===");
        recordMode = true;
        sniffHits = 0;
        if (setSysProxy && hasSecureSettings()) {
            saveOldProxy(readProxy());
            if (setProxy(true)) sysProxyOwned = true;
        }
        if (!startProxyCore()) { recordMode = false; return; }
        log(">>> 现在去步道乐跑正常跑一次，跑完回来点「⏹ 停止提取」");
        setStatus("📝 记录中");
    }

    // ==================================================================
    //  系统代理（只在真的需要时才碰）
    // ==================================================================
    private boolean hasSecureSettings() {
        return checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private String readProxy() {
        try {
            return android.provider.Settings.Global.getString(getContentResolver(), "http_proxy");
        } catch (Throwable e) { return null; }
    }

    private void saveOldProxy(String v) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("old_proxy", v == null ? "" : v).apply();
    }

    /** 返回值表示"到底有没有写成功"，异常不再往日志里喷 */
    private boolean setProxy(boolean on) {
        try {
            android.provider.Settings.Global.putString(getContentResolver(), "http_proxy",
                    on ? ("127.0.0.1:" + LocalProxy.PORT) : ":0");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只有我们真的改过系统代理才需要恢复；VPN 模式压根没碰过 */
    private void restoreProxy() {
        if (!sysProxyOwned) return;
        sysProxyOwned = false;
        android.content.ContentResolver cr = getContentResolver();
        String old = getSharedPreferences(PREF, MODE_PRIVATE).getString("old_proxy", "");
        boolean clear = (old == null || old.isEmpty() || "null".equals(old) || ":0".equals(old)
                || old.contains(String.valueOf(LocalProxy.PORT)));
        String target = clear ? ":0" : old;
        for (int i = 0; i < 3; i++) {
            setProxyRaw(cr, target);
            String now = readProxy();
            if (now == null || !now.contains(String.valueOf(LocalProxy.PORT))) break;
            try { Thread.sleep(250); } catch (Exception ignore) { }
        }
        String fin = readProxy();
        log("[代理] 已恢复系统代理 -> [" + fin + "]");
        if (fin != null && fin.contains(String.valueOf(LocalProxy.PORT)))
            log("  ⚠ 恢复失败！请手动关闭：设置 → WLAN → 当前网络 → 代理 → 无");
    }

    private void setProxyRaw(android.content.ContentResolver cr, String v) {
        try {
            android.provider.Settings.Global.putString(cr, "http_proxy", v);
        } catch (Throwable ignore) { }
    }

    /**
     * 上次进程被杀留下的代理残留 —— 这时手机是断网的。
     * ★ 没权限的时候**不要**去试着写（那只会抛个没意义的 SecurityException），直接告诉用户怎么手动关。
     *
     * ★★★ 2026-10-06 23:5x 修复一个**真实的漏网**：
     *   上面这段判断只看 `127.0.0.1:8899`（我们自己的端口）。
     *   而抓包时为了让**电脑上的 Reqable** 看到流量，会把系统代理指到
     *   `127.0.0.1:19100`（adb reverse 隧道口）。那个值**不是 8899**，于是从这段
     *   自愈逻辑眼皮底下走过去了 —— 一旦电脑拔线/关机，手机全局断网，
     *   用户连步道乐跑都登不上，而且完全不知道为什么。
     *   （真实事故：2026-10-06 深夜，用户登录失败来问。）
     *
     *   现在把判据放宽成：**任何指向 127.0.0.1/localhost 的系统代理，只要不是
     *   我们自己正在监听的那个口，就一律清掉。** 因为：
     *     · 我们自己的代理只在 8899 且 LocalProxy.listening() 时才算"合法"；
     *     · 其余指向本机的代理必然是外部工具（PC 抓包隧道之类）留下的，
     *       对这种残留，**清掉永远是安全的方向**（最坏情况也只是让抓包断掉，
     *       而断掉的抓包远好过整机断网）。
     */
    private void healStaleProxy() {
        String now = readProxy();
        if (now == null || now.trim().length() == 0 || ":0".equals(now.trim())) return;

        boolean isLoopback = now.contains("127.0.0.1") || now.contains("localhost");
        if (!isLoopback) return;                      // 局域网/外部代理不是我们的事

        boolean isOurs = now.contains(String.valueOf(LocalProxy.PORT));
        if (isOurs && LocalProxy.listening()) return;  // 我们自己的，且活着 —— 不动

        if (!hasSecureSettings()) {
            log("⚠ 系统代理还指着 [" + now + "]，但那个代理已经不可用了 —— 手机会上不了网。");
            log("   请手动关掉：设置 → WLAN → 当前网络 → 代理 → 无");
            setStatus("⚠ 请手动把 WLAN 代理改回「无」");
            return;
        }
        if (!isOurs) {
            android.provider.Settings.Global.putString(getContentResolver(), "http_proxy", ":0");
            log("[代理] ★ 发现外部工具留下的本机代理 [" + now + "] —— 已清除。");
            log("        （这种残留会让手机在电脑不在线时整机断网，所以一律清掉）");
            log("        现在 = [" + readProxy() + "]");
            return;
        }
        setProxy(false);
        log("[代理] 已清除上次留下的残留代理 -> [" + readProxy() + "]");
    }

    // ==================================================================
    //  证书
    // ==================================================================
    private byte[] readAsset(String name) {
        try {
            java.io.InputStream is = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return bo.toByteArray();
        } catch (Exception e) { return null; }
    }

    private static byte[] sha1(byte[] b) {
        try { return java.security.MessageDigest.getInstance("SHA-1").digest(b); }
        catch (Exception e) { return new byte[0]; }
    }

    private boolean isCaInstalled() {
        byte[] der = readAsset("ledao-ca.der");
        if (der == null) return false;
        byte[] want = sha1(der);
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            java.util.Enumeration<String> as = ks.aliases();
            while (as.hasMoreElements()) {
                String a = as.nextElement();
                try {
                    java.security.cert.Certificate c = ks.getCertificate(a);
                    if (c != null && java.util.Arrays.equals(sha1(c.getEncoded()), want)) return true;
                } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }
        return false;
    }

    private void installCa() {
        byte[] der = readAsset("ledao-ca.der");
        if (der == null) { log("!! 内置证书读取失败"); return; }
        if (isCaInstalled()) {
            log("✅ 证书已经装过了，不用重复安装");
            setStatus("✅ 证书已安装");
            return;
        }
        try {
            android.content.Intent it = android.security.KeyChain.createInstallIntent();
            it.putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, der);
            it.putExtra(android.security.KeyChain.EXTRA_NAME, "Ledao Tester Root CA");
            startActivity(it);
            log("★ 已弹出系统「安装证书」界面 —— 直接点「确定」即可");
            log("   若问类型选「CA 证书」；可能要求输入锁屏密码");
        } catch (Throwable e) {
            log("!! 弹出安装界面失败：" + e);
            log("   手动路径：设置 → 安全 → 加密与凭据 → 安装证书 → CA 证书");
        }
    }

    /** 「设置代理」按钮：有 VPN 就直接劝退（不需要），否则给精确的手动路径 */
    private void proxyHelp() {
        log("");
        if (ProxyVpn.supported()) {
            log("=== 关于代理 ===");
            log("✅ 你的手机是 Android 10+，抓包走 VPN，**不需要**设置任何代理。");
            log("   点「📲 自动提取」→ 允许 VPN → 剩下的自动完成。");
            log("   系统 WLAN 代理栏位保持「无」，步道乐跑也不会察觉。");
            log("");
        }
        log("=== 手动设置系统代理（仅 Android 10 以下需要）===");
        log("目标：让手机 HTTP 代理指向 127.0.0.1:" + LocalProxy.PORT);
        if (hasSecureSettings()) {
            saveOldProxy(readProxy());
            if (setProxy(true)) {
                sysProxyOwned = true;
                log("★ 已自动设置成功（用完会自动恢复）");
                setStatus("✅ 系统代理已设为 127.0.0.1:" + LocalProxy.PORT);
                return;
            }
            log("!! 自动设置失败，请按下面的手动步骤");
        } else {
            log("本应用没有 WRITE_SECURE_SETTINGS 权限，无法自动改代理（这是系统的限制）。");
        }
        log("");
        log("手动设置（不用电脑，任何手机都能做）：");
        log("  设置 → WLAN → 点当前网络 → 代理 → 手动");
        log("  主机名：127.0.0.1");
        log("  端口：  " + LocalProxy.PORT);
        log("  保存即可。用完记得改回「无」。");
        setStatus("请按日志里的步骤手动设置代理");
    }

    // ==================================================================
    //  复制日志
    // ==================================================================
    /**
     * ★ 界面优化 #2（2026-10-07）：复制日志 = **全部原始数据**，只把「个人用户标识」打掉。
     *
     * 和旧实现的区别（旧的是按形状打码，会把协议值也误伤）：
     *   旧：`\b[0-9a-f]{32}\b` → 32 位 hex 一律打码。
     *       结果 32 位 hex 的**签名/令牌**（sign=…、lpi1=…、record_str=…、data.str=…）
     *       全被打成 `····`，而签名恰恰是排查「服务端为什么拒」最关键的东西。
     *   新：按**键名**打码 —— 只有确实是个人标识的字段才脱敏：
     *       uid / student_num / mobileDeviceId / newMobileDeviceId /
     *       token / access_token / refresh_token / umid / PHPSESSID / Cookie
     *   其余一律**逐字节原样**保留：sign、nonce、timestamp、lpi1、record_str、
     *   log_data、坐标、里程、配速、服务端响应…… 一个字符都不动。
     */
    static String maskSensitive(String s) {
        if (s == null) return "";
        // ① uid / 学号 —— 数字型标识
        s = s.replaceAll("(?i)\\buid=([0-9]{4,})", "uid=【已隐藏】");
        s = s.replaceAll("(?i)\\bstudent_num=([0-9]{4,})", "student_num=【已隐藏】");
        // ② 设备号（GUID）
        s = s.replaceAll("(?i)\\b(mobileDeviceId|newMobileDeviceId)=([0-9a-f\\-]{8,})",
                "$1=【已隐藏】");
        // ③ 登录令牌 / 续期令牌 —— 保留前 4 位便于对账，其余打掉
        s = s.replaceAll("(?i)\\b(access_token|refresh_token|token)=([0-9A-Za-z]{8,})",
                "$1=【已隐藏】");
        // ④ 风控 umid（lpi0 明文前 34 字符）
        s = s.replaceAll("(?i)\\bumid\\s*=\\s*([0-9a-zA-Z]{16,})", "umid=【已隐藏】");
        // ⑤ 抓包 cookie 里的会话号
        s = s.replaceAll("(?i)(PHPSESSID=)[0-9a-zA-Z]{6,}", "$1【已隐藏】");
        s = s.replaceAll("(?i)(acw_tc=)[^;\\s]+", "$1【已隐藏】");
        s = s.replaceAll("(?i)(aliyungf_tc=)[^;\\s]+", "$1【已隐藏】");
        return s;
    }

    private void copyLog() {
        try {
            String txt = logBuf.toString();
            if (txt.length() == 0) {
                Toast.makeText(this, "日志是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            String body = maskSensitive(txt);
            String head = "【宇达步道乐跑助手 " + getString(R.string.app_version)
                    + " 完整日志 · 仅隐藏个人标识，其余原始数据均在】 "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\n\n";
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("乐跑日志", head + body));
            Toast.makeText(this, "已复制完整日志（" + body.length()
                    + " 字符，只隐藏了 uid/学号/设备号/令牌）", Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, "复制失败：" + e, Toast.LENGTH_LONG).show();
            log("[复制日志] 失败：" + describeError(e));
        }
    }

    // ==================================================================
    //  杂项
    // ==================================================================
    private String readDeviceId() {
        byte[] b = Identity.suCat(Identity.FILE_DEVID);
        if (b == null) return "";
        String s = new String(b).trim();
        if (s.length() == 0) return "";
        String first = s.split("\\s+")[0].trim();
        return first.length() == 36 ? first : "";
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11.5f);
        t.setTextColor(Color.parseColor("#37474F"));
        t.setPadding(dp(4), dp(4), dp(3), dp(4));
        return t;
    }

    private EditText numBox(String def, int widthDp) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setTextSize(12f);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setWidth(dp(widthDp));
        e.setGravity(Gravity.CENTER);
        e.setBackground(round("#F1F6F3", "#C9DCD1", 6));
        e.setPadding(dp(4), dp(4), dp(4), dp(4));
        return e;
    }

    private Button mkBtn(String s, String color, float size) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(size);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setPadding(dp(2), dp(6), dp(2), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        paintBtn(b, color);        // ★ 记下原色 + 按当前透明度上色
        tinted.add(b);
        return b;
    }

    /**
     * 给按钮上色，并把**原色**记在 tag 上 —— {@link #applyTheme} 靠它重刷，
     * 所以任何时候改按钮颜色都要走这里，别直接 setBackgroundColor。
     */
    private void paintBtn(Button b, String color) {
        if (b == null || color == null) return;
        b.setTag(color);
        b.setBackgroundColor(AppPrefs.alpha(Color.parseColor(color), btnAlpha));
    }

    /** ★ 第 5 条：按当前的 btnAlpha 把所有按钮重刷一遍（换主题 / 换背景照片时调） */
    private void repaintButtons() {
        for (Button b : tinted) {
            Object t = b.getTag();
            if (t instanceof String) {
                try {
                    b.setBackgroundColor(
                            AppPrefs.alpha(Color.parseColor((String) t), btnAlpha));
                } catch (Throwable ignore) { }
            }
        }
        // 开始/停止按钮的颜色跟着「在不在跑」变，单独补一次
        paintBtn(btnRun, RUN_ACTIVE ? "#B00020" : "#1B7A4B");
    }

    private GradientDrawable round(String fill, String stroke, int radiusDp) {
        return round(Color.parseColor(fill), stroke, radiusDp);
    }

    private GradientDrawable round(int fill, String stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != null) g.setStroke(dp(1), Color.parseColor(stroke));
        return g;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String shortTok(String t) {
        if (t == null) return "(无)";
        return t.length() <= 6 ? t : t.substring(0, 6) + "…";
    }

    // ==================================================================
    //  ★ 界面优化 #7（2026-10-07）：日志排错增强
    //
    //  以前写的是 `log("!! " + e)` —— 打出来只有异常类名，不知道在哪一步炸的。
    //  现在统一走这两个方法：位置（类名.方法:行号）+ 中文原因 + 原始异常。
    // ==================================================================
    /** 打一条错误日志：`!! [位置] 中文原因（原始异常）` */
    private void logFail(String what, Throwable e) {
        log("      !! " + what + " 失败");
        log("         ├ 位置：" + or(Err.where(e), "(不在本工程代码里)"));
        log("         ├ 原因：" + Err.hint(e));
        log("         └ 原始：" + e);
    }

    /** 给不在 Activity 里的地方用：一行式错误描述 */
    static String describeError(Throwable e) {
        return Err.one(e);
    }

    /** 打一条「步骤通过/失败」的勾叉行，让日志自己会判卷 */
    private void logStep(String tag, boolean ok, String what) {
        log("   " + (ok ? "✔" : "✘") + " " + tag + " " + what);
    }

    private static double parse(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private void setStatus(String s) {
        ui.post(() -> {
            if (tvStatus != null) tvStatus.setText(s);
        });
    }

    // ==================================================================
    //  日志 + 路线回调
    // ==================================================================
    @Override
    public void log(String s) {
        logLine(s, true);
    }

    /**
     * ★ v1.0.25（docs/59）：日志现在有**三个去处**。
     *
     * <ol>
     *   <li>logcat（tag {@code LedaoTester}）—— 一直都有；</li>
     *   <li>{@code files/run.log} —— 跑步期间的每一行都同步落盘并 flush，
     *       这样"进程被系统清掉"之后还能把日志捞回来（{@link RunLog}）；</li>
     *   <li>界面上的日志框 —— 界面还在才贴；被销毁了就跳过（跑步不受影响）。</li>
     * </ol>
     *
     * @param persist 从磁盘捞回来的旧日志不要再写回磁盘（{@code false}），否则会越滚越多
     */
    private void logLine(String s, boolean persist) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        android.util.Log.i("LedaoTester", s);
        if (persist && RUN_ACTIVE) RunLog.append(line);
        // ★ 打到「当前活着的 Activity」上。Activity 可能被重建，
        //   旧跑步线程如果往已销毁的实例上打日志，用户就"看不见"了。
        final MainActivity dst = (LIVE != null) ? LIVE : this;
        if (dst.dead) return;              // 界面没了：日志留在 logcat 与 run.log 里
        dst.ui.post(() -> {
            if (dst.tvLog == null) return;
            dst.logBuf.append(line).append('\n');
            if (dst.logBuf.length() > 200000) dst.logBuf.delete(0, 100000);
            dst.tvLog.setText(dst.logBuf);
            if (System.currentTimeMillis() > dst.logHoldUntil && dst.scLog != null) {
                dst.scLog.post(() -> dst.scLog.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /**
     * ★ 静态信息：打卡点 / 本次路线。**不动位置**。
     *
     * ★★★ 2026-10-08 修「点开始跑步位置就瞬移」：以前这两样东西走的是 onRoute，
     *   而 onRoute 会把 (lat,lon) 追加进实跑轨迹 —— 于是每局开跑，轨迹的前三个点
     *   分别是「预选打卡点 A / 服务端指派打卡点 A / 路线起点」，三个点相距几百米，
     *   画出来就是两条笔直的长线。现在元数据走 onPlan，轨迹只由真 GPS 点构成。
     */
    @Override
    public void onPlan(java.util.List<double[]> all, java.util.List<double[]> route) {
        final List<double[]> cpList = new ArrayList<>();
        if (all != null) for (double[] p : all) cpList.add(new double[]{p[0], p[1]});
        final List<double[]> pathList = new ArrayList<>();
        if (route != null) for (double[] p : route) pathList.add(new double[]{p[0], p[1]});
        ui.post(() -> {
            if (map == null) return;
            if (!cpList.isEmpty()) map.setCheckpoints(cpList);
            if (!pathList.isEmpty()) map.setRoute(pathList);
            refreshRouteHead();
        });
    }

    @Override
    public void onRoute(java.util.List<double[]> all, java.util.List<double[]> route,
                        double lat, double lon, int hit, double speed) {
        final List<double[]> cpList = new ArrayList<>();
        if (all != null) for (double[] p : all) cpList.add(new double[]{p[0], p[1]});
        final List<double[]> pathList = new ArrayList<>();
        if (route != null) for (double[] p : route) pathList.add(new double[]{p[0], p[1]});
        ui.post(() -> {
            if (hit > routeKpHit) routeKpHit = hit;
            if (map != null) {
                if (!cpList.isEmpty()) map.setCheckpoints(cpList);
                if (!pathList.isEmpty()) map.setRoute(pathList);
                map.setProgress(lat, lon, speed);   // ★ 只有真 GPS 点走这里（带速度 → 轨迹上色）
                // ★ 界面优化 #4（2026-10-07）：这里以前有一句
                //     if (!routeOpen) { routeOpen = true; applyRouteOpen(); }
                //   —— 每写一条跑步日志就把用户刚收起的「跑步路线」卡片重新弹开。
                //   用户明确说「收起地图后每当输出跑步日志地图就又会显示出来」。
                //   收/放完全由用户点标题栏决定，程序再也不动它。
            }
            refreshRouteHead();
        });
    }

    /** ★ v1.0.25：上一次刷后台通知的时间（节流用，见 {@link #onTick}） */
    private volatile long notiAt = 0;

    /**
     * ★★★ v1.0.25（docs/59）：每一拍把进度写进那条常驻通知。
     *
     * <p>为什么需要：跑步改成"可以息屏、可以切走"之后，那条通知就是用户唯一的
     * 仪表盘。原来它只有一句「抓包进行中」，跑了多远、多久、打了几次卡都看不见 ——
     * 揣兜里十几分钟，人根本没法判断"到底还在不在跑"。
     *
     * <p>节流到 20 秒一次：一场跑步一百来拍，每拍都刷会让通知栏一直抖，也费电。
     * 数值口径与日志里那行心跳**完全一致**（{@code validM} 是上报里程，刷脸缺口不算）。
     */
    @Override
    public void onTick(double validM, double totalM, long secs, int hit) {
        long now = System.currentTimeMillis();
        if (now - notiAt < 20000) return;
        notiAt = now;
        String txt = String.format(Locale.US,
                "已跑 %.2f km（路线 %.2f km）· %d 分 %02d 秒 · 已打卡 %d 次",
                validM / 1000.0, totalM / 1000.0, secs / 60, secs % 60, hit);
        SniffGuard.setText(this, txt);
        // 顺手也写进界面顶上那条状态栏：用户切回来第一眼就是它
        setStatus("🏃 " + txt + "（后台运行中，别从最近任务里划掉）");
    }

    /** 把标题栏文案收敛到一处 —— 打卡点数、进度都从当前状态取，避免互相覆盖 */
    private void refreshRouteHead() {        if (tvRouteHead == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(routeOpen ? "▼ " : "▶ ")
          .append("跑步路线（点击").append(routeOpen ? "收起" : "展开").append("）");
        if (routeKpHit > 0) sb.append("   已打卡 ").append(routeKpHit);
        if (routePair != null) sb.append("   打卡点 ").append(routePair);
        tvRouteHead.setText(sb.toString());
    }

    /** 本次必须经过的 2 个打卡点 —— 推到地图上着重标注 */
    @Override
    public void onKeyPoints(java.util.List<double[]> keys, java.util.List<String> ids) {
        final List<double[]> kl = new ArrayList<>();
        if (keys != null) for (double[] p : keys) kl.add(new double[]{p[0], p[1]});
        final List<String> il = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
        ui.post(() -> {
            if (map != null) map.setKeyPoints(kl, il);
            if (il.size() >= 2) {
                routePair = il.get(0) + " → " + il.get(1);
                // ★ 服务端指派下来了：白框改说真实的两个点 id（地图上的红 1 / 蓝 2 就是它们），
                //   里程那一项不变 —— 它一直等于绿框里的目标里程。
                planIds = new String[]{il.get(0), il.get(1)};
                refreshPlanInfo();
            }
            refreshRouteHead();
        });
    }

    // ==================================================================
    //  ★★★ 2026-10-10 用户第 1 条 / 第 2 条：人脸结论 + 途中提示
    // ==================================================================

    /**
     * 起跑前人脸核验的结论。
     *
     * <p>通过 → 渐显一张小卡片「人脸识别通过」，停 1.6 秒自己淡出（用户要的
     * 「渐进窗口 + 停留后消失」）；没通过 → 三段式弹窗（原因 + 下一步），
     * 而 {@link Ledao} 那边已经**当场终止**了本场跑步（用户明确要求）。
     *
     * <p>这个回调跑在跑步线程上，所以先 post 到主线程。
     */
    @Override
    public void onFacePre(final boolean ok, final int status, final String info) {
        final MainActivity dst = (LIVE != null) ? LIVE : this;
        dst.ui.post(() -> {
            if (ok) {
                Alerts.flash(dst.rootFrame, "✅ 人脸识别通过\n正在开始跑步…", false);
                return;
            }
            Alerts.error(dst, "人脸核验没通过，本次跑步已停止",
                    Err.faceReason(status, info), Err.faceAdvice(status, info), dst::copyLog);
        });
    }

    /** 途中一闪而过的提示（中途人脸核验失败）—— 不拦操作，跑步照跑 */
    @Override
    public void onFlash(final String text, final boolean warn) {
        final MainActivity dst = (LIVE != null) ? LIVE : this;
        dst.ui.post(() -> Alerts.flash(dst.rootFrame, text, warn));
    }

    /**
     * ★★★ 调试 / 自检入口（2026-10-10 新加的东西都得亲眼看过一遍）。
     *
     * <p>为什么要留这么一个口子：这一版新增的三条路 —— **出错弹窗 / 后台通知 /
     * 渐显小卡片** —— 都只在"真出错"时才出现，而真跑一次要二十分钟、还得先有
     * 一份活着的会话。没有这个口子，就等于"改完只能靠想象"（这个项目已经在这上面
     * 栽过好几次：改了 N 版，装机一看根本不是想的那样）。
     *
     * <p>用法（不碰屏幕、不需要会话）：
     * <pre>
     *   adb shell am start -n com.ledao.tester/.MainActivity --es selftest dialog
     *   adb shell am start -n com.ledao.tester/.MainActivity --es selftest flash
     *   adb shell am start -n com.ledao.tester/.MainActivity --es selftest notify
     *   adb shell am start -n com.ledao.tester/.MainActivity --es selftest devnote
     *   adb shell am start -n com.ledao.tester/.MainActivity --es selftest face
     *   adb shell am start -n com.ledao.tester/.MainActivity --es selftest background
     *   adb shell am start -n com.ledao.tester/.MainActivity --es selftest pay
     * </pre>
     *
     * <ul>
     *   <li>{@code dialog} —— 弹出真实的出错弹窗（内容就是 status=59 那一套）。</li>
     *   <li>{@code flash}  —— 弹出「人脸识别通过」那张渐显小卡片，1.6 秒后自己消失。</li>
     *   <li>{@code notify} —— 走**后台那条路**：发一条横幅通知，并落盘；
     *       3 秒后再走一遍"用户点通知回来"的补弹，证明这条往返是通的。
     *       两样都不留残留（pending 会被 flush 消费掉）。</li>
     *   <li>{@code devnote} —— 强制弹一次开发者留言（勾了「不再弹出」也照弹），
     *       看那四颗复制小按键排得对不对。</li>
     *   <li>{@code face} —— 只读 files/face.jpg，把跑前那两道体检（JPEG 段落结构 +
     *       本机解码器）单独跑一遍。**不联网、不跑步、不碰用户数据**。
     *       2026-10-10 加：那天用户的照片被第一道关误拦（EOI 后面挂了 24 字节尾巴），
     *       而这种错要等"真跑一次"才暴露 —— 有这个自检就不用赌。</li>
     *   <li>{@code bg} / {@code bgclear} —— 第 5 条（背景半透明）只能看图。
     *       {@code bg} 会**当场画一张黑白棋盘图**存成 files/bgtest.png 并铺成背景，
     *       透过去多少一眼就能看出来；{@code bgclear} 恢复纯色主题。
     *       用棋盘而不是随便找张照片，是为了不把用户自己的照片扯进来。</li>
     *   <li>{@code background} —— ★ v1.0.25（docs/59）后台运行那四件事：
     *       ① 电池优化白名单 ② PARTIAL_WAKE_LOCK ③ 前台服务 ④ 日志落盘。
     *       唤醒锁故意持 10 秒，好让人在电脑上用 {@code dumpsys power} 抓到它；
     *       同样**不联网、不跑步、不碰用户数据**。
     *       ⚠ v1.0.28：③ 是**轮询最多 3 秒**才报的 —— {@code startService} 是异步的，
     *       紧接着查必然 false，2026-10-10 在 MI 8 上就是这么误报了一次。</li>
     *   <li>{@code pay} —— ★ v1.0.28 侧边栏那块收款码：那句话 + 图片能不能从
     *       assets 解出来 + 整块建不建得起来。**不联网、不弹窗、不碰用户数据**；
     *       加它是因为侧边栏要手点 ☰ 才看得到，而手机上不给发点击事件。</li>
     *   <li>{@code bgwizard} —— ★ v1.0.30 后台运行向导那一屏（docs/62）：
     *       把状态四行打进日志，同时把向导弹出来看排版。</li>
     *   <li>{@code bgrom} —— ★ v1.0.30：走一遍「打开这台机器自己的自启动页」，
     *       把最终落到哪个组件写进日志（换机型时用这个确认那串组件名还在不在）。</li>
     *   <li>{@code bgprobe} —— ★ v1.0.30：开一次后台体检（90 秒）。
     *       配合电脑上 {@code am start ... HOME} 把助手切到后台、90 秒后再切回来，
     *       就能在真机上量出"这台机器到底会不会把助手冻住"。
     *       **不联网、不跑步、不碰账号**。</li>
     * </ul>
     */
    private void runSelfTest(String kind) {
        if (isFinishing()) return;
        if ("background".equals(kind)) {
            /* ★★★ v1.0.25（docs/59）：后台运行这条路单独体检 —— 不联网、不跑步、
             *   不碰用户数据。四件事：白名单 / 唤醒锁 / 日志落盘 / 前台服务通知。
             *   唤醒锁故意持 10 秒，好让人在电脑上 `dumpsys power` 抓到它。 */
            log("[自检] 后台运行自检开始（10 秒后自动释放唤醒锁）");
            log("[自检] ①电池优化白名单：" + (ignoringBatteryOpt()
                    ? "已在 ✅（MIUI 的「省电策略」还要单独设成无限制）"
                    : "不在 ❌ —— 点首页「🔋 后台运行设置」申请"));
            try {
                if (!SniffGuard.alive) SniffGuard.start(this);
                SniffGuard.runMode = true;
                SniffGuard.holdAwake(this, 60L * 1000);
                log("[自检] ②唤醒锁 PARTIAL_WAKE_LOCK："
                        + (SniffGuard.awake() ? "已持有 ✅（tag=ledao:run）" : "拿不到 ❌"));
                /* ★★★ v1.0.28：③**不能接着就报** —— 2026-10-10 在 MI 8 上真踩到：
                 *   `SniffGuard.start()` 是异步的（startService 之后 onCreate 才跑），
                 *   紧接着查 `alive` 必然是 false → 日志里写「前台服务：没起来 ❌」，
                 *   可 4 秒后 `dumpsys activity services` 明明显示 isForeground=true。
                 *   一个会撒谎的体检比没有体检更坏（用户会去查一个不存在的问题），
                 *   所以改成**轮询最多 3 秒**，并把它等了多久一起报出来。 */
                SniffGuard.setText(this, "自检：后台运行这一路通了（10 秒）");
                final long svcT0 = System.currentTimeMillis();
                new Thread(() -> {
                    try {
                        while (!SniffGuard.alive
                                && System.currentTimeMillis() - svcT0 < 3000) {
                            Thread.sleep(200);
                        }
                        long ms = System.currentTimeMillis() - svcT0;
                        log("[自检] ③前台服务：" + (SniffGuard.alive
                                ? "在跑 ✅（等了 " + ms + " ms 才起来，属正常）"
                                : "3 秒了还没起来 ❌（常驻通知 id=" + SniffGuard.NOTI_ID
                                        + "；多半是被系统的后台限制拦了）"));
                        RunLog.start(getFilesDir(), "[自检] background " + new java.util.Date());
                        RunLog.append("[自检] 这一行应该能在 files/run.log 里看到");
                        log("[自检] ④运行日志落盘：" + (RunLog.active() ? "可写 ✅" : "写不了 ❌")
                                + "  " + new File(getFilesDir(), RunLog.NAME).getAbsolutePath());
                    } catch (Throwable t) {
                        log("[自检] ③④这两步出错：" + Err.one(t));
                    }
                }, "selftest-bg").start();
            } catch (Throwable t) {
                log("[自检] 后台这四件事里出错：" + Err.one(t));
            }
            ui.postDelayed(() -> {
                SniffGuard.dropAwake();
                RunLog.stop();
                SniffGuard.runMode = false;
                SniffGuard.setText(MainActivity.this, null);
                log("[自检] 释放唤醒锁：" + (SniffGuard.awake() ? "还持有 ❌" : "已释放 ✅"));
                log("[自检] 结论：后台运行这条路由 ①白名单 ②唤醒锁 ③前台服务 ④日志落盘 四段组成 ——"
                        + "上面四行全 ✅ 才算通。跑步时它们会在开跑那一瞬间自动挂上，收尾自动放开。");
            }, 10000);
            return;
        }
        if ("bgwizard".equals(kind)) {
            /* ★ v1.0.30（docs/62）：后台运行向导 —— 状态四行 + 四个按钮。
             *   状态先落进日志（手机上不给点，日志是唯一能带出来的东西），
             *   弹窗只为看排版。不联网、不跑步、不碰账号。 */
            log("[自检] 后台运行向导 · 状态");
            for (String ln : bgStateText().split("\n")) log("   " + ln);
            askBackgroundPermission();
            return;
        }
        if ("bgrom".equals(kind)) {
            // ★ v1.0.30：确认这台机器上「自启动页」那串组件名还认不认
            log("[自检] 尝试打开本机自己的「自启动 / 后台运行」页…");
            String p = openRomBackgroundPage();
            log("[自检] 结果：" + (p == null ? "一个都没打开 ❌" : "打开了 → " + p));
            return;
        }
        if ("bgprobe".equals(kind)) {
            /* ★ v1.0.30：后台体检（90 秒）。用法（电脑上）：
             *   am start -n com.ledao.tester/.MainActivity --es selftest bgprobe
             *   am start -a android.intent.action.MAIN -c android.intent.category.HOME
             *   （等 90 秒）
             *   am start -n com.ledao.tester/.MainActivity
             *   然后看日志里那行「[体检] …」 */
            log("[自检] 起一次后台体检（" + BG_PROBE_SEC + " 秒）——"
                    + "请把助手切到后台 / 息屏，到点回来");
            startBgProbe();
            return;
        }
        if ("pay".equals(kind)) {
            /* ★★★ v1.0.28：侧边栏那块收款码的体检 —— 不联网、不弹窗、不碰用户数据，
             *   只从 assets 里解一次图。加它的原因很实在：这块只在**侧边栏**里出现，
             *   而侧边栏要靠手点一下 ☰ 才打得开；手机上不让发点击事件（那台机在用），
             *   于是"图到底能不能解出来"就没人能替用户确认。这里绕过界面直接验。 */
            log("[自检] 收款码（侧边栏「💬 开发者留言」最下面那一块）");
            try {
                log("[自检] ①那句话：" + DevNote.TIP_TEXT);
                android.graphics.Bitmap bm;
                try (java.io.InputStream in = getAssets().open(DevNote.TIP_QR)) {
                    bm = android.graphics.BitmapFactory.decodeStream(in);
                }
                log("[自检] ②图片 assets/" + DevNote.TIP_QR + "："
                        + (bm == null ? "解不出来 ❌（装机包里那张图坏了）"
                                : "解码成功 ✅ " + bm.getWidth() + "×" + bm.getHeight()));
                android.view.View blk = DevNote.tipBlock(this);
                int kids = (blk instanceof android.view.ViewGroup)
                        ? ((android.view.ViewGroup) blk).getChildCount() : -1;
                log("[自检] ③那一块建起来了：" + kids + " 个子视图（文案 + 图 + 一行小字）"
                        + "，点左上角 ☰ 拉到最下面就能看到");
            } catch (Throwable t) {
                log("[自检] 收款码这块出错：" + Err.one(t));
            }
            return;
        }
        if ("devnote".equals(kind)) {
            // 留言弹窗平时勾了「不再弹出」就看不到了，自检里强制看一次
            DevNote.show(this, null);
            log("[自检] 开发者留言弹窗（看那四个复制小按键）");
            return;
        }
        if ("face".equals(kind)) {
            /* ★ 2026-10-10 新加：跑前人脸体检一共有两道关（JPEG 结构 + 本机解码器），
             *   而它只在"真的开跑"时才走 —— 上次被误拦就是因为这两道关里第一道写错了，
             *   却要等用户真跑一次才发现。这个自检把两道关单独跑一遍：不联网、不跑步、
             *   不碰用户任何数据，只读 files/face.jpg。 */
            File f = faceFile();
            if (!f.exists()) {
                log("[自检] 还没设置人脸照片（" + f.getAbsolutePath() + " 不存在）");
                return;
            }
            try {
                byte[] jpg = new byte[(int) f.length()];
                java.io.DataInputStream in = new java.io.DataInputStream(
                        new java.io.FileInputStream(f));
                in.readFully(jpg);
                in.close();

                String bad = Ledao.jpegDiag(jpg);
                log(String.format(Locale.US,
                        "[自检] 人脸体检①结构：%s（%d 字节，EOI 在 %d，尾巴 %d 字节）",
                        bad.length() == 0 ? "通过 ✅" : "没过 ❌ " + bad,
                        jpg.length, Ledao.jpegEoi(jpg), Ledao.jpegTailBytes(jpg)));

                android.graphics.BitmapFactory.Options bo =
                        new android.graphics.BitmapFactory.Options();
                bo.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length, bo);
                boolean ok2 = bo.outWidth > 0 && bo.outHeight > 0;
                log(String.format(Locale.US,
                        "[自检] 人脸体检②本机解码器：%s（%dx%d）",
                        ok2 ? "通过 ✅" : "没过 ❌ 报不出宽高", bo.outWidth, bo.outHeight));

                /* ★ v1.0.24（docs/58）加的两行：跑前那次核验现在还会**先修一次**、
                 *   修不动就用上次通过核验的那张 —— 所以自检也得把这三件事一起报出来，
                 *   否则"照片有问题"和"这一场能不能跑"还是两笔账。 */
                Ledao.FaceFix fx = Ledao.faceJpegFix(jpg);
                log(fx == null
                        ? "[自检] 人脸体检③本地小修：不需要（结构上没毛病）"
                        : ("[自检] 人脸体检③本地小修：能修 ✅ —— " + fx.note
                           + "（" + jpg.length + " → " + fx.bytes.length + " 字节"
                           + (fx.lossy ? "，有损" : "，无损") + "）"));
                byte[] okb = null;
                try {
                    File okf = new File(getFilesDir(), Ledao.FACE_OK_NAME);
                    if (okf.exists() && okf.length() > 0) okb = readFileBytes(okf);
                } catch (Throwable ignore) { }
                log(okb == null
                        ? "[自检] 人脸体检④备用照片：还没有（得先有一场核验通过才会有）"
                        : String.format(Locale.US,
                           "[自检] 人脸体检④备用照片：有（%d 字节，结构%s）",
                           okb.length, Ledao.jpegDiag(okb).length() == 0 ? "完整 ✅" : "也不完整 ❌"));

                boolean willSend = bad.length() == 0 || fx != null || okb != null;
                log(bad.length() == 0 && ok2
                        ? "[自检] 结论：这张照片跑前那道关会放行 —— 可以正常开跑"
                        : (willSend
                           ? "[自检] 结论：这张照片本身有毛病，但**本地能修**（或能拿备用那张顶上）——"
                             + "跑前那次核验会照发，让服务端给结论"
                           : "[自检] 结论：跑前会被拦下（和真跑时的判断一致）"));
            } catch (Throwable t) {
                log("[自检] 读人脸照片失败：" + Err.one(t));
            }
            return;
        }
        if ("bg".equals(kind) || "bgclear".equals(kind)) {
            String path = AppPrefs.bgImage(this);
            if ("bgclear".equals(kind)) {
                AppPrefs.sp(this).edit().putString(AppPrefs.K_BGIMG, "").apply();
                applyTheme();
                log("[自检] 已清除背景图，恢复纯色主题（当前 th_bgimg=\""
                        + AppPrefs.bgImage(this) + "\"）");
                return;
            }
            try {
                File f = new File(getFilesDir(), "bgtest.png");
                int w = 180, h = 360;               // 够铺满，又小得不用等
                Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // 48px 见方的黑白橙棋盘 —— 半透明挡掉了多少，一眼就能量出来
                        bm.setPixel(x, y, ((x / 24 + y / 24) % 2 == 0) ? 0xFF202020 : 0xFFFFCC00);
                    }
                }
                FileOutputStream fo = new FileOutputStream(f);
                bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();
                bm.recycle();
                AppPrefs.sp(this).edit().putString(AppPrefs.K_BGIMG, f.getAbsolutePath()).apply();
                applyTheme();
                log("[自检] 已铺上测试背景图 " + f.getAbsolutePath()
                        + "（" + w + "×" + h + " 棋盘）—— 按钮和卡片现在应该是半透明的");
                log("[自检] 原来的背景图是：" + (path.length() > 0 ? path : "(无)"));
            } catch (Throwable t) {
                log("[自检] 铺背景图失败：" + Err.one(t));
            }
            return;
        }
        if ("flash".equals(kind)) {
            Alerts.flash(rootFrame, "✅ 人脸识别通过\n正在开始跑步…", false);
            log("[自检] 渐显小卡片已弹出（停留 1.6 秒后自动淡出）");
            return;
        }
        if ("notify".equals(kind)) {
            String[] a = Err.advise("未成单: {\"status\":100,\"info\":\"上传数据失败\"}");
            Alerts.setForeground(false);                 // 假装助手此刻在后台
            log("[自检] isForeground=" + Alerts.isForeground() + "（必须是 false 才会发通知）");
            Alerts.error(this, a[0], a[1], a[2], this::copyLog);
            Alerts.setForeground(true);
            log("[自检] 已按「后台」那条路发出通知：" + a[0]);
            log("[自检] 3 秒后再模拟一次「用户点通知回来」的补弹 …");
            ui.postDelayed(() -> Alerts.flushPending(this, this::copyLog), 3000);
            return;
        }
        String[] a = Err.advise("未成单: {\"status\":59,\"info\":\"人脸照片验证不合格\"}");
        Alerts.error(this, a[0], a[1], a[2], this::copyLog);
        log("[自检] 已弹出「出错」三段式弹窗：" + a[0]);
    }

    /**
     * ★★★ 出错了的统一出口 —— 前台弹窗，后台发消息式通知（{@link Alerts}）。
     *
     * <p>三段式是用户明确要求的：**标题 / 发生了什么 / 下一步该做什么**。
     * 文案由 {@link Err#advise} 按服务端原话生成，认不出来也一定给下一步。
     */
    private void reportError(String title, String reason, String advice) {
        final MainActivity dst = (LIVE != null) ? LIVE : this;
        dst.ui.post(() -> {
            try { Alerts.error(dst, title, reason, advice, dst::copyLog); }
            catch (Throwable ignore) { }
        });
    }

    // ==================================================================
    //  没拿到真打卡点之前的兜底数据 —— 内置的 14 个点
    // ==================================================================
    private List<double[]> defaultCps() {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < RouteView.CAMPUS.length; i++)
            l.add(new double[]{RouteView.CAMPUS[i][0], RouteView.CAMPUS[i][1]});
        return l;
    }

    // ==================================================================
    //  侧边栏文案
    // ==================================================================
    /* ★★ 2026-10-10（v1.0.29）：这份文案是用户能看到的唯一"说明书"，三处必须改：
     *   ① 去掉 markdown 星号 —— 侧边栏是普通 TextView，星号会被原样画出来
     *      （用户看到的是"两个星号夹一段字"，不是加粗）。这条规则以前只管弹窗，漏了这里。
     *   ② 补上 v1.0.25 之后的行为：切走 / 息屏 / 划掉最近任务都不会停跑，
     *      要停必须回来点「■ 停止跑步」。不写清楚，用户划掉以后会以为停不掉。
     *   ③ 把"加白名单"这个建议写进正文（首页那行按钮默认只是一句灰提示，
     *      很多用户根本不知道要点它）。
     *   ★ 文案只此一份；仓库里的《使用说明.md》与它同源，改一处要一起改。 */
    private static final String HELP_TEXT =
            "【三步走】\n"
          + "1. 装证书（只需一次）\n"
          + "   侧边栏 → 🔐 安装抓包证书 → 系统弹窗点「确定」。\n"
          + "   装好后主界面状态栏会显示「证书已就绪」。\n\n"
          + "2. 点「▶ 开始跑步」\n"
          + "   接下来全自动：\n"
          + "     ① 检查手上有没有身份，没有就自动抓\n"
          + "     ② 系统弹「网络连接请求」→ 点「确定」（只需一次）\n"
          + "     ③ 自动建立 VPN + 本地中间人代理\n"
          + "     ④ 自动打开步道乐跑停 8 秒，把它的令牌抓下来\n"
          + "     ⑤ 自动切回，结束步道乐跑进程\n"
          + "     ⑥ 开始跑步，地图上会实时画出轨迹\n\n"
          + "3. 跑完点「📋 复制日志」\n"
          + "   一次复制全部原始日志，只把个人标识（uid / 学号 /\n"
          + "   设备号 / 登录令牌）换成【已隐藏】；签名、时间戳、里程、\n"
          + "   坐标、服务端响应等一律原样保留，可以直接发人排查。\n\n"
          + "【跑步期间可以走开】\n"
          + "· 切到别的 App、锁屏（息屏）、揣兜里都行 —— 跑步期间有\n"
          + "  唤醒锁，节拍不会被系统拉长（配速也就不会被拖慢）。\n"
          + "· 但系统还有一层自己的省电策略（荣耀 / 华为 / 小米都有），\n"
          + "  没放开时它会把助手整进程冻住：通知还挂着、看着像在跑，\n"
          + "  其实一条指令都没执行 —— 这就是「切走就停」。\n"
          + "  首页那行「🔋 后台运行设置」点开有三步：\n"
          + "    ① 申请系统的电池优化白名单；\n"
          + "    ② 打开这台机器自己的「自启动 / 后台活动」页，把助手\n"
          + "       全部放开（小米这类还要把省电策略设成「无限制」）；\n"
          + "    ③ 做一次 90 秒后台体检 —— 说「后台是通的」才算稳。\n"
          + "· 没放开也能跑完：被冻住的那几段会自动从「用时」里剔除\n"
          + "  （日志里会有「⚠ 系统把助手冻住了 X 秒」），配速不受影响。\n"
          + "· 从最近任务里把助手划掉也不会停：跑步归进程管，界面只是\n"
          + "  一扇窗。重新打开就能接管，日志会从磁盘捞回来。\n"
          + "· 想停：回到这里点「■ 停止跑步」。划掉不算停。\n\n"
          + "【参数怎么设】\n"
          + "· 只有一个「系统自动设置」，不用选、也没有按钮。\n"
          + "  点「开始跑步」就是自动模式：\n"
          + "    里程 2.0~5.0 公里、配速 3.5~6.0 分/公里，每次随机；\n"
          + "    路线沿校园道路生成，起点随机但一定在能跑通的路上；\n"
          + "    速度是连续变化的曲线（起跑有加速、收尾有减速、\n"
          + "    中途缓慢游走），不是每段跳一个数。\n\n"
          + "【地图怎么用】\n"
          + "· 两指捏合缩放，单指拖动\n"
          + "· 点水滴标注 → 跳到当前位置并放大\n"
          + "· 跑步时地图会自动跟着你走\n"
          + "· 标题栏点一下即可收起；收起后不会自己弹回来\n\n"
          + "【位置标注 / 图片】\n"
          + "· 侧边栏 → 🙂 定位标注 / 头像：内置 46 张图（点一下即用），\n"
          + "  也可以上传自己的图片，或选「不用图片」走纯色水滴。\n"
          + "· 侧边栏 → 🏃 水滴颜色：水滴的描边/底色。\n\n"
          + "【计分规则】\n"
          + "· 单次里程 ≥ 2.00 公里\n"
          + "· 配速 3'00\" ~ 8'00\" 每公里\n"
          + "· 经过 ≥ 2 个打卡点（跑区内共 14 个）\n"
          + "· 每天只计 1 次\n\n"
          + "【注意】\n"
          + "· 步道乐跑是单会话登录。本程序会在开跑前自动结束它的进程、\n"
          + "  抢过会话；如果令牌已被它刷掉，会自动重新抓一次再接着跑。\n"
          + "· 跑步期间不要打开步道乐跑 —— 两边同时在线会互相顶掉。\n"
          + "· 中途会随机刷一次脸，用你设置的自拍自动完成；停下来那\n"
          + "  十几秒是正常的，那一段里程本来就不计入有效里程。\n"
          + "· 抓包（VPN）只在需要时开，抓到就自动关，系统代理栏位始终不动。\n"
          + "· 本工具只作用于本机已登录的那个账号，无法给别人刷记录。";

    /** 侧边栏里的开发者留言 —— 与启动弹窗（DevNote.MESSAGE）共用同一份文案，改一处即可 */
    private static final String DEV_TEXT = DevNote.MESSAGE;
}
