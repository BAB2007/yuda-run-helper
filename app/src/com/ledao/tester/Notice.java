package com.ledao.tester;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Notice —— 「助手被切到后台时，像收消息一样提醒你」（2026-10-10 用户第 2 条）。
 *
 * <p>用户原话：
 * <pre>
 *   跑步时出错就弹出错窗口，并在窗口加上报错原因，
 *   如果用户将助手开到后台就类似发消息一样提醒用户。
 * </pre>
 *
 * <p>所以这一层只干一件事：**把一条错误做成一条「消息」**——
 * 顶部横幅弹出来（heads-up，就是微信来消息那种），内容里写清楚
 * <b>哪一步失败了 / 为什么 / 下一步做什么</b>，并且自带两个按钮：
 * 「复制日志」和「看看详情」。点任意一个都会把助手拉到前台，
 * 前台再把同一个弹窗补上（见 {@link Alerts#flushPending}）。
 *
 * <h3>几个必须踩对的坑</h3>
 * <ul>
 *   <li><b>Android 8.0 起必须建渠道</b>，否则一条通知都发不出来（静默丢弃，不报错）。
 *       渠道一旦建过，它的「重要级别」就**改不动了** —— 所以 IMPORTANCE_HIGH 必须
 *       第一次就给对，否则以后只会在通知栏里躺着，不会弹横幅。</li>
 *   <li><b>PendingIntent 的 filterEquals 不看 extras</b>。两个只差 extra 的 Intent
 *       算「同一个」PendingIntent，后建的那个会把前一个的 extras 覆盖掉 ——
 *       于是「复制日志」按钮点下去会变成「看详情」。所以两个按钮必须用**不同的
 *       requestCode**（下面写死 0 / 1）。</li>
 *   <li>小图标必须是**纯 alpha 的通知图标**。直接拿 mipmap/ic_launcher（彩色）
 *       当小图标，Android 5.0+ 会把它渲染成一坨白色方块。这里用系统自带的
 *       {@code android.R.drawable.stat_notify_error}（就是个「!」圆圈，正是通知栏要的）。</li>
 * </ul>
 */
public final class Notice {

    private Notice() { }

    /** 通知渠道 id —— 建了就不能改语义，要换级别得换 id */
    private static final String CH_ID = "ledao_run_alert";
    private static final CharSequence CH_NAME = "跑步提醒";
    /** 固定 id：新的错误覆盖旧的，不在通知栏里堆一排 */
    private static final int NOTIFY_ID = 8801;

    private static volatile boolean channelReady = false;

    /**
     * 建渠道（幂等）。Android 8.0 以下没有渠道这回事，直接跳过。
     *
     * <p>IMPORTANCE_HIGH 是「弹横幅」的必要条件 —— 用户要的「像收消息一样」
     * 就是它。附带默认提示音和震动，跟真消息一致。
     */
    public static void ensureChannel(Context c) {
        if (channelReady || c == null) return;
        if (Build.VERSION.SDK_INT < 26) { channelReady = true; return; }
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CH_ID, CH_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("跑步过程中出错时提醒你（不影响正在跑的记录）");
            // 锁屏上也看得见原因 —— 用户多半就是把手机揣兜里了
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
            channelReady = true;
        } catch (Throwable ignore) {
            // 建渠道失败不该把跑步本身搞挂
        }
    }

    /**
     * 把一条错误做成通知发出去。
     *
     * @param title  一句话结论（弹窗标题，也是通知标题）
     * @param reason 「发生了什么」
     * @param advice 「下一步该做什么」
     */
    public static void error(Context c, String title, String reason, String advice) {
        if (c == null) return;
        try {
            ensureChannel(c);
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CH_ID)
                    : new Notification.Builder(c);
            /* ★ 小图标必须是**本 App 自己的**资源。
             *   踩坑记录：第一版用的是 android.R.drawable.stat_notify_error ——
             *   那只是把 framework 的资源 id 存进 Notification，真正解析它的是系统，
             *   而系统按**发通知的包名**去查这张图，我们包里没有这个 id。
             *   现在换成 res/drawable/ic_notify_alert.xml（纯白单色矢量图）。 */
            b.setSmallIcon(R.drawable.ic_notify_alert);
            b.setContentTitle(title == null ? "跑步出错了" : title);
            b.setContentText(firstLine(reason));
            b.setStyle(new Notification.BigTextStyle().bigText(
                    "发生了什么：\n" + safe(reason) + "\n\n下一步该做什么：\n" + safe(advice)));
            b.setAutoCancel(true);
            b.setShowWhen(true);
            b.setCategory(Notification.CATEGORY_ERROR);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            b.setContentIntent(open(c, 0, "showError"));
            // 第二个按钮 —— requestCode 必须不同，见类注释里的坑
            b.addAction(android.R.drawable.ic_menu_save, "复制日志", open(c, 1, "copyLog"));
            b.addAction(android.R.drawable.ic_menu_info_details, "看看详情", open(c, 0, "showError"));
            Notification n = b.build();
            nm.notify(NOTIFY_ID, n);
            // 打一行到 logcat：装机自检时 `adb logcat | grep 通知` 就能确认真的发出去了
            android.util.Log.i("LedaoTester", "[通知] 已发出 —— " + safe(title)
                    + " / " + firstLine(reason));
            verifyPosted(c);
        } catch (Throwable ignore) {
            // 通知发不出去（没权限 / 被系统限制）绝不能影响跑步流程
        }
    }

    /** 这条错误已经在前台看过了 —— 把通知撤掉，别留在通知栏里 */
    public static void clear(Context c) {
        if (c == null) return;
        try {
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFY_ID);
        } catch (Throwable ignore) { }
    }

    /**
     * ★★★ 2026-10-10 装机实测逼出来的一步：**通知发出去 ≠ 用户看得见**。
     *
     * <p>在真机（MIUI 12 / Android 10）上，`nm.notify()` 不抛异常、我们自己的日志
     * 也照打，但通知栏里根本没有 —— logcat 里躺着系统自己的一句话：
     * <pre>
     *   I NotificationService: notification blocked by assistant request
     * </pre>
     * 这是 MIUI 的「通知过滤」（它自己的 NotificationAssistantService）在
     * NotificationManagerService 里直接把通知拦掉了。新装的应用几乎必中，
     * 而且**用户那边一点痕迹都没有** —— 他会以为"助手根本没提醒我"。
     *
     * <p>所以只能发完自己去问一句：我这条还在不在？
     * {@link NotificationManager#getActiveNotifications()} 只列本应用的通知，
     * 不需要任何权限（API 23+）。不在了就把这件事补进待弹的那条错误里，
     * 等用户回到前台，弹窗的「下一步」会多一句告诉他去哪放行。
     */
    private static void verifyPosted(final Context c) {
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(700);          // 给系统一点时间落定
                if (Build.VERSION.SDK_INT < 23) return;      // 低版本查不了，不猜
                NotificationManager nm = (NotificationManager)
                        app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                boolean alive = false;
                for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) {
                    if (s.getId() == NOTIFY_ID) { alive = true; break; }
                }
                if (alive) return;
                android.util.Log.w("LedaoTester",
                        "[通知] ⚠ 系统把这条通知拦掉了（NotificationService: blocked by assistant）"
                      + " —— MIUI 的「通知过滤」会拦新装应用的通知，用户什么都看不到");
                Alerts.noteNotifyBlocked(app);
            } catch (Throwable ignore) { }
        }, "notice-verify");
        t.setDaemon(true);
        t.start();
    }

    /** 点通知 → 拉起助手；extra 决定拉起来之后干什么 */
    private static PendingIntent open(Context c, int req, String extra) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(extra, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, req, i, flags);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** 通知栏收起时只显示一行 —— 取第一行，太长就截断 */
    private static String firstLine(String s) {
        String t = safe(s).trim();
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(0, nl).trim();
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }
}
