package com.llama.hub.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 简单的 IP / CIDR 匹配器，支持 IPv4 与 IPv6。
 * 规则为逗号分隔，例如 "192.168.1.1,10.0.0.0/8,::1/128"。
 */
public final class IpMatcher {

    private IpMatcher() {}

    public static boolean matches(String ip, String ruleList) {
        if (ruleList == null || ruleList.trim().isEmpty()) {
            return true;
        }
        String query = normalizeIp(ip);
        if (query == null) {
            return false;
        }
        for (String rule : ruleList.split(",")) {
            String r = rule.trim();
            if (r.isEmpty()) {
                continue;
            }
            if (matchesRule(query, r)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRule(String ip, String rule) {
        int slash = rule.indexOf('/');
        if (slash < 0) {
            return ip.equals(normalizeIp(rule));
        }
        String netIp = normalizeIp(rule.substring(0, slash));
        if (netIp == null) {
            return false;
        }
        int prefix = Integer.parseInt(rule.substring(slash + 1));
        byte[] addr = parse(ip);
        byte[] net = parse(netIp);
        if (addr == null || net == null) {
            return false;
        }
        if (addr.length != net.length) {
            return false;
        }
        int bits = addr.length * 8;
        if (prefix > bits) {
            prefix = bits;
        }
        int fullBytes = prefix / 8;
        int remBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addr[i] != net[i]) {
                return false;
            }
        }
        if (remBits > 0) {
            int mask = 0xff << (8 - remBits);
            return (addr[fullBytes] & mask) == (net[fullBytes] & mask);
        }
        return true;
    }

    private static byte[] parse(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a instanceof Inet4Address) {
                return a.getAddress();
            }
            if (a instanceof Inet6Address) {
                return a.getAddress();
            }
        } catch (UnknownHostException ignored) {
        }
        return null;
    }

    private static String normalizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        String v = ip.trim();
        try {
            InetAddress a = InetAddress.getByName(v);
            return a.getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
