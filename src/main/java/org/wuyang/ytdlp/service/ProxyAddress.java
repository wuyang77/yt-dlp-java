package org.wuyang.ytdlp.service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

record ProxyAddress(URI uri, Protocol protocol) {

    enum Protocol {
        HTTP,
        SOCKS5
    }

    static ProxyAddress resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return parse(configured);
        }
        return parse(System.getenv("YTDLP_HTTP_PROXY"));
    }

    static ProxyAddress parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = value.trim();
        if (!raw.contains("://")) {
            raw = "http://" + raw;
        }

        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            Protocol protocol = switch (scheme) {
                case "http" -> Protocol.HTTP;
                case "socks5", "socks5h" -> Protocol.SOCKS5;
                default -> throw new IllegalArgumentException(
                        "Unsupported proxy scheme. Use http://, socks5://, or socks5h://");
            };
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Proxy address must include a host");
            }
            if (uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Proxy port must be between 1 and 65535");
            }
            if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Proxy address cannot include a path, query, or fragment");
            }
            return new ProxyAddress(uri, protocol);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid proxy address", e);
        }
    }

    String value() {
        return uri.toString();
    }

    String host() {
        return uri.getHost();
    }

    int port() {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return switch (protocol) {
            case HTTP -> 80;
            case SOCKS5 -> 1080;
        };
    }

    boolean isSocks5() {
        return protocol == Protocol.SOCKS5;
    }

    Proxy toJavaProxy() {
        Proxy.Type type = isSocks5() ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, InetSocketAddress.createUnresolved(host(), port()));
    }
}
