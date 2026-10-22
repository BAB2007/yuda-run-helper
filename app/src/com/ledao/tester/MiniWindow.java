package com.ledao.tester;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * MiniWindow —— 切走时缩到屏幕**右上角**的那个小窗（v1.0.32，docs/64）。
 *
 * <p>为什么要有它：后台运行这件事，在国产 ROM 上根本不由我们说了算。
 * 前台服务 + 唤醒锁都挂上了（{@link SniffGuard}），荣耀/华为/小米的省电策略
 * 照样能把**整个进程**冻住 —— 通知还在、看着像在跑，其实一条指令都不执行
 * （v1.0.30 定案的那件事，见 docs/62）。
 *
 * <p>那就不跟它抢"后台"了：**别让助手进后台**。
 * 用一层 {@code TYPE_APPLICATION_OVERLAY} 悬浮窗（用户可见的小窗）把自己
 * 一直挂在屏幕上，系统眼里这个进程始终"有可见窗口"，优先级远高于被冻结
 * 的那一档（cached）—— 这是普通 App 唯一能自己掌控的那层保护，
 * 而且**不需要 root、不需要各家 ROM 的白名单**，只要一次「悬浮窗」权限，
 * Android 6 以上所有机器都有这一项。
 *
 * <p>顺带还解决了两件事：
 * <ul>
 *   <li><b>看得见</b>：切到别的 App 之后，里程 / 用时 / 配速 / 打卡数就在右上角。
 *       数字还在动 = 进程还活着；数字停住了 = 就在这一刻被冻了 ——
 *       恢复之后第三行会多一个「❄卡12s」，把"刚才卡了几秒"写出来。
 *       <br>★★ v1.0.34：标题那句话是**「正在跑 04:00」**，不再写"卡了"。
 *       用户 2026-10-11 拿真机截图来问「它跑步正常，但是却显示卡了」——
 *       那一拍只是慢了不到 6 秒（数字一个没少），把"助手歇了一下"说成"出事了"，
 *       是描述错了，不是系统真冻了（docs/67）。卡住的痕迹现在只在第三行，
 *       而且只写成一个记号。</li>
 *   <li><b>停得掉</b>：不用切回助手就能停（要按两下，防口袋误触）。</li>
 * </ul>
 *
 * <p>★★★ v1.0.33（docs/65）：**没在跑的时候它也挂着**，显示的是
 * 「未开跑 / 学期目标 N 次 已完成 M 次」，最下面一行是当前时间 ——
 * 那一行的秒在跳，就是"助手还活着"的目视判据（被冻过会写成「❄ 卡12s」）。
 * <p>为什么没跑也要挂：这一版把「🔋 后台运行设置」那块整个删掉了 ——
 * 后台这件事从此只有一条路，就是"别让自己进后台"。窗口一直挂着，
 * 系统眼里这个进程就一直有可见窗口（用户去步道乐跑那边操作、回来接着点开跑，
 * 中间那段时间助手不会被顺手清掉）。它不额外耗电：一秒刷一个 TextView。
 *
 * <p>它**不是**万能的，两种情况仍然要靠别的手段：
 * <ol>
 *   <li>息屏：屏幕一黑，窗口就不算"可见"了 —— 那一段仍然靠
 *       {@code PARTIAL_WAKE_LOCK} + 前台服务（v1.0.25/30 已经做了）；</li>
 *   <li>个别 ROM 连悬浮窗一起冻 —— 那就只剩系统自己那层「自启动 / 后台活动」，
 *       入口在「🪟 迷你小窗」向导的最后一个按钮里。</li>
 * </ol>
 *
 * <p>本类里的格式化 / 判定函数全是**纯函数**（不碰任何 Android API），
 * 好在 {@code work/looptest/MiniWindowTest.java} 里逐条断言。
 */
public final class MiniWindow {

    private MiniWindow() { }

    /** 窗口标题 —— {@code dumpsys window} 里能直接搜到它（真机验证用） */
    public static final String WIN_TAG = "ledao-mini";

    /**
     * 小窗宽度（dp）—— 这是**下限**，真宽度由 {@link #windowWidth} 按这一屏最长的字量出来。
     *
     * <p>★★ v1.0.34：176 → 200。起因是用户发来一张真机截图问"为什么显示卡了"——
     * 量的过程中发现两件事：标题被单行剪切（「❄ 04:00（卡了 1」），
     * 而且**用户点名要的那行学期数也是被切掉的**（那台机器上要 27.5 个数，
     * 176dp 只能放 26 个）。所以这一版不再写死：宽度按最长那行量出来，
     * 下限 200dp（比原来的 176 宽一点，视觉上不再挤），上限 {@link #MAX_DP}。
     */
    private static final int W_DP = 200;

    /** 小窗宽度上限（dp）—— 系统字体再大也不许糊住小半个屏幕（200dp 的 1.7 倍） */
    private static final int MAX_DP = 340;

    /** 距离屏幕右上角的初始间距（dp） */
    private static final int MARGIN_DP = 6;
    private static final int TOP_DP = 44;

    /**
     * 上一拍隔了多久才算"卡住了一下"（毫秒）—— **「没在跑」那一屏用的**（心跳 1 秒一拍）。
     *
     * <p>跑步那一屏不要用这个数，用 {@link #staleSecIn}（见下面的说明）。
     *
     * <p>v1.0.34 从 4000 提到 6000：真机（荣耀 X80）上 4 秒会误报 —— 主线程被 GC
     * 或者窗口操作占住 4 秒多，用户看到的字一个没少，却挂着个 ❄。
     */
    public static final long STALE_MS = 6000;

    /**
     * ★★★ v1.0.36（2026-10-21）：跑步那一屏判断"卡住"的**计划节拍**（毫秒）。
     *
     * <p>为什么突然要这个数：用户拿真机截图来问「为什么每次小窗都会显示卡五秒」。
     * 查下来是**阈值比错了标尺** —— {@link #STALE_MS} 是按"心跳 1 秒一拍"定的，
     * 而跑步那一屏的 {@code tickRun} 是跟着**跑步节拍**来的：{@code Ledao} 每 **6 秒**
     * 一拍（协议侧就是 6 秒一个点，见 {@code MainActivity} 里 {@code L.run(..., 6.0, ...)}）。
     * 于是正常间隔本来就贴着 6000 ms，网络抖个几十毫秒就"超阈值"，
     * {@code staleSec} 再减掉那 1 秒，报出来**永远是「卡5s」** —— 一句完全的假话。
     *
     * <p>现在跑步那一屏改成"计划节拍 + 余量"：只有比计划多出 {@link #RUN_STALE_MARGIN_MS}
     * 以上才记账，报出来的秒数也是**超出计划的真实秒数**。
     */
    private static volatile long tickMs = 6000;

    /** 跑步那一屏的余量：6 秒的计划之外再多 4 秒才算"卡"（网络慢一拍不算） */
    public static final long RUN_STALE_MARGIN_MS = 4000;

