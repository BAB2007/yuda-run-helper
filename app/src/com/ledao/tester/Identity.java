package com.ledao.tester;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identity —— 从手机上的步道乐跑 App 里**自动提取**身份标识（需要 root）
 *
 * 实测有效的来源：
 *
 *   ① token / refresh_token / uid / school_id / student_num / umid
 *      /data/data/com.lptiyu.tanke/files/mmkv/setting               （MMKV 键值库）
 *
 *      MMKV 是二进制，键值形如  access_token<1字节类型>62A58724...
 *      同名的历史值会留在文件里，所以**可能有好几个候选**，必须逐个拿到服务端验证。
 *
 *   ② .system_id.log（**只是兜底，别当 mobileDeviceId 用**）
 *      /data/data/com.lptiyu.tanke/files/.system_id/.system_id.log
 *      里面是 d69d4891-388e-44fc-90f2-167e9e626440 这样一个正常 UUID，
 *      但它在真机 622 次请求里出现 **0 次** —— 真机的 mobileDeviceId 是
 *      ffffffff-af0e-5497-ffff-ffffef05ac4a，且**不落盘**。
 *      ★ 2026-10-04 之前的注释把这两个搞反了，导致「一直抓不到个人标识」，
 *        详见 docs/23；正确取法见 {@link RealId}（载入真机 App、读它的进程内存）。
 */
public class Identity {

        public static final String PKG = "com.lptiyu.tanke";
    public static final String FILE_MMKV = "/data/data/" + PKG + "/files/mmkv/setting";
    public static final String FILE_DEVID = "/data/data/" + PKG + "/files/.system_id/.system_id.log";

    public String deviceId = "";
    public final List<String> tokens = new ArrayList<>();
    public final List<String> refreshTokens = new ArrayList<>();
    public String uid = "";
    /** ★★ lpi0 风控令牌的明文前缀（真机 MMKV 里的 umid，34 字符，设备绑定） */
    public String umid = "";
    public String schoolId = "647";
    public String studentNum = "";
    public String termId = "";
    public String zoneName = "";
    /** 跑区打卡点（从 run_rule 里顺手带出来，拿不到就留空，主流程会自己拉） */
    public final List<double[]> points = new ArrayList<>();
    public final List<String> pointIds = new ArrayList<>();
    public final StringBuilder trace = new StringBuilder();

    private void say(String s) { trace.append(s).append('\n'); }

