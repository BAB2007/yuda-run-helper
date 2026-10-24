package com.ledao.tester;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Updater —— 「发现新版本」那一步的界面与安装。
 *
 * <p>分工：{@link Cloud} 只负责"有没有新版"（网络 + 比 versionCode），
 * 这里负责"问用户 / 下载 / 校验 / 把安装器叫起来"。两件事分开写，
 * 是因为前者能离线回归（CloudTest），后者只能真机验。
 *
 * <h3>为什么下载要自己写（不用 DownloadManager）</h3>
 * <p>DownloadManager 下完只会丢一条通知，安装那一下仍然要 {@code content://}
 * 和一次授权；而"下到哪、校验失败怎么办、用户在哪个页面"全在系统手里，出错时
 * 我们只能说"你自己点通知栏"。APK 只有 2 MB（真机网络实测几秒），自己拉一遍
 * 反而能把进度条、sha256 校验、失败重试都放在一个地方，用户看得到。
 *
 * <h3>已知边界（文档和界面里都不许说成"一定成功"）</h3>
 * <ul>
 *   <li>Android 8+ 第一次装非市场应用，系统会拦一次：必须由用户去
 *       「允许安装未知应用」里点开。我们只能把那个页面跳出来，不能替他点。</li>
 *   <li>签名必须和已装的那份一致（本工程用固定的 {@code work/keys/ledao.keystore}）。
 *       换了签名就只能卸载重装 —— 这一步没有绕过的办法。</li>
 *   <li>厂商 ROM 可能对"下载目录里的 APK"做二次扫描，慢一点是正常的。</li>
 * </ul>
 */
public final class Updater {

    private Updater() { }

    private static final String MIME = "application/vnd.android.package-archive";

    /** 防止重复弹（启动检查 + 用户手动点，可能同时到）。 */
    private static boolean showing = false;

    // ==================================================================
    //  一、问用户
    // ==================================================================

    /**
     * @param st {@link Cloud#state} 的结果：1=可选 · 2=强制
     */
    public static void prompt(final Activity act, final Cloud.M m, final int st) {
        if (act == null || act.isFinishing() || showing) return;
        if (act.getPackageManager() == null) return;
        showing = true;

        final int my = Cloud.myVer(act);
        StringBuilder sb = new StringBuilder();
        sb.append("当前 v").append(verName(act)).append("  →  新版本 v").append(m.name)
                .append("（").append(mb(m.size)).append("）\n\n");
        if (m.note != null && m.note.trim().length() > 0) {
            sb.append("这一版：").append(m.note.trim()).append("\n\n");
        }
        sb.append("装完还是同一个包名、同一把签名 —— 直接覆盖安装，")
                .append("身份、设置、日志都还在，不用重新提取。");
        if (st == 2) {
            sb.append("\n\n⚠ 这一版低于最低支持版本，不更新会跑不了。");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(st == 2 ? "必须更新到 v" + m.name : "发现新版本 v" + m.name)
                .setMessage(sb.toString())
                .setCancelable(st != 2)
                .setPositiveButton("立即更新", (d, w) -> {
                    showing = false;
                    download(act, m);
                });
        if (st == 2) {
            b.setNegativeButton("退出助手", (d, w) -> {
                showing = false;
                act.finish();
            });
        } else {
            b.setNegativeButton("以后再说", (d, w) -> {
                showing = false;
                // 记下"这一版我看过了"，下次启动不再烦他；出了更新的版本会再问
                Cloud.sp(act).edit().putInt(Cloud.K_SKIP, m.ver).apply();
            });
        }
        if (m.page != null && m.page.startsWith("http")) {
            b.setNeutralButton("去仓库看看", (d, w) -> {
                showing = false;
                openUrl(act, m.page);
            });
        }
        b.setOnDismissListener(d -> showing = false);
        try {
            b.show();
        } catch (Throwable t) {
            showing = false;
            Log.d("ledao", "更新弹窗失败：" + t);
        }
    }

    // ==================================================================
    //  二、下载
    // ==================================================================

    static void download(final Activity act, final Cloud.M m) {
        final File dir = ApkProvider.dir(act);
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(act, "建不出下载目录，空间可能满了", Toast.LENGTH_LONG).show();
            return;
        }
        final String fname = "ledao-v" + safe(m.name) + ".apk";
        final File out = new File(dir, fname);

        // 上次已经下好、而且校验过 → 不再重下（比如刚被系统拦去开权限，回来再点一次）
        if (out.exists() && out.length() > 0
                && (m.sha.length() == 0 || m.sha.equalsIgnoreCase(shaOf(out)))) {
            install(act, out, m.sha.length() > 0);
            return;
        }

        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("正在下载 v" + m.name);
        pd.setMessage("正在连接…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            String err = null;
            boolean ok = false;
            for (String raw : m.urls) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(Cloud.encodeUrl(raw)).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "ledao-helper");
                    if (c.getResponseCode() != 200) {
                        err = "服务器回 " + c.getResponseCode();
                        c.disconnect();
                        continue;
                    }
                    long total = m.size > 0 ? m.size : c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    long got = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            final int pct = (int) (got * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final long g = got, t = total;
                                act.runOnUiThread(() -> {
                                    if (act.isFinishing()) return;
                                    pd.setProgress(pct);
                                    pd.setMessage(String.format(Locale.US, "%.1f / %.1f MB",
                                            g / 1048576.0, t / 1048576.0));
                                });
                            }
                        }
                    }
                    fo.close();
                    in.close();
                    c.disconnect();
                    // 长度对不上 = 半截包，换下一个源
                    if (total > 0 && got != total) {
                        err = "只下到 " + got + " / " + total + " 字节";
                        continue;
                    }
                    ok = true;
                    break;
                } catch (Throwable t) {
                    err = String.valueOf(t);
                    Log.d("ledao", "下载失败：" + t);
                }
            }

            final boolean got = ok;
            final String e = err;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                try { pd.dismiss(); } catch (Throwable ignore) { }
                if (!got) {
                    Toast.makeText(act, "下载失败（" + e + "）—— 换个时间再试，"
                            + "或者点弹窗里那个「去仓库看看」手动下", Toast.LENGTH_LONG).show();
                    return;
                }
                if (m.sha.length() > 0 && !m.sha.equalsIgnoreCase(shaOf(out))) {
                    out.delete();     // 坏包一定要删，否则下次还当它已经下好
                    Toast.makeText(act, "校验没通过（下载被改坏了）—— 已删掉，再点一次更新",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                install(act, out, m.sha.length() > 0);
            });
        }, "ledao-dl").start();
    }

    // ==================================================================
    //  三、交给系统安装器
    // ==================================================================

    /**
     * @param verified 有没有做过 sha256 校验（只影响界面上的说法，不影响安装）
     */
    static void install(Activity act, File apk, boolean verified) {
        if (!ApkProvider.validName(apk.getName())) {
            Toast.makeText(act, "安装包名字不对，已放弃", Toast.LENGTH_LONG).show();
            return;
        }
        // Android 8+：装非市场应用要用户本人授权。我们只能把他送到那个开关前。
        if (Build.VERSION.SDK_INT >= 26) {
            boolean can;
            try {
                can = act.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                can = true;
            }
            if (!can) {
                Toast.makeText(act, "还差一步：在系统设置里把「允许安装未知应用」给助手打开，"
                        + "然后回来再点一次「立即更新」（包已经下好了，不用重下）",
                        Toast.LENGTH_LONG).show();
                try {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + act.getPackageName())));
                } catch (Throwable t) {
                    try {
                        act.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    } catch (Throwable ignore) { }
                }
                return;
            }
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ApkProvider.uriOf(apk.getName()), MIME);
        // 没有这个 flag，安装器读不到 content://（它就是靠这一次临时授权拿到流的）
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
            if (verified) {
                Toast.makeText(act, "已校验通过，接着在系统弹窗里点「安装」", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(act, "叫不起系统安装器：" + t, Toast.LENGTH_LONG).show();
            Log.d("ledao", "安装失败：" + t);
        }
    }

    // ==================================================================
    //  小工具
    // ==================================================================

    /** 读本机 APK 的 versionName（只用于弹窗里那句"当前 vX"）。 */
    static String verName(Activity act) {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** public 是为了让 work/looptest/CloudTest 能在没有手机的情况下断言它（纯运算）。 */
    public static String mb(long bytes) {
        if (bytes <= 0) return "大小未知";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** 文件名里只留安全字符（清单里的 name 是外部输入，不能直接拼路径）。 */
    public static String safe(String s) {
        if (s == null) return "x";
        String r = s.replaceAll("[^0-9A-Za-z._-]", "");
        return r.length() == 0 ? "x" : r;
    }

    /** 文件 sha256（小写十六进制）。读不出来返回空串 —— 调用方按"校验不通过"处理。 */
    public static String shaOf(File f) {
        InputStream in = null;
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) d.update(buf, 0, n);
            byte[] b = d.digest();
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    static void openUrl(Activity act, String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(act, "没有能打开网页的应用", Toast.LENGTH_SHORT).show();
        }
    }
}
