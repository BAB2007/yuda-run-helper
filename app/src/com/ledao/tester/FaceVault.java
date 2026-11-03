package com.ledao.tester;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FaceVault —— 人脸照片的**留存库**：只留最近 {@value #KEEP_DAYS} 天，只为"起跑查重"存在。
 *
 * <h3>用户为什么要它（v1.0.60，2026-11-03）</h3>
 * 用户原话：
 * <ol>
 *   <li>「再增加一个功能，留存照片，将最近 10 天的照片保存（仅保存最近 10 天）」</li>
 *   <li>「每次跑步进行一次查重，重了就弹窗口，'您的照片在最近存在重复上传，请重新上传照片'」</li>
 * </ol>
 *
 * <p>要解决的是一类**看不出来、但代价极大**的事：把三五天前用过的同一张自拍再传一次。
 * 服务端拿到的是"同一张图反复出现"，人工审查最先看的就是这个；而当场并不会报错，
 * 照样能跑完 —— 等发现时已经是几场之后。所以**照片得留一份下来**，才有东西可比。
 *
 * <h3>三条硬规矩</h3>
 * <ol>
 *   <li><b>只留 10 天</b>：{@link #prune} 每次进库前先删过期的（按文件名里的时间戳；
 *       名字坏了就看 {@code lastModified}）。用户说的是"仅保存最近 10 天"，多一天都不留。</li>
 *   <li><b>只在手机里</b>：全部落在 {@code files/face_vault/}（应用私有目录，
 *       不申请存储权限、不进相册、不上传任何服务器）。它存在的唯一用途就是下次查重。</li>
 *   <li><b>绝不因为留存而影响跑步</b>：任何一步失败都只是少留一张，
 *       调用方那边一律当作"没留成"，不拦跑步。</li>
 * </ol>
 *
 * <h3>什么时候写进来（时机很关键）</h3>
 * <p>**不是**用户选完照片就留 —— 那时照片还没发出去，可能马上被换掉，
 * 留了就会让用户"换了一张新的还被判重复"。写进来的时机是**这一场真的用上了**：
 * 起跑前人脸核验通过（照片已经传上 OSS 并拿到 id），或者这一场真跑出了里程
 * （服务端配了 {@code start_status=0} 时一次脸都不用验）。见
 * {@code MainActivity#archiveFaces}。
 *
 * <p>同理，**查重必须在留存之前**：{@link #findSame} 只跟"以前那几场"比，
 * 不会拿当前这张跟它自己刚留下的那一份比 —— 那条路是自锁的。
 *
 * <h3>文件长什么样</h3>
 * <pre>
 *   files/face_vault/20261103-204512_pre.jpg      ← 11-03 20:45:12 留的「跑前」那张
 *   files/face_vault/20261103-204512_mid.jpg      ← 同一场的「跑中」那张
 *   files/face_vault/20261102-071533_pre-2.jpg    ← 同一秒撞名时加 -2（几乎不可能）
 * </pre>
 * <p>名字里的时间戳就是"这张照片是哪一场留的"，界面上报"11-03 20:45 那一场用过"
 * 靠它，不依赖 {@code lastModified}（复制、备份都会改那个值）。
 */
public final class FaceVault {

    /** 只留最近这么多天 —— 用户原话「仅保存最近 10 天」 */
    public static final int KEEP_DAYS = 10;

    /** 相对 {@code MainActivity.getFilesDir()} 的目录名 */
    public static final String DIR = "face_vault";

    private static final long DAY_MS = 24L * 3600L * 1000L;

    /** 文件名里的时间戳格式（也是人物"哪一场"的唯一来源） */
    private static final String FMT = "yyyyMMdd-HHmmss";

    /** 时间戳部分的固定长度（{@code 20261103-204512} = 15 个字符） */
    private static final int STAMP_LEN = 15;

    private FaceVault() { }

    /** 留存窗口（毫秒）—— 界面文案 / 日志都从这里取，别处不要再写死 10 */
    public static long keepMs() {
        return KEEP_DAYS * DAY_MS;
    }

    /** 库目录（顺手建出来；建不出来也不用在这里报错 —— 写文件那一步自然会失败） */
    public static File dir(File filesDir) {
        if (filesDir == null) return null;
        File d = new File(filesDir, DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * 删掉 10 天以前的所有留存。
     *
     * @return 这次删掉几张（日志里要报出来）
     */
    public static int prune(File filesDir, long now) {
        File d = new File(filesDir, DIR);
        if (!d.isDirectory()) return 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        long cut = now - keepMs();
        int n = 0;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            long ts = tsOf(f, 0);
            if (ts > 0 && ts < cut && f.delete()) n++;
        }
        return n;
    }

    /**
     * 库里的全部照片（**窗口内**的；过期的先删掉），按时间从旧到新。
     *
     * <p>先 prune 再列：这样调用方拿到的"张数"跟磁盘上真正留下的
     * 永远是同一个数 —— 用户要的是"只留 10 天"，不是"10 天内不删、过期的下次再说"。
     */
    public static List<File> list(File filesDir, long now) {
        prune(filesDir, now);
        List<File> out = new ArrayList<>();
        File d = new File(filesDir, DIR);
        File[] fs = d.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f != null && f.isFile() && isJpg(f)) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long x = tsOf(a, 0), y = tsOf(b, 0);
                return x < y ? -1 : (x > y ? 1 : 0);
            }
        });
        return out;
    }

    /** 库里现在几张（窗口内） */
    public static int count(File filesDir, long now) {
        return list(filesDir, now).size();
    }

    /**
     * 把一张照片存进留存库。
     *
     * @param src  源文件（当前的 {@code files/face_pre.jpg} / {@code face_mid.jpg}）
     * @param slot {@code "pre"} / {@code "mid"}，只进文件名、不参与任何判断
     * @return 存进去的那个文件；源文件不在 / 读不了 / 写不进都返回 {@code null}
     */
    public static File put(File filesDir, File src, String slot, long now) {
        if (src == null || !src.isFile() || src.length() <= 0) return null;
        File d = dir(filesDir);
        if (d == null) return null;
        prune(filesDir, now);                    // 每次进库前先清过期的（"仅保存最近 10 天"）
        String base = stamp(now) + "_" + (slot == null || slot.length() == 0 ? "x" : slot);
        File dst = new File(d, base + ".jpg");
        for (int i = 2; dst.exists() && i < 100; i++) {      // 同一秒撞名（几乎不可能）
            dst = new File(d, base + "-" + i + ".jpg");
        }
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            return dst.length() > 0 ? dst : null;
        } catch (Throwable t) {
            if (dst.exists() && !dst.isDirectory()) dst.delete();    // 别把半个文件留在库里
            return null;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) { }
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 查重：{@code probe} 这张，跟库里窗口内的照片有没有"同一张"。
     *
     * @return 命中的那个留存文件（调用方拿它报"哪一场用过"）；没有重复返回 {@code null}
     */
    public static File findSame(File filesDir, File probe, long now) {
        if (probe == null || !probe.isFile() || probe.length() <= 0) return null;
        List<File> all = list(filesDir, now);
        for (int i = all.size() - 1; i >= 0; i--) {      // 从最新的找起：多半上一次就撞上
            if (sameImage(probe, all.get(i))) return all.get(i);
        }
        return null;
    }

    /** 库里那张是**哪一场**留的：{@code "11-03 20:45"} */
    public static String when(File f) {
        long ts = tsOf(f, 0);
        if (ts <= 0) return "最近某一场";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ts));
    }

    /** 这张是「跑前」还是「跑中」（只用于把话说准，认不出就当跑前） */
    public static String slotOf(File f) {
        String n = f == null ? "" : f.getName();
        int i = n.indexOf('_');
        String s = i > 0 ? n.substring(i + 1) : "";
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash);
        return "mid".equals(s) ? "mid" : "pre";
    }

    // ==================================================================
    //  图片比对 —— 从 MainActivity 搬过来的（v1.0.55 那套判据，一个字没改）
    // ==================================================================

    /**
     * 两个图片文件是不是"同一张脸照"：字节完全相同，或 24×24 灰度几乎逐像素重合。
     *
     * <p>两道都要，因为用户能"重复"出两种来源：
     * <ol>
     *   <li><b>字节完全一样</b> —— 从相册里把同一张再选一次（最常见）；</li>
     *   <li><b>内容一样</b> —— 同一张图被另存 / 被微信转过一手 / 重新压缩过，
     *       字节不同，但缩成 24×24 灰度后几乎重合。</li>
     * </ol>
     *
     * <p>阈值"平均灰度差 ≤ 3/255"：同一张图重编码的实测差异在 0~2 之间，
     * 而两张不同自拍（哪怕同一个人、同一个背景）通常 15 以上。宁可放过，不误杀。
     */
    public static boolean sameImage(File a, File b) {
        if (a == null || b == null) return false;
        try {
            if (a.length() == b.length()) {
                byte[] x = readFileBytes(a), y = readFileBytes(b);
                if (x != null && y != null && Arrays.equals(x, y)) return true;
            }
        } catch (Throwable ignore) { }
        try {
            float[] p = fingerprint(a), q = fingerprint(b);
            if (p == null || q == null) return false;
            double sum = 0;
            for (int i = 0; i < p.length; i++) sum += Math.abs(p[i] - q[i]);
            return sum / p.length <= 3.0;
        } catch (Throwable t) {
            return false;              // 比不出来就当"不一样"，绝不因此拦住用户
        }
    }

    /**
     * 缩成 24×24 灰度当指纹（只读，不写盘，不改用户文件）。
     *
     * <p>先用 {@code inJustDecodeBounds} 拿尺寸，再按尺寸选 {@code inSampleSize} ——
     * 一张 4000×3000 的自拍也只解出 ~100×75，几十毫秒就完。
     */
    public static float[] fingerprint(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / 96);
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bm == null) return null;
            Bitmap s = Bitmap.createScaledBitmap(bm, 24, 24, true);
            float[] out = new float[24 * 24];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int c = s.getPixel(x, y);
                    out[y * 24 + x] = 0.299f * Color.red(c)
                            + 0.587f * Color.green(c) + 0.114f * Color.blue(c);
                }
            }
            if (s != bm) s.recycle();
            bm.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    //  内部小工具
    // ==================================================================

    private static boolean isJpg(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    /** 文件名 → 那一场的毫秒时间；名字不合规矩就退回 {@code lastModified}，再不行给 fallback */
    static long tsOf(File f, long fallback) {
        if (f == null) return fallback;
        String n = f.getName();
        int i = n.indexOf('_');
        String s = i > 0 ? n.substring(0, i) : "";
        if (s.length() == STAMP_LEN) {
            try {
                return new SimpleDateFormat(FMT, Locale.US).parse(s).getTime();
            } catch (Throwable ignore) { }
        }
        long lm = f.lastModified();
        return lm > 0 ? lm : fallback;
    }

    private static String stamp(long now) {
        return new SimpleDateFormat(FMT, Locale.US).format(new Date(now));
    }

    private static byte[] readFileBytes(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int len = (int) Math.min(f.length(), 16L * 1024 * 1024);
            byte[] b = new byte[Math.max(1, len)];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            return off == b.length ? b : Arrays.copyOf(b, off);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }
}