    /**
     * 把这一场跑步的节拍告诉小窗（由 {@code MainActivity} 在开跑那一刻调）。
     * 小于 1 秒的值一律按 1 秒记 —— 节拍再快，容错也不能没有。
     */
    public static void setTickSec(double sec) {
        tickMs = Math.max(1000L, (long) (sec * 1000.0));
        /* ★ v1.0.36：新的一场开始 —— 上一场留下的「卡Ns」不许带进来
         *   （staleMarker 是静态的，不清就会让新一场开局就挂个记号）。 */
        staleMarker = 0;
        staleUntil = 0;
    }

    /** 当前认为的跑步节拍（毫秒）—— 单测与日志用 */
    public static long tickMs() {
        return tickMs;
    }


    /** 卡住的痕迹在标题上留多久（毫秒）—— 30 秒太短，人切回来已经没了 */
    public static final long STALE_KEEP_MS = 60000;

    /** 显示的卡住秒数上限 —— 再长就写不下了，而且具体多少已经不重要（见 {@link #staleShort}） */
    public static final long STALE_MAX_SEC = 999;

    /**
     * ★★★ v1.0.34：小窗**任何一行**最多能画多长（单位＝一个半角数字的宽度）。
     *
     * <p>为什么要有这个数：小窗是固定 176dp 宽，文字却是**单行**（{@code singleLine}），
     * 画不下就从右边切掉。v1.0.33 真机上用户就看到过被切一半的标题
     * 「{@code ❄ 04:00（卡了 1}」—— 秒数没了，只留下一个残缺的"1"，看着像负号
     * （docs/67）。窗口小、系统字体一大就更容易切，所以"一行最多几个字"必须是
     * 一条可断言的规则，而不是"大概能放下"。
     *
     * <p>30 这个数是**量出来的**，不是估的：拿用户那张荣耀截图逐列量小窗里的墨迹 ——
     * 176dp 宽时标题从 x≈473 画到 x≈775（那头正是"卡了 12"的"2"，后面就被切了），
     * 而标题右边留给三个按钮的地方是算得出来的（176dp − 左右内边距 − 三个键 − 间距）。
     * 反推出来：那台机器上一行放得下 26 个数。窗口 v1.0.34 加宽到 200dp 之后，
     * 一行就是 30 个数（多加的 24dp 里，扣掉内边距，正好给 4 个数）。
     *
     * <p>{@link #width} 那把尺子是按"汉字 2 个数"算的，对"次""卡"这种字偏保守，
     * 所以上限取整（宁可放过一点，也别把本来放得下的字删掉）。
     */
    public static final int MAX_W = 30;

    /** 那个「停」要按两下的时间窗（毫秒） */
    public static final long STOP_ARM_MS = 3000;

    /** 没在跑的时候，标题就这两个字（用户点名要的原文，别改写） */
    public static final String IDLE_TITLE = "未开跑";

    /**
     * ★ v1.0.34：学期数还没取到时，标题改挂这一句。
     *
     * <p>为什么从第三行挪到标题上来：原来挂在时间那一行（「12:34:05 · 学期数还没取到」），
     * 但那一行又要走秒、又可能带「❄ 卡45s」，加起来 25 个数 —— 超出 {@link #MAX_W}，
     * 于是那句话被整句丢掉，用户反而什么都看不到。挪到标题以后，
     * 标题本来就短（「未开跑」才 6 个数），加这一句 17 个数也放得下。
     *
     * <p>用词故意含糊一点（「还没取到数」而不是「学期数」）：标题那行的宽度
     * 不值得为两个字再赌一次。个数在第二行，用户对着看就明白。
     */
    public static final String IDLE_TITLE_NO_TERM = "未开跑·数没取到";

    /** 小窗自己那一秒一跳的心跳间隔（毫秒）—— 只在"没在跑"时用它走时钟 */
    public static final long BEAT_MS = 1000;

    // ==================================================================
    //  一、纯逻辑（离线单测）
    // ==================================================================

