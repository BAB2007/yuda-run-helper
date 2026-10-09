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
 * 抓包守护前台服务。
 *
 * 目的只有一个：把本进程提到「前台服务」优先级，这样切到步道乐跑去跑步时，
 * MIUI 不会因为我们退到后台而把进程杀掉。
 * 进程一死，LocalProxy 的监听就没了，但系统代理还指着 127.0.0.1:8899，
 * 步道乐跑会直接「服务器开小差」—— 那一次跑步就白跑了。
 *
 * 代理本体仍然跑在 MainActivity 里（Activity 切后台只是 onStop，不会 onDestroy，
 * 所以那根线程还活着）。这里只负责保命。
 */
public class SniffGuard extends Service {

    public static final int NOTI_ID = 8801;
    public static final String CHANNEL = "ledao_sniff";

    /** 服务是否在运行（MainActivity.onDestroy 用它判断该不该恢复代理） */
    public static volatile boolean alive = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        alive = true;
        try {
            goForeground();
        } catch (Throwable t) {
            // 通知失败也不能让服务崩掉
        }
        return START_STICKY;
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel c = new NotificationChannel(CHANNEL,
                        "跑步抓包守护", NotificationManager.IMPORTANCE_LOW);
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
        b.setContentTitle("正在记录跑步数据")
                .setContentText("抓包进行中，跑步期间请勿关闭本应用")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);

        startForeground(NOTI_ID, b.build());
    }

    @Override
    public void onDestroy() {
        alive = false;
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
