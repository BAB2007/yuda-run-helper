package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Announce —— 「进软件先弹一条公告，5 秒后才让关」（2026-11-05 用户要求）。
 *
 * <h3>正文换过两次，别再照着旧的那段加回来</h3>
 * <ul>
 *   <li><b>2026-11-05（v1.0.62 第一版）</b>：用户原话「更新软件，进入弹一个公告，5秒后可以进行删除，
 *       内容如下：发现大佬做的模拟宇达网站，给他推广一下 https://exist.qd.je/pixel。
 *       加一个点击直接跳转的按钮。」—— 那一版正文是这句推广，另外多一颗
 *       **🌐 打开网站** 的键（不受 5 秒锁限制，随时可点）。</li>
 *   <li><b>2026-11-08（本版）</b>：用户「确认像素小游戏网站推广公告已经删除，添加新的 5s 公告」。
 *       于是——<b>推广那段文字、那个网址（exist.qd.je/pixel）、那颗「打开网站」的键，全部删掉</b>；
 *       正文换成一句入冬提醒（{@link #MESSAGE}），弹窗只剩一颗「关闭」。
 *       留一颗键还指着那个网址，等于"广告删了、入口还在"，所以键跟着一起删。</li>
 * </ul>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>锁 **5 秒**（不是 v1.0.57 那条的 25 秒），到点关闭键才可点；</li>
 *   <li><b>装完第一次进</b>才弹：判据是「上次弹过的 versionCode &lt; 本机 versionCode」，
 *       同一个版本只弹一次，重进不再弹（跟 v1.0.57 那条一致）。</li>
 *   <li><b>5 秒内关不掉</b>：点屏幕外面、按返回键都不行（{@code setCancelable(false)}），
 *       只能等倒计时走完再点「关闭」。</li>
 *   <li>没有「不再弹出」勾选框：公告本来一个版本只弹一次，不需要第二个开关。</li>
 *   <li>关掉之后接着走原来的启动流程（调用方在 {@code onClosed} 里接
 *       {@link DevNote#show}），两个弹窗不打架。</li>
 * </ul>
 *
 * <p>★ 想再验一次：{@code adb shell am start -n com.ledao.tester/.MainActivity --es selftest announce}
 *   —— 走 {@link #showForTest}，**不记已读**，所以验完再正常启动一次该弹还会弹。
 */
public final class Announce {

    private Announce() { }

    /** 必须停留的秒数（用户指定 5 秒） */
    public static final int LOCK_SEC = 5;

    /**
     * 公告正文（用户 2026-11-08 给的原文，**逐字照抄**）。
     *
     * <p>⚠ 只做一件事：把用户给的那句话原样放进来，不增删标点、不改语序
     *   （「今天是11-8」这种写法也照抄）。旧的那段像素小游戏推广已经按用户要求删掉了。
     */
    public static final String MESSAGE =
            "今天是11-8，邯郸已经入冬，本次更新后大概率就不会再对功能进行修改了，"
                    + "如有问题或步道乐跑软件更新请及时联系煮波。";

    /**
     * 偏好键：上次弹过的 versionCode。
     *
     * <p>★★★ 2026-11-08 换过一次键名（{@code announce_ver} → {@code announce_ver2}），
     *   **不是笔误，别再改回去**：
     *   判据是「上次弹过的 versionCode &lt; 本机 versionCode」，而这一版按用户要求
     *   **版本号退回 1.0.62（versionCode 162）**。可 1.0.63~1.0.71 那些废弃版本
     *   早就把 {@code announce_ver} 写成了 163~171（真机上现在就是 170）——
     *   于是 `162 < 170` 永远为假，正常人一个都看不到新公告。
     *   换一个新键，老值等于不存在（默认 0 &lt; 162），这一版公告必定弹一次。
     */
    private static final String K_VER = "announce_ver2";

    private static volatile boolean showing = false;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE);
    }

    /**
     * 这一版要不要弹。
     *
     * <p>读不到 versionCode（{@link Cloud#myVer} 返回 0）时返回 false ——
     * 那种情况下如果还弹，就等于**每次启动都弹**，比不弹糟得多。
     */
    public static boolean shouldShow(Context c) {
        if (c == null) return false;
        int v = Cloud.myVer(c);
        return v > 0 && sp(c).getInt(K_VER, 0) < v;
    }

    /** 记下"这一版的公告已经看过了" */
    private static void markSeen(Context c) {
        int v = Cloud.myVer(c);
        if (v > 0) sp(c).edit().putInt(K_VER, v).apply();
    }

    /**
     * 正常入口：弹公告，关掉后执行 {@code onClosed}。
     */
    public static void show(final Activity act, final Runnable onClosed) {
        show(act, onClosed, true);
    }

    /**
     * 自检入口（{@code --es selftest announce}）：**不记已读** —— 验完再正常启动一次，
     * 该弹还会弹，用户不会因为验过一次就永远看不到这条公告。
     */
    public static void showForTest(final Activity act) {
        show(act, null, false);
    }

    private static void show(final Activity act, final Runnable onClosed, final boolean mark) {
        if (act == null || act.isFinishing()) return;
        if (showing) return;
        showing = true;

        final int bg = AppPrefs.bg(act), fg = AppPrefs.fg(act), acc = AppPrefs.accent(act);
        final int card = (AppPrefs.card(bg) & 0x00FFFFFF) | 0xFF000000;   // 不透明，跟留言窗一致
        final float d = act.getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(14 * d);
        box.setBackground(g);
        box.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (12 * d));

        // ---- 标题（带版本号：一眼能看出这是"哪一版"的公告）
        TextView title = new TextView(act);
        title.setText("📢  公告");
        title.setTextSize(18f);
        title.setTextColor(acc);
        title.setGravity(Gravity.START);
        box.addView(title);

        TextView ver = new TextView(act);
        try {
            ver.setText(act.getString(R.string.app_version) + " · 5 秒后可关闭");
        } catch (Throwable t) {
            ver.setText("5 秒后可关闭");
        }
        ver.setTextSize(12f);
        ver.setTextColor(AppPrefs.alpha(fg, 0.75f));
        ver.setPadding(0, (int) (4 * d), 0, (int) (10 * d));
        box.addView(ver);

        // ---- 正文（用户原句，逐字；网址就在这句里，不再单列一行）
        //   ★ 第一版曾经把网址再单列一行（带下划线、可点）—— 真机上一条网址出现两次，
        //     看着像 bug。用户要的只是"一颗点了能跳的键"，所以跳转只留那颗键。
        TextView body = new TextView(act);
        body.setText(MESSAGE);
        body.setTextSize(14f);
        body.setTextColor(fg);
        body.setLineSpacing(4 * d, 1.18f);
        body.setPadding(0, 0, 0, (int) (4 * d));
        box.addView(body);

        // ---- 倒计时提示
        final TextView count = new TextView(act);
        count.setTextSize(12f);
        count.setTextColor(Color.parseColor("#B26A00"));
        count.setPadding(0, (int) (6 * d), 0, (int) (8 * d));
        box.addView(count);

        /* ---- 只有一颗「关闭」键（5 秒后才可点）
         *   ★ 2026-11-08：原来这里是两颗并排 ——「🌐 打开网站」+「关闭」。
         *     那颗键跳的就是像素小游戏那条推广（exist.qd.je/pixel）。推广按用户要求删了，
         *     键必须跟着删：正文里已经没有那个网址了，再留一颗键指过去，
         *     等于"广告删了、入口还在"。所以这里不再建 row，直接摆一颗通栏的关闭键。 */
        final Button close = new Button(act);
        close.setAllCaps(false);
        close.setTextSize(14f);
        /* 次要动作：底色取「当前主题前景色的 14%」，字用前景色本身 ——
         * 不能写死奶油灰：夜间黑主题下前景色是浅字，浅字压浅灰就看不见了。 */
        close.setTextColor(fg);
        close.setBackgroundColor(AppPrefs.alpha(fg, 0.14f));
        close.setEnabled(false);
        close.setAlpha(0.55f);
        close.setText("5 秒后可关");

        int bh = (int) (44 * d);
        box.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bh));

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(box)
                .setCancelable(false)          // 5 秒内点外面 / 返回键都关不掉
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        }

        final CountDownTimer[] timerRef = new CountDownTimer[1];

        final Runnable dismissNow = () -> {
            try { if (timerRef[0] != null) timerRef[0].cancel(); } catch (Throwable ignore) { }
            if (mark) markSeen(act);
            showing = false;
            try { dlg.dismiss(); } catch (Throwable ignore) { }
            if (onClosed != null) onClosed.run();
        };

        timerRef[0] = new CountDownTimer(LOCK_SEC * 1000L, 1000L) {
            @Override public void onTick(long ms) {
                int left = (int) Math.ceil(ms / 1000.0);
                count.setText("⏳ 请看完这遍公告：" + left + " 秒后才能关闭");
                close.setText(left + " 秒后可关");
            }
            @Override public void onFinish() {
                count.setText("✔ 已可关闭。这条公告只在本版本第一次进来时弹一次。");
                count.setTextColor(Color.parseColor("#1B7A4B"));
                close.setEnabled(true);
                close.setAlpha(1f);
                close.setText("关闭");
            }
        }.start();

        close.setOnClickListener(v -> dismissNow.run());

        /* 万一系统把窗取消了（比如 Activity 被回收），也要把 showing 复位，
         * 否则这个进程里再也弹不出来了。 */
        dlg.setOnDismissListener(dlg2 -> {
            try { if (timerRef[0] != null) timerRef[0].cancel(); } catch (Throwable ignore) { }
            if (mark) markSeen(act);
            showing = false;
        });

        try {
            dlg.show();
            log(act, "📢 公告已弹出（" + LOCK_SEC + " 秒后可关）");
        } catch (Throwable t) {
            showing = false;
            if (onClosed != null) onClosed.run();
        }
    }

    /** 只在主界面上才留日志（公告只从 MainActivity 弹，这里只是防御）。 */
    private static void log(Activity act, String s) {
        try {
            if (act instanceof MainActivity) ((MainActivity) act).log(s);
        } catch (Throwable ignore) {
            /* 日志打不出来不影响弹窗 */
        }
    }
}