    /** 秒 → {@code 12:34} / {@code 1:02:03}；负数当 0 */
    public static String clock(long secs) {
        if (secs < 0) secs = 0;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** 第二行：{@code 1.82 / 2.20 km}（左=上报里程，右=路线进度） */
    public static String kmText(double validM, double totalM) {
        return String.format(Locale.US, "%.2f / %.2f km", validM / 1000.0, totalM / 1000.0);
    }

    /**
     * 第三行左半：配速。
     *
     * <p>里程太少时（&lt;50 米）不显示配速 —— 头几秒除以一个接近 0 的里程，
     * 会算出三位数分钟的配速（300 分/公里那种），用户会以为软件坏了。
     */
    public static String paceText(long secs, double validM) {
        if (validM < 50 || secs < 5) return "配速 —";
        long spk = Math.round(secs / (validM / 1000.0));
        return String.format(Locale.US, "配速 %d'%02d\"", spk / 60, spk % 60);
    }

    /** 第三行右半：{@code 打卡 3} */
    public static String hitText(int hit) {
        return "打卡 " + Math.max(0, hit);
    }

    /**
     * 上一拍到这一拍之间，"多出来"的秒数（0 = 没卡住）。
     *
     * <p>为什么减 1 秒：正常节拍本身就占 1 秒，超过 {@link #STALE_MS} 的那一段里
     * 有 1 秒是本来就该走的 —— 不减就把每次小抖动都算成冻结，数字会虚高。
     */
    public static long staleSec(long nowMs, long lastMs) {
        if (lastMs <= 0) return 0;
        long over = nowMs - lastMs;
        if (over <= STALE_MS) return 0;
        return Math.max(1, (over - 1000) / 1000);
    }

    /**
     * ★★★ v1.0.36：跑步那一屏的"卡住"判据 —— 比的是**计划节拍**，不是 1 秒心跳。
     *
     * <p>为什么必须换尺子：跑步每一拍的计划间隔就是 6 秒（{@code Ledao} 的
     * {@code intervalSec}），拿 6 秒阈值去卡 6 秒的计划间隔，正常一拍就已经贴着线，
     * 网络抖几十毫秒就报「卡5s」—— 用户看到的假话就是这么来的。
     *
     * <p>现在的口径：{@code 实际间隔 − 计划节拍} 才是"多出来的"，多出
     * {@link #RUN_STALE_MARGIN_MS} 以上才记账；报出来的也是这个**真实超出量**。
     * 真被冻 30 秒 → 报「卡30s」；网络慢一拍多花 3 秒 → 不吭声（那本来就不算冻）。
     *
     * @param planMs 这一场的计划节拍（{@link #setTickSec} 设进来的）
     */
    public static long staleSecIn(long nowMs, long lastMs, long planMs) {
        if (lastMs <= 0) return 0;
        long plan = planMs > 0 ? planMs : tickMs;
        long over = nowMs - lastMs - plan;
        if (over <= RUN_STALE_MARGIN_MS) return 0;
        return Math.max(1, over / 1000);
    }

    /** 「停」第一次点只是"上膛"，{@link #STOP_ARM_MS} 之内再点一次才算真的停 */
    public static boolean stopArmed(long lastTapMs, long nowMs) {
        return lastTapMs > 0 && nowMs - lastTapMs <= STOP_ARM_MS;
    }

    /**
     * ★ v1.0.34：一个字符串画出来有多宽（单位＝一个半角数字的宽度）。
     *
     * <p>不是精确排版，只是"够不够放"的尺子：汉字按 2 个数算，ASCII 按 1 个算，
     * 空格按半个。要断言的是"最长的那种情况也别顶到边"，所以宁可估宽一点。
     *
     * <p>例外是间隔号 {@code ·}：它是**窄**的标点（真机上量出来 10 像素，
     * 跟破折号一个量级），按 1 个数算 —— 这条是把真机截图量出来的宽度反推回去
     * 核对时发现的：算 2 个数的话「学期目标 65 次·已完成 12 次」会显示成 27.5，
     * 比量出来的可用宽度还宽，可它在真机上明明放得下。
     */
    public static double width(String s) {
        if (s == null) return 0;
        double w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '\uD800' && ch <= '\uDBFF' && i + 1 < s.length()) {
                /* ★ 代理对（🏃 🌨 这些表情）：两个 char 算**一个**字，按 1.5 个数 ——
                 *   真机上量过，它比一个汉字窄（🏃 正在跑 12:34 那行 250px 里图标约占 34px）。
                 *   按 2 算会把"其实放得下"的标题误判成放不下（第一版就这样，图标白让了）。 */
                w += 1.5;
                i++;
                continue;
            }
            if (ch == ' ') w += 0.5;
            else if (ch < 128) w += 1;
            else if (ch == '·' || ch == '—') w += 1;      // 窄标点
            else w += 2;
        }
        return w;
    }

    /** 这一行会不会被单行剪切掉（见 {@link #MAX_W}） */
    public static boolean fits(String s) {
        return width(s) <= MAX_W;
    }

    /** 跑步中，标题前面就是这五个字（v1.0.34 用户点名要的说法，别改写） */
    public static final String RUN_TITLE = "正在跑";

    /**
     * 跑步中标题那行怎么写。
     *
     * <p>★★ v1.0.34：**这一行不再说"卡了几秒"**。用户 2026-10-11 拿着真机截图来问
     * 「它跑步正常，但是却显示卡了」—— 那一刻进程只慢了不到 6 秒（够触发阈值，
     * 但数字一个没少），标题却挂着一句"卡了"，等于把"助手歇了一下"说成了"出事了"。
     * 用户要的说法是「正在跑」：跑的时候这一行就该是「正在跑 04:00」。
     * 卡住这件事挪到 {@link #staleMark}，由第三行去说 ——
     * 那一行本来就写着配速和打卡，加个记号不挤，而且不抢"我正在跑"这句话的位置。
     */
    public static String titleText(long secs, long staleMarkerSec) {
        String end = RUN_TITLE + " " + clock(secs);
        String full = (staleMarkerSec > 0 ? "❄ " : "🏃 ") + end;
        /* ★ 图标只在"带上它仍然放得下"的时候才挂。阈值用的是真机量出来的那个宽度
         *   （那台机器上第一行那格 298px，见 MiniWindowTest M10p3），换算成尺子上的数
         *   约 14 个 —— 跑满九个多小时的罕见情况就让图标走，报文一个字都不能少。 */
        return width(full) <= 14.5 ? full : end;
    }

    /**
     * ★ v1.0.34：卡住的那个记号（短写法 + 封顶）。
     *
     * <p>为什么必须短：这一行是"配速 … · 打卡 N"，它本来就在 176dp 的窗口里贴着边，
     * 再挂一句「卡了 12 秒」就把打卡数挤出屏幕了。真机上量过：
     * 窗口 200dp 时这一格能放 427px，「配速 6 分 54 秒 · 打卡 3」占 307px，
     * 加上「卡12s」（约 90px）刚好放得下。
     *
     * <p>封顶到 {@link #STALE_MAX_SEC} 秒：卡 20 分钟和卡 1 小时对用户是同一件事，
     * 而"再长"只会把这一行撑爆。
     */
    public static String staleShort(long secs) {
        if (secs <= 0) return "";
        if (secs > STALE_MAX_SEC) return "❄卡" + STALE_MAX_SEC + "s+";
        return "❄卡" + secs + "s";
    }

    /** 第三行那个记号（「配速 … · 打卡 N ❄卡12s」里的尾巴）；没卡过就是空串 */
    public static String staleMark(long secs) {
        String s = staleShort(secs);
        return s.isEmpty() ? "" : " " + s;
    }

    /**
     * 墙上时钟：毫秒 → {@code 12:34:05}（本地时区，秒一定带 —— 秒在跳才是"活着"的判据）。
     *
     * <p>纯函数、可离线断言：只用加减乘除取模，不碰 {@code Calendar} / 时区表，
     * 所以时区怎么变都只影响那几个小时的偏移，不会算出 25 点。
     */
    public static String dayClock(long nowMs) {
        long secOfDay = Math.floorDiv(nowMs, 1000L) % 86400L;
        if (secOfDay < 0) secOfDay += 86400L;
        // 本机时区偏移（分钟）—— 单独取，免得把"秒"也算进去
        int offMin = java.util.TimeZone.getDefault()
                .getOffset(nowMs) / 60000;
        long local = (secOfDay + offMin * 60L) % 86400L;
        if (local < 0) local += 86400L;
        return String.format(Locale.US, "%02d:%02d:%02d",
                local / 3600, (local % 3600) / 60, local % 60);
    }

    /**
     * 「没在跑」那一种状态的第三行：当前时间（+ 被冻过的痕迹）。
     *
     * <p>为什么要一行时间：切走之后用户唯一能看到的判据就是"它还在不在动"。
     * 秒不跳了 = 这一刻被系统冻住了；恢复之后这里会写「❄ 卡12s」。
     *
     * <p>★ v1.0.34：卡住那段也用 {@link #staleShort} 的短写法（「卡12s」）——
     * 这一行本来最长（还要挂「学期数还没取到」），用长写法同样会被切掉尾巴。
     */
    public static String idleClock(long nowMs, long staleMarkerSec) {
        String c = dayClock(nowMs);
        String m = staleShort(staleMarkerSec);
        return m.isEmpty() ? c : c + " " + m;
    }

    /**
     * 同上，外加"学期次数还没取到"这句实话。
     *
     * <p>为什么要说一句：第二行只能画破折号（{@link #termText}），
     * 不解释一句，用户会以为软件坏了（这条是 v1.0.33 的初衷，没变）。
     *
     * <p>★★ v1.0.34：**这一行放不下那句话** —— 时间 8 个数 + 「❄ 卡45s」5 个 +
     * 那句话 19 个 = 32 个数，超出 {@link #MAX_W} 一倍。v1.0.33 的写法是「放不下就
     * 整句删掉」，结果用户什么都看不到。现在改成挂在标题上
     * （见 {@link #IDLE_TITLE_NO_TERM}）：标题本来就短，加得下。
     */
    public static String idleClock(long nowMs, long staleMarkerSec, boolean termKnown) {
        return idleClock(nowMs, staleMarkerSec);
    }

    /** 「没在跑」那一屏的标题：数没取到就顺手说一句（放得下，见 {@link #IDLE_TITLE_NO_TERM}） */
    public static String idleTitle(boolean termKnown) {
        return termKnown ? IDLE_TITLE : IDLE_TITLE_NO_TERM;
    }

    /**
     * 「没在跑」那一种状态的第二行：学期目标 / 已完成。
     *
     * <p>两个数只有**一个**来源：服务端 {@code Run2/beforeRunV260} 响应里的
     * {@code term_num}（学期要求次数）与 {@code total_num}（本学期已完成次数）。
     * 还没取到（新装 / 还没开跑过）就如实画成破折号 —— 编一个数字比空着更坏。
     *
     * <p>★ v1.0.34：两个数字中间的空档换成一个全角间隔号 —— 用户点名要的那句
     * 「学期目标 N 次 已完成 M 次」在 176dp 的窗口里**本来就贴着边**，
     * 真机上（荣耀 X80，系统字体调大过）量出来是 30 个数，而一行只放得下 26 个：
     * 也就是说 v1.0.33 上「已完成 M 次」有一截是被切掉的，只是没人往下看。
     * 换成「·」之后是 25.5 个数，正好放得下（见 {@link #MAX_W}）。
     */
    public static String termText(int goal, int done) {
        return "学期目标 " + (goal >= 0 ? goal + " 次" : "—")
                + "·已完成 " + (done >= 0 ? done + " 次" : "—");
    }

    // ==================================================================
    //  二、权限 / 开关
    // ==================================================================

    /** 有没有「悬浮窗」权限（Android 6 以下没有这项，当作有） */
    public static boolean allowed(Context c) {
        try {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 去系统页面开「悬浮窗」权限（各家 ROM 都是这一页，Android 原生也有） */
    public static void requestPermission(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            i.setData(android.net.Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable t2) {
                RunLog.append("[小窗] 打不开悬浮窗权限页（" + Err.one(t2) + "）");
            }
        }
    }

    /** 用户设定的"切走时自动缩成小窗"开关（默认开） */
    public static boolean prefOn(Context c) {
        try {
            return AppPrefs.sp(c).getBoolean(AppPrefs.K_MINI, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setPrefOn(Context c, boolean on) {
        try {
            AppPrefs.sp(c).edit().putBoolean(AppPrefs.K_MINI, on).apply();
        } catch (Throwable ignore) { }
    }

    // ==================================================================
    //  三、显示 / 更新 / 收起
    // ==================================================================

    private static WindowManager wm;
    private static LinearLayout box;
    private static TextView tvTitle, tvL2, tvL3, btnStop, btnFold, btnBack;
    private static View view;

    /** 自检用的演示模式：没有真的开跑，但"切走"这条路要能走通（只由 selftest 置位） */
    public static volatile boolean demoMode = false;

    private static volatile boolean shown = false;
    private static volatile String mode = "";
    private static boolean compact = false;
    private static long lastTickAt = 0;
    private static long lastBeatAt = 0;      // ★ v1.0.33：「没在跑」时那一秒一跳的心跳
    private static long staleMarker = 0;
    private static long staleUntil = 0;
    private static long lastStopTap = 0;

    /* ★★★ v1.0.33：最后一次拿到的跑步数据（每一拍由 {@link #tickRun} 记下）。
     *   为什么要有它：小窗是"收起 — 再挂出来"反复用的（回助手又切走）。
     *   挂出来那一刻必须拿真实数据渲染 —— 不能拿演示那套顶着，
     *   不然真跑的人切回来再切走，会看到一瞬间的假里程。 */
    private static volatile double lastValidM = 0, lastTotalM = 0;
    private static volatile long lastSecs = 0;
    private static volatile int lastHit = 0;

    /* ★★★ v1.0.33（docs/65）：学期目标 / 已完成次数。
     *   只有服务端一个来源（Run2/beforeRunV260 的 term_num / total_num）——
     *   -1 = 还没取到，界面上画破折号。 */
    private static volatile int termGoal = -1;
    private static volatile int termDone = -1;

    /** ★ v1.0.33：这一次显示的两个数是不是"只放内存的演示值"（见 {@link #showIdleDemo}） */
    private static volatile boolean demoTerm = false;

    /** 记下服务端给的学期次数（跑步起跑那次、以及启动时补的那一次都会调它） */
    public static void noteTerm(Context c, int goal, int done) {
        if (goal >= 0) termGoal = goal;
        if (done >= 0) termDone = done;
        try {
            if (c != null) {
                AppPrefs.sp(c).edit()
                        .putInt(AppPrefs.K_TERM_GOAL, termGoal)
                        .putInt(AppPrefs.K_TERM_DONE, termDone)
                        .apply();
            }
        } catch (Throwable ignore) { }
        if (goal >= 0 || done >= 0) {
            RunLog.append("[学期] " + termText(termGoal, termDone));
        }
    }

    /** 把上一次存下来的学期次数读回内存（每次挂小窗之前调一次，进程重启也不丢） */
    public static void loadTerm(Context c) {
        try {
            if (c == null) return;
            int g = AppPrefs.sp(c).getInt(AppPrefs.K_TERM_GOAL, -1);
            int d = AppPrefs.sp(c).getInt(AppPrefs.K_TERM_DONE, -1);
            if (g >= 0) termGoal = g;
            if (d >= 0) termDone = d;
        } catch (Throwable ignore) { }
    }

    public static int termGoal() { return termGoal; }

    public static int termDone() { return termDone; }

    /** 现在屏幕上真的有这个窗口吗（真机自检 / 日志用） */
    public static boolean shown() { return shown; }

    /** 主线程 Handler —— View 和 WindowManager 都只能在主线程碰 */
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 跑步中：切走时调它（也由 onTick 每秒刷新） */
    public static void showRun(Context c) { demoTerm = false; show(c, "run"); }

    /**
     * ★ v1.0.33：**没在跑**的时候切走 —— 挂的就是这一种。
     *
     * <p>文案是用户点名的：标题「未开跑」，第二行「学期目标 N 次 已完成 M 次」，
     * 第三行是当前时间（秒在跳 = 助手还活着，见 {@link #idleClock}）。
     * 这一屏取代了原来的「🔬 体检 Ns / 切走息屏都行 / 数字在动…」——
     * 用户原话：「没有开始跑的话就不要体检那套，改成未开跑 + 学期目标/已完成」。
     */
    public static void showIdle(Context c) { demoTerm = false; show(c, "idle"); }

    /**
     * ★ v1.0.33：**只放内存**地挂一屏"有数"的「未开跑」（自检 / 真机验收用）。
     *
     * <p>为什么需要它：那台验收机上的会话已经失效（服务端只回 102），
     * 永远取不到真数 —— 可"取到数以后那一屏长什么样"必须看过才算验过。
     * 所以给一个只改内存、**不落盘**的入口：下一次 {@link #showIdle} 会
     * {@link #loadTerm} 把盘上那份读回来，演示值自然就没了。
     */
    public static void showIdleDemo(Context c, int goal, int done) {
        termGoal = goal;
        termDone = done;
        demoTerm = true;
        show(c, "idle");
    }

    /** 自检 / 用户点"看看长什么样"：演示文案，不接真实数据 */
    public static void showDemo(Context c) { show(c, "demo"); }

    /** 这一种状态是不是"真的在跑"（演示也算 —— 演示要能点「停」那条 UI 路径） */
    private static boolean runMode() {
        return mode.startsWith("run") || mode.startsWith("demo");
    }

    private static void show(final Context c, final String m) {
        if (c == null || !allowed(c)) return;
        final Context app = c.getApplicationContext();
        main().post(() -> {
            try {
                mode = m;                                   // ★ 必须在 build() 之前：文案按 mode 渲染
                lastTickAt = 0;                             // ★ 刚挂上来不算"卡住"
                lastBeatAt = 0;
                if (m.startsWith("idle") && !demoTerm) loadTerm(app);   // ★ 学期次数落盘过一份，重启也不丢
                if (view == null) build(app);
                apply();
                render();                                   // ★ 三个 mode 的文案都在这儿刷
                if (!shown) {
                    if (wm == null) {
                        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (wm == null) return;
                    /* ★★★ v1.0.34：**先 render 再把窗口挂上去**。顺序反了的话
                     *   {@code params()} 里量到的还是上一屏的字（甚至是空串），
                     *   窗口就会按错的宽度出现 —— 真机上截出来就是"文字比底色框宽"
                     *   （框窄了一截，字溢出在外面）。 */
                    WindowManager.LayoutParams lp = params(app);
                    view.setLayoutParams(lp);
                    applyWidth(app);
                    wm.addView(view, lp);
                    shown = true;
                } else {
                    applyWidth(app);
                }
                if (m.startsWith("idle")) startBeat();      // ★ 没在跑：小窗自己一秒一跳
                else stopBeat();
            } catch (Throwable t) {
                /* 有些 ROM 会直接拒（「后台弹出界面」被关掉时）—— 记下来，别把跑步搞挂 */
                android.util.Log.e("LedaoTester", "[小窗] 没显示出来：" + t);
                RunLog.append("[小窗] 没显示出来（" + Err.one(t) + "）");
                shown = false;
            }
        });
    }

    /** 跑步每一拍刷新（{@link MainActivity#onTick} 里调，约 1 秒一次） */
    public static void tickRun(final double validM, final double totalM,
                               final long secs, final int hit) {
        /* ★ 先把这一拍记下来 —— 小窗收起来再挂出来（回助手又切走）时，
         *   要拿"最后一次拿到的数"渲染，而不是拿演示值糊一下。 */
        lastValidM = validM;
        lastTotalM = totalM;
        lastSecs = secs;
        lastHit = hit;
        if (!shown) return;                                 // 没在屏幕上就不折腾
        final long now = System.currentTimeMillis();
        final long last = lastTickAt;
        lastTickAt = now;
        main().post(() -> {
            if (view == null || !runMode()) return;         // ★ 期间被切成了别的 mode 就别乱写
            /* ★★★ v1.0.36：这里以前是 staleSec(now, last) —— 拿 1 秒心跳的阈值
             *   （6 秒）去卡 6 秒的跑步节拍，正常一拍就贴着线，于是**每次**都报
             *   「卡5s」（用户 2026-10-21 截图来问的就是这个）。现在按计划节拍比。 */
            long stale = staleSecIn(now, last, tickMs);
            if (stale > 0) {
                staleMarker = stale;
                staleUntil = now + STALE_KEEP_MS;           // 这 1 分钟里第三行一直带记号
            }
            if (now > staleUntil) staleMarker = 0;
            tvTitle.setText(titleText(secs, staleMarker));
            tvL2.setText(kmText(validM, totalM));
            /* ★ v1.0.34：卡住那个记号挂在**第三行**（用户要的是"跑就写正在跑"，
             *   标题那句留给状态本身）；这一行本来是「配速 … · 打卡 N」，
             *   加个「❄卡12s」正好还有地方。
             * ★ v1.0.42：中段人脸那三步也要挂在这里（「📷扫脸」「📷缺口41m」「✅人脸」）
             *   —— 用户切到别的应用时，小窗是他唯一能看见的地方。 */
            tvL3.setText(paceText(secs, validM) + " · " + hitText(hit)
                    + staleMark(staleMarker) + faceMark());
        });
    }

    /* ==================================================================
     *  ★★★ v1.0.42（用户第 30 轮）：中段人脸那三步的记号
     *
     *  为什么要有它：那三步一共只持续几秒（2~20 m 按正常配速跑完），
     *  而用户绝大多数时间是**切走了**的（切走才有小窗）。前台那张卡片
     *  他未必看得见，小窗第三行才是他回头验证"到底做没做"的地方。
     * ================================================================== */

    /** 现在该挂什么记号（"" = 不挂） */
    private static volatile String faceNote = "";

    private static String faceMark() {
        String f = faceNote;
        return (f == null || f.length() == 0) ? "" : " · " + f;
    }

    /**
     * 改这一场的「人脸记号」。
     *
     * <p>三个取值由 {@link MainActivity#onFaceScan} / {@link MainActivity#onFaceInvalid}
     * / {@link MainActivity#onFaceDone} 喂进来：{@code 📷扫脸} → {@code 📷缺口41m} → {@code ✅人脸}。
     * 传空串就是擦掉。可以随便从哪个线程调（内部会 post 到主线程）。
     */
    public static void noteFace(final String s) {
        faceNote = s == null ? "" : s;
        final double v = lastValidM;
        final long secs = lastSecs;
        final int hit = lastHit;
        final long stale = staleMarker;
        main().post(() -> {
            try {
                if (view == null || !runMode() || tvL3 == null) return;
                tvL3.setText(paceText(secs, v) + " · " + hitText(hit)
                        + staleMark(stale) + faceMark());
            } catch (Throwable ignore) {
                /* 刷字失败不值得把跑步搞挂 */
            }
        });
    }

    /** ★ v1.0.33：小窗自己那一秒一跳的心跳 —— 只有「没在跑」这一种状态用它 */
    private static Handler beat;

    private static final Runnable BEAT = new Runnable() {
        @Override
        public void run() {
            try {
                if (shown && mode.startsWith("idle")) {
                    long now = System.currentTimeMillis();
                    long last = lastBeatAt;
                    lastBeatAt = now;
                    long stale = staleSec(now, last);
                    if (stale > 0) {
                        staleMarker = stale;
                        staleUntil = now + STALE_KEEP_MS;
                    }
                    if (now > staleUntil) staleMarker = 0;
                    if (tvTitle != null) tvTitle.setText(idleTitle(termKnown()));
                    if (tvL2 != null) tvL2.setText(termText(termGoal, termDone));
                    if (tvL3 != null) {
                        tvL3.setText(idleClock(now, staleMarker));
                    }
                }
            } catch (Throwable ignore) {
                /* 刷字失败不值得把跑步 / 抓包搞挂 */
            }
            if (beat != null && shown && mode.startsWith("idle")) {
                beat.postDelayed(this, BEAT_MS);
            }
        }
    };

    private static void startBeat() {
        stopBeat();
        beat = main();
        lastBeatAt = 0;                       // 第一跳不算"卡住"
        beat.postDelayed(BEAT, BEAT_MS);
    }

    private static void stopBeat() {
        try {
            if (beat != null) beat.removeCallbacks(BEAT);
        } catch (Throwable ignore) { }
        beat = null;
    }

    /** 收起（回到助手 / 跑步结束 / 用户点 ✕ 都要收） */
    public static void hide() {
        stopBeat();
        main().post(() -> {
            try {
                if (shown && wm != null && view != null) wm.removeViewImmediate(view);
            } catch (Throwable ignore) { }
            shown = false;
        });
    }

    /** 把显示状态写成一行字（日志 / 自检 / 向导共用同一份口径） */
    public static String statusLine(Context c) {
        if (!allowed(c)) return "迷你小窗：没给「悬浮窗」权限（开一次就够，切走自动缩到右上角）";
        if (!prefOn(c)) return "迷你小窗：你自己关掉了（点一下可以再打开）";
        return "迷你小窗：已就绪 ✅" + (shown ? "（现在就在右上角）" : "（切走时自动出现）");
    }

    /** 学期次数那一行（向导 / 日志用）—— 还没取到就如实说"还没取到" */
    public static String termLine() {
        return termText(termGoal, termDone)
                + (termGoal < 0 && termDone < 0 ? "（还没取到 —— 开跑一次就会自己填上）" : "");
    }

    // ==================================================================
    //  四、窗口本身
    // ==================================================================

    private static WindowManager.LayoutParams params(Context c) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                windowWidth(c), WindowManager.LayoutParams.WRAP_CONTENT, type,
                /* 不抢焦点（否则后面的 App 收不到按键）
                 * + 只在窗口内部吃触摸（外面的点击照常传给下层的 App） */
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = Math.max(0, c.getResources().getDisplayMetrics().widthPixels
                - windowWidth(c) - dp(c, MARGIN_DP));
        lp.y = dp(c, TOP_DP);
        lp.setTitle(WIN_TAG);
        return lp;
    }

    /**
     * ★★★ v1.0.34：小窗要**多宽**，由"这一屏最长的那一行字"决定，而不是写死一个 dp。
     *
     * <p>为什么必须自适应：写死的宽度只在**当前字号**下成立。用户的荣耀把系统字体
     * 调大过，同样 176dp 里能放的字就少了一截 —— 于是 v1.0.33 上标题被切、
     * 连「已完成 M 次」都被切掉一块（docs/67）。这里的做法：
     * 把最长的那一行（标题 + 三个键）真正量一遍，取够用的宽度，
     * 夹在 [{@link #W_DP}（美观下限）, {@link #MAX_DP}（不许糊住半个屏幕）] 之间。
     *
     * <p>量不出来的极端情况（没有窗口服务）就退回 {@link #W_DP}。
     */
    public static int windowWidth(Context c) {
        int want = naturalWidth(c);
        if (want <= 0) return dp(c, W_DP);
        return dp(c, Math.max(W_DP, Math.min(MAX_DP, want)));
    }

    /** 量一次"最长那行 + 三个键"需要多少 dp（量不出来返回 0） */
    private static int naturalWidth(Context c) {
        try {
            if (view == null) return 0;
            int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            TextView[] lines = new TextView[]{tvL2, tvL3};
            int prose = 0;
            for (TextView t : lines) {
                if (t == null || t.getVisibility() != View.VISIBLE) continue;
                t.measure(unspecified, unspecified);
                prose = Math.max(prose, t.getMeasuredWidth());
            }
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.leftMargin = dp(c, 4);
            int btns = 0;
            for (TextView b : new TextView[]{btnFold, btnBack, btnStop}) {
                if (b == null) continue;
                b.measure(unspecified, unspecified);
                btns += b.getMeasuredWidth() + bp.leftMargin;
            }
            btns += dp(c, 4);          // 标题和第一个键之间也留一点缝

            int pad = dp(c, 8) * 2;
            double dens = c.getResources().getDisplayMetrics().density;
            int head = tvTitle == null ? 0 : (int) Math.ceil(tvTitle.getPaint().measureText(
                    tvTitle.getText() == null ? "" : tvTitle.getText().toString()));
            int rowNeed = Math.max(prose, head + btns + dp(c, 4));
            return (int) Math.ceil((rowNeed + pad) / dens);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void build(final Context c) {
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101A14);                            // 深底、略透 —— 别把下面的界面糊死
        bg.setCornerRadius(dp(c, 10));
        bg.setStroke(dp(c, 1), 0x803FD08A);
        box.setBackground(bg);

        // ---- 第一行：标题（拖动把手）+ ⤢ 回助手 + – 收起 + 停（要说的是：真机上那个
        //   ■ 会被字体换成一大块红方块，糊在这么小的窗口里很难看，所以用汉字「停」）
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        tvTitle = new TextView(c);
        tvTitle.setTextSize(12.5f);
        tvTitle.setTextColor(Color.parseColor("#EAF6EE"));
        tvTitle.setSingleLine(true);
        row.addView(tvTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnFold = miniBtn(c, "–");
        btnFold.setOnClickListener(v -> {
            compact = !compact;
            btnFold.setText(compact ? "＋" : "–");
            tvL2.setVisibility(compact ? View.GONE : View.VISIBLE);
            tvL3.setVisibility(compact ? View.GONE : View.VISIBLE);
        });
        row.addView(btnFold);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(c, 4);

        btnBack = miniBtn(c, "⤢");
        btnBack.setOnClickListener(v -> openApp(c));
        row.addView(btnBack, gap);

        /* 第三个键是"看情况变"的（在 {@link #apply()} 里按 mode 定文案与动作）：
         *   · 跑步中 = 「停」（按两下才真停，防口袋误触）；
         *   · 没在跑 = 「✕」（这一屏没什么可停的，只给一个"收起来"的出口）。 */
        btnStop = miniBtn(c, "停");
        btnStop.setTextColor(Color.parseColor("#FF8A80"));
        row.addView(btnStop, gap);
        box.addView(row);

        tvL2 = line(c);
        tvL3 = line(c);
        box.addView(tvL2);
        box.addView(tvL3);

        // 拖动把手就放在标题文字上：按钮照常点，空白处按住就能拖走（挡住别的 App 时很需要）
        drag(c, tvTitle);
        view = box;                    // ★ 建好了才登记 —— show() 拿它去 addView
        render();                      // ★ 文案统一由 render() 出（build 不再自己写死一套）
        applyWidth(c);                 // ★ 文案出来了才量得准宽度（v1.0.34）
    }

    private static TextView miniBtn(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(Color.parseColor("#CFE9DA"));
        t.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 2));
        return t;
    }

    /**
     * ★ v1.0.34：按**这一刻的文案**把小窗调成刚好的宽度。
     *
     * <p>挂在 {@link #render} 之后调用：那时候三个键的文案（「停」/「✕」）和三行字
     * 都已经定了，量出来的才是真需要的宽度。窗口已经在屏幕上就用
     * {@code updateViewLayout} 就地改，没在屏幕上就等 {@link #show} 里 addView 时生效。
     */
    private static void applyWidth(Context c) {
        try {
            int w = windowWidth(c);
            if (view == null || w <= 0) return;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
            if (lp != null && lp.width == w) return;              // 没变就别折腾（避免每拍抖一下）
            if (lp != null) {
                lp.width = w;
                lp.x = Math.max(0, c.getResources().getDisplayMetrics().widthPixels
                        - w - dp(c, MARGIN_DP));
                if (wm != null && shown) wm.updateViewLayout(view, lp);
            }
        } catch (Throwable ignore) {
            /* 调宽度失败不值得把跑步搞挂 —— 大不了还是 200dp，跟 v1.0.33 一样 */
        }
    }

    private static TextView line(Context c) {
        TextView t = new TextView(c);
        t.setTextSize(10.5f);
        t.setTextColor(Color.parseColor("#9FC4AE"));
        t.setSingleLine(true);
        return t;
    }

    /** 只在标题文字上做拖动：起手记下"手指与窗口左上角"的差，移动时保持它 */
    private static void drag(final Context c, View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (view == null || wm == null) return false;
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) view.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - lp.x;
                        dy = e.getRawY() - lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int sw = c.getResources().getDisplayMetrics().widthPixels;
                        int sh = c.getResources().getDisplayMetrics().heightPixels;
                        lp.x = (int) Math.max(0, Math.min(e.getRawX() - dx, sw - view.getWidth()));
                        lp.y = (int) Math.max(0, Math.min(e.getRawY() - dy, sh - view.getHeight()));
                        try {
                            wm.updateViewLayout(view, lp);
                        } catch (Throwable ignore) { }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 切回助手（Activity 一回到前台就会把这个小窗收起来） */
    private static void openApp(Context c) {
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            c.startActivity(i);
        } catch (Throwable t) {
            RunLog.append("[小窗] 打不开助手界面（" + Err.one(t) + "）");
        }
    }

    /** 「停」（按两下）：停掉这一场。真正的收尾在跑步线程里，这里只喊一声。 */
    private static void stopRunFromWindow() {
        try {
            MainActivity.requestStopFromMini();
        } catch (Throwable t) {
            RunLog.append("[小窗] 停止失败（" + Err.one(t) + "）");
        }
    }

    /** 「停」的点击：第一下只上膛，{@link #STOP_ARM_MS} 之内第二下才真停 */
    private static void twoTapStop() {
        long now = System.currentTimeMillis();
        if (stopArmed(lastStopTap, now)) {                     // 第二下：真停
            lastStopTap = 0;
            stopRunFromWindow();
        } else {                                               // 第一下：上膛
            lastStopTap = now;
            btnStop.setText("再点");
            main().postDelayed(() -> {
                btnStop.setText("停");
                lastStopTap = 0;
            }, STOP_ARM_MS);
        }
    }

    /**
     * 按当前 {@code mode} 把三行文字全部刷一遍 —— **唯一**一处决定"这一屏显示什么"。
     *
     * <p>★★★ 为什么要单独抽出来（v1.0.33 真机踩到的 bug）：小窗是"收起 — 再挂出来"
     * 反复用的，而 {@link #build} 只跑一次。v1.0.32 里三行文字只在 build 时按 mode
     * 写死一次，之后靠 tick 覆盖 —— 于是"未开跑 → 跑步"这种**同一块视图换 mode**
     * 的切换会把上一屏的字留在上面（真机上截出来就是：按钮已经变成「停」了，
     * 标题还写着「未开跑」）。现在每次 show() 都走这里，mode 和字永远一致。
     */
    private static void render() {
        if (view == null) return;
        if (mode.startsWith("idle")) {
            tvTitle.setText(idleTitle(termKnown()));
            tvL2.setText(termText(termGoal, termDone));
            tvL3.setText(idleClock(System.currentTimeMillis(), 0));
        } else if (mode.startsWith("demo")) {
            // 自检 / 用户点"看看长什么样"：演示那一套（只有这一种 mode 用假数）
            tvTitle.setText(titleText(754, 0));
            tvL2.setText(kmText(1820, 2200));
            tvL3.setText(paceText(754, 1820) + " · " + hitText(3));
        } else {
            // 真跑：拿最后一次拿到的数渲染（第一拍 tickRun 立刻就盖掉它）
            tvTitle.setText(titleText(lastSecs, 0));
            tvL2.setText(kmText(lastValidM, lastTotalM));
            tvL3.setText(paceText(lastSecs, lastValidM) + " · " + hitText(lastHit)
                    + staleMark(staleMarker));
        }
        applyWidth(view == null ? null : view.getContext());
    }

    /** 两个数里有一个拿到了就算"有数"（决定第三行要不要加那句"还没取到"） */
    private static boolean termKnown() {
        return termGoal >= 0 || termDone >= 0;
    }

    /**
     * ★★★ v1.0.34 自检：把两种状态里**最长的那几行**量一遍 —— 会不会被单行剪切。
     *
     * <p>为什么要这么较真：用户那张真机截图证明"算出来的宽度"和"实际画出来的宽度"
     * 可以差到把字切掉（docs/67）。{@link #width} 那把尺子只是估计，
     * 所以真正的判据是 {@code TextView} 自己报的
     * {@code getLayout().getEllipsisCount(0)}：非 0 就是被切了。
     *
     * <p>★ 踩过的坑：刚 {@code addView} 完 {@code getWidth()} 还是 0 ——
     * 窗口的布局是**异步**跑的，那一瞬间量什么都是 0（第一次真机跑出来就是
     * 「可用 0px / 量不到布局」）。所以这里不靠窗口管理器排版，而是自己
     * 按小窗的真实宽度（{@link #W_DP} dp）算一次 {@code measure()} ——
     * 像素口径与 {@code build()} 里那套布局完全一致：同样的内边距、
     * 同样的三个键、同样的间距，标题那一格剩下的才是可用宽度。
     */
    public static java.util.List<String> measureSelfTest(Context c, String kind) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (c == null) {
            out.add("拿不到 Context，量不了");
            return out;
        }
        String[] texts;
        boolean[] onTitleRow = {true, true, true, false, false, true};
        if ("miniwinmeasureidle".equals(kind)) {
            texts = new String[]{
                    IDLE_TITLE_NO_TERM,
                    termText(65, 12),
                    idleClock(System.currentTimeMillis(), 0),
                    idleClock(System.currentTimeMillis(), 999999),
            };
            /* ★ 「未开跑」是标题那格（跟三个键抢），其余三行都是整行 */
            onTitleRow = new boolean[]{true, false, false, false};
        } else {
            texts = new String[]{
                    titleText(754, 0),
                    titleText(754, 45),
                    titleText(9 * 3600L + 59, 999999),
                    kmText(1820, 2200),
                    paceText(754, 1820) + " · " + hitText(3) + staleMark(999999),
                    termText(65, 12),                     // ★ 整行的第二行（学期数）
            };
        }
        try {
            // ① 按 build() 那套搭一个壳（不上屏，只用来量尺寸）
            LinearLayout shell = new LinearLayout(c);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tv = new TextView(c);
            tv.setTextSize(12.5f);
            tv.setSingleLine(true);
            row.addView(tv, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            gap.leftMargin = dp(c, 4);
            row.addView(miniBtn(c, "–"));
            row.addView(miniBtn(c, "⤢"), gap);
            TextView btnThird = miniBtn(c, "停");     // ★ 用局部变量：btnStop 是给真窗口用的静态字段
            row.addView(btnThird, gap);
            shell.addView(row);

            int winPx = dp(c, W_DP);
            int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            int exactly = View.MeasureSpec.makeMeasureSpec(winPx, View.MeasureSpec.EXACTLY);
            shell.measure(exactly, unspecified);

            /* ② 先量每个键的自然宽度，再反推标题那一格真正有多少可用宽度。
             *    ★ 别用 row.getMeasuredWidth() 去减：行里有个 weight=1 的标题，
             *      它会**先抢走**剩余空间，减出来的可用宽度是错的（真机上第一次
             *      就这么量，量出来「可用 427px」而字符串需要的也正好是 427px，
             *      一眼看去像是刚好放下，其实是尺度撞在一起了）。 */
            int padL = dp(c, 6), padR = dp(c, 6);
            LinearLayout.LayoutParams p1 = (LinearLayout.LayoutParams) row.getChildAt(0).getLayoutParams();
            LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams();
            LinearLayout.LayoutParams p3 = (LinearLayout.LayoutParams) row.getChildAt(2).getLayoutParams();
            LinearLayout.LayoutParams p4 = (LinearLayout.LayoutParams) row.getChildAt(3).getLayoutParams();
            int btnW = row.getChildAt(1).getMeasuredWidth() + p2.leftMargin
                    + row.getChildAt(2).getMeasuredWidth() + p3.leftMargin
                    + row.getChildAt(3).getMeasuredWidth() + p4.leftMargin;
            int avail = winPx - padL - padR - btnW - p1.leftMargin - p1.rightMargin;
            int atMost = View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.AT_MOST);
            /* ★★ 第二、三行是**整行**的（那一排按钮只在第一行），所以它们的可用宽度
             *   是窗口去掉左右内边距 —— 拿"标题那一格的宽度"去量它们就全成了"超出"，
             *   那是尺子拿错了（第一版就是这么量的，白紧张一场）。 */
            int fullAtMost = View.MeasureSpec.makeMeasureSpec(
                    winPx - padL - padR, View.MeasureSpec.AT_MOST);
            int fullAvail = winPx - padL - padR;
            out.add(String.format(Locale.US,
                    "[量] 窗口 %dpx（%ddp，密度 %.2f）；三个键占掉 %dpx；"
                    + "第一行那格可用 %dpx，第二/三行可用 %dpx",
                    winPx, W_DP, c.getResources().getDisplayMetrics().density, btnW, avail, fullAvail));
            for (int i = 0; i < texts.length; i++) {
                String t = texts[i];
                /* ★ 第一行那格要跟三个键抢宽度，第二/三行是**整行**（按钮只在第一行）——
                 *   分别用不同的可用宽度，别拿一把尺子量两处。 */
                boolean onTitle = onTitleRow[i];
                /* ★ 先用 UNSPECIFIED 量"这句话本来要多少像素" —— 拿 AT_MOST 去量的话，
                 *   量出来的是**被夹住的**宽度，永远等于可用宽度，看上去"恰好放得下"，
                 *   其实什么都没证明（真机上第一版就是这么量的，一串 288/288，一眼假）。 */
                tv.setText(t);
                tv.measure(unspecified, unspecified);
                int natural = tv.getMeasuredWidth();
                tv.measure(onTitle ? atMost : fullAtMost, unspecified);
                int cut = (tv.getLayout() == null) ? -1 : tv.getLayout().getEllipsisCount(0);
                int spare = (onTitle ? avail : fullAvail) - natural;
                out.add(String.format(Locale.US, "[量] %s  → 本来要 %dpx / 可用 %dpx  %s",
                        t, natural, onTitle ? avail : fullAvail,
                        cut > 0 ? "★被切掉 " + cut + " 个字符"
                                : (cut < 0 ? "量不到布局"
                                        : (spare < 0 ? "★超出 " + (-spare) + "px"
                                                : "放得下 ✅（还余 " + spare + "px）"))));
            }
            out.add("[量] 尺子复算：这个标题 " + width(titleText(754, 45))
                    + " 个数（上限 " + MAX_W + "）；这一格能放 "
                    + String.format(Locale.US, "%.1f", avail / (double) tv.getPaint().measureText("0"))
                    + " 个数字的宽度");
            out.add("[量] 自适应宽度：这一屏要 " + windowWidth(c) + "px（下限 "
                    + dp(c, W_DP) + "／上限 " + dp(c, MAX_DP) + "）");
        } catch (Throwable t) {
            out.add("[量] 量不了：" + Err.one(t));
        }
        return out;
    }

    /**
     * 每次挂出来都调一次：按当前 {@code mode} 把第三个键定成「停」还是「✕」，
     * 并把两行 / 三行文字的显隐对齐（收起状态 compact 也要跟着走）。
     */
    private static void apply() {
        if (view == null) return;
        boolean run = runMode();
        tvL2.setVisibility(compact ? View.GONE : View.VISIBLE);
        // ★ 没在跑时第三行是"当前时间"（秒在跳 = 没被冻住），它同样是信息，收起时才藏
        tvL3.setVisibility(compact ? View.GONE : View.VISIBLE);
        if (run) {
            btnStop.setText("停");
            btnStop.setOnClickListener(v -> twoTapStop());
        } else {
            btnStop.setText("✕");
            btnStop.setOnClickListener(v -> {
                lastStopTap = 0;
                hide();
                RunLog.append("[小窗] 用户在小窗上点了 ✕ —— 先收起来"
                        + "（回到助手再切走，它还会自己出现）");
            });
        }
    }
}
