package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    /**
     * **默认**必须停留的秒数（用户 2026-11-08 指定 5 秒）。
     *
     * <p>★★★ 2026-11-11（用户「60s公告」）：从这一版起**服务端公告可以自带秒数** ——
     *   清单里的 {@code notice_wait} 写多少就锁多少（{@link Cloud#noticeWait}），
     *   只有它没写时才回落成这里的 5 秒。所以「5s 公告 / 60s 公告」是同一份代码，
     *   差别只在 update.json 那一行 —— 想改回 5 秒**不用发版**。
     */
    public static final int LOCK_SEC = 5;

    /**
     * 公告正文（用户 2026-11-08 给的原文，**逐字照抄**）。
     *
     * <p>⚠ 只做一件事：把用户给的那几句话原样放进来，不增删标点、不改语序
     *   （「今天是11-8」这种写法也照抄）。旧的那段像素小游戏推广已经按用户要求删掉了。
     *
     * <p>★ 2026-11-08 晚（v1.0.63）：用户「公告加一句话:多多推广」——
     *   末尾另起一段放「多多推广」。**没加句号**：用户给的就是这四个字，
     *   补标点属于"顺手改通顺"，这个项目里一律不做（同 docs/118）。
     */
    public static final String MESSAGE =
            "今天是11-8，邯郸已经入冬，本次更新后大概率就不会再对功能进行修改了，"
                    + "如有问题或步道乐跑软件更新请及时联系煮波。"
                    + "\n\n多多推广";

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

    /**
     * ★★★ v1.0.65：远程公告的「看到第几号了」。
     *
     * <p>为什么不复用 {@link #K_VER}：远程公告的判据是作者在 {@code update.json} 里
     *   递增的 {@code notice_id}，跟版本号没关系 —— 同一版（1.0.65）可以先后发三条
     *   公告，用 versionCode 判就永远只弹第一条。
     */
    private static final String K_NID = "announce_nid";

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
    /**
     * ★★★ v1.0.65：内置公告（{@link #MESSAGE}）的开关，这一版起是 <b>false</b>。
     *
     * <p>为什么关掉：内置公告的判据是 versionCode，每出一版就必然重弹一次 ——
     *   用户 1.0.65 装完会再看到一遍 1.0.64 那条「入冬 / 多多推广」，跟这一版要说的
     *   事（修提取卡住 + 上服务端公告）完全对不上。这一版要说的那句话，走
     *   {@code update.json} 的 {@code notice}，**不用发版**就能替换 —— 那才是正路。
     *
     * <p>以后要是又想临时在 APK 里硬写一条，把它改回 true 即可。
     */
    private static final boolean BUILTIN_ON = false;

    public static boolean shouldShow(Context c) {
        if (c == null) return false;
        if (pendingRemote(c) != null) return true;
        if (!BUILTIN_ON) return false;
        int v = Cloud.myVer(c);
        return v > 0 && sp(c).getInt(K_VER, 0) < v;
    }

    /**
     * ★ v1.0.65：清单里有没有一条**没看过**的远程公告。
     *
     * <p>读的是 {@link Cloud#cached}（落盘的清单），所以：作者改完 {@code update.json}，
     *   用户下一次打开软件，即使 ver 没变、一个字都不用更新，这条公告也会弹出来。
     *
     * @return 有就返回正文；没有公告 / 已经看过 / 清单没拉到 → null
     */
    public static String pendingRemote(Context c) {
        if (c == null) return null;
        int nid = Cloud.noticeId(c);
        if (nid <= 0) return null;
        if (sp(c).getInt(K_NID, 0) >= nid) return null;
        String txt = Cloud.noticeText(c);
        return txt.length() == 0 ? null : txt;
    }

    /**
     * ★ v1.0.65：清单**刚拉回来**时调一次 —— 有新公告就当场弹。
     *
     * <p>为什么需要它：启动流程里公告是 700 ms 弹的，那时清单还没拉（3 秒后才拉）；
     *   所以"作者刚发的那一条"要靠这次补弹，否则用户得关掉重开才看得见。
     *
     * @return 真弹了才 true
     */
    public static boolean showIfPending(Activity act) {
        if (act == null || act.isFinishing() || showing) return false;
        if (pendingRemote(act) == null) return false;
        show(act, null, true);
        return true;
    }

    /** 记下"这一版的公告已经看过了" */
    private static void markSeen(Context c, boolean remote) {
        int v = Cloud.myVer(c);
        int nid = Cloud.noticeId(c);
        android.content.SharedPreferences.Editor e = sp(c).edit();
        /* 弹的是远程公告时，把"这一版的公告"也一并记掉：
         * 否则用户下次进来，编译进去的那条又补弹一次 —— 一次启动弹两条公告。 */
        if (v > 0) e.putInt(K_VER, v);
        if (remote && nid > 0) e.putInt(K_NID, nid);
        e.apply();
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

        /* ★ v1.0.65：优先弹**远程公告**（update.json 的 notice/notice_id），
         *   没有才回落到编译进 APK 的 {@link #MESSAGE}。
         *   这条优先级就是"不更新也能推公告"的全部实现。 */
        final String remoteTxt = pendingRemote(act);
        final boolean remote = remoteTxt != null;
        /* 内置公告关掉以后，真实弹出只认服务端那一条。
         * mark=false 是自检入口（--es selftest announce），它不看开关，
         * 否则验的人连公告长什么样都看不到。 */
        if (!remote && !BUILTIN_ON && mark) {
            showing = false;   // ★ 退出前必须复位，否则这个进程里再也弹不出公告
            return;
        }

        /* ★ v1.0.65：这一条到底锁几秒 —— 服务端公告先看清单里的 notice_wait，
         *   没写才用默认 5 秒。上限压到 10 分钟：清单写错一个 0 也不至于关不掉。 */
        int lock = LOCK_SEC;
        if (remote) {
            int w = Cloud.noticeWait(act);
            if (w > 0) lock = Math.min(w, 600);
        }
        final int lockSec = lock;

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
        /* ★ v1.0.65：服务端公告不打版本号 —— 它跟"哪一版"无关，是临时要说的那句话。 */
        try {
            ver.setText(remote ? ("📡 服务端公告 · " + lockSec + " 秒后可关闭")
                    : (act.getString(R.string.app_version) + " · " + lockSec + " 秒后可关闭"));
        } catch (Throwable t) {
            ver.setText(lockSec + " 秒后可关闭");
        }
        ver.setTextSize(12f);
        ver.setTextColor(AppPrefs.alpha(fg, 0.75f));
        ver.setPadding(0, (int) (4 * d), 0, (int) (10 * d));
        box.addView(ver);

        // ---- 正文（用户原句，逐字；网址就在这句里，不再单列一行）
        //   ★ 第一版曾经把网址再单列一行（带下划线、可点）—— 真机上一条网址出现两次，
        //     看着像 bug。用户要的只是"一颗点了能跳的键"，所以跳转只留那颗键。
        LinearLayout bodyBox = new LinearLayout(act);
        bodyBox.setOrientation(LinearLayout.VERTICAL);

        /* ★★★ v1.0.65（用户 2026-11-11「<=这行话标红」）：清单里 notice_red 那行
         *   单独渲染成红字加粗摆在正文最上面。不红的话它就是一行普通提示，
         *   而这句话（"上传人脸一定要按规则上传，任何事都有风险，我只能降低风险"）
         *   正是这一条公告里唯一要求用户必须看见的。 */
        final String red = remote ? Cloud.noticeRed(act) : "";
        if (red.length() > 0) {
            TextView warn = new TextView(act);
            warn.setText(red);
            warn.setTextSize(14.5f);
            warn.setTextColor(Color.parseColor("#C62828"));
            warn.setTypeface(Typeface.DEFAULT_BOLD);
            warn.setLineSpacing(4 * d, 1.18f);
            warn.setPadding(0, 0, 0, (int) (8 * d));
            bodyBox.addView(warn);
        }

        TextView body = new TextView(act);
        body.setText(remote ? remoteTxt : MESSAGE);
        body.setTextSize(14f);
        body.setTextColor(fg);
        body.setLineSpacing(4 * d, 1.18f);
        body.setPadding(0, 0, 0, (int) (4 * d));
        bodyBox.addView(body);

        /* ★ v1.0.65：这条公告 500 多字，1080×2248 一屏根本放不下 —— 正文进 ScrollView。
         *   短公告（≤ 240 字）按内容高度走，不留白；长公告固定占 52% 屏高，
         *   倒计时和「关闭」键永远露在屏幕里（小屏 720×1280 也算过）。 */
        ScrollView sc = new ScrollView(act);
        sc.addView(bodyBox);
        int chars = red.length() + (remote ? remoteTxt.length() : MESSAGE.length());
        int scH = chars > 240
                ? (int) (act.getResources().getDisplayMetrics().heightPixels * 0.52)
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scH));

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
        close.setText(lockSec + " 秒后可关");

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
            if (mark) markSeen(act, remote);
            showing = false;
            try { dlg.dismiss(); } catch (Throwable ignore) { }
            if (onClosed != null) onClosed.run();
        };

        timerRef[0] = new CountDownTimer(lockSec * 1000L, 1000L) {
            @Override public void onTick(long ms) {
                int left = (int) Math.ceil(ms / 1000.0);
                count.setText("⏳ 请看完这遍公告：" + left + " 秒后才能关闭");
                close.setText(left + " 秒后可关");
            }
            @Override public void onFinish() {
                count.setText(remote ? "✔ 已可关闭。这条公告只弹这一次，关掉不会再出现。"
                        : "✔ 已可关闭。这条公告只在本版本第一次进来时弹一次。");
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
            if (mark) markSeen(act, remote);
            showing = false;
        });

        try {
            dlg.show();
            log(act, "📢 公告已弹出（" + (remote ? "服务端第 " + Cloud.noticeId(act) + " 号"
                    : "内置") + "，" + lockSec + " 秒后可关）");
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