    // ------------------------------------------------------------------ root 读文件
    /** 以 root 读取一个文件的全部字节；失败返回 null */
    public static byte[] suCat(String path) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat '" + path + "'"});
            InputStream is = p.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
            // 把 stderr 抽干，避免 su 因管道写满而卡住
            InputStream es = p.getErrorStream();
            while (es != null && es.read(buf) > 0) { /* drain */ }
            p.waitFor();
            byte[] out = bo.toByteArray();
            return out.length > 0 ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignore) { }
        }
    }

    /** 以 root 执行一条 shell 命令，返回合并后的输出 */
    public static String su(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.getOutputStream().close();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            long deadline = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < deadline && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignore) { }
        }
    }

    /** 判断有没有 root */
    public static boolean hasRoot() {
        String s = su("id");
        return s != null && s.contains("uid=0");
    }

    /**
     * ★★ 诊断「为什么没有 root」—— 这两种情况处理方式完全不同，必须分开报：
     *
     *   ① 本应用不在 Magisk 的 SuList / MagiskHide **白名单**里
     *      → 它的进程 namespace 里**连 su 二进制都不存在**，magisk 挂载数是 0。
     *      这种情况光在「超级用户」里点允许没用，必须把它加进白名单再重启。
     *      （2026-10-04 实测：这台机器的 sulist 表里原来只有 bin.mt.plus，
     *        所以助手的 root 功能全部静默失效 —— 表现出来就是「自动提取」一直
     *        回落到抓包。见 docs/24。）
     *
     *   ② 看得见 su，但请求被拒 → 去「超级用户」里改成允许即可。
     */
    public static String rootDiagnosis() {
        boolean suVisible = false;
        for (String p : new String[]{"/system/bin/su", "/sbin/su", "/system/xbin/su",
                "/debug_ramdisk/su", "/data/adb/magisk/su"}) {
            if (new java.io.File(p).exists()) { suVisible = true; break; }
        }
        int magiskMounts = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/mountinfo"));
            String l;
            while ((l = r.readLine()) != null) if (l.contains("magisk")) magiskMounts++;
            r.close();
        } catch (Throwable ignore) { }

        if (!suVisible || magiskMounts == 0) {
            return "本应用被 Magisk 藏起来了（在 SuList / MagiskHide 白名单之外）："
                    + "进程里 su " + (suVisible ? "存在" : "不存在")
                    + "、magisk 挂载数 " + magiskMounts + "（正常应该 > 0）。\n"
                    + "   修法：Magisk → 设置 → SuList（或 MagiskHide 白名单）→ "
                    + "把「宇达步道乐跑助手」勾上 → 重启手机。";
        }
        return "看得见 su（magisk 挂载数 " + magiskMounts + "），但请求被拒 —— "
                + "去 Magisk 的「超级用户」里把「宇达步道乐跑助手」改成允许。";
    }

    // ------------------------------------------------------------------ 提取
    private static final Pattern P_ACCESS = Pattern.compile("access_token[^0-9A-Fa-f]{1,4}([0-9A-Fa-f]{32})");
    private static final Pattern P_REFRESH = Pattern.compile("refresh_token[^0-9A-Fa-f]{1,4}([0-9A-Fa-f]{32})");
    private static final Pattern P_UID = Pattern.compile("\"uid\"\\s*:\\s*\"?(\\d{5,12})\"?");
    private static final Pattern P_SCHOOL = Pattern.compile("\"school_id\"\\s*:\\s*\"?(\\d{1,8})\"?");
    private static final Pattern P_TERM = Pattern.compile("\"term_id\"\\s*:\\s*\"?(\\d{1,8})\"?");
    private static final Pattern P_ZONE = Pattern.compile("\"run_zone_name\"\\s*:\\s*\"([^\"]{1,40})\"");
    private static final Pattern P_POINT = Pattern.compile(
            "\\{\"address\"[^}]*?\"id\"\\s*:\\s*(\\d+)[^}]*?\"jingwei\"\\s*:\\s*\"([0-9.]+),([0-9.]+)\"");
    /**
     * ★★★ umid —— lpi0 风控令牌的明文前半段（真机 MMKV 里的 key 名就叫 umid）。
     * 真机值形如 ab82863e63d59e250a3d836c44277484od（34 字符）。
     * MMKV 里 MMKV 的键值布局是  key <1字节类型> 值，所以用 [^0-9a-zA-Z]{1,4} 跨过类型字节。
     */
    private static final Pattern P_UMID =
            Pattern.compile("umid[^0-9a-zA-Z]{1,4}([0-9a-zA-Z]{30,40})");

    /** 执行提取。返回的对象里 trace 记录了每一步，便于在界面上显示。 */
    public static Identity extract(Ledao.Log log) {
        Identity id = new Identity();
        if (!hasRoot()) {
            id.say("✗ 没有 root 权限 —— 无法读取步道乐跑的私有目录");
            id.say("  请在 Magisk 里给本应用授权，或改用手动粘贴身份。");
            if (log != null) log.log(id.trace.toString());
            return id;
        }

        // ---- ① 设备 ID
        // ★★★ 更正（2026-10-04，docs/23）：`.system_id.log` 里那个 UUID **不是**
        //   mobileDeviceId！真机 622 次请求里 `mobileDeviceId` 只有唯一取值
        //   `ffffffff-af0e-5497-ffff-ffffef05ac4a`，而这里读到的 d69d4891-…
        //   在真机请求里出现 **0 次**。
        //   真机的 mobileDeviceId 是**运行时算出来的、不落盘**（grep 遍整个
        //   /data/data/com.lptiyu.tanke，只有 WebView 缓存带着它）——
        //   所以「从文件里读设备号」这条路本来就走不通，只能从抓包拿。
        //   这里读到的值只当**兜底**，而且调用方在已有抓包值时必须继续用抓包值。
        byte[] dev = suCat(FILE_DEVID);
        if (dev != null) {
            String s = new String(dev).trim();
            if (s.length() > 0) {
                String first = s.split("\\s+")[0].trim();
                if (first.length() == 36) {
                    id.deviceId = first;
                    id.say("① .system_id.log = " + first
                            + "  ⚠ 这不是真机的 mobileDeviceId（真机请求里出现 0 次），仅作兜底");
                }
            }
        }
        if (id.deviceId.length() == 0) id.say("① 读不到 .system_id.log（步道乐跑没装/没登录过？）");

        // ---- ② MMKV
        byte[] mk = suCat(FILE_MMKV);
        if (mk == null) {
            id.say("② 读不到 MMKV（App 没启动过？）");
            if (log != null) log.log(id.trace.toString());
            return id;
        }
        // 用 ISO-8859-1 做「字节 -> 字符」的一一映射，正则才能跨二进制工作
        String txt;
        try { txt = new String(mk, "ISO-8859-1"); } catch (Exception e) { txt = new String(mk); }

        Matcher m = P_ACCESS.matcher(txt);
        while (m.find()) if (!id.tokens.contains(m.group(1))) id.tokens.add(m.group(1));
        m = P_REFRESH.matcher(txt);
        while (m.find()) if (!id.refreshTokens.contains(m.group(1))) id.refreshTokens.add(m.group(1));
        id.say("② 找到 token 候选 " + id.tokens.size() + " 个，refresh_token 候选 "
                + id.refreshTokens.size() + " 个");

        m = P_UID.matcher(txt);            if (m.find()) id.uid = m.group(1);
        m = P_SCHOOL.matcher(txt);         if (m.find()) id.schoolId = m.group(1);
        m = P_TERM.matcher(txt);           if (m.find()) id.termId = m.group(1);
        m = P_ZONE.matcher(txt);           if (m.find()) id.zoneName = m.group(1);

        // user_num 就是学号（MMKV 里存的是 user_num，不是 student_num）
        Matcher sm = Pattern.compile("user_num[^0-9]{1,4}(\\d{6,20})").matcher(txt);
        if (sm.find()) id.studentNum = sm.group(1);

        // ★★★ umid —— lpi0 明文的前 34 字符，必须和服务端认的那个一致
        Matcher um = P_UMID.matcher(txt);
        if (um.find()) {
            id.umid = um.group(1);
            id.say("② umid = " + id.umid);
        } else {
            id.say("② ⚠ 没找到 umid —— lpi0 会回落到内置默认值（同机同装一般没问题）");
        }

        // run_rule 里的打卡点
        Matcher pm = P_POINT.matcher(txt);
        while (pm.find() && id.points.size() < 64) {
            try {
                id.points.add(new double[]{Double.parseDouble(pm.group(2)),
                                           Double.parseDouble(pm.group(3))});
                id.pointIds.add(pm.group(1));
            } catch (Exception ignore) { }
        }

        id.say("   uid=" + id.uid + "  school_id=" + id.schoolId
                + "  student_num=" + id.studentNum + "  term_id=" + id.termId);
        id.say("   跑区=" + id.zoneName + "  缓存打卡点=" + id.points.size() + " 个");

        if (id.uid.length() == 0) {
            id.say("✗ 没找到 uid —— 请先打开一次步道乐跑（让它拉一次跑区规则）再试");
        }
        if (log != null) log.log(id.trace.toString());
        return id;
    }

    /**
     * ★★★ 只读 umid —— lpi0 风控令牌明文的前 34 字符。
     * lpi0 = Base64.DEFAULT(AES-CBC(umid + MD5(timestamp), KEYS[i], IVS[i]))
     * umid 是设备绑定的，所以每次跑步前现读一次，别用写死的值。
     */
    public static String readUmid() {
        byte[] mk = suCat(FILE_MMKV);
        if (mk == null) return null;
        String txt;
        try { txt = new String(mk, "ISO-8859-1"); } catch (Exception e) { txt = new String(mk); }
        Matcher m = P_UMID.matcher(txt);
        return m.find() ? m.group(1) : null;
    }

    /** 从 MMKV 里读上一次跑步的服务器时间串（record_str），一般用不到，留着备用 */    public static String lastTimeStr() {
        byte[] mk = suCat(FILE_MMKV);
        if (mk == null) return "";
        try {
            String txt = new String(mk, "ISO-8859-1");
            Matcher m = Pattern.compile("timeStr[^0-9A-Za-z]{1,4}([0-9a-f]{32})").matcher(txt);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) { return ""; }
    }
}
