package com.ledao.tester;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RealId —— 「载入步道乐跑 → 现场取回 mobileDeviceId」（需要 root）
 *
 * ★★★ 为什么必须有这个类（docs/23、docs/24）：
 *   真机的 mobileDeviceId（ffffffff-af0e-5497-ffff-ffffef05ac4a）**不落盘** ——
 *   MMKV / SharedPreferences / 数据库里都找不到；`.system_id.log` 里那个
 *   d69d4891-388e-44fc-90f2-167e9e626440 是真机请求里出现 **0 次**的另一个东西。
 *   以前只能靠抓包拿，抓包又要装 CA、设代理。
 *
 * ★★★ 真机 App 自己会把值放在哪（2026-10-04 在 MI 8 UD 上一条条验证）：
 *
 *   ① MEM —— 进程内存里的 H5 页面 URL。真机把 H5 的 URL 拼出来交给 WebView：
 *        https://api2.lptiyu.com/bdlp_h5_fitness_test/view/ar/goal.html
 *          ?version=229&version_name=4.2.9&mobileModel=MI_8_UD
 *          &mobileDeviceId=ffffffff-af0e-5497-ffff-ffffef05ac4a&...
 *      这个字符串活在 ART 主堆/匿名映射里。**最权威**（是它当场发出去的东西）。
 *      ⚠ 但它只在 App 真的加载了带参 H5 页面之后才在内存里 ——
 *        刚冷启动那一两秒可能还没有，所以扫不到时不要下结论（实测踩过）。
 *
 *   ② WEB —— 它自己的 WebView 缓存（HTTP Cache + Local Storage）。
 *      URL 被 Chromium 缓存下来，实测 42 处命中真值、1 处命中 .system_id.log 那个假值。
 *      只要它加载过一次 H5 页面就有，属于「板上钉钉」的二手证据。
 *
 *   ③ AUX —— files / shared_prefs / databases。
 *      ★★ 这里放的是 `.system_id.log` 之类**看着像设备号、其实不是**的东西。
 *      实测：如果把它也当候选，就会出现「取回来的值把原先正确的值覆盖掉」这种事故
 *      （2026-10-04 真发生过一次）。所以 AUX **只报告、永不自动采用**。
 */
public class RealId {

    public static final String PKG = "com.lptiyu.tanke";
    /** 真机实测反复出现的那个值（用来自检：取到的到底是不是真机在用的那个） */
    public static final String KNOWN_ID = "ffffffff-af0e-5497-ffff-ffffef05ac4a";

    /** 采集结果 */
    public static class Result {
        public int pid = 0;
        public int regions = 0;
        public long ms = 0;
        /** 内存命中：id -> 次数 */
        public final Map<String, Integer> mem = new LinkedHashMap<>();
        /** WebView 缓存命中：id -> 次数 */
        public final Map<String, Integer> web = new LinkedHashMap<>();
        /** files/shared_prefs 等辅助位置命中：id -> 次数（只报告，不采用） */
        public final Map<String, Integer> aux = new LinkedHashMap<>();
        public String id = "";
        public String source = "";
        /** 机器可读的原始输出，出问题时好排查 */
        public String raw = "";
        public final StringBuilder trace = new StringBuilder();

        public boolean ok() { return id.length() == 36; }
        /** 可信来源（内存 / WebView 缓存）—— 只有可信的值才允许覆盖身份里的旧值 */
        public boolean trusted() { return ok() && ("MEM".equals(source) || "WEB".equals(source)); }
        public boolean isKnown() { return KNOWN_ID.equals(id); }
        public void say(String s) { trace.append(s).append('\n'); }
    }

