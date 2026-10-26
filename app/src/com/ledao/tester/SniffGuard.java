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
 * 抓包守护前台服务 + 跑步期间的**进程优先级**。
 *
 * <p>目的只有一个：把本进程提到「前台服务」优先级，这样切到步道乐跑去抓身份、
 * 或者跑步中途切到别的应用时，系统不会因为我们退到后台就把进程清掉。
 * 进程一死，LocalProxy 的监听就没了，但系统代理还指着 127.0.0.1:8899，
 * 步道乐跑会直接「服务器开小差」—— 那一次跑步就白跑了。
 *
 * <p>★★★ v1.0.54（用户第 65 轮「去除息屏继续跑的功能」）：这里原来还持一把
 * {@code PARTIAL_WAKE_LOCK}（v1.0.25）和一个 {@code WifiLock}（v1.0.51），
 * 为的是"屏幕灭了以后进程照样被调度"。用户点名删了：那套东西成不成取决于
 * 每台 ROM 的省电策略，无法适配多机型。现在改成**跑步期间锁住屏幕**
 * （{@code FLAG_KEEP_SCREEN_ON}，见 MainActivity#setRunScreenOn）——
 * 屏幕根本不灭，也就不需要唤醒锁；这条服务只管"进程别被清"。
 * 所以本类**不再申请任何唤醒锁**，manifest 里的 WAKE_LOCK 权限也一并撤掉了。
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
                    + "没有它，切走以后系统随时可能把助手冻住；"
                    + "到首页把「🪟 迷你小窗」开上，能顶很大一部分");
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

    /**
     * ★ v1.0.25：改常驻通知里那句话（跑步期间显示进度，切走时用户下拉就能看）。
     * 服务还活着才会真的刷；没活着时只记下来，下次 {@link #start} 时用上。
     */
    public static void setText(Context c, String text) {
        notiText = (text == null || text.length() == 0)
                ? (runMode ? "跑步进行中 —— 请让屏幕保持亮着，别退出助手"
                           : "抓包进行中，跑步期间请勿关闭本应用")
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
