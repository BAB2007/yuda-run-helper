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
            // 通知失败也不能让服务崩掉
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
            if (wake == null) {
                android.os.PowerManager pm = (android.os.PowerManager)
                        c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ledao:run");
                wake.setReferenceCounted(false);   // 重复 acquire 不需要重复 release
            }
            if (!wake.isHeld()) wake.acquire(ms);
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

        startForeground(NOTI_ID, b.build());
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
        } catch (Throwable ignore) {
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
