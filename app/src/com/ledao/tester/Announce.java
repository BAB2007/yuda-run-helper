package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Announce —— 「装完新版本，第一次进来先弹一条 25 秒公告」（2026-10-31 用户要求）。
 *
 * <p>用户原话：
 * <pre>
 *   更新软件之后首次进入弹一个25秒公告
 * </pre>
 *
 * <p>为什么不是再去改 {@link DevNote}：开发者留言是**长期**挂在那儿的东西
 *   （每次进都弹，能勾「未来都不弹出」），而这条公告说的是**这一版**的事
 *   （水院查数据、照片必须换）。混在一起两个后果：
 *   <ol>
 *     <li>留言正文会越堆越长，长到没人看；</li>
 *     <li>公告过两版就过期了，可留言里那句会一直留着，反而误导。</li>
 *   </ol>
 *   所以这里单独开一个类，正文只放**这一版**要说的话，弹过就记版本号，
 *   下次升版本再换文案（改 {@link #MESSAGE} 即可）。
 *
 * <h3>行为</h3>
 * <ul>
 *   <li><b>装完第一次进</b>就弹：判据是「上次弹过的 versionCode &lt; 本机 versionCode」，
 *       所以覆盖安装 / 升级后必弹一次，同一个版本只弹一次（重进不再弹）。</li>
 *   <li><b>必须停够 25 秒</b>：关闭键先禁用并显示倒计时，25 秒后才变可点；
 *       这期间点屏幕外面 / 按返回键都关不掉（{@code setCancelable(false)}）——
 *       跟开发者留言首次那个 30 秒锁一个道理：不锁就没人看。</li>
 *   <li>没有「不再弹出」勾选框：公告本来就一个版本只弹一次，不需要第二个开关。</li>
 *   <li>弹完接着走原来的启动流程（调用方在 {@code onClosed} 里接
 *       {@link DevNote#show}），两件事不互相打架。</li>
 * </ul>
 *
 * <p>文案本身放在 {@link #MESSAGE}，要改只改那一处。
 */
public final class Announce {

    private Announce() { }

    /** 必须停留的秒数（用户指定 25 秒） */
    public static final int LOCK_SEC = 25;

    /**
     * 公告正文（用户 2026-10-31 给的原文，**逐字照抄**）。
     *
     * <p>只做了一件事：在「每点一次"开始跑步"…」前面断开成第二段 —— 一段两百多字
     *   在小屏上就是一大坨，断一下才读得下去。字一个没动、没加没删。
     *
     * <p>里面三个细节别"顺手改通顺"：
     *   · {@code '开始跑步'} 带引号，指的是首页那颗键的名字；
     *   · {@code 7~10天}、{@code (最好照片都不要重)} 是用户自己的半角写法；
     *   · 最后一句「我即使完善」是原文（"即时"的笔误），照抄不改。
     */
    public static final String MESSAGE =
            "前两天水院查数据来着，有神人举报舍友，我只能说林子大了什么鸟都有，"
          + "其他学院都没查，其他院以前照片相同的可以蒙混过关了，"
          + "水院的不放心可以先避避风头，为了避免以后出问题，一定要按规则上传照片，"
          + "根据内部人员说这个软件只有照片重复问题，其他数据都伪造成功了。\n\n"
          + "每点一次'开始跑步'无论是否跑步成功，是否成绩有效都要换照片，"
          + "至少保证7~10天照片不重样(最好照片都不要重)。"
          + "有其他类似问题即时联系煮波，我即使完善。";

    /** 偏好键：上次弹过的 versionCode（0 = 从没弹过） */
    private static final String K_VER = "announce_ver";

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
     * 弹公告。可重复调用（内部会防重入）。
     * @param onClosed 关闭后的回调，可为 null
     */
    public static void show(final Activity act, final Runnable onClosed) {
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
        title.setText("📢  更新公告");
        title.setTextSize(18f);
        title.setTextColor(acc);
        title.setGravity(Gravity.START);
        box.addView(title);

        TextView ver = new TextView(act);
        try {
            ver.setText(act.getString(R.string.app_version) + " · 请看一遍，25 秒后可关");
        } catch (Throwable t) {
            ver.setText("请看一遍，25 秒后可关");
        }
        ver.setTextSize(12f);
        ver.setTextColor(AppPrefs.alpha(fg, 0.75f));
        ver.setPadding(0, (int) (4 * d), 0, (int) (6 * d));
        box.addView(ver);

        // ---- 正文（可滚动）
        TextView body = new TextView(act);
        body.setText(MESSAGE);
        body.setTextSize(13.5f);
        body.setTextColor(fg);
        body.setLineSpacing(4 * d, 1.18f);

        ScrollView sc = new ScrollView(act);
        sc.addView(body);
        sc.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
        /* 最多占屏幕 45% 高 —— 下面还有倒计时那行和关闭键，
         * 小屏（720×1280）也要保证两者都露在屏幕里。 */
        int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.45);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, (int) (300 * d))));

        // ---- 倒计时提示
        final TextView count = new TextView(act);
        count.setTextSize(12f);
        count.setTextColor(Color.parseColor("#B26A00"));
        count.setPadding(0, (int) (2 * d), 0, (int) (8 * d));
        box.addView(count);

        // ---- 关闭按钮
        final Button close = new Button(act);
        close.setAllCaps(false);
        close.setTextSize(14f);
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(acc);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * d));
        blp.setMargins(0, (int) (4 * d), 0, 0);
        box.addView(close, blp);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(box)
                .setCancelable(false)          // 25 秒内点外面 / 返回键都关不掉
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        }

        final CountDownTimer[] timerRef = new CountDownTimer[1];

        final Runnable dismissNow = () -> {
            try { if (timerRef[0] != null) timerRef[0].cancel(); } catch (Throwable ignore) { }
            markSeen(act);
            showing = false;
            try { dlg.dismiss(); } catch (Throwable ignore) { }
            if (onClosed != null) onClosed.run();
        };

        close.setEnabled(false);
        close.setAlpha(0.55f);
        close.setText("请先阅读（" + LOCK_SEC + " 秒后可关闭）");
        timerRef[0] = new CountDownTimer(LOCK_SEC * 1000L, 1000L) {
            @Override public void onTick(long ms) {
                int left = (int) Math.ceil(ms / 1000.0);
                count.setText("⏳ 请看完这遍公告：" + left + " 秒后才能关闭");
                close.setText("请先阅读（" + left + " 秒后可关闭）");
            }
            @Override public void onFinish() {
                count.setText("✔ 已可关闭。这条公告只在本版本第一次进来时弹一次。");
                count.setTextColor(Color.parseColor("#1B7A4B"));
                close.setEnabled(true);
                close.setAlpha(1f);
                close.setText("我知道了，关闭");
            }
        }.start();

        close.setOnClickListener(v -> dismissNow.run());

        /* 万一系统把窗取消了（比如 Activity 被回收），也要把 showing 复位，
         * 否则这个进程里再也弹不出来了。 */
        dlg.setOnDismissListener(dlg2 -> {
            try { if (timerRef[0] != null) timerRef[0].cancel(); } catch (Throwable ignore) { }
            markSeen(act);
            showing = false;
        });

        try {
            dlg.show();
        } catch (Throwable t) {
            showing = false;
            if (onClosed != null) onClosed.run();
        }
    }
}
