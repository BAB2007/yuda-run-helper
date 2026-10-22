package com.ledao.tester;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AppLog —— 真机格式的「客户端日志」，跑完随 {@code Report/appLog} 上传（v1.0.39）。
 *
 * <h3>为什么补这一块（docs/71 §三.6 #10）</h3>
 * 真机不是"跑完只交三份文件"就完事：它整场都在写
 * {@code files/log_report/android_error_<日期>.log}，并且定期把整份日志
 * **PUT 到 OSS**、再用 {@code Report/appLog} 把对象名报给服务端。
 * 我们这条链路一条都没走 —— 也就是说，官方客户端"留下的痕迹"里最完整的那一份
 * （客户端自己写的运行日志）我们**从来没有过**。
 *
 * <h3>格式是实测抄来的，不是编的</h3>
 * 真机原件（{@code work/session/auto_20261006_212849.jsonl} 里那次 OSS PUT 的 body）：
 * <pre>
 *   2026-10-06 21:16:22| uid | 19790779|河北工程大学|是否有更新：false
 *   2026-10-06 21:28:07| uid | 19790779|河北工程大学|screenOn:true
 * </pre>
 * 即 {@code 时间| uid | <uid>|<学校>|<一行日志>}，**CRLF 换行**，UTF-8。
 * 我们照抄这个形状，只改一处：**uid 打码**（见下）。
 *
 * <h3>为什么不写真 uid</h3>
 * 用户明确要求「日志中不能输出用户标识相关内容」（v1.0.34 那条），
 * 而这份日志既会落盘在 {@code files/log_report/} 里、又会被用户「复制日志」带出去。
 * 所以 uid 一律写成 {@code 197****79} —— 服务端本来就知道请求来自哪个 uid，
 * 打码不会让它少任何信息；但用户把文件发出去时，标识不会跟着泄露。
 *
 * <h3>v1.0.40：这份日志只能写「真机也会写的那种话」</h3>
 * v1.0.39 一度把界面上的每一句提示都塞进这份日志（{@code MainActivity.logLine}
 * 尾部的那道钩），于是它 PUT 到 OSS 的正文里出现了
 * 「本场随机量：整体平移 3.7 m」「步长系数 0.973」「路网随机游走 种子 …」
 * 这种句子 —— 那不是日志，是**自白书**：这份文件的对象名会被
 * {@code Report/appLog} 报给服务端，任何拿到这个对象名的人下载一次，
 * 就同时得到了「这一场是造的」和「它是怎么造出来的」。
 * <p>两件事一起做：
 * <ol>
 *   <li>源头只写中性行 —— {@link Ledao#uploadAppLog} 附近那几句（开场/定时定位/
 *       收尾），界面日志**不再**进这里（{@code MainActivity.logLine} 的钩已删）；</li>
 *   <li>这里再兜一道 {@link #SCRUB}：命中任一词就整行**丢弃**，不是替换 ——
 *       换个说法写进去照样是自白。</li>
 * </ol>
 *
 * <h3>「中性行」长什么样</h3>
 * 照真机那两句的形状：{@code 是否有更新：false}、{@code screenOn:true}、
 * {@code network:wifi}、{@code 定位：114.531,36.570}。它们的共同点是：
 * 一台**真的**手机跑出来的日志里一模一样会出现，而且不携带任何
 * 「这一场不是人跑的」的信息。宁少勿多：这份文件的作用是让"客户端有过日志"
 * 这件事成立，不是用来记录我们自己的算法。
 *
 * <h3>它不参与成绩判定</h3>
 * 这条链路失败**绝不影响**跑步/上传/收尾：{@link Ledao#uploadAppLog} 全程吞异常，
 * 只在日志里留一行。理由和 {@link RunLog} 一样 —— 日志是旁证，不是主线。
 */
public final class AppLog {

    /** 上限（字符数）。真机那份一场几万到几十万字节；我们只留最近的这一截。 */
    private static final int MAX_CHARS = 400_000;

    /**
     * 不许进这份文件的词（v1.0.40）。全部小写比一次，中文不分大小写。
     *
     * <p>挑词的原则是「真机日志里不会出现、而我们的内部文案里一定会出现」：
     * 内部量的名字（随机量/平移/步长系数/误差/漂移/种子/游走）、这套软件自己的
     * 身份（助手/仿真/模拟/自检/兜底）、协议私有物（lpi/umid/logPoints/log_data）、
     * 以及会暴露意图的界面词（目标/配速/截图）。误伤一条中性行的代价只是
     * 少一行可用作旁证的日志，而漏过一条自白的代价是整场作废 —— 所以宁可多列。
     */
    private static final String[] SCRUB = {
        "助手", "随机", "平移", "步长", "系数", "误差", "漂移", "高斯",
        "路网", "游走", "种子", "冻结", "冻住", "补回", "虚拟", "仿真",
        "模拟", "伪造", "风控", "抓包", "身份", "打码", "掩码", "兜底",
        "自检", "重绘", "截图", "目标", "配速",
        "lpi", "umid", "logpoints", "log_data",
    };

    private static final StringBuilder BUF = new StringBuilder(1 << 15);
    private static String uid = "-";
    private static String school = "-";

    private AppLog() { }

    /** 绑定这一场是谁的日志（{@code uid} 会被打码，{@code school} 原样） */
    public static synchronized void bind(String u, String s) {
        if (u != null && u.trim().length() > 0) uid = mask(u.trim());
        if (s != null && s.trim().length() > 0) school = s.trim();
    }

    /**
     * 追加一行（多行会自动拆成多行；空行丢掉）。
     *
     * <p>命中 {@link #SCRUB} 的行**整行丢弃**（v1.0.40）—— 见类注释。
     * 丢弃是静默的：既不抛异常也不落占位符，写入方无从知道少了哪一句，
     * 也就不需要为「日志里有没有这一句」写任何判断逻辑。
     */
    public static synchronized void add(String msg) {
        if (msg == null) return;
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        int start = 0;
        while (start <= msg.length()) {
            int nl = msg.indexOf('\n', start);
            String line = nl < 0 ? msg.substring(start) : msg.substring(start, nl);
            String body = line.replace("\r", "").trim();
            if (body.length() > 0 && !scrubbed(body)) {
                BUF.append(ts).append("| uid | ").append(uid).append('|')
                   .append(school).append('|').append(body)
                   .append("\r\n");
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        if (BUF.length() > MAX_CHARS) BUF.delete(0, BUF.length() - MAX_CHARS);
    }

    /**
     * 这一行是否该被丢掉（v1.0.40）。包内可见，方便离线测试直接照着词表验；
     * {@code null} 当要丢处理 —— 空的东西没有写进去的理由。
     */
    static boolean scrubbed(String line) {
        if (line == null) return true;
        String low = line.toLowerCase(Locale.US);
        for (int i = 0; i < SCRUB.length; i++) {
            if (low.contains(SCRUB[i])) return true;
        }
        return false;
    }

    /** 这一场攒下来的整份日志（真机就是整份传） */
    public static synchronized String text() { return BUF.toString(); }

    /** 同上，UTF-8 字节；还没内容时返回空数组（调用方据此跳过上传） */
    public static synchronized byte[] bytes() {
        try {
            return BUF.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /** 这一场开始前清空（每场一份，不混上一场） */
    public static synchronized void clear() { BUF.setLength(0); }

    /** 现在攒了多少行（日志里报一个数，便于核对） */
    public static synchronized int lines() {
        int n = 0;
        for (int i = 0; i < BUF.length(); i++) if (BUF.charAt(i) == '\n') n++;
        return n;
    }

    /**
     * uid 打码：{@code 19790779 → 197****79}。
     * 只有两三位的那种极短 id 直接全遮 —— 打一半等于没打。
     *
     * <p>{@code public} 是为了离线测试能直接照着这几条边界验（{@code AppLogTest} A4）；
     * 生产路径只经 {@link #bind} 调它。
     */
    public static String mask(String u) {
        if (u == null || u.length() == 0) return "-";
        if (u.length() <= 5) return "***";
        return u.substring(0, 3) + "****" + u.substring(u.length() - 2);
    }
}
