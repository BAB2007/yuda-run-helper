package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Alerts —— 界面上两种「告诉用户出事了」的方式（2026-10-10 用户第 1、2 条）。
 *
 * <h3>用户原话</h3>
 * <pre>
 *   1. 跑前，如果人脸照片通过，就弹一个渐进窗口「人脸识别通过」，并在停留几毫秒后消失；
 *      如果没通过，就弹原因，并且终止跑步并提示
 *   2. 跑步时出错就弹出错窗口，并在窗口加上报错原因，
 *      如果用户将助手开到后台就类似发消息一样提醒用户
 *   该软件面向普通用户，弹窗内容应该让用户看后就知道大概发生了什么以及下一步该做什么。
 * </pre>
 *
 * <h3>两种方式，用途分得很清</h3>
 * <ul>
 *   <li>{@link #flash} —— **渐显小卡片**，居中淡入、停一会儿、淡出，不挡操作、不用点。
 *       给「一切正常」的确认（人脸识别通过）和「不影响继续跑」的提醒用。</li>
 *   <li>{@link #error} —— **模态弹窗**，三段式：标题 / 发生了什么 / 下一步该做什么，
 *       底下带「复制日志」按钮（用户要把日志发给开发者，这一步不该再让他去侧边栏找）。
 *       如果此刻助手在后台，就改发一条横幅通知，等他回来再补弹同一个窗。</li>
 * </ul>
 *
 * <h3>为什么「后台」要单独判断</h3>
 * 跑步线程是活的，用户很可能把助手切到后台、把手机揣兜里。这时候弹窗
 * 是弹给空气看的 —— 用户要的是「像收消息一样」在通知栏里看到。
 * 前台/后台由 {@code MainActivity.onResume/onPause} 喂进 {@link #setForeground}。
 */
public final class Alerts {

    private Alerts() { }

    /** 助手在前台吗（onResume = true，onPause = false） */
    private static volatile boolean fg = false;

    /** 后台期间攒下的那条错误：回到前台补弹同一个窗（避免"通知看过了、回来啥也没有"） */
    private static volatile String[] pending = null;

    /** 渐显小卡片停留多久 —— 用户写的是"几毫秒"，但几毫秒人根本看不见，
     *  取 1.6 秒：一眼能读完「人脸识别通过」这五个字，又不至于挡着接下来的操作。 */
    private static final long FLASH_HOLD_MS = 1600;

    public static void setForeground(boolean v) {
        fg = v;
    }

    public static boolean isForeground() {
        return fg;
    }

    // ==================================================================
    //  渐显小卡片
    // ==================================================================

    /**
     * 居中渐显 → 停留 → 渐隐 的一张小卡片。**不拦截点击**（点不到它，点得到它下面）。
     *
     * @param host 挂到哪个容器上（MainActivity 传的是 rootFrame，能盖住整个界面）
     * @param warn true=红色（出事了但还要继续），false=绿色（一切正常）
     */
    public static void flash(ViewGroup host, String text, boolean warn) {
        if (host == null || text == null || text.length() == 0) return;
        try {
            final Context c = host.getContext();
            final float d = c.getResources().getDisplayMetrics().density;
            final TextView tv = new TextView(c);
            tv.setText(text);
            tv.setTextSize(16f);
            tv.setTextColor(Color.WHITE);
            tv.setGravity(Gravity.CENTER);
            tv.setLineSpacing(2 * d, 1.15f);
            tv.setPadding((int) (20 * d), (int) (14 * d), (int) (20 * d), (int) (14 * d));
            GradientDrawable g = new GradientDrawable();
            // 带一点透明度：它盖在地图上，别像一堵墙
            g.setColor(warn ? 0xE6B00020 : 0xE61B7A4B);
            g.setCornerRadius(14 * d);
            tv.setBackground(g);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            int side = (int) (28 * d);
            lp.leftMargin = side;
            lp.rightMargin = side;
            host.addView(tv, lp);

            tv.setAlpha(0f);
            tv.animate().alpha(1f).setDuration(180).start();
            host.postDelayed(() -> {
                try {
                    tv.animate().alpha(0f).setDuration(320).withEndAction(() -> {
                        try { host.removeView(tv); } catch (Throwable ignore) { }
                    }).start();
                } catch (Throwable ignore) { }
            }, FLASH_HOLD_MS);
        } catch (Throwable ignore) {
            // 一个提示卡片画不出来，绝不能影响跑步
        }
    }

    // ==================================================================
    //  出错：前台弹窗 / 后台发通知
    // ==================================================================

    /**
     * 出错了。前台 → 模态弹窗；后台 → 横幅通知 + 记下来，回前台补弹。
     *
     * @param act     当前活着的 Activity（可为 null —— 那时只能发通知）
     * @param copyLog 「复制日志」按钮要干的事（MainActivity 传 this::copyLog）
     */
    public static void error(Activity act, String title, String reason, String advice,
                             Runnable copyLog) {
        if (fg && act != null && !act.isFinishing()) {
            dialog(act, title, reason, advice, copyLog);
            return;
        }
        pending = new String[]{title, reason, advice};
        Context app = act == null ? null : act.getApplicationContext();
        // ★ 同时落一份到磁盘：用户很可能过了很久才点那条通知，中间进程
        //   被系统回收过是常态（跑步结束了，守护服务也就不拦着清理了）。
        //   只放内存的话，点开就是一个"空手回来"的界面。
        remember(app, pending);
        Notice.error(app, title, reason, advice);
    }

    /**
     * 回到前台时补弹后台攒下的那条错误（MainActivity.onResume 调）。
     * 没有就什么都不做。
     */
    public static void flushPending(Activity act, Runnable copyLog) {
        String[] p = pending;
        pending = null;
        if (act == null || act.isFinishing()) return;
        if (p == null) p = recall(act);       // 进程重启过 —— 从磁盘捞回来
        if (p == null) return;
        remember(act, null);
        Notice.clear(act);            // 人已经回来了，通知栏里那条撤掉
        dialog(act, p[0], p[1], p[2], copyLog);
    }

    /** 丢掉还没弹的错误（重新开跑时调，别让上一场的错在下一场弹出来） */
    public static void clearPending(Activity act) {
        pending = null;
        remember(act == null ? null : act.getApplicationContext(), null);
        Notice.clear(act);
    }

    // ---- 待弹错误的持久化（就三个短字符串，不必另开 SharedPreferences）

    private static final String K_T = "err_title", K_R = "err_reason", K_A = "err_advice";

    private static void remember(Context c, String[] e) {
        if (c == null) return;
        try {
            android.content.SharedPreferences.Editor ed =
                    c.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE).edit();
            if (e == null) ed.remove(K_T).remove(K_R).remove(K_A);
            else ed.putString(K_T, e[0]).putString(K_R, e[1]).putString(K_A, e[2]);
            ed.apply();
        } catch (Throwable ignore) { }
    }

    private static String[] recall(Context c) {
        try {
            android.content.SharedPreferences sp =
                    c.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE);
            String t = sp.getString(K_T, null);
            if (t == null) return null;
            return new String[]{t, sp.getString(K_R, ""), sp.getString(K_A, "")};
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * ★★★ 2026-10-10 真机实测逼出来的一句：**通知被系统拦掉了**。
     *
     * <p>MIUI 的「通知过滤」会在系统层直接拦掉新装应用的通知（logcat 原话
     * `NotificationService: notification blocked by assistant request`），
     * 用户那边一点痕迹都没有。这时光靠通知提醒就是空的 —— 但用户**迟早会回到
     * 助手**（跑步结束了总要看一眼），所以把这句补进待弹的那条错误里最省事：
     * 他回来看到的弹窗，「下一步」里会多一行，直接告诉他去哪儿放行。
     *
     * <p>由 {@code Notice.verifyPosted()} 在后台线程调，所以这里同步要小心：
     * 只做一次字符串拼接 + 一次落盘。
     */
    static void noteNotifyBlocked(Context c) {
        final String note = "\n\n（另外：系统把这条通知拦掉了，它根本没弹出来。"
                + "小米 / MIUI 的「通知过滤」会拦新安装应用的通知 —— "
                + "到「设置 → 通知和状态栏 → 通知过滤」里把本应用设为重要，"
                + "或者在「设置 → 应用管理 → 宇达步道乐跑助手 → 通知管理」里放行，"
                + "以后跑步出错就能在通知栏里看到了。）";
        try {
            String[] p = pending;
            if (p != null) p[2] = p[2] + note;
            String[] saved = recall(c);
            if (saved != null) {
                saved[2] = saved[2] + note;
                remember(c, saved);
            }
        } catch (Throwable ignore) { }
    }

    // ==================================================================
    //  三段式弹窗
    // ==================================================================

    /**
     * 标题 / 发生了什么 / 下一步该做什么 —— 用户明确要求的三段。
     *
     * <p>「下一步」单独放在一块浅色底里，因为用户扫一眼弹窗，
     * 真正要找的是**我该点哪儿**。
     */
    public static void dialog(final Activity act, String title, String reason, String advice,
                              final Runnable copyLog) {
        if (act == null || act.isFinishing()) return;
        try {
            final float d = act.getResources().getDisplayMetrics().density;
            int bg = AppPrefs.bg(act), fg = AppPrefs.fg(act), acc = AppPrefs.accent(act);
            int card = (AppPrefs.card(bg) & 0x00FFFFFF) | 0xFF000000;   // 不透明，别透出地图
            // 夜间主题给个更亮的"下一步"底色，否则浅色底在深色卡片上很脏
            boolean dark = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg) <= 150;

            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bgl = new GradientDrawable();
            bgl.setColor(card);
            bgl.setCornerRadius(14 * d);
            box.setBackground(bgl);
            box.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (10 * d));

            TextView tvTitle = new TextView(act);
            tvTitle.setText("⚠  " + (title == null ? "出错了" : title));
            tvTitle.setTextSize(17f);
            tvTitle.setTextColor(0xFFB00020);
            box.addView(tvTitle);

            ScrollView sc = new ScrollView(act);
            LinearLayout inner = new LinearLayout(act);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(0, (int) (8 * d), 0, (int) (4 * d));

            inner.addView(section(act, "发生了什么", acc, d));
            TextView tvReason = new TextView(act);
            tvReason.setText(reason == null ? "" : reason);
            tvReason.setTextSize(13f);
            tvReason.setTextColor(fg);
            tvReason.setLineSpacing(3 * d, 1.15f);
            inner.addView(tvReason);

            inner.addView(section(act, "下一步该做什么", acc, d));
            TextView tvAdvice = new TextView(act);
            tvAdvice.setText(advice == null ? "" : advice);
            tvAdvice.setTextSize(13f);
            tvAdvice.setTextColor(fg);
            tvAdvice.setLineSpacing(3 * d, 1.15f);
            tvAdvice.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
            GradientDrawable ag = new GradientDrawable();
            ag.setColor(dark ? 0x332E9E6B : 0x1F1B7A4B);
            ag.setStroke((int) Math.max(1, d), dark ? 0x552E9E6B : 0x331B7A4B);
            ag.setCornerRadius(8 * d);
            tvAdvice.setBackground(ag);
            inner.addView(tvAdvice);

            sc.addView(inner);
            /* 正文可滚，但**高度必须给够**：用户扫一眼弹窗，真正要找的是
             * 「下一步该做什么」，而它排在「发生了什么」后面 —— 装机第一版给到
             * 300dp，结果最常见那条（人脸没通过）的下一步正好被切在按钮上沿，
             * 要滚一下才看得见。加到 380dp / 屏高 50% 之后，只有特别长的原因
             * （比如会话失效那种）才需要滚。 */
            int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.50);
            box.addView(sc, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, (int) (380 * d))));

            /* ★★★ 按钮必须**放进卡片里**，不能用 AlertDialog 自带的按钮栏。
             *
             * 装机第一版就是栽在这里（v1.0.19 自检截图）：卡片用的是自己的
             * 圆角底 + 透明窗背景，而 AlertDialog 的按钮画在窗口**下面那块独立的
             * buttonPanel** 上 —— 结果「知道了 / 复制日志发给开发者」两个键飘在
             * 卡片外面、压在背景按钮上，看着完全是坏的。
             * 自己画一排就够了：左「知道了」，右「复制日志发给开发者」。 */
            final AlertDialog[] ref = new AlertDialog[1];
            LinearLayout btns = new LinearLayout(act);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, (int) (12 * d), 0, 0);

            Button ok = new Button(act);
            ok.setText("知道了");
            ok.setTextSize(14f);
            ok.setAllCaps(false);
            ok.setTextColor(fg);
            ok.setMinWidth(0); ok.setMinimumWidth(0);
            ok.setMinHeight(0); ok.setMinimumHeight(0);
            GradientDrawable og = new GradientDrawable();
            og.setColor(dark ? 0x22FFFFFF : 0x14000000);
            og.setStroke((int) Math.max(1, d), dark ? 0x44FFFFFF : 0x22000000);
            og.setCornerRadius(9 * d);
            ok.setBackground(og);
            ok.setOnClickListener(v -> { try { ref[0].dismiss(); } catch (Throwable ignore) { } });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            olp.setMargins(0, 0, (int) (5 * d), 0);
            btns.addView(ok, olp);

            if (copyLog != null) {
                // ★ 用户要把日志发给开发者（开发者留言里写的就是那个邮箱）——
                //   那这一步就不该再让他去侧边栏找「复制日志」。
                Button cp = new Button(act);
                cp.setText("复制日志发给开发者");
                cp.setTextSize(13f);
                cp.setAllCaps(false);
                cp.setTextColor(Color.WHITE);
                cp.setMinWidth(0); cp.setMinimumWidth(0);
                cp.setMinHeight(0); cp.setMinimumHeight(0);
                GradientDrawable cg = new GradientDrawable();
                cg.setColor(acc);
                cg.setCornerRadius(9 * d);
                cp.setBackground(cg);
                cp.setOnClickListener(v -> {
                    try { copyLog.run(); } catch (Throwable ignore) { }
                    try { ref[0].dismiss(); } catch (Throwable ignore) { }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f);
                clp.setMargins((int) (5 * d), 0, 0, 0);
                btns.addView(cp, clp);
            }
            box.addView(btns);

            // 只 setView，不给 Builder 任何按钮 —— 按钮栏是空的，卡片才是唯一的一块底
            AlertDialog dlg = new AlertDialog.Builder(act).setView(box).create();
            ref[0] = dlg;
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0x00000000));
            }
            dlg.show();
        } catch (Throwable ignore) {
            // 弹窗本身失败（主题/资源异常）时退化成一行 Toast，至少让用户看见结论
            try {
                android.widget.Toast.makeText(act, title + "\n" + reason, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignore2) { }
        }
    }

    private static TextView section(Context c, String s, int acc, float d) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(acc);
        t.setPadding(0, (int) (10 * d), 0, (int) (2 * d));
        return t;
    }
}
