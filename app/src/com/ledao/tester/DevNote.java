package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
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
 * <p>★ 2026-10-21（用户第 5 次改留言）：正文换回**长版**（获取渠道 + 匿名开源 + 河北工程大学
 *   + 做它的初衷 + 还是要锻炼 + 删库跑路 + 报错发邮箱），末尾加一句
 *   「开发不易，可赏一两块回回本」，收款码（{@link #TIP_QR}）**同时**出现在
 *   · 留言弹窗正文末尾（小图，跟那句话贴在一起）
 *   · 侧边栏「💬 开发者留言」最下面（{@link #tipBlock}，大图 + 一句提示）
 *   上一版（2026-10-17）那句「此版本为正式版…请联系 QQ」与「删掉打赏」的改动一并作废。
 *
 * 文案本身放在 {@link #MESSAGE}，要改只改那一处。
 */
public final class DevNote {

    private DevNote() { }

    /** 首次必须停留的秒数 */
    private static final int FIRST_LOCK_SEC = 30;

    // ------------------------------------------------------------------ 联系方式
    /**
     * 留言里的**四个**联系方式：github / gitee / QQ 号 / 邮箱。
     *
     * <p>为什么是常量而不是在正文里写字面量：{@link Err#advise} 也要用它告诉用户
     *   "把日志发到哪"（见 {@link #QQ}）—— 同一个值只能有一份，别处不许再写一遍。
     *
     * <p>为什么还要那四颗可复制小按键（{@link #copyRow}）：普通用户不会在弹窗正文里
     *   长按拖选，要么抄错一位、要么干脆放弃。而这两串地址是**唯一的官方渠道**，
     *   抄错就意味着装到来路不明的包 —— 那正是留言第二段在警告的事。
     */
    public static final String GITHUB = "https://github.com/BAB2007/yuda-run-helper";
    public static final String GITEE  = "https://gitee.com/BAB2007/yuda-run-helper";
    public static final String QQ     = "3855962053";
    public static final String EMAIL  = "3855962053@qq.com";

    /**
     * 开发者留言正文（用户 2026-10-28 给的原文，**逐字照抄**，只把网址/QQ/邮箱换成上面的常量，
     * 并把原文里两处半角逗号/括号按中文排版改成全角）。
     *
     * <p>九段，顺序就是用户给的顺序：
     *   ① 今天（26-10-28）去查跑步记录，才知道筛查是人工的 —— 软件只处理了步频、陀螺仪、
     *      路线，而人脸是**整张照片**直接存在服务端的，所以照片一定要是户外的；
     *   ② 这版不是最终版了，还有很多东西要改；更新了会通知你，装不装由你；尽量只在本校传播；
     *   ③ 获取渠道（gitee / github / QQ 小号）；
     *   ④ 匿名开源、别去找作者；
     *   ⑤ 河北工程大学在读，不会害校友；
     *   ⑥ 邯郸的冬天太冷（校园跑不合时宜）；
     *   ⑦ 存续与更新的决定权在作者，不可抗力就删库；
     *   ⑧ 报错多次重试仍不行 → 复制日志发邮箱；
     *   ⑨ 打赏（正文末尾那张小收款码就是给这句话配的；用户这次没提，仍保留）。
     *
     * <p>历史沿革（别再改回去，也别把哪一段当成"多余的"删掉）：
     *   ① 2026-10-10 删掉第一段里质问代跑定价的括号；
     *   ② 2026-10-10 倒数第三段末尾加「大家」；
     *   ③ 2026-10-11 那句警告里去掉「手机变砖，系统被入侵」；
     *   ④ 2026-10-17 整篇缩成三句「此版本为正式版…」（并删掉打赏）；
     *   ⑤ 2026-10-21 换回长版 + 打赏回来；
     *   ⑥ 2026-10-21 第 2 轮：开头加一句「用了的尽量加煮波QQ小号…」（v1.0.38）；
     *   ⑦ 2026-10-22 第 7 次：用户把第一句换成「用的话尽量加煮波QQ，后续软件可能会存在更新」
     *      （v1.0.41）；
     *   ⑧ 2026-10-26（v1.0.54）第 8 次：**整篇换成新公告** —— 第一段不再是求加 QQ，
     *      改成「今天已测试一个月 / 这算是最终版 / 以后更新了也会通知你」；
     *      获取渠道那句 gitee 提到前面；「一次1块」改「一次一块」，
     *      「自助烧烤」改「自助烤肉」，「掏个脸」改「扫个脸」。
     *      ★ 打赏那句**保留**（收款码还挂着，用户没让删）。
     *   ⑨ 2026-10-28（v1.0.55）第 9 次：**整篇再换一次** —— 第一段改成「28 号查跑步记录，
     *      才知道人工筛查、人脸照片是整张存下来的，照片一定要是户外的」；
     *      「这算是最终版」改「并非是最终版」，加「尽量只在本校同学间传播就好」；
     *      删掉非官方渠道那段免责声明、删掉代跑太贵/自助烤肉那段、「锻炼还是必要的」那段；
     *      新增「冬天校园跑真的太不和时宜了，邯郸过些天下雪…手冻得无知觉」。
     *      ★ 打赏那句仍然保留。
     */
    public static final String MESSAGE =
            "今天是26-10-28，28号查跑步记录来着，据我所知，筛查是人工筛的，"
          + "这个软件已经处理好了步频，陀螺仪和路线。"
          + "但是我以为人脸照片识别通过以后只会保存一个True或者False之类的，"
          + "然后也没强调要每次换照片（我几乎也没咋换），"
          + "但实际上直接把整张照片存下来了，然后再人工筛查我没想到。"
          + "照片一定要是户外的，不然也不行。\n\n"
          + "当前版本并非是最终版了，看来还有很多东西需要改进。"
          + "如果软件更新，也没关系，你会收到更新通知并决定是否更新。\n"
          + "尽量只在本校同学间传播就好。\n\n"
          + "请在gitee(" + GITEE + ")，github(" + GITHUB + ")"
          + "或通过本人QQ小号(" + QQ + ")进行获取。\n\n"
          + "煮波匿名开源，请不要试图找到煮波，如果你知道了煮波的一些信息，也不必在意，"
          + "无视就好，因为煮波也只是一个普通的学生，没有什么特殊的。\n\n"
          + "煮波也是河北工程大学的学生，所以大可不必担心我做出损害校友利益的事。\n\n"
          + "冬天校园跑真的太不和时宜了，邯郸过些天下雪出去吃个饭都能给手冻得无知觉了。\n\n"
          + "本人拥此软件是否存活以及是否更新的决策权，"
          + "如果煮波受到不可抗拒因素的影响，会果断删库跑路。\n\n"
          + "如遇到软件报错多次重试后仍无法使用，请点击复制日志，"
          + "并将日志信息发送至" + EMAIL + "\n\n"
          + "开发不易，可赏一两块回回本";

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

        // ---- 正文（可滚动：正文 + 末尾那张小收款码）
        TextView body = new TextView(act);
        body.setText(MESSAGE);
        body.setTextSize(13f);
        body.setTextColor(fg);
        body.setLineSpacing(3 * d, 1.15f);

        LinearLayout bodyBox = new LinearLayout(act);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        bodyBox.addView(body);
        /* ★ 2026-10-21：正文最后一句是「开发不易，可赏一两块回回本」，
         *   收款码就贴在它下面 —— 图放在 ScrollView **里面**，
         *   外面那圈高度（38% 屏高）一点没变，勾选框和关闭按钮还在原位。 */
        ImageView qrInDialog = qrView(act, 150);
        if (qrInDialog != null) {
            bodyBox.addView(qrInDialog);
            TextView qrHint = new TextView(act);
            qrHint.setText("支付宝扫这个码就行（不扫也完全不影响功能）");
            qrHint.setTextSize(11.5f);
            qrHint.setTextColor(AppPrefs.alpha(fg, 0.75f));
            qrHint.setGravity(Gravity.CENTER_HORIZONTAL);
            qrHint.setPadding(0, (int) (6 * d), 0, (int) (4 * d));
            bodyBox.addView(qrHint);
        }

        ScrollView sc = new ScrollView(act);
        sc.addView(bodyBox);
        sc.setPadding(0, (int) (4 * d), 0, (int) (8 * d));
        // 留言卡片最多占屏幕 38% 高 —— 下面还有勾选框、四个复制小按键和关闭按钮，
        // 小屏（720×1280）也要保证「复制邮箱」和「关闭」都露在屏幕里。
        int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.38);
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

        // ---- ★ 2026-10-10 第 3 条：网址 / QQ / 邮箱的可复制小按键（两行两列）
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
     * 四个「复制」小按键，两行两列。留言弹窗和侧边栏「💬 开发者留言」共用同一份，
     * 保证两处看到的东西、点到的东西完全一样。
     *
     * <p>★ 2026-10-21：正文里四个联系方式都回来了（见 {@link #MESSAGE} 第二段与第八段），
     *   所以这一行也从「只剩 QQ 一颗」恢复成四颗。
     */
    public static LinearLayout copyRow(final Activity act) {
        float d = act.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (2 * d), 0, 0);
        box.addView(chipRow(act, new String[][]{
                {"\uD83D\uDCCB github", GITHUB},
                {"\uD83D\uDCCB gitee", GITEE}}));
        box.addView(chipRow(act, new String[][]{
                {"\uD83D\uDCCB QQ 号", QQ},
                {"\uD83D\uDCCB 邮箱", EMAIL}}));
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

    // ==================================================================
    //  ★ 2026-10-21：收款码（弹窗正文末尾 + 侧边栏最下面，两处都用这一张）
    // ==================================================================

    /** 收款码旁边那句话（也就是留言最后那一句的短版） */
    public static final String TIP_TEXT =
            "开发不易，可赏一两块回回本";

    /**
     * 收款码图片：`assets/pay_qr.png`。
     *
     * 从用户发来的支付宝收款海报里**只裁剪、不缩放**抠出来的（660×749）——
     * 二维码的模块还是原像素，屏幕上放大一点也不影响扫。
     * 四角那点蓝色圆角背景已经用 `work/pay/` 里那段脚本刷成白色，
     * 不然贴到深色主题上会看见四个蓝角。
     */
    public static final String TIP_QR = "pay_qr.png";

    /**
     * 读 assets 里那张收款码，按 {@code dpW} 宽等比放进 ImageView。
     * 读不出来（assets 缺了 / 解码失败）返回 null —— 调用方跳过就行，不能因此崩。
     */
    public static ImageView qrView(final Activity act, int dpW) {
        try {
            java.io.InputStream in = act.getAssets().open(TIP_QR);
            Bitmap bm = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
            if (bm == null) return null;
            ImageView iv = new ImageView(act);
            iv.setImageBitmap(bm);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            float d = act.getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (dpW * d), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, (int) (8 * d), 0, 0);
            iv.setLayoutParams(lp);
            return iv;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 「打赏」那一块：一句话 + 收款码。挂在侧边栏「💬 开发者留言」的**最后**。
     *
     * <p>为什么弹窗和侧边栏都要有：弹窗那边可能被勾成「未来都不弹出」，
     *   之后就只剩侧边栏这一条路；反过来，第一次进软件的人先看到的是弹窗。
     *   两处用的是同一张图、同一句话（{@link #TIP_QR} / {@link #TIP_TEXT}）。
     */
    public static View tipBlock(final Activity act) {
        float d = act.getResources().getDisplayMetrics().density;
        int fg = AppPrefs.fg(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, (int) (12 * d), 0, 0);

        TextView t = new TextView(act);
        t.setText(TIP_TEXT);
        t.setTextSize(12.5f);
        t.setTextColor(fg);
        t.setLineSpacing(3 * d, 1.15f);
        box.addView(t);

        ImageView iv = qrView(act, 230);
        if (iv != null) {
            box.addView(iv);

            TextView hint = new TextView(act);
            hint.setText("支付宝扫这个码就行（另一个手机扫、或者截图下来扫都可以）；"
                    + "不扫也完全没关系，功能一个不少。");
            hint.setTextSize(11.5f);
            hint.setTextColor(AppPrefs.alpha(fg, 0.75f));
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(0, (int) (6 * d), 0, 0);
            box.addView(hint);
        }
        return box;
    }
}