    // 只认 mobileDeviceId= 后面那个 36 字符值 —— 别的 UUID 一概不要，
    // 免得把 .system_id.log 里那个 d69d4891-… 又捞回来（真踩过）。
    private static final Pattern P = Pattern.compile("mobileDeviceId=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}"
            + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /**
     * 采集脚本。写成 sh 文件而不是一条条 su -c：
     * 正则里的反斜杠 + 引号在多层 shell 之间转义必出事故（踩过），走文件最省心。
     */
    private static String script(int secondPass) {
        return "PKG=" + PKG + "\n" +
            "APP=/data/data/$PKG\n" +
            "WEB=$APP/app_webview_$PKG\n" +
            "PAT='mobileDeviceId=[0-9a-fA-F-]\\{36\\}'\n" +
            "\n" +
            "PID=$(pidof $PKG | awk '{print $1}')\n" +
            "echo \"PID=$PID\"\n" +
            "\n" +
            "scan_mem() {\n" +
            "  i=0; budget=0\n" +
            "  for rg in $(grep -E '^[0-9a-f]+-[0-9a-f]+ rw' /proc/$PID/maps | cut -d' ' -f1); do\n" +
            "    start=${rg%-*}; end=${rg#*-}\n" +
            "    s=$((16#$start)); e=$((16#$end)); len=$((e-s))\n" +
            "    [ \"$len\" -lt 4096 ] && continue\n" +
            "    [ \"$len\" -gt 33554432 ] && len=33554432\n" +
            "    i=$((i+1)); budget=$((budget + len))\n" +
            "    [ \"$budget\" -gt 805306368 ] && break\n" +          // 单次最多读 768 MB
            "    skip=$((s/4096)); cnt=$((len/4096))\n" +
            "    out=$(dd if=/proc/$PID/mem bs=4096 skip=$skip count=$cnt 2>/dev/null " +
                     "| grep -a -o \"$PAT\" | sort | uniq -c)\n" +
            "    if [ -n \"$out\" ]; then\n" +
            "      MEMHIT=1\n" +
            "      echo \"$out\" | while read -r c v; do echo \"MEM $c $v $rg\"; done\n" +
            "      echo \"MEMREG=$i\"\n" +
            "      return 0\n" +
            "    fi\n" +
            "  done\n" +
            "  echo \"MEMREG=$i\"\n" +
            "  return 1\n" +
            "}\n" +
            "\n" +
            "MEMHIT=\n" +
            "echo '==MEM=='\n" +
            "[ -n \"$PID\" ] && scan_mem\n" +
            (secondPass == 1
                ? "if [ -z \"$MEMHIT\" ]; then\n" +
                  "  echo '==MEM2=='\n" +   // App 可能这会儿才把 H5 页面加载出来
                  "  sleep 6\n" +
                  "  [ -n \"$PID\" ] && scan_mem\n" +
                  "fi\n"
                : "") +
            "\n" +
            "echo '==WEB=='\n" +
            "for d in \"$WEB\" \"$APP/cache\"; do\n" +
            "  [ -d \"$d\" ] || continue\n" +
            "  grep -rhao \"$PAT\" \"$d\" 2>/dev/null | sort | uniq -c " +
                   "| while read -r c v; do echo \"WEB $c $v $d\"; done\n" +
            "done\n" +
            "\n" +
            "echo '==AUX=='\n" +
            "for d in \"$APP/files\" \"$APP/shared_prefs\" \"$APP/databases\"; do\n" +
            "  [ -d \"$d\" ] || continue\n" +
            "  grep -rhao \"$PAT\" \"$d\" 2>/dev/null | sort | uniq -c " +
                   "| while read -r c v; do echo \"AUX $c $v $d\"; done\n" +
            "done\n" +
            "echo '==DONE=='\n";
    }

    // ------------------------------------------------------------------ 脚本落盘
    private static String writeScript(Context ctx, int secondPass) {
        try {
            File f = new File(ctx.getFilesDir(), "realid.sh");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(script(secondPass).getBytes("UTF-8"));
            fo.close();
            f.setReadable(true, false);
            f.setExecutable(true, false);
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** su -c 跑命令，带超时；返回 stdout */
    private static String run(String cmd, long deadlineMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.getOutputStream().close();
            InputStream is = p.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long deadline = System.currentTimeMillis() + deadlineMs;
            while (System.currentTimeMillis() < deadline && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
            InputStream es = p.getErrorStream();       // 抽干 stderr，免得 su 卡在写管道上
            while (es != null && es.read(buf) > 0) { /* drain */ }
            p.waitFor();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignore) { }
        }
    }

    private static int pidOf() {
        String s = Identity.su("pidof " + PKG);
        if (s == null) return 0;
        for (String t : s.trim().split("\\s+")) {
            try { return Integer.parseInt(t.trim()); } catch (Exception ignore) { }
        }
        return 0;
    }

    // ------------------------------------------------------------------ 主入口
    /**
     * 载入步道乐跑（没在跑就拉起来）并现场取回 mobileDeviceId。
     *
     * @param launch true = 没在跑就把它拉起来
     */
    public static Result capture(Context ctx, boolean launch, Ledao.Log log) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();

        if (!Identity.hasRoot()) {
            r.say("✗ 没有 root —— 取不了（这条路要读真机进程内存和它的私有目录）");
            r.say("   " + Identity.rootDiagnosis());
            if (log != null) log.log(r.trace.toString());
            return r;
        }

        int pid = pidOf();
        if (pid == 0 && launch) {
            r.say("① 步道乐跑没在跑 —— 现在载入它");
            Identity.su("monkey -p " + PKG + " -c android.intent.category.LAUNCHER 1");
            for (int i = 0; i < 25 && pid == 0; i++) {          // 最多等 25 秒
                try { Thread.sleep(1000); } catch (Exception ignore) { }
                pid = pidOf();
            }
            if (pid != 0) {
                r.say("   进程起来了 pid=" + pid + " —— 等 8 秒，让它把带 mobileDeviceId 的 H5 页面加载出来");
                try { Thread.sleep(8000); } catch (Exception ignore) { }
            }
        }
        if (pid == 0) {
            r.say("✗ 步道乐跑没在跑，也拉不起来（装了没？）");
            if (log != null) log.log(r.trace.toString());
            return r;
        }
        r.pid = pid;
        r.say("① 目标进程 pid=" + pid);

        String path = writeScript(ctx, 1);
        if (path == null) {
            r.say("✗ 采集脚本写不进私有目录");
            if (log != null) log.log(r.trace.toString());
            return r;
        }

        String out = run("sh '" + path + "'", 300000);
        r.raw = out;
        r.ms = System.currentTimeMillis() - t0;
        if (out.length() == 0) {
            r.say("✗ 采集脚本没有输出（su 被拒了？）");
            if (log != null) log.log(r.trace.toString());
            return r;
        }

        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("MEMREG=")) {
                try {
                    int n = Integer.parseInt(line.substring(7).trim());
                    if (n > r.regions) r.regions = n;
                } catch (Exception ignore) { }
                continue;
            }
            Map<String, Integer> dst = null;
            if (line.startsWith("MEM ")) dst = r.mem;
            else if (line.startsWith("WEB ")) dst = r.web;
            else if (line.startsWith("AUX ")) dst = r.aux;
            if (dst == null) continue;
            Matcher m = P.matcher(line);
            if (!m.find()) continue;
            int cnt = 1;
            try { cnt = Integer.parseInt(line.split("\\s+")[1]); } catch (Exception ignore) { }
            String val = m.group(1);
            Integer old = dst.get(val);
            dst.put(val, old == null ? cnt : old + cnt);
        }

