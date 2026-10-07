package com.mauricio.adaptiveperformance;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

public class AdGuardDnsVpnService extends VpnService {
    public static final String ACTION_START = "com.mauricio.adaptiveperformance.DNS_FIREWALL_START";
    public static final String ACTION_STOP = "com.mauricio.adaptiveperformance.DNS_FIREWALL_STOP";
    public static final String ACTION_RESTART = "com.mauricio.adaptiveperformance.DNS_FIREWALL_RESTART";

    private static final String CH = "adguard_dns_firewall";
    private static final int NOTIF_ID = 7714;
    private static final String VPN_ADDR = "10.111.0.1";
    private static final String VPN_DNS = "10.111.0.2";
    private static final String DNS_PRIMARY = "94.140.14.14";
    private static final String DNS_SECONDARY = "94.140.15.15";

    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private Thread worker;

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, notification("Preparando firewall DNS…"));
        startVpn();
        return START_STICKY;
    }

    private synchronized void startVpn() {
        stopVpnOnly();
        android.content.SharedPreferences p = getSharedPreferences("adaptive", MODE_PRIVATE);
        Set<String> selected = new HashSet<>(p.getStringSet("dns_firewall_filtered_apps", Collections.emptySet()));
        selected.remove(getPackageName());

        if (selected.isEmpty()) {
            p.edit().putBoolean("dns_firewall_running", false)
                    .putString("dns_firewall_status", "Nenhum app selecionado para filtrar").apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        try {
            Builder b = new Builder()
                    .setSession("Adaptive • AdGuard DNS")
                    .addAddress(VPN_ADDR, 32)
                    .addDnsServer(VPN_DNS)
                    .addRoute(VPN_DNS, 32)
                    .setMtu(1500)
                    .setBlocking(true);
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);

            int added = 0;
            for (String pkg : selected) {
                try {
                    b.addAllowedApplication(pkg);
                    added++;
                } catch (Exception ignored) {}
            }
            if (added == 0) throw new IllegalStateException("Nenhum pacote válido selecionado");

            tun = b.establish();
            if (tun == null) throw new IllegalStateException("VPN não autorizada");
            running = true;
            p.edit().putBoolean("dns_firewall_running", true)
                    .putInt("dns_firewall_filtered_count", added)
                    .putString("dns_firewall_status", "Ativo • " + added + " app(s) filtrados pelo AdGuard DNS")
                    .putLong("dns_firewall_started_at", System.currentTimeMillis()).apply();
            startForeground(NOTIF_ID, notification("AdGuard DNS ativo • " + added + " app(s)"));
            worker = new Thread(this::loop, "AdGuardDnsVpn");
            worker.start();
        } catch (Throwable t) {
            running = false;
            p.edit().putBoolean("dns_firewall_running", false)
                    .putString("dns_firewall_status", "Falha ao iniciar: " + t.getClass().getSimpleName()).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void loop() {
        android.content.SharedPreferences p = getSharedPreferences("adaptive", MODE_PRIVATE);
        long queries = p.getLong("dns_firewall_queries", 0);
        long failures = p.getLong("dns_firewall_failures", 0);
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            byte[] buf = new byte[32767];
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                byte[] response = handleIpv4UdpDns(buf, n);
                if (response != null) {
                    out.write(response);
                    queries++;
                    if ((queries & 15) == 0) p.edit().putLong("dns_firewall_queries", queries).apply();
                }
            }
        } catch (Throwable t) {
            if (running) {
                failures++;
                p.edit().putLong("dns_firewall_failures", failures)
                        .putString("dns_firewall_status", "Firewall interrompido; rede normal preservada").apply();
            }
        } finally {
            p.edit().putLong("dns_firewall_queries", queries)
                    .putLong("dns_firewall_failures", failures)
                    .putBoolean("dns_firewall_running", false).apply();
        }
    }

    private byte[] handleIpv4UdpDns(byte[] packet, int len) {
        try {
            if (len < 28 || (packet[0] >> 4 & 0xF) != 4) return null;
            int ihl = (packet[0] & 0x0F) * 4;
            if (ihl < 20 || len < ihl + 8 || (packet[9] & 0xFF) != 17) return null;
            int dstPort = u16(packet, ihl + 2);
            if (dstPort != 53) return null;
            int udpLen = u16(packet, ihl + 4);
            int dnsLen = Math.min(len - ihl - 8, udpLen - 8);
            if (dnsLen <= 0) return null;

            byte[] dns = Arrays.copyOfRange(packet, ihl + 8, ihl + 8 + dnsLen);
            byte[] answer = forwardDns(dns);
            if (answer == null) return null;

            int srcPort = u16(packet, ihl);
            byte[] srcIp = Arrays.copyOfRange(packet, 12, 16);
            byte[] dstIp = Arrays.copyOfRange(packet, 16, 20);
            return buildUdpResponse(dstIp, srcIp, 53, srcPort, answer);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private byte[] forwardDns(byte[] query) {
        byte[] servers = new byte[]{0,1};
        for (byte which : servers) {
            String host = which == 0 ? DNS_PRIMARY : DNS_SECONDARY;
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                protect(s);
                s.setSoTimeout(2500);
                InetAddress address = InetAddress.getByName(host);
                s.send(new DatagramPacket(query, query.length, address, 53));
                byte[] ans = new byte[4096];
                DatagramPacket r = new DatagramPacket(ans, ans.length);
                s.receive(r);
                return Arrays.copyOf(ans, r.getLength());
            } catch (Throwable ignored) {
            } finally {
                if (s != null) s.close();
            }
        }
        getSharedPreferences("adaptive", MODE_PRIVATE).edit()
                .putLong("dns_firewall_last_failure_at", System.currentTimeMillis()).apply();
        return null;
    }

    private static byte[] buildUdpResponse(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort, byte[] payload) {
        int total = 20 + 8 + payload.length;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[1] = 0;
        put16(p, 2, total);
        put16(p, 4, (int)(System.nanoTime() & 0xFFFF));
        put16(p, 6, 0);
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(srcIp, 0, p, 12, 4);
        System.arraycopy(dstIp, 0, p, 16, 4);
        put16(p, 10, ipChecksum(p, 0, 20));
        put16(p, 20, srcPort);
        put16(p, 22, dstPort);
        put16(p, 24, 8 + payload.length);
        put16(p, 26, 0); // UDP checksum zero is valid for IPv4.
        System.arraycopy(payload, 0, p, 28, payload.length);
        return p;
    }

    private static int ipChecksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len; i += 2) {
            if (i == off + 10) continue;
            int hi = b[i] & 0xFF;
            int lo = (i + 1 < off + len) ? (b[i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
            while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int)(~sum) & 0xFFFF;
    }
    private static int u16(byte[] b, int o) { return ((b[o] & 255) << 8) | (b[o+1] & 255); }
    private static void put16(byte[] b, int o, int v) { b[o]=(byte)(v>>8); b[o+1]=(byte)v; }

    private synchronized void stopVpn() {
        stopVpnOnly();
        getSharedPreferences("adaptive", MODE_PRIVATE).edit()
                .putBoolean("dns_firewall_running", false)
                .putString("dns_firewall_status", "Desativado").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    private synchronized void stopVpnOnly() {
        running = false;
        Thread oldWorker = worker;
        worker = null;
        try { if (tun != null) tun.close(); } catch (Throwable ignored) {}
        tun = null;
        if (oldWorker != null) {
            oldWorker.interrupt();
            if (oldWorker != Thread.currentThread()) {
                try { oldWorker.join(500L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
    }
    @Override public void onDestroy() { stopVpn(); super.onDestroy(); }

    private Notification notification(String text) {
        Intent i = new Intent(this, DnsFirewallActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Adaptive Performance • Firewall DNS")
                .setContentText(text).setContentIntent(pi).setOngoing(true).build();
    }
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager n = getSystemService(NotificationManager.class);
            n.createNotificationChannel(new NotificationChannel(CH, "Firewall DNS", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
