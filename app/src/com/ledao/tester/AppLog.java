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
 * <h3>它不参与成绩判定</h3>
 * 这条链路失败**绝不影响**跑步/上传/收尾：{@link Ledao#uploadAppLog} 全程吞异常，
 * 只在日志里留一行。理由和 {@link RunLog} 一样 —— 日志是旁证，不是主线。
 */
public final class AppLog {

    /** 上限（字符数）。真机那份一场几万到几十万字节；我们只留最近的这一截。 */
    private static final int MAX_CHARS = 400_000;

    private static final StringBuilder BUF = new StringBuilder(1 << 15);
    private static String uid = "-";
    private static String school = "-";

    private AppLog() { }

    /** 绑定这一场是谁的日志（{@code uid} 会被打码，{@code school} 原样） */
    public static synchronized void bind(String u, String s) {
        if (u != null && u.trim().length() > 0) uid = mask(u.trim());
        if (s != null && s.trim().length() > 0) school = s.trim();
    }

    /** 追加一行（多行会自动拆成多行；空行丢掉） */
    public static synchronized void add(String msg) {
        if (msg == null) return;
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        int start = 0;
        while (start <= msg.length()) {
            int nl = msg.indexOf('\n', start);
            String line = nl < 0 ? msg.substring(start) : msg.substring(start, nl);
            if (line.trim().length() > 0) {
                BUF.append(ts).append("| uid | ").append(uid).append('|')
                   .append(school).append('|').append(line.replace("\r", "").trim())
                   .append("\r\n");
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        if (BUF.length() > MAX_CHARS) BUF.delete(0, BUF.length() - MAX_CHARS);
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
     */
    static String mask(String u) {
        if (u == null || u.length() == 0) return "-";
        if (u.length() <= 5) return "***";
        return u.substring(0, 3) + "****" + u.substring(u.length() - 2);
    }
}