        // ★★★ 选值规则（这里出过一次事故，所以写死顺序）：
        //   ① 内存里的（活的、它当场用的）→ 直接采用
        //   ② WebView 缓存里的 → 取命中次数最多的那个
        //   ③ files/shared_prefs（AUX）→ **只报告，绝不采用**
        //      `.system_id.log` 就在里面，它看着像设备号，其实真机从来没用过。
        if (r.mem.size() > 0) {
            r.id = pickMost(r.mem);
            r.source = "MEM";
        } else if (r.web.size() > 0) {
            r.id = pickMost(r.web);
            r.source = "WEB";
        }

        r.say("② 内存：扫了 " + r.regions + " 个 rw 段，命中 " + r.mem.size() + " 个取值"
                + (r.mem.isEmpty() ? "（可能它还没加载带参 H5 页面）" : "  " + r.mem));
        r.say("   WebView 缓存：命中 " + r.web.size() + " 个取值"
                + (r.web.isEmpty() ? "" : "  " + r.web));
        if (!r.aux.isEmpty()) {
            r.say("   ⚠ files/shared_prefs 里另有 " + r.aux.size() + " 个「像设备号但不是」的值"
                    + "（.system_id.log 就在这里，真机请求里出现 0 次）：" + r.aux + " —— 已忽略");
        }
        if (r.ok()) {
            r.say("③ 取到 mobileDeviceId = " + r.id + "   （来源 " + r.source
                    + (r.trusted() ? "，可信" : "，不可信") + "）");
            r.say(r.isKnown()
                    ? "   ✅ 就是真机一直在用的那个值，服务端一定认"
                    : "   ⚠ 和已知值不一样 —— 真机换过标识（重装/清数据）？以这次取到的为准");
        } else {
            r.say("✗ 内存和 WebView 缓存里都没有 —— "
                    + "先手动打开一次步道乐跑、随便点进一个页面，再回来点一次");
        }
        r.say("   用时 " + r.ms + " ms");

        if (r.trusted()) {
            // ④ 收尾：把步道乐跑关掉。
            //   ★★ 这一步不能省（2026-10-04 实测踩到）：refresh_token 是一次性的，
            //      乐跑**每启动一次**都会刷一次令牌，服务端只认最新那份 ——
            //      不关掉它，我们取完标识开始跑步时手上那份已经是旧的，一路 102
            //      「该账号已在其他手机登录」。取完就结束它，再等它把新令牌落盘，
            //      调用方随后重新读一次 MMKV 里的令牌（MainActivity 里接着做）。
            Identity.su("am force-stop " + PKG);
            r.say("④ 已结束步道乐跑的进程（它启动一次就会刷一次令牌，不关掉会把我们顶下线）");
            try { Thread.sleep(1500); } catch (Exception ignore) { }
            r.say("   等 1.5 秒让它把新令牌写进 MMKV —— 调用方随后要重新提取一次身份");
        }
        if (log != null) log.log(r.trace.toString());
        return r;
    }

    /** 取命中次数最多的那个（WebView 缓存里真值 42 次、假值 1 次） */
    private static String pickMost(Map<String, Integer> m) {
        String best = null;
        int bc = -1;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() > bc) { bc = e.getValue(); best = e.getKey(); }
        }
        return best == null ? "" : best;
    }

    /** 只要值，给自动提取用（不可信就返回空串） */
    public static String harvest(Context ctx, Ledao.Log log) {
        Result r = capture(ctx, true, log);
        return r.trusted() ? r.id : "";
    }
}
