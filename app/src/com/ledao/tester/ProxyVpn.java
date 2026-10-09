package com.ledao.tester;

import android.content.Intent;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * ProxyVpn —— 用 VPN 把「步道乐跑」的 HTTP(S) 流量引到我们内置的本地代理，
 *            从而**不用改系统代理**（也就不会触发它的 isWifiProxy 检测）。
 *
 * 原理（API 29 / Android 10 起可用）：
 *   VpnService.Builder.setHttpProxy(127.0.0.1:8899)
 *   → 被 VPN 覆盖的应用，其 HTTP(S) 请求会自动发到 127.0.0.1:8899。
 *     走的是**回环**，不经过 tun，所以不需要自己写 TCP/IP 协议栈。
 *
 *   ★ 关键：把自己 addDisallowedApplication 排除在 VPN 之外，
 *     否则我们的代理进程也出不去，会自己把自己憋死。
 *
 *   tun 里剩下的是非 HTTP 流量（DNS / QUIC 之类），我们直接丢弃：
 *   HTTP(S) 都走代理了，代理自己解析域名，不依赖 tun 里的 DNS。
 *
 * 为什么这样更好：
 *   · 不需要 adb 授权 WRITE_SECURE_SETTINGS，也不需要手动去 WLAN 里改代理
 *   · 系统代理栏位保持「无」→ 步道乐跑记到的 isWifiProxy 依然是 false
 *   · 用户只需要点一次「允许 VPN」
 *
 * 兼容性：Android 10 以下没有 setHttpProxy，此时本类会建立失败，
 *         调用方（MainActivity）会自动退回「改系统代理」的老路。
 */
public class ProxyVpn extends VpnService {

    public static final String ACTION_START = "com.ledao.tester.VPN_START";
    public static final String ACTION_STOP = "com.ledao.tester.VPN_STOP";

    /** VPN 是否已经建立（供界面判断） */
    public static volatile boolean running = false;
    /** 建立失败的原因，供界面显示 */
    public static volatile String lastError = "";

    private static final String VPN_ADDR = "10.111.0.2";
    private static final int VPN_PREFIX = 32;

    private ParcelFileDescriptor tun;
    private Thread drain;

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 29;      // setHttpProxy 是 API 29 加的
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        if (it != null && ACTION_STOP.equals(it.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        lastError = "";
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (tun != null) return;
        if (!supported()) {
            lastError = "Android 版本低于 10，不支持 VPN 代理（请改用系统代理）";
            return;
        }
        try {
            Builder b = new Builder();
            b.setSession("乐跑测试助手");
            b.addAddress(VPN_ADDR, VPN_PREFIX);
            b.addDnsServer("8.8.8.8");
            b.addRoute("0.0.0.0", 0);
            // ★ 把自己排除掉 —— 我们的代理要能正常访问外网
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignore) { }
            // ★ 核心：被 VPN 覆盖的应用，HTTP(S) 走我们的本地代理
            b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", LocalProxy.PORT));

            tun = b.establish();
            if (tun == null) {
                lastError = "establish() 返回 null（VPN 权限被拒绝？）";
                running = false;
                return;
            }
            running = true;
            startDrain();
        } catch (Exception e) {
            lastError = String.valueOf(e);
            running = false;
            closeTun();
        }
    }

    /** tun 里剩下的非 HTTP 流量直接丢掉，避免队列堆积 */
    private void startDrain() {
        final ParcelFileDescriptor fd = tun;
        if (fd == null) return;
        drain = new Thread(() -> {
            try {
                InputStream is = new FileInputStream(fd.getFileDescriptor());
                byte[] buf = new byte[32768];
                while (running && is.read(buf) > 0) {
                    // 故意丢弃
                }
            } catch (Exception ignore) {
                // 关闭时会抛异常，正常
            }
        }, "vpn-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private void closeTun() {
        try { if (tun != null) tun.close(); } catch (Exception ignore) { }
        tun = null;
    }

    public void stopVpn() {
        running = false;
        closeTun();
        if (drain != null) { drain.interrupt(); drain = null; }
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        // 用户在系统里撤掉了 VPN 授权
        stopVpn();
        super.onRevoke();
    }
}
