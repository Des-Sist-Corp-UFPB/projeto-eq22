package com.iwrite.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The one place that decides whether {@code X-Forwarded-For}/{@code Forwarded} can be believed.
 * Everything else in the app should call {@link #resolve(HttpServletRequest)} instead of reading
 * {@code request.getRemoteAddr()} or a forwarded header directly.
 *
 * <p>Confirmed against the real Compose topology (frontend proxies {@code /api} to the backend
 * over the compose network; the backend's port is also published to the host): a request that
 * reaches this backend directly on its published port arrives with {@code remoteAddr} set to the
 * compose network's gateway address, while a request proxied through the frontend container
 * arrives with {@code remoteAddr} set to that container's own address — the two are always
 * distinct addresses on the same subnet. Trusting the whole subnet (e.g. Tomcat's built-in
 * "internal proxies" default, which matches any private range) would trust both indiscriminately,
 * which is exactly the spoofing path this class exists to close; trusting one specific,
 * operator-configured peer does not.
 *
 * <p>{@code iwrite.auth.trusted-proxies} lists the peers allowed to supply a forwarded address —
 * IPs, CIDRs, or hostnames (resolved by DNS on every check, not cached at startup, because in the
 * Compose topology the frontend container may not exist yet when this bean is constructed: the
 * backend starts first). Empty (the default) means no peer is trusted and {@code remoteAddr} is
 * always used — the safe default for any deployment that has not explicitly named its proxy.
 */
@Component
public class ClientAddressResolver {

    private final List<String> trustedProxies;

    public ClientAddressResolver(@Value("${iwrite.auth.trusted-proxies:}") String trustedProxiesCsv) {
        this.trustedProxies = Arrays.stream(trustedProxiesCsv.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toList());
        // Literal IPs/CIDRs are validated eagerly, so a typo fails the deployment at startup
        // rather than silently disabling trust. Hostnames cannot be validated here: see the class
        // Javadoc on why resolution is deferred to request time.
        for (String entry : trustedProxies) {
            if (looksLikeAddress(entry)) {
                new IpAddressMatcher(entry); // throws IllegalArgumentException on malformed input
            }
        }
    }

    /**
     * Real client address for this request: the peer, unless the peer is a configured trusted
     * proxy and it supplied one — in which case the header wins, since only then does the peer
     * have standing to speak for someone upstream of it.
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrustedPeer(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = firstForwardedAddress(request);
        return (forwarded == null || forwarded.isBlank()) ? remoteAddr : forwarded;
    }

    private boolean isTrustedPeer(String remoteAddr) {
        return trustedProxies.stream().anyMatch(entry -> matches(entry, remoteAddr));
    }

    private boolean matches(String entry, String remoteAddr) {
        if (looksLikeAddress(entry)) {
            return new IpAddressMatcher(entry).matches(remoteAddr);
        }
        try {
            for (InetAddress resolved : InetAddress.getAllByName(entry)) {
                if (resolved.getHostAddress().equals(remoteAddr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            // A configured proxy hostname that does not resolve right now degrades to "not
            // trusted" rather than either side of the unsafe extremes: it never falls back to
            // trusting everyone, and a transient DNS hiccup never takes the login endpoint down.
        }
        return false;
    }

    /** The {@code Forwarded} header (RFC 7239) wins when present; {@code X-Forwarded-For}'s
     *  left-most entry is the original client otherwise — both name the proxy's own address, not
     *  the request's, everywhere after their first entry. */
    private String firstForwardedAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null) {
            for (String directive : forwarded.split(",")[0].split(";")) {
                String[] pair = directive.trim().split("=", 2);
                if (pair.length == 2 && pair[0].trim().equalsIgnoreCase("for")) {
                    return stripForValue(pair[1].trim());
                }
            }
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return null;
    }

    private static String stripForValue(String value) {
        String unquoted = value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
        // IPv6 "for" values are bracketed and may carry a port, e.g. "[2001:db8::1]:8080".
        if (unquoted.startsWith("[")) {
            int closing = unquoted.indexOf(']');
            return closing < 0 ? unquoted : unquoted.substring(1, closing);
        }
        int colon = unquoted.indexOf(':');
        return colon < 0 ? unquoted : unquoted.substring(0, colon);
    }

    private static boolean looksLikeAddress(String entry) {
        char first = entry.charAt(0);
        return Character.isDigit(first) || entry.indexOf(':') >= 0;
    }
}
