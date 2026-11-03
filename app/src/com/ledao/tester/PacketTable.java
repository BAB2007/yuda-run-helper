package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * PacketTable —— 首页右上角那颗「···」点开的**数据发送对照表**。
 *
 * <h3>它要回答的问题</h3>
 * 「这个助手到底往服务器发了什么？和步道乐跑自己发的一样吗？」——
 * 用户 2026-11-03 原话：「点击以后有个表，两边分别是步道和助手发的数据种类名称，紧凑点」。
 * 所以这里**只列数据种类、不列参数值**：一场跑步按时间顺序分成
 * 「起跑前 / 跑中 / 收尾」三段，左边写步道乐跑发不发、右边写助手发不发，
 * 一行一行对着看。手机屏幕窄，三列刚好，再多就挤了。
 *
 * <h3>每一行的依据（不是"大体上差不多"，都是实证）</h3>
 * <ul>
 *   <li>起跑前六行：{@code User/User}、{@code Login/RefreshToken}、
 *       {@code Run/GetAllZone}、{@code Run/GetZonePoints}、{@code Run/getTimestampV278}、
 *       {@code Run2/beforeRunV260}、{@code Run/setRunZone} —— 真机那几包见 docs/20 §二、
 *       docs/28、docs/31，助手这边的调用点见 {@link Ledao} 的 {@code call("…")}；</li>
 *   <li>「人脸照片 ①/②」：真机 {@code type=0} 起跑前、{@code type=2} 中途 600 m
 *       （docs/28、docs/29、docs/31、docs/86）；助手发的一模一样（docs/92）；</li>
 *   <li>「位置上报 20 秒」：真机实测 19~20 s 一拍（docs/20 §四.0 的
 *       {@code 生成打卡json数据失败} 那一串），助手也是 20 s（{@code Run/setRunLocationRecord}）；</li>
 *   <li>「打卡记录」：**没有独立请求** —— 真机是把 {@code isSignUp:true} 和两条
 *       {@code logPoints} 压在位置那一拍里（docs/12、docs/19），助手照做（docs/22）；</li>
 *   <li>「陀螺仪」：跑中 {@code Run2/gyroscope} 分段 + 收尾 {@code type=8} 文件，
 *       真机两样都发（docs/96、{@link Ledao} 3.8k 行那一段）；</li>
 *   <li>「客户端日志」：真机 {@code Report/appLog} + OSS PUT（docs/71 §三.6 #10），
 *       助手从 v1.0.39 起补上（{@link AppLog}）。</li>
 * </ul>
 *
 * <h3>为什么表里没有「只有一边发」那一段（2026-11-03 当晚删掉的）</h3>
 * 第一版最后摆了两行：广告 / 推送 / 分享 SDK、图片 / CDN —— 真机发、助手不发，
 * 想说明"我们只发和成绩有关的东西"。用户当天看完就要删：「去掉'只有一边发'部分」。
 * 理由也站得住：这张表是用来讲**同一件事两边怎么发**的，
 * 掺两行"人家有我们没有"的，读起来像是在辩解，而不是对照。
 * <p>那两条事实本身没变，仍旧记在 docs/20 §1.2：真机挂了 {@code zztfly}（广告）、
 * {@code idazhe}（上报）、{@code mob}（分享），助手一个都没接 —— 只是不再上这张表。
 *
 * <h3>口径</h3>
 * {@code ✅} = 会发。凡是两边都是 {@code ✅} 的，
 * **接口名、字段名、发送节奏都照真机抄过**，不是"发了个类似的东西"。
 * 表下面的四行小字专门说三处"看着一样、其实来源不同"的地方
 * （定位是谁读的、打卡藏在哪、人脸照片哪来的）—— 免得 {@code ✅} 被读成"连数字都是真的"。
 */
public final class PacketTable {

    private PacketTable() { }

    /** 表头三列 */
    private static final String[] HEAD = {"数据", "步道乐跑", "助手"};

    /** 三列的宽度比例（第一列放名字，留宽一点） */
    private static final float[] W = {0.42f, 0.29f, 0.29f};

    /**
     * 表体。**长度为 1 的行是分段标题**（起跑前 / 跑中 / 收尾），
     * 其余都是「数据 / 步道乐跑 / 助手」三格。
     *
     * <p>顺序就是一场跑步的时间顺序 —— 这一列排下来，读者顺手也就看懂了流程：
     * 先登记跑区、取规则、拍第一张脸，再每 20 秒报一次位置（打卡混在里面），
     * 中途拍第二张脸，最后交轨迹 / 截图 / 日志 / 成绩。
     */
    private static final String[][] ROWS = {
            {"① 起跑前"},
            {"身份 / 令牌",       "✅ 自己登录",    "✅ 读真机的"},
            {"跑区 / 打卡点表",   "✅",             "✅"},
            {"服务器时间",        "✅",             "✅"},
            {"开跑规则",          "✅",             "✅"},
            {"跑区登记",          "✅",             "✅"},
            {"人脸照片 ①",        "✅ 现场拍",      "✅ 你传的"},

            {"② 跑中"},
            {"位置上报",          "✅ 每 20 秒",    "✅ 每 20 秒"},
            {"打卡记录",          "✅ 跟位置一起",  "✅ 跟位置一起"},
            {"人脸照片 ②",        "✅ 600 m 处",    "✅ 600 m 处"},
            {"陀螺仪",            "✅ 分段传",      "✅ 分段传"},

            {"③ 收尾"},
            {"轨迹文件",          "✅",             "✅"},
            {"跑步截图",          "✅",             "✅"},
            {"客户端日志",        "✅",             "✅"},
            {"成绩单",            "✅",             "✅"},
            {"记录详情",          "✅",             "✅"},
    };

