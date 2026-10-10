package com.ledao.tester;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 抓包守护前台服务 + 跑步期间的**保命与保活**。
 *
 * <p>目的有两个：
 * <ol>
 *   <li>把本进程提到「前台服务」优先级，这样切到步道乐跑去跑步时，
 *       MIUI 不会因为我们退到后台而把进程杀掉。
 *       进程一死，LocalProxy 的监听就没了，但系统代理还指着 127.0.0.1:8899，
 *       步道乐跑会直接「服务器开小差」—— 那一次跑步就白跑了。</li>
 *   <li>★ v1.0.25：跑步期间再持一个 {@code PARTIAL_WAKE_LOCK}
 *       —— 前台服务只保证"进程不被杀"，**不保证 CPU 不睡**。
 *       少了这把锁，息屏后系统随时可以把 CPU 挂起：每一拍的间隔会被拉长
 *       （{@code Thread.sleep} 在挂起期间不走），而配速是按墙上时钟算的，
 *       服务端又卡着 3:00~8:00 分/公里 —— 拖长了就可能被判「配速超限」。
 *       见 docs/59。</li>
 * </ol>
 *
 * 代理本体仍然跑在 MainActivity 里（Activity 切后台只是 onStop，不会 onDestroy，
 * 所以那根线程还活着）。这里只负责保命。
 */
public class SniffGuard extends Service {

    public static final int NOTI_ID = 8801;
    public static final String CHANNEL = "ledao_sniff";

    /** 服务是否在运行（MainActivity.onDestroy 用它判断该不该恢复代理） */
    public static volatile boolean alive = false;

    /** ★ v1.0.25：是不是"跑步中"（决定常驻通知那两句话说什么） */
    public static volatile boolean runMode = false;

    /** 服务实例（静态留着），好让外面随时改写那条通知 */
    private static volatile SniffGuard inst = null;

    /** 通知正文 */
    private static volatile String notiText = "抓包进行中，跑步期间请勿关闭本应用";

