package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * DevNote —— 「开发者留言」弹窗（2026-10-07 界面优化 #9）。
 *
 * 行为（照用户要求逐条实现，2026-10-08 修正）：
 *   · 一进软件就弹，**默认弹出**；只要没勾「未来都不弹出此窗口」，**每次打开都弹**。
 *   · **首次使用**：界面上要停够 30 秒才允许关掉 —— 关按钮先禁用并显示倒计时，
 *     倒计时结束才变可点；同时顶部有「请务必阅读开发者留言」的醒目提示。
 *   · 非首次：没有 30 秒限制，随时可关（点外面/返回键也算关）。
 *   · 「未来都不弹出此窗口」勾选框**每次弹出都在**（首次那 30 秒里也在，可以先勾好）；
 *     勾上并关闭，以后就不再弹了。
 *   · 首次时不给点外面关（setCancelable(false)），必须等满 30 秒 —— 只点一次「关闭」。
 *
 * 文案本身放在 {@link #MESSAGE}，要改只改那一处。
 */
public final class DevNote {

    private DevNote() { }

    /** 首次必须停留的秒数 */
    private static final int FIRST_LOCK_SEC = 30;

    // ------------------------------------------------------------------ 联系方式
    /**
     * ★★★ 2026-10-17（用户第 4 次改留言，正式版口径）：
     *   留言正文换成「此版本为正式版…」，**联系方式只留 QQ** ——
     *   原先那四个可复制小按键（github / gitee / QQ 号 / 邮箱）里的仓库地址与邮箱
     *   不再出现在留言里，所以 {@code GITHUB} / {@code GITEE} / {@code EMAIL}
     *   三个常量一并删掉，只保留 {@link #QQ}。
     *
     * <p>为什么只留一个常量而不是删光：{@link Err#advise} 要用它告诉用户
     *   "把日志发给谁"，字面量只能有一份，不许在别处再写一遍。
     *
     * <p>可复制小按键（{@link #copyRow}）保留 —— 普通用户不会在弹窗正文里长按拖选，
     *   要么抄错一位、要么干脆放弃；QQ 号是**唯一的官方渠道**，抄错就意味着
     *   装到了来路不明的包。现在只剩这一个键。
     */
    public static final String QQ = "3855962053";

    /**
     * ★★★ 2026-10-17 用户第 4 次改留言（正式版口径）—— 全文换成三句：
     * <pre>
     *   此版本为正式版，以前发布过一个开源版本，但是存在一些安全问题，
     *   避免可能的刷跑异常、账号限制等麻烦，请使用正式版。
     *   作者联系方式请联系QQ3855962053。
     * </pre>
     *   与旧版（匿名开源、吐槽代跑定价、github/gitee 获取渠道、删库跑路…）相比：
     *   · 不再提开源仓库地址（那两行已从界面与留言里去掉）；
     *   · 不再有打赏那段（见文件末尾原来的 tipBlock，已整块删除）；
     *   · 联系方式只有 QQ 一个（{@link #QQ}）。
     *
     * <p>历史沿革（为什么之前是那样，别再改回去）：
     *   ① 2026-10-10：第一段那句质问代跑定价的括号删掉；
     *   ② 2026-10-10：倒数第三段末尾加一个「大家」；
     *   ③ 2026-10-11：那句警告里的「手机变砖，系统被入侵，」去掉，只留「信息被盗，财产损失」；
     *   ④ 2026-10-17：整篇换成上面这三句。
     */
    public static final String MESSAGE =
            "此版本为正式版，以前发布过一个开源版本，但是存在一些安全问题，"
          + "避免可能的刷跑异常、账号限制等麻烦，请使用正式版。\n\n"
          + "作者联系方式请联系QQ" + QQ + "。";

    // ---- 偏好键
    private static final String K_SEEN   = "devnote_seen";     // 已经完整看过一次
    private static final String K_NEVER  = "devnote_never";    // 未来均不弹出

    public static boolean never(Context c) {
        return sp(c).getBoolean(K_NEVER, false);
    }

    /** 是否还需要弹：勾了「不再弹出」就不再弹，否则每次进都弹 */
    public static boolean shouldShow(Context c) {
        return !never(c);
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE);
    }

    /**
     * 弹出留言窗。可重复调用（内部会防重入）。
     * @param onClosed 关闭后的回调，可为 null
     */
    public static void show(final Activity act, final Runnable onClosed) {
        if (act == null || act.isFinishing()) return;
        if (showing) return;
        showing = true;

        final boolean first = !sp(act).getBoolean(K_SEEN, false);
        final int bg = AppPrefs.bg(act), fg = AppPrefs.fg(act), acc = AppPrefs.accent(act);
        final int card = (AppPrefs.card(bg) & 0x00FFFFFF) | 0xFF000000;   // 不透明
        final float d = act.getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(14 * d);
        box.setBackground(g);
        box.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (12 * d));

        // ---- 标题
        TextView title = new TextView(act);
        title.setText("📣  开发者留言");
        title.setTextSize(18f);
        title.setTextColor(acc);
        title.setGravity(Gravity.START);
        box.addView(title);

        // ---- 首次的醒目提示
        final TextView warn = new TextView(act);
        warn.setTextSize(12.5f);
        warn.setTextColor(Color.parseColor("#B00020"));
        warn.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        warn.setText("请务必阅读开发者留言");
        if (!first) warn.setVisibility(View.GONE);
        box.addView(warn);

        // ---- 正文
        TextView body = new TextView(act);
        body.setText(MESSAGE);
        body.setTextSize(13f);
        body.setTextColor(fg);
        body.setLineSpacing(3 * d, 1.15f);
        ScrollView sc = new ScrollView(act);
        sc.addView(body);
        sc.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
        // 留言卡片最多占屏幕 38% 高 —— 下面还有勾选框、四个复制小按键和关闭按钮，
        // 小屏（720×1280）也要保证「复制邮箱」和「关闭」都露在屏幕里。
        int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.38);
        body.setMovementMethod(new ScrollingMovementMethod());
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, (int) (220 * d))));

        // ---- 倒计时提示（仅首次）
        final TextView count = new TextView(act);
        count.setTextSize(12f);
        count.setTextColor(Color.parseColor("#B26A00"));
        count.setPadding(0, (int) (2 * d), 0, (int) (8 * d));
        count.setVisibility(first ? View.VISIBLE : View.GONE);
        box.addView(count);

        // ---- 「未来均不弹出」勾选框
        //   ★ 2026-10-08 用户澄清：**每一次弹出都要有这个选项**，不是只有首次之后才有。
        //     所以这里永远可见（首次那 30 秒里也能先勾上，勾完照样要等满 30 秒才让关）。
        final CheckBox cb = new CheckBox(act);
        cb.setText("未来都不弹出此窗口");
        cb.setTextSize(12.5f);
        cb.setTextColor(fg);
        cb.setChecked(false);
        cb.setVisibility(View.VISIBLE);
        box.addView(cb);

        // ---- ★ 2026-10-10 第 3 条：网址 / QQ / 邮箱的可复制小按键
        box.addView(copyRow(act));

        // ---- 关闭按钮
        final Button close = new Button(act);
        close.setAllCaps(false);
        close.setTextSize(14f);
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(acc);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * d));
        blp.setMargins(0, (int) (8 * d), 0, 0);
        box.addView(close, blp);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(box)
                .setCancelable(false)          // 首次不给点外面关；下面用倒计时决定按钮
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        }

        final CountDownTimer[] timerRef = new CountDownTimer[1];

        final Runnable dismissNow = () -> {
            try { if (timerRef[0] != null) timerRef[0].cancel(); } catch (Throwable ignore) { }
            SharedPreferences.Editor e = sp(act).edit();
            e.putBoolean(K_SEEN, true);
            if (cb.isChecked()) e.putBoolean(K_NEVER, true);
            e.apply();
            showing = false;
            try { dlg.dismiss(); } catch (Throwable ignore) { }
            if (onClosed != null) onClosed.run();
        };

        if (first) {
            close.setEnabled(false);
            close.setAlpha(0.55f);
            close.setText("请先阅读（" + FIRST_LOCK_SEC + " 秒后可关闭）");
            // 首次：关不掉，用户只能等
            dlg.setCancelable(false);
            timerRef[0] = new CountDownTimer(FIRST_LOCK_SEC * 1000L, 1000L) {
                @Override public void onTick(long ms) {
                    int left = (int) Math.ceil(ms / 1000.0);
                    count.setText("⏳ 首次使用需停留阅读：" + left + " 秒后才能关闭");
                    close.setText("请先阅读（" + left + " 秒后可关闭）");
                }
                @Override public void onFinish() {
                    count.setText("✔ 已可关闭。以后打开不再有这个时间限制。");
                    count.setTextColor(Color.parseColor("#1B7A4B"));
                    close.setEnabled(true);
                    close.setAlpha(1f);
                    close.setText("我已阅读完毕，关闭");
                }
            }.start();
        } else {
            close.setText("关闭");
            dlg.setCancelable(true);
            dlg.setCanceledOnTouchOutside(true);
        }

        close.setOnClickListener(v -> dismissNow.run());
        dlg.setOnCancelListener(dlg2 -> {
            // 非首次才可能走到这（首次 setCancelable(false)）
            SharedPreferences.Editor e = sp(act).edit().putBoolean(K_SEEN, true);
            if (cb.isChecked()) e.putBoolean(K_NEVER, true);
            e.apply();
            showing = false;
            if (onClosed != null) onClosed.run();
        });

        try {
            dlg.show();
        } catch (Throwable t) {
            showing = false;
            if (onClosed != null) onClosed.run();
        }
    }

    // ==================================================================
    //  ★ 2026-10-10 第 3 条：网址 / QQ / 邮箱的可复制小按键
    // ==================================================================

    /**
     * 可复制小按键。留言弹窗和侧边栏「💬 开发者留言」共用同一份，
     * 保证两处看到的东西、点到的东西完全一样。
     *
     * <p>★ 2026-10-17：从四个键（github / gitee / QQ 号 / 邮箱）减到**一个**
     *   —— 留言正文里现在只剩 QQ 这一个联系方式（见 {@link #MESSAGE}），
     *   留着一个已经不在正文里出现的仓库地址按键，比删掉更让人困惑。
     */
    public static LinearLayout copyRow(final Activity act) {
        float d = act.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (2 * d), 0, 0);
        box.addView(chipRow(act, new String[][]{
                {"\uD83D\uDCCB QQ 号", QQ}}));
        return box;
    }

    private static LinearLayout chipRow(Activity act, String[][] items) {
        float d = act.getResources().getDisplayMetrics().density;
        int acc = AppPrefs.accent(act);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] it : items) {
            Button b = new Button(act);
            b.setText(it[0]);
            b.setTextSize(11.5f);
            /* ★ 用"主色的 18% 淡底 + 主色字"，别用实心主色 ——
             *   装机第一版四颗复制键跟底下那颗「关闭」长得一模一样（都是实心绿），
             *   一屏里五块一样重的绿，用户根本分不清哪个是主要动作。
             *   复制键是**次要**动作，视觉上就得轻一档。 */
            b.setTextColor(acc);
            b.setAllCaps(false);
            // 系统 Button 自带一个很大的最小尺寸，不压下去这四个会变成四条横杠
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding((int) (4 * d), (int) (3 * d), (int) (4 * d), (int) (3 * d));
            GradientDrawable g = new GradientDrawable();
            g.setColor(AppPrefs.alpha(acc, 0.18f));
            g.setStroke((int) Math.max(1, d), AppPrefs.alpha(acc, 0.55f));
            g.setCornerRadius(9 * d);
            b.setBackground(g);
            final String what = it[0].replace("\uD83D\uDCCB ", "");
            final String val = it[1];
            b.setOnClickListener(v -> copy(act, what, val));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
            row.addView(b, lp);
        }
        return row;
    }

    /** 复制到剪贴板 + 一句明确的回执（不弹 Toast 用户会以为没生效，然后又手抄一遍） */
    public static void copy(Activity act, String what, String value) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(what, value));
            android.widget.Toast.makeText(act, "已复制 " + what + "：" + value,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(act, "复制失败，请手动记：" + value,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static volatile boolean showing = false;
}