    /** 表下面那几行小字：把三处"两边都打勾、但来源不同"的地方说清楚 */
    private static final String[] NOTES = {
            "每一行右边两格都是 ✅ —— 接口名、字段名、20 秒的节奏，全照真机抄。",
            "位置：真机读 GPS，助手按路线算 —— 报文一样，数字是手机算的。",
            "打卡不单独发请求，压在位置那一拍里（isSignUp=true + 两点坐标）。",
            "人脸：真机现场拍，助手用你传的两张；type 一样（跑前 0 / 中途 2）。",
    };

    /** 点「···」走这里。全程不联网、不读用户数据，就是把这张表画出来。 */
    public static void show(final Activity act) {
        if (act == null) return;
        try {
            LinearLayout box = UiKit.col(act);
            box.setPadding(Q.dp(act, 10), Q.dp(act, 2), Q.dp(act, 10), Q.dp(act, 4));

            TextView intro = UiKit.label(act,
                    "一场跑步，两边各自发了哪些数据 · 同一行对着看", 11f);
            intro.setTextColor(Q.INK2);
            intro.setPadding(0, 0, 0, Q.dp(act, 6));
            box.addView(intro);

            LinearLayout table = UiKit.col(act);
            table.addView(headRow(act));
            boolean alt = false;
            for (String[] r : ROWS) {
                if (r.length == 1) {
                    table.addView(partRow(act, r[0]));
                    alt = false;
                } else {
                    table.addView(dataRow(act, r, alt));
                    alt = !alt;
                }
            }
            box.addView(table);

            TextView note = UiKit.label(act, join(NOTES), 10.5f);
            note.setTextColor(Q.INK2);
            note.setLineSpacing(Q.dp(act, 2), 1f);
            note.setPadding(0, Q.dp(act, 9), 0, 0);
            box.addView(note);

            ScrollView sc = new ScrollView(act);
            sc.addView(box);
            final int capH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.70f);
            sc.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, capH));

            AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle("📦 数据发送对照表")
                    .setView(sc)
                    .setPositiveButton("知道了", null)
                    .create();
            dlg.show();
            Q.style(act, dlg);       // ★ Q 版：方角系统弹窗换成圆角白卡（必须 show() 之后）

            /* ★ 去掉「只有一边发」那一段之后，表比一屏矮了 —— 别留一大块白肚子。
             *   量一次内容：比上限矮就把 ScrollView 收到内容高度（只做一次，
             *   放在 post 里是因为不 show() 就拿不到真实高度）。 */
            box.post(() -> {
                try {
                    int ch = box.getHeight();
                    if (ch > 0 && ch < capH) {
                        ViewGroup.LayoutParams lp = sc.getLayoutParams();
                        if (lp != null && lp.height != ch) {
                            lp.height = ch;
                            sc.setLayoutParams(lp);
                        }
                    }
                } catch (Throwable ignore) { }
            });
        } catch (Throwable t) {
            // 这张表只是"说明"，画不出来也绝不能拦着用户跑步
            if (act instanceof MainActivity) ((MainActivity) act).logFail("打开数据发送对照表", t);
        }
    }

    // ==================================================================
    //  表格零件
    // ==================================================================
    private static LinearLayout headRow(Activity a) {
        LinearLayout r = UiKit.row(a);
        r.setBackground(Q.roundRect(a, Q.PANEL2, 0, 8));
        for (int i = 0; i < HEAD.length; i++) {
            TextView t = cell(a, HEAD[i], Q.INK, Gravity.START, true, true);
            r.addView(t, cellLp(a, i));
        }
        return r;
    }

    /** 分段标题：整行一条奶油绿底，顶上一道间距把它和上一段分开。 */
    private static TextView partRow(Activity a, String text) {
        TextView t = UiKit.label(a, text, 10.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Q.INK);
        t.setBackground(Q.roundRect(a, Q.mix(Q.PANEL, Q.MINT, 0.30f), 0, 7));
        t.setPadding(Q.dp(a, 8), Q.dp(a, 4), Q.dp(a, 8), Q.dp(a, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Q.dp(a, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private static LinearLayout dataRow(Activity a, String[] row, boolean alt) {
        LinearLayout r = UiKit.row(a);
        // 隔行浅色：不画分割线也能一行一行看准（手机上比 1px 的线清楚）
        r.setBackgroundColor(alt ? Q.mix(Q.PANEL, Q.PANEL2, 0.45f) : Q.PANEL);
        for (int i = 0; i < row.length; i++) {
            TextView t = cell(a, row[i], Q.INK,
                    i == 0 ? Gravity.START : Gravity.CENTER_HORIZONTAL, false, i == 0);
            r.addView(t, cellLp(a, i));
        }
        return r;
    }

    /**
     * 一格。
     *
     * @param first 第一列（数据名）用主文字色、其余两列稍微淡一点 ——
     *              ✅ 本身有颜色，格子里的文字再抢眼就乱了
     */
    private static TextView cell(Activity a, String s, int color, int gravity,
                                 boolean bold, boolean first) {
        TextView t = UiKit.label(a, s, 10.5f);
        t.setTextColor(first ? color : Q.mix(color, Q.INK2, 0.45f));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(gravity);
        t.setLineSpacing(Q.dp(a, 1), 1f);
        t.setPadding(Q.dp(a, 5), Q.dp(a, 4), Q.dp(a, 5), Q.dp(a, 4));
        return t;
    }

    private static LinearLayout.LayoutParams cellLp(Activity a, int i) {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, W[i]);
    }

    private static String join(String[] lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }
}
