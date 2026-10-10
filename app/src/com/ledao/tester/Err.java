package com.ledao.tester;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Err —— 把异常变成「人能在日志里一眼看出在哪、为什么」的一行字。
 *
 * 为什么单独抽一个类（2026-10-07 界面优化 #7）：
 *   以前日志里到处是 `catch (Throwable t) { log("      !! " + t); }`，
 *   打出来只有一句 `java.lang.NullPointerException` —— **不知道在哪一步炸的**。
 *   用户的原话是「尽量出错后，错误所在位置和问题就能在日志中体现出来」。
 *   所以这里统一做三件事：
 *     ① 展开 cause 链（A 因为 B，B 因为 C）
 *     ② 抓出**第一帧属于 com.ledao.tester 的栈帧**，那就是出事的位置
 *        （类名.方法名:行号 —— 反查源码一步到位）
 *     ③ 常见异常翻译成中文人话 + 处置建议
 *
 * 另外提供一个进程级崩溃兜底 installCrashHandler()：跑步线程里没被 catch 的
 * 异常会连栈一起落盘到 files/crash.txt，并在界面上打出来。
 */
public final class Err {

    private Err() { }

    // ------------------------------------------------------------------ 位置

    /**
     * 抓出第一帧属于本 App 的栈帧，返回 `类名:行号`。
     * 拿不到就返回 ""（绝不抛异常 —— 它本身就在错误路径上）。
     */
    public static String where(Throwable t) {
        if (t == null) return "";
        try {
            StackTraceElement[] st = t.getStackTrace();
            if (st == null) return "";
            // 先找本工程的帧
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn != null && cn.startsWith("com.ledao.tester")) {
                    String simple = cn.substring(cn.lastIndexOf('.') + 1);
                    return simple + "." + e.getMethodName() + ":" + e.getLineNumber();
                }
            }
            // 没有就退回第一帧（通常是 JDK / Android 框架里）
            StackTraceElement e = st.length > 0 ? st[0] : null;
            if (e == null) return "";
            String cn = e.getClassName();
            return (cn == null ? "?" : cn.substring(cn.lastIndexOf('.') + 1))
                    + "." + e.getMethodName() + ":" + e.getLineNumber();
        } catch (Throwable ignore) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 人话

    /** 常见异常 → 中文解释 + 建议。认不出来就返回类型名。 */
    public static String hint(Throwable t) {
        if (t == null) return "未知错误";
        Throwable root = rootCause(t);
        String n = root.getClass().getName();
        String m = root.getMessage() == null ? "" : root.getMessage();

        if (n.endsWith("NullPointerException"))
            return "空指针：某个该有值的东西是 null（多半是接口没返回字段 / 身份缺项）";
        if (n.endsWith("SocketTimeoutException"))
            return "网络超时：服务端没在超时时间内回，检查手机网络或代理是否还开着";
        if (n.endsWith("UnknownHostException"))
            return "DNS 解析失败：手机没网，或系统/VPN 代理指向了一个不通的地址";
        if (n.endsWith("ConnectException"))
            return "连接被拒：本地代理端口没在监听，或目标端口不通";
        if (n.endsWith("SSLHandshakeException") || n.endsWith("CertificateException"))
            return "证书握手失败：抓包 CA 没装好，或系统时间不对";
        if (n.endsWith("JSONException"))
            return "JSON 解析失败：服务端返回的不是预期格式（多半是被拦了/返回了 HTML）";
        if (n.endsWith("NumberFormatException"))
            return "数字解析失败：字段是空串或非数字（注意服务端有时发数字、有时发字符串）";
        if (n.endsWith("BadPaddingException") || n.endsWith("IllegalBlockSizeException"))
            return "解密失败：密钥/IV/通道用错了，或响应体根本不是密文";
        if (n.endsWith("FileNotFoundException") || n.endsWith("IOException"))
            return "文件/流异常：路径不存在或没有读写权限（" + m + "）";
        if (n.endsWith("SecurityException"))
            return "权限不足：被系统拦了（root 命令、后台限制、代理设置）";
        if (n.endsWith("OutOfMemoryError"))
            return "内存不足：图片/轨迹太大，或某处无限累加";
        if (n.endsWith("StackOverflowError"))
            return "栈溢出：递归没收住";
        if (n.endsWith("InterruptedException"))
            return "线程被中断：多半是用户点了停止";
        if (n.endsWith("IllegalStateException"))
            return "状态不对：调用顺序错了（" + m + "）";
        if (n.endsWith("IllegalArgumentException"))
            return "参数不合法：" + m;
        if (n.endsWith("IndexOutOfBoundsException"))
            return "下标越界：列表比预期短（接口返回条数变了）";
        return root.getClass().getSimpleName() + (m.length() > 0 ? "：" + m : "");
    }

    /** 最深层的 cause */
    public static Throwable rootCause(Throwable t) {
        Throwable c = t;
        int guard = 0;
        while (c.getCause() != null && c.getCause() != c && guard++ < 20) c = c.getCause();
        return c;
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 一行式描述：`类名.方法:行号 | 中文原因 | 原始异常`
     * 例：`Ledao.run:1503 | 空指针：某个该有值的东西是 null | NullPointerException: null`
     */
    public static String one(Throwable t) {
        if (t == null) return "(无异常)";
        String w = where(t);
        StringBuilder sb = new StringBuilder();
        if (w.length() > 0) sb.append(w).append(" | ");
        sb.append(hint(t));
        // cause 链（只列一层，够定位了）
        Throwable rc = rootCause(t);
        sb.append(" | 原始：").append(rc.getClass().getSimpleName());
        if (rc.getMessage() != null && rc.getMessage().length() > 0)
            sb.append(": ").append(rc.getMessage());
        return sb.toString();
    }

    /** 多行：一行式描述 + 完整栈（给「复制日志」用） */
    public static String full(Throwable t) {
        if (t == null) return "(无异常)";
        StringBuilder sb = new StringBuilder(one(t));
        sb.append('\n');
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        for (String line : sw.toString().split("\n")) {
            String s = line.trim();
            if (s.length() == 0) continue;
            sb.append("        ").append(s).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 给用户看的话

    /**
     * ★★★ 2026-10-10（用户第 2 条要求）：把一句「开发话」翻成**普通用户看得懂**的三段。
     *
     * <p>用户原话：
     * <pre>
     *   该软件面向普通用户，弹窗内容应该让用户看后就知道大概发生了什么
     *   以及下一步该做什么。
     * </pre>
     *
     * <p>所以每个弹窗固定三段：**标题**（一句话结论）→ **发生了什么**（人话 + 服务端原话）
     * → **下一步该做什么**（具体到点哪个按钮）。绝不把 `status=100` 这种话直接甩给用户，
     * 但也**不隐瞒原话** —— 原话写在小括号里，用户要发给开发者时照抄就行。
     *
     * <p>纯函数，不碰 Android API ⇒ 可以离线回归（见 work/looptest/AlertTest.java）。
     *
     * @param raw Ledao.Result.message 或任何一句日志
     * @return 定长 3 的数组：{标题, 发生了什么, 下一步该做什么}
     */
    public static String[] advise(String raw) {
        String m = raw == null ? "" : raw;

        // ---- ① 服务端那三句被反复实测过的门禁 ----
        if (m.contains("上传数据失败") || m.contains("\"status\":100")) {
            return new String[]{
                    "这次没算数（服务端拒收）",
                    "服务端回的是「上传数据失败」——它没把这次上传当成一条有效记录。\n"
                  + "最常见的三种原因：\n"
                  + "· 这场跑步的登记令牌过期了（中途停太久再接着跑最容易碰到）\n"
                  + "· 上传的三段文件（轨迹 / 截图 / 陀螺仪）有一段落空\n"
                  + "· 服务端那头刚好在抽风",
                    "再点一次「▶ 开始跑步」重跑一遍就行 —— 路线、里程、配速都会重新生成。\n"
                  + "如果连着两三次都是这句话：点下面的「复制日志」，把日志发到 "
                  + DevNote.EMAIL + "。"};
        }
        if (m.contains("人脸照片验证不合格") || m.contains("\"status\":59")) {
            return new String[]{
                    "这次没算数（人脸核验没通过）",
                    "跑完了，但服务端说这一场的人脸核验链不完整 —— 它回的是"
                  + "「人脸照片验证不合格」。这条「跟轨迹、配速、打卡点都没关系」，"
                  + "别去改那些。",
                    "先看一眼首页那颗「🧑 人脸照片」按钮：\n"
                  + "· 没设过 → 设一张（正脸、光线足、脸占满画面的自拍）\n"
                  + "· 设过了 → 点开预览，确认是本人、脸清楚\n"
                  + "然后重跑一次。"};
        }
        if (m.contains("打卡次数过少") || m.contains("\"status\":8")) {
            return new String[]{
                    "这次没算数（打卡点没跑到）",
                    "服务端要求一次跑步至少经过 2 个打卡点，这次它数出来不够。\n"
                  + "多半是中途网络断了，打卡上报没发出去。",
                    "重跑一次即可（每次开跑服务端都会重新指派 2 个打卡点，"
                  + "路线会自动从那两个点旁边过）。"};
        }
        if (m.contains("102") && m.contains("其他手机登录") || m.contains("\"status\":102")) {
            return new String[]{
                    "账号在别处登录了，会话被顶掉",
                    "步道乐跑是「单会话」登录：它自己在后台刷了一次令牌，"
                  + "我们手上这份就作废了（服务端原话「该账号已在其他手机登录」）。\n"
                  + "继续跑下去也没用 —— 后面每一个请求都会被拒。",
                    "① 点「📲 自动提取」重新抓一次身份（会自己打开步道乐跑，全自动）\n"
                  + "② 抓完再点「▶ 开始跑步」\n"
                  + "★ 跑步期间不要同时打开步道乐跑，一开就会把会话抢走。"};
        }
        if (m.contains("\"status\":101") || m.contains("登录失效") || m.contains("登录已过期")) {
            return new String[]{
                    "登录状态失效了",
                    "服务端说这份身份已经过期（status=101）。",
                    "点「📲 自动提取」重新抓一次身份，再点「▶ 开始跑步」。"};
        }
        if (m.contains("拿不到 OSS STS") || m.contains("OSS 凭证")) {
            return new String[]{
                    "上传通道没打开",
                    "上传轨迹需要一个临时凭证（OSS STS），服务端这次没给。\n"
                  + "一般是网络不稳，或者会话刚好在这时候到期。",
                    "重跑一次；如果每次都在这里失败，先点「📲 自动提取」换一份身份。"};
        }
        if (m.contains("HTTP 4") || m.contains("HTTP 5") || m.contains("HTTP 0")) {
            return new String[]{
                    "文件上传失败（网络）",
                    "轨迹 / 截图 / 陀螺仪有一段没传上去：" + oneLine(m) + "\n"
                  + "这是网络层的事，不是跑步数据本身的问题。",
                    "① 确认手机能上网（如果开着 VPN 或代理，先关掉）\n"
                  + "② 再点一次「▶ 开始跑步」重跑一遍\n"
                  + "换 Wi-Fi 或换流量再试，通常就好了。"};
        }
        if (m.contains("SocketTimeout") || m.contains("UnknownHost")
                || m.contains("ConnectException") || m.contains("SocketException")) {
            return new String[]{
                    "连不上服务器",
                    oneLine(m) + "\n"
                  + "手机没网、或者网络被切走了。",
                    "① 确认手机能上网（开关一次飞行模式，或者换个 Wi-Fi）\n"
                  + "② 再点一次「▶ 开始跑步」重跑一遍\n"
                  + "如果开了 VPN / 代理，先关掉再试。"};
        }
        if (m.contains("未找到跑区")) {
            return new String[]{
                    "没找到你的跑区",
                    "服务端返回的跑区列表里，没有一条的 school_id 跟你身份里的对得上。",
                    "点「✎ 手动粘贴 / 修改身份」核对一下 school_id 对不对，"
                  + "再点「📲 自动提取」重新抓一次。"};
        }
        if (m.contains("跑前人脸核验没通过")) {
            // 这一条正常情况下走的是 onFacePre 那条专用弹窗；这里只是兜底，别退化成开发话
            return new String[]{
                    "人脸核验没通过",
                    m,
                    "点首页「🧑 人脸照片」重新设一张正脸自拍（光线足、脸占满画面），然后重跑。"};
        }
        if (m.contains("轨迹序列化")) {
            return new String[]{
                    "轨迹整理失败（本机的问题）",
                    m + "\n这一段是纯本机计算，跟网络和服务端都无关。",
                    "点「复制日志」发给开发者：" + DevNote.EMAIL + "。"};
        }
        if (m.contains("异常:") || m.contains("异常：")) {
            return new String[]{
                    "跑步中途出错了，已经停下",
                    "出错位置的原始描述是：\n" + oneLine(m).replace(": null", "") + "\n"
                  + "（这一行也写进了日志，开发者按它就能定位到具体哪一步。）",
                    "先原样再点一次「▶ 开始跑步」试试 —— 有些错误是服务端偶发的，重跑就好。\n"
                  + "如果每次都停在同一个地方，点下面的「复制日志」把日志发到 "
                  + DevNote.EMAIL + "。"};
        }
        if (m.contains("已手动停止")) {
            return new String[]{
                    "已停止",
                    "你点了「停止跑步」，这一场没有生成记录。",
                    "想重跑就再点一次「▶ 开始跑步」。"};
        }

        // ---- ② 认不出来：原样呈现，但一定要给出下一步 ----
        return new String[]{
                "这一场没成功",
                oneLine(m),
                "再点一次「▶ 开始跑步」试试。\n"
              + "如果每次都一样：点下面的「复制日志」，把日志发到 " + DevNote.EMAIL + "。"};
    }

    /** 跑前人脸核验没过 —— 「发生了什么」那一句 */
    public static String faceReason(int status, String info) {
        String s = info == null ? "" : info;
        /* ★★★ 2026-10-10 真机事故：status == -1 是**我们自己**在本地体检时拦下的
         *（请求根本没发出去），可这里原来会落到最后那句「服务端回的是「…」」——
         * 等于拿本地检查的理由冒充服务端的话。用户看到"服务端说我照片不完整"，
         * 只会一遍遍重选照片，而服务端其实一个字都没收到。 */
        if (status == -1) {
            if (s.length() == 0) {
                return "这一次根本没发出去 —— 本地检查就没过：多半是「还没设置人脸照片」。";
            }
            return "本地检查把这一次拦下了 —— 照片「没有」发给服务端，服务端还不知道这件事。\n"
                 + "它给出的原因：" + s;
        }
        if (s.length() == 0) {
            return "服务端没给原因（status=" + status + "）。";
        }
        if (s.contains("请稍后重试") || s.contains("系统错误")) {
            return "服务端自己没跑通 —— 它回的是「" + s + "」。\n"
                 + "「这不是你照片的问题」：有实测证据，真机自己的那张原图在服务端抽风时"
                 + "同样被这句话顶回来，而且每枪要等 6~13 秒（正常只要 0.4 秒）。";
        }
        if (s.contains("未拍到完整人脸")) {
            return "服务端说画面里找不到一张完整的人脸 —— 它回的是「" + s + "」。\n"
                 + "这一次确实是照片的事。";
        }
        return "服务端回的是「" + s + "」（status=" + status + "）。";
    }

    /** 跑前人脸核验没过 —— 「下一步该做什么」那一句 */
    public static String faceAdvice(int status, String info) {
        String s = info == null ? "" : info;
        /* 本地拦下的（照片文件本身有问题）：重点提醒**别重选同一张** ——
         * 同一个文件再选一次结果一模一样，用户会以为软件坏了。 */
        if (status == -1 && s.length() > 0) {
            return "别再选刚才那一张（再选一次还是同一个文件，结果一样）。\n"
                 + "① 换相册里另一张正脸近照；如果这张是从微信 / QQ 收到的，"
                 + "先在相册里「另存为」一份再选它\n"
                 + "② 选完首页那行会显示照片大小，点一下能预览 —— 看着是正常照片，"
                 + "再点「▶ 开始跑步」\n"
                 + "③ 要是每张都这样，点「复制日志」发到 " + DevNote.EMAIL + " 让开发者看看";
        }
        if (status == -1 || s.length() == 0 || s.contains("未拍到完整人脸")) {
            return "点首页那颗「🧑 设置人脸照片」重新选一张：\n"
                 + "· 正脸、光线足、背景干净\n"
                 + "· 脸要占满画面（别用全身照或远景）\n"
                 + "选完再点「▶ 开始跑步」。";
        }
        if (s.contains("请稍后重试") || s.contains("系统错误")) {
            return "这次不用换照片（换了也没用）。等十几分钟再点一次「▶ 开始跑步」，"
                 + "服务端缓过来就好了。\n"
                 + "如果一整天都是这句话，点「复制日志」发到 " + DevNote.EMAIL + " 让开发者看看。";
        }
        return "点首页那颗「🧑 设置人脸照片」换一张正脸自拍，再点「▶ 开始跑步」。";
    }

    /** 把一句可能很长的东西压成更适合放在弹窗里的一行 */
    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    // ------------------------------------------------------------------ 崩溃兜底

    private static volatile Context appCtx = null;
    private static volatile boolean installed = false;

    /**
     * 装进程级崩溃兜底。
     *
     * 为什么需要：跑步跑在后台线程里，一旦有没被 catch 的异常（比如某个接口
     * 返回了没预料的形状），线程直接死，界面上**什么都不会显示** —— 用户看到
     * 的就是「点了开始跑步，然后没有然后了」。这是最难排查的一类问题。
     * 装上之后：界面上打出「崩溃 + 位置 + 中文原因 + 栈」，同时落盘 files/crash.txt。
     */
    public static void installCrashHandler(final Context ctx, final Ledao.Log log) {
        appCtx = ctx == null ? null : ctx.getApplicationContext();
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                String w = where(ex);
                if (log != null) {
                    log.log("");
                    log.log("╔══════════════════════════════════════════════════════╗");
                    log.log("║ 💥 未捕获异常 —— 这一局到这里就断了                  ║");
                    log.log("╚══════════════════════════════════════════════════════╝");
                    log.log("   线程   : " + (thread == null ? "?" : thread.getName()));
                    log.log("   位置   : " + (w.length() > 0 ? w : "(不在本工程代码里)"));
                    log.log("   原因   : " + hint(ex));
                    log.log("   原始   : " + ex);
                    log.log("   完整栈 :");
                    log.log(full(ex));
                    log.log("   ★ 把这段「复制日志」发给开发者即可定位。");
                }
                saveCrash(thread, ex, w);
            } catch (Throwable ignore) {
                // 兜底里再出错就彻底放弃，交回系统
            }
            if (def != null) def.uncaughtException(thread, ex);
        });
    }

    /**
     * 启动时读一次崩溃记录，返回几行可以直接打进日志的话。
     * 没崩过就返回一句「无崩溃记录」。读完把文件改名成 .old，避免每次都报。
     */
    public static String[] crashSummary(Context c) {
        try {
            File f = new File(c.getFilesDir(), "crash.txt");
            if (!f.exists() || f.length() == 0) {
                return new String[]{"上次运行：无崩溃记录 ✔"};
            }
            byte[] b = new byte[(int) Math.min(f.length(), 4000)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            String txt = new String(b, 0, Math.max(0, n), "UTF-8");
            // 只取最后一次（最后一个 ══════ 之后）
            int at = txt.lastIndexOf("══════");
            String body = at >= 0 ? txt.substring(at) : txt;
            String[] raw = body.split("\n");
            java.util.List<String> out = new java.util.ArrayList<>();
            out.add("⚠ 上次运行崩溃过 —— 以下是崩溃记录（完整记录在 files/crash.txt）");
            for (String l : raw) {
                if (l.trim().length() == 0) continue;
                out.add("   " + l.trim());
                if (out.size() >= 14) break;
            }
            out.add("   （本行之后不再重复提示；下次崩溃会重新记录）");
            // 改个名，下次启动不再刷屏
            File bak = new File(c.getFilesDir(), "crash.old.txt");
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(bak);
            return out.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[]{"上次运行：崩溃记录读取失败（" + hint(t) + "）"};
        }
    }

    /** 崩溃落盘：files/crash.txt（追加，保留最近 5 次） */    private static void saveCrash(Thread thread, Throwable ex, String where) {
        if (appCtx == null) return;
        try {
            File f = new File(appCtx.getFilesDir(), "crash.txt");
            if (f.exists() && f.length() > 400_000) {
                File bak = new File(appCtx.getFilesDir(), "crash.old.txt");
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(bak);
            }
            FileOutputStream fo = new FileOutputStream(f, true);
            StringBuilder sb = new StringBuilder();
            sb.append("\n══════ ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date())).append(" ══════\n");
            sb.append("线程   : ").append(thread == null ? "?" : thread.getName()).append('\n');
            sb.append("位置   : ").append(where).append('\n');
            sb.append("原因   : ").append(hint(ex)).append('\n');
            sb.append(full(ex)).append('\n');
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignore) { }
    }
}
