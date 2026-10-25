package org.telegram.utils.proxy;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Parses V2Ray share links (vless://, vmess://, trojan://, ss://) and builds an Xray-core
 * config that exposes the server as a local SOCKS5 proxy for Telegram to connect through.
 */
public final class V2RayConfig {

    public final @NonNull String link;
    public final @NonNull String protocol;
    public final @NonNull String address;
    public final int port;
    public final @NonNull String remark;
    private final @NonNull JSONObject outbound;

    private V2RayConfig(@NonNull String link, @NonNull String protocol, @NonNull String address, int port, @Nullable String remark, @NonNull JSONObject outbound) {
        this.link = link;
        this.protocol = protocol;
        this.address = address;
        this.port = port;
        this.remark = remark != null ? remark : "";
        this.outbound = outbound;
    }

    public static boolean isV2RayLink(@Nullable String link) {
        if (link == null) {
            return false;
        }
        final String lower = link.trim().toLowerCase(Locale.US);
        return lower.startsWith("vless://") || lower.startsWith("vmess://") || lower.startsWith("trojan://") || lower.startsWith("ss://");
    }

    @Nullable
    public static V2RayConfig parse(@Nullable String link) {
        try {
            return parseOrThrow(link);
        } catch (Exception e) {
            return null;
        }
    }

    @NonNull
    public static V2RayConfig parseOrThrow(@Nullable String link) throws Exception {
        if (!isV2RayLink(link)) {
            throw new IllegalArgumentException("Unsupported link");
        }
        link = link.trim();
        final String scheme = link.substring(0, link.indexOf(':')).toLowerCase(Locale.US);
        switch (scheme) {
            case "vmess":
                return parseVmess(link);
            case "ss":
                return parseShadowsocks(link);
            case "vless":
            case "trojan":
                return parseUriLink(link, scheme);
            default:
                throw new IllegalArgumentException("Unsupported protocol: " + scheme);
        }
    }

    /** Xray config with a SOCKS5 inbound on 127.0.0.1:{@code socksPort}. */
    @NonNull
    public String buildConfig(int socksPort) throws JSONException {
        final JSONObject config = new JSONObject();
        config.put("log", new JSONObject().put("loglevel", "warning"));

        final JSONObject inbound = new JSONObject()
                .put("tag", "socks")
                .put("listen", "127.0.0.1")
                .put("port", socksPort)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "noauth").put("udp", false));
        config.put("inbounds", new JSONArray().put(inbound));

        final JSONObject proxy = new JSONObject(outbound.toString()).put("tag", "proxy");
        config.put("outbounds", new JSONArray()
                .put(proxy)
                .put(new JSONObject().put("tag", "direct").put("protocol", "freedom")));
        return config.toString();
    }

    @NonNull
    public String getDisplayName() {
        if (!TextUtils.isEmpty(remark)) {
            return remark;
        }
        return address + ":" + port;
    }

    private static V2RayConfig parseUriLink(String link, String scheme) throws Exception {
        final Uri uri = Uri.parse(link);
        final String userInfo = uri.getUserInfo();
        final String host = stripBrackets(uri.getHost());
        final int port = uri.getPort();
        if (TextUtils.isEmpty(userInfo) || TextUtils.isEmpty(host) || port <= 0) {
            throw new IllegalArgumentException("Invalid " + scheme + " link");
        }
        final String security = param(uri, "security", "trojan".equals(scheme) ? "tls" : "none");
        final String encryption = param(uri, "encryption", "none");
        if ("none".equalsIgnoreCase(security) && ("trojan".equals(scheme) || "none".equals(encryption))) {
            // Xray-core refuses plaintext VLESS/Trojan to public servers.
            throw new IllegalArgumentException(scheme + " without TLS/REALITY is not supported by Xray-core");
        }
        final JSONObject outbound = new JSONObject();
        if ("vless".equals(scheme)) {
            final JSONObject user = new JSONObject()
                    .put("id", userInfo)
                    .put("encryption", encryption);
            final String flow = uri.getQueryParameter("flow");
            if (!TextUtils.isEmpty(flow)) {
                user.put("flow", flow);
            }
            outbound.put("protocol", "vless");
            outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(new JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("users", new JSONArray().put(user)))));
        } else {
            outbound.put("protocol", "trojan");
            outbound.put("settings", new JSONObject().put("servers", new JSONArray().put(new JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("password", userInfo))));
        }
        final StreamParams params = new StreamParams();
        params.network = param(uri, "type", "tcp");
        params.security = security;
        params.headerType = uri.getQueryParameter("headerType");
        params.host = uri.getQueryParameter("host");
        params.path = uri.getQueryParameter("path");
        params.serviceName = uri.getQueryParameter("serviceName");
        params.authority = uri.getQueryParameter("authority");
        params.mode = uri.getQueryParameter("mode");
        params.extra = uri.getQueryParameter("extra");
        params.seed = uri.getQueryParameter("seed");
        params.sni = firstNonEmpty(uri.getQueryParameter("sni"), uri.getQueryParameter("peer"));
        params.alpn = uri.getQueryParameter("alpn");
        params.fingerprint = uri.getQueryParameter("fp");
        params.publicKey = firstNonEmpty(uri.getQueryParameter("pbk"), uri.getQueryParameter("password"));
        params.shortId = uri.getQueryParameter("sid");
        params.spiderX = uri.getQueryParameter("spx");
        params.mldsa65Verify = uri.getQueryParameter("pqv");
        params.pinnedPeerCertSha256 = uri.getQueryParameter("pcs");
        params.verifyPeerCertByName = uri.getQueryParameter("vcn");
        params.echConfigList = uri.getQueryParameter("ech");
        outbound.put("streamSettings", params.build());
        return new V2RayConfig(link, scheme, host, port, uri.getFragment(), outbound);
    }

    private static V2RayConfig parseVmess(String link) throws Exception {
        final JSONObject json = new JSONObject(decodeBase64(link.substring("vmess://".length())));
        final String host = json.optString("add");
        final int port = parsePort(json.optString("port"));
        final String id = json.optString("id");
        if (TextUtils.isEmpty(host) || port <= 0 || TextUtils.isEmpty(id)) {
            throw new IllegalArgumentException("Invalid vmess link");
        }
        final JSONObject outbound = new JSONObject()
                .put("protocol", "vmess")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(new JSONObject()
                        .put("address", host)
                        .put("port", port)
                        .put("users", new JSONArray().put(new JSONObject()
                                .put("id", id)
                                .put("security", nonEmpty(json.optString("scy"), "auto")))))));
        final StreamParams params = new StreamParams();
        params.network = nonEmpty(json.optString("net"), "tcp");
        params.security = nonEmpty(json.optString("tls"), "none");
        params.headerType = json.optString("type");
        params.host = json.optString("host");
        params.path = json.optString("path");
        if ("grpc".equals(params.network)) {
            params.serviceName = params.path;
            params.mode = params.headerType;
            params.headerType = null;
        } else if ("kcp".equals(params.network)) {
            params.seed = params.path;
        }
        params.sni = json.optString("sni");
        params.alpn = json.optString("alpn");
        params.fingerprint = json.optString("fp");
        params.pinnedPeerCertSha256 = json.optString("pcs");
        params.verifyPeerCertByName = json.optString("vcn");
        params.echConfigList = json.optString("ech");
        outbound.put("streamSettings", params.build());
        return new V2RayConfig(link, "vmess", host, port, json.optString("ps"), outbound);
    }

    private static V2RayConfig parseShadowsocks(String link) throws Exception {
        String body = link.substring("ss://".length());
        String remark = null;
        final int hash = body.indexOf('#');
        if (hash >= 0) {
            remark = Uri.decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        final int query = body.indexOf('?');
        if (query >= 0) {
            body = body.substring(0, query);
        }
        if (body.endsWith("/")) {
            body = body.substring(0, body.length() - 1);
        }
        String userInfo;
        String hostPort;
        final int at = body.lastIndexOf('@');
        if (at >= 0) {
            // SIP002: base64url(method:password)@host:port, or plain method:password@host:port
            userInfo = Uri.decode(body.substring(0, at));
            if (userInfo.indexOf(':') < 0) {
                userInfo = decodeBase64(userInfo);
            }
            hostPort = body.substring(at + 1);
        } else {
            // Legacy: base64(method:password@host:port)
            final String decoded = decodeBase64(body);
            final int decodedAt = decoded.lastIndexOf('@');
            if (decodedAt < 0) {
                throw new IllegalArgumentException("Invalid ss link");
            }
            userInfo = decoded.substring(0, decodedAt);
            hostPort = decoded.substring(decodedAt + 1);
        }
        final int colon = userInfo.indexOf(':');
        final int portColon = hostPort.lastIndexOf(':');
        if (colon <= 0 || portColon <= 0) {
            throw new IllegalArgumentException("Invalid ss link");
        }
        final String method = userInfo.substring(0, colon);
        final String password = userInfo.substring(colon + 1);
        final String host = stripBrackets(hostPort.substring(0, portColon));
        final int port = parsePort(hostPort.substring(portColon + 1));
        if (TextUtils.isEmpty(host) || port <= 0) {
            throw new IllegalArgumentException("Invalid ss link");
        }
        final JSONObject outbound = new JSONObject()
                .put("protocol", "shadowsocks")
                .put("settings", new JSONObject().put("servers", new JSONArray().put(new JSONObject()
                        .put("address", host)
                        .put("port", port)
                        .put("method", method)
                        .put("password", password))));
        return new V2RayConfig(link, "ss", host, port, remark, outbound);
    }

    private static final class StreamParams {
        String network;
        String security;
        String headerType;
        String host;
        String path;
        String serviceName;
        String authority;
        String mode;
        String extra;
        String seed;
        String sni;
        String alpn;
        String fingerprint;
        String publicKey;
        String shortId;
        String spiderX;
        String mldsa65Verify;
        String pinnedPeerCertSha256;
        String verifyPeerCertByName;
        String echConfigList;

        JSONObject build() throws JSONException {
            String network = this.network.toLowerCase(Locale.US);
            final JSONObject stream = new JSONObject();
            switch (network) {
                case "tcp":
                case "raw":
                    network = "raw";
                    if ("http".equals(headerType)) {
                        final JSONObject request = new JSONObject()
                                .put("path", new JSONArray().put(nonEmpty(path, "/")));
                        if (!TextUtils.isEmpty(host)) {
                            request.put("headers", new JSONObject().put("Host", splitToArray(host)));
                        }
                        stream.put("rawSettings", new JSONObject().put("header", new JSONObject()
                                .put("type", "http")
                                .put("request", request)));
                    }
                    break;
                case "ws":
                case "websocket":
                    network = "ws";
                    stream.put("wsSettings", new JSONObject()
                            .put("path", nonEmpty(path, "/"))
                            .put("host", nonEmpty(host, "")));
                    break;
                case "httpupgrade":
                    stream.put("httpupgradeSettings", new JSONObject()
                            .put("path", nonEmpty(path, "/"))
                            .put("host", nonEmpty(host, "")));
                    break;
                case "xhttp":
                case "splithttp":
                    network = "xhttp";
                    final JSONObject xhttp = new JSONObject()
                            .put("path", nonEmpty(path, "/"))
                            .put("host", nonEmpty(host, ""))
                            .put("mode", nonEmpty(mode, "auto"));
                    if (!TextUtils.isEmpty(extra)) {
                        xhttp.put("extra", new JSONObject(extra));
                    }
                    stream.put("xhttpSettings", xhttp);
                    break;
                case "grpc":
                    final JSONObject grpc = new JSONObject()
                            .put("serviceName", nonEmpty(serviceName, ""))
                            .put("multiMode", "multi".equals(mode));
                    if (!TextUtils.isEmpty(authority)) {
                        grpc.put("authority", authority);
                    }
                    stream.put("grpcSettings", grpc);
                    break;
                case "kcp":
                case "mkcp":
                    network = "kcp";
                    final JSONObject kcp = new JSONObject()
                            .put("header", new JSONObject().put("type", nonEmpty(headerType, "none")));
                    if (!TextUtils.isEmpty(seed)) {
                        kcp.put("seed", seed);
                    }
                    stream.put("kcpSettings", kcp);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported transport: " + network);
            }
            stream.put("network", network);

            final String security = this.security.toLowerCase(Locale.US);
            switch (security) {
                case "":
                case "none":
                    stream.put("security", "none");
                    break;
                case "tls":
                    final JSONObject tls = new JSONObject();
                    final String serverName = firstNonEmpty(sni, firstHost(host));
                    if (!TextUtils.isEmpty(serverName)) {
                        tls.put("serverName", serverName);
                    }
                    if (!TextUtils.isEmpty(fingerprint)) {
                        tls.put("fingerprint", fingerprint);
                    }
                    if (!TextUtils.isEmpty(alpn)) {
                        tls.put("alpn", splitToArray(alpn));
                    }
                    if (!TextUtils.isEmpty(pinnedPeerCertSha256)) {
                        tls.put("pinnedPeerCertSha256", pinnedPeerCertSha256);
                    }
                    if (!TextUtils.isEmpty(verifyPeerCertByName)) {
                        tls.put("verifyPeerCertByName", verifyPeerCertByName);
                    }
                    if (!TextUtils.isEmpty(echConfigList)) {
                        tls.put("echConfigList", echConfigList);
                    }
                    stream.put("security", "tls");
                    stream.put("tlsSettings", tls);
                    break;
                case "reality":
                    if (TextUtils.isEmpty(publicKey)) {
                        throw new IllegalArgumentException("REALITY link has no public key (pbk)");
                    }
                    final JSONObject reality = new JSONObject()
                            .put("serverName", nonEmpty(sni, ""))
                            .put("fingerprint", nonEmpty(fingerprint, "chrome"))
                            .put("publicKey", publicKey)
                            .put("shortId", nonEmpty(shortId, ""))
                            .put("spiderX", nonEmpty(spiderX, ""));
                    if (!TextUtils.isEmpty(mldsa65Verify)) {
                        reality.put("mldsa65Verify", mldsa65Verify);
                    }
                    stream.put("security", "reality");
                    stream.put("realitySettings", reality);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported security: " + security);
            }
            return stream;
        }
    }

    private static String param(Uri uri, String name, String def) {
        final String value = uri.getQueryParameter(name);
        return TextUtils.isEmpty(value) ? def : value;
    }

    private static String nonEmpty(String value, String def) {
        return TextUtils.isEmpty(value) ? def : value;
    }

    private static String firstNonEmpty(String a, String b) {
        return TextUtils.isEmpty(a) ? b : a;
    }

    private static String firstHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        final int comma = host.indexOf(',');
        return (comma >= 0 ? host.substring(0, comma) : host).trim();
    }

    private static JSONArray splitToArray(String value) {
        final JSONArray array = new JSONArray();
        for (String part : value.split(",")) {
            part = part.trim();
            if (!part.isEmpty()) {
                array.put(part);
            }
        }
        return array;
    }

    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String decodeBase64(String value) {
        value = value.trim().replace('-', '+').replace('_', '/');
        return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
    }
}
