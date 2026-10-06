package com.aiproject.aiassitant.module.knowledge.service;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/** Rejects private and local destinations before a user supplied URL is fetched. */
public final class PublicHttpUrl {
    private PublicHttpUrl() {}

    public static void validate(String text) throws IOException {
        try {
            URI uri = URI.create(text);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || host == null || host.isBlank() || uri.getUserInfo() != null) {
                throw new IOException("只允许公开的 HTTP/HTTPS 地址");
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IOException("不允许访问本机或内网地址");
                }
                byte[] b = address.getAddress();
                if (address instanceof Inet6Address && (b[0] & 0xfe) == 0xfc) {
                    throw new IOException("不允许访问内网地址");
                }
                if (b.length == 4) {
                    int a = b[0] & 255, c = b[1] & 255;
                    if (a == 0 || a >= 224 || a == 100 && c >= 64 && c <= 127
                            || a == 198 && (c == 18 || c == 19)) {
                        throw new IOException("不允许访问保留地址");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("无效的网页地址", e);
        }
    }
}
