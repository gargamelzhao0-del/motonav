package com.motonav.companion;

import java.net.URI;
import java.util.Locale;

/** The secret stays in the fragment when a user opens the connection link in a browser. */
public final class DiagnosticEndpoint {
    public final String uploadUrl, host, token;
    private DiagnosticEndpoint(String host, String token) {
        this.host = host; this.token = token; this.uploadUrl = "https://" + host + "/v1/events";
    }
    public static DiagnosticEndpoint parse(String link) {
        try {
            if (link == null || link.length() > 1024) throw new IllegalArgumentException();
            URI uri = new URI(link.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath(), token = uri.getRawFragment();
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || !(path.isEmpty() || path.equals("/"))
                    || !host.matches("[a-z0-9][a-z0-9-]{0,100}\\.trycloudflare\\.com")
                    || token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            return new DiagnosticEndpoint(host, token);
        } catch (Exception e) { throw new IllegalArgumentException("invalid_diagnostic_link"); }
    }
}