    /** 跑步期间的唤醒锁（PARTIAL_WAKE_LOCK 不会点亮屏幕，只是不让 CPU 睡） */
    private static android.os.PowerManager.WakeLock wake = null;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alive = true;
        inst = this;
        try {
            goForeground();
        } catch (Throwable t) {
            /* ★★★ v1.0.30（docs/62）：这里以前是**空 catch**。
             *   前台服务没起来（少权限 / 被 ROM 拦 / 通知渠道被禁 / pass 了没声明的
             *   FGS 类型）时外面完全不知道，用户看到的就是"通知不见了、切走就被冻" ——
             *   而日志里一个字都没有，谁也没法查。现在至少留下可查的痕迹。 */
            android.util.Log.e("LedaoTester", "[后台] ❌ 前台服务没起来：" + t);
            RunLog.append("[后台] ❌ 前台服务没起来（" + t + "）—— "
                    + "没有它，切走 / 息屏后系统随时可能把助手冻住，"
                    + "见首页「🔋 后台运行设置」");
        }
        return START_STICKY;
    }

    /**
     * ★ v1.0.25：用户从最近任务里把界面划掉了。
     *
     * <p>服务本身留着（{@code stopWithTask="false"}），跑步线程也留着
     * （{@code MainActivity.onDestroy} 已经不再停它）—— 这一场照跑、照收尾上报。
     * 这里只往 logcat 留一行脚印，方便日后对时间线。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        android.util.Log.i("LedaoTester",
                "[后台] 任务被划掉了 —— 跑步线程与前台服务继续（这是设计好的，"
                + "重新打开 App 就能看见它还在跑）");
        super.onTaskRemoved(rootIntent);
    }

    // ==================================================================
    //  唤醒锁：跑步期间别让 CPU 睡（v1.0.25）
    // ==================================================================

    /**
     * 持住唤醒锁。
     *
     * @param ms 兜底超时 —— 万一某条路径忘了释放，最多也就耗这么久（跑步一场撑死 30 分钟），
     *           不会变成一个永远拿着的漏电锁。
     */
    public static synchronized void holdAwake(Context c, long ms) {
        try {
            if (c != null) ctx = c.getApplicationContext();
            if (wake == null) {
                if (ctx == null) return;
                android.os.PowerManager pm = (android.os.PowerManager)
                        ctx.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ledao:run");
                wake.setReferenceCounted(false);   // 重复 acquire 不需要重复 release
            }
            if (!wake.isHeld()) wake.acquire(ms);
            else wake.acquire(ms);                 // 已持有：这一下等于把兜底超时往后推
        } catch (Throwable ignore) {
            /* 拿不到就算了：跑步照跑，只是息屏后节拍可能被拉长 */
        }
    }

    /** 放掉唤醒锁（跑步结束 / 出错退出都要调） */
    public static synchronized void dropAwake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignore) { }
    }

    /**
     * ★★★ v1.0.30（docs/62）：把唤醒锁的兜底超时**往后推**。
     *
     * <p>{@link #holdAwake} 只 acquire 一次（45 分钟兜底）。被系统冻过的场次里，
     * 墙上时钟会走得比模拟时长多得多（冻 20 分钟就有 20 分钟是"白等"），
     * 45 分钟可能不够 —— 跑步循环每隔几分钟续一下，长跑/被冻久了也不会半路掉锁。
     *
     * <p>没持有时只是重新 acquire；已经持有时（引用计数已关）等于重设超时。
     */
    public static synchronized void keepAwake() {
        if (ctx == null) return;
        holdAwake(ctx, 45L * 60 * 1000);
    }

    /** 拿住唤醒锁时顺手记住 application context，好让 {@link #keepAwake()} 能续 */
    private static Context ctx = null;

    /** 现在拿着这把锁吗（自检和日志用） */
    public static synchronized boolean awake() {
        try {
            return wake != null && wake.isHeld();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ v1.0.25：改常驻通知里那句话（跑步期间显示进度，息屏/切走时用户下拉就能看）。
     * 服务还活着才会真的刷；没活着时只记下来，下次 {@link #start} 时用上。
     */
    public static void setText(Context c, String text) {
        notiText = (text == null || text.length() == 0)
                ? (runMode ? "跑步进行中 —— 可以息屏、可以切到别的应用" : "抓包进行中，跑步期间请勿关闭本应用")
                : text;
        SniffGuard s = inst;
        if (s != null) {
            try {
                s.goForeground();
            } catch (Throwable ignore) { }
        }
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel c = new NotificationChannel(CHANNEL,
                        "跑步守护", NotificationManager.IMPORTANCE_LOW);
                c.setShowBadge(false);
                c.enableVibration(false);
                c.setSound(null, null);
                nm.createNotificationChannel(c);
            }
        }

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(runMode ? "跑步进行中 · 助手在后台记录" : "正在记录跑步数据")
                .setContentText(notiText)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);

        startForegroundCompat(b.build());
    }

    /**
     * ★★★ v1.0.30（docs/62）：显式声明前台服务**类型**。
     *
     * <p>本 App 的 targetSdk 还是 28，Android 14+ 只对"高 targetSdk"强制要求类型，
     * 所以不写也不会当场抛 —— 但国产 ROM 自己的省电策略（荣耀/华为的「应用速冻」、
     * 小米的省电策略）会看这个类型来决定"这算不算正经前台服务"，
     * 而且哪天把 targetSdk 抬上去就必须有。manifest 里同名声明了 dataSync
     * （"正在同步数据"，与本服务的用途最贴切），这里传的必须和它一致。
     */
    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        alive = false;
        inst = null;
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, SniffGuard.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            /* ★ v1.0.30：以前这里也是空 catch —— 前台服务压根没起来的话，
             *   跑步就成了"裸奔"（切走即被冻），而日志里看不出来。 */
            android.util.Log.e("LedaoTester", "[后台] ❌ 起前台服务失败：" + t);
            RunLog.append("[后台] ❌ 起前台服务失败（" + t + "）");
        }
    }

    public static void stop(Context c) {
        alive = false;
        try {
            c.stopService(new Intent(c, SniffGuard.class));
        } catch (Throwable ignore) {
        }
    }
}
