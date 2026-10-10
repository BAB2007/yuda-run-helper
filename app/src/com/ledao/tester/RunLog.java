package com.ledao.tester;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * RunLog —— 把**这一场跑步的日志**实时写一份到 {@code files/run.log}。
 *
 * <h3>为什么要有它（v1.0.25 的后台运行）</h3>
 * 助手的日志以前只在内存里（{@code MainActivity.logBuf}）。跑步改成"可以息屏、
 * 可以切走"之后，最怕的就不是"跑的时候看不见"，而是**进程被系统清掉之后什么都查不到**：
 * 界面日志随着进程一起没了，只剩一条挂着的通知。
 *
 * <p>所以从 v1.0.25 起，跑步期间的每一行日志都同步落盘。三条规矩：
 * <ol>
 *   <li><b>一场一个文件</b>：{@link #start} 会开新的一份（上一场挪成 {@code run_prev.log}，
 *       不混在一起）；每次 append 后立刻 flush —— 进程被 kill 也不会丢最后几行。</li>
 *   <li><b>捞得回来</b>：下次开 App 时 {@link #tail} 把最后几十 KB 读回界面日志，
 *       用户直接点「复制日志」就能发出来。</li>
 *   <li><b>绝不因为写日志而影响跑步</b>：任何异常都吞掉，写不动就放弃
 *       （{@link #active()} 会变 false，别处照常跑）。</li>
 * </ol>
 */
public final class RunLog {

    /** 跑步期间实时写的那个文件 */
    public static final String NAME = "run.log";

    /** 上一场挪过去的归档（只留一代，不删） */
    public static final String PREV = "run_prev.log";

    /** 单个文件的上限 —— 一场跑步撑死几百行，3 MB 是给"万一刷屏"兜底的 */
    private static final long MAX_BYTES = 3L * 1024 * 1024;

    private static FileOutputStream out;
    private static long written = 0;

    private RunLog() { }

    /**
     * 开一场新的记录（每次「开始跑步」时调）。上一场如果还在，先挪成 {@code run_prev.log}。
     *
     * @param filesDir 应用的 files 目录（{@code Context.getFilesDir()}）
     * @param header   第一行（写清时间 / 目标，方便日后翻出来看）
     */
    public static synchronized void start(File filesDir, String header) {
        stop();
        if (filesDir == null) return;
        try {
            File f = new File(filesDir, NAME);
            File prev = new File(filesDir, PREV);
            if (f.exists()) {
                prev.delete();
                f.renameTo(prev);
            }
            out = new FileOutputStream(f, false);
            written = 0;
            append(header);
        } catch (Throwable t) {
            out = null;
        }
    }

    /** 追加一行（带 flush）。没开记录 / 写失败时静默返回。 */
    public static synchronized void append(String line) {
        if (out == null || line == null) return;
        if (written > MAX_BYTES) return;
        try {
            byte[] b = (line + "\n").getBytes("UTF-8");
            out.write(b);
            out.flush();
            written += b.length;
        } catch (Throwable t) {
            try { out.close(); } catch (Throwable ignore) { }
            out = null;
        }
    }

    /** 这一场结束（正常收尾 / 出错退出 / 用户点停止）时调。文件留着，不删。 */
    public static synchronized void stop() {
        try { if (out != null) out.close(); } catch (Throwable ignore) { }
        out = null;
    }

    /** 现在正在往磁盘写吗（进程被杀后重启，这里一定是 false） */
    public static synchronized boolean active() { return out != null; }

    /**
     * 把上一场（或正在跑的这一场）的日志归档改名。
     *
     * <p>只在 {@link #active()} 为 false 时调 —— 也就是"本进程没有正在写的记录"：
     * 要么进程刚重启（上次被系统杀掉），要么这场早就结束了。
     * 这样 {@link #tail} 读过一次之后就不会每次开 App 都重复显示。
     */
    public static synchronized void archive(File filesDir) {
        if (out != null || filesDir == null) return;
        try {
            File f = new File(filesDir, NAME);
            if (!f.exists() || f.length() == 0) return;
            File prev = new File(filesDir, PREV);
            prev.delete();
            f.renameTo(prev);
        } catch (Throwable ignore) { }
    }

    /**
     * 磁盘上那份日志的**尾巴**（最多 maxBytes 字节，按行对齐）；没有就返回 ""。
     * 优先看正在写的 {@code run.log}，没有再看归档的 {@code run_prev.log}。
     */
    public static String tail(File filesDir, int maxBytes) {
        if (filesDir == null) return "";
        String s = readTail(new File(filesDir, NAME), maxBytes);
        if (s.length() == 0) s = readTail(new File(filesDir, PREV), maxBytes);
        return s;
    }

    private static String readTail(File f, int maxBytes) {
        try {
            if (!f.exists() || f.length() == 0) return "";
            long len = f.length();
            int want = (int) Math.min(len, Math.max(4096, maxBytes));
            FileInputStream in = new FileInputStream(f);
            long skip = len - want;
            long done = 0;
            while (done < skip) {
                long n = in.skip(skip - done);
                if (n <= 0) break;
                done += n;
            }
            byte[] b = new byte[want];
            int off = 0;
            while (off < want) {
                int r = in.read(b, off, want - off);
                if (r <= 0) break;
                off += r;
            }
            in.close();
            String s = new String(b, 0, off, "UTF-8");
            // 从中间切进来的话，第一行多半是半截 —— 切掉它
            if (skip > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Throwable t) {
            return "";
        }
    }
}
