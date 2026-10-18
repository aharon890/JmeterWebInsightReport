package com.jmeterwebinsightreport.core.servermetrics.collect;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * What a resolved URL looks like once it is written somewhere a person can read it.
 *
 * <p>The payload, the report and the sidecar all carry the URL that was queried, because a
 * reader diagnosing {@code SOURCE_FAILED} needs to know where the collector went. They must
 * not carry what it authenticated with. Two rules, and the order matters:
 * <ul>
 *   <li>{@link #values} — <b>by value</b>: every secret the collector actually resolved (a
 *       {@code ${secret.*}} it substituted, an {@code auth.*Env} it read) is replaced wherever
 *       it appears, in a URL path, in a parameter of any name, in an error message that quotes
 *       the URL or a response body. This is the rule that cannot be fooled by naming: the
 *       collector knows the values because it put them there.</li>
 *   <li>{@link #url} — <b>by shape</b>: userinfo ({@code https://alice:s3cret@host}) and query
 *       parameters whose name looks like a credential. This catches what the first rule cannot
 *       see — a credential the author typed into the URL instead of declaring — and it is the
 *       last line, not the first.</li>
 * </ul>
 * The first rule was missing once: a {@code ${secret.TOKEN}} in a parameter called {@code t}, or
 * in the path, went into {@code resolvedUrl} and every HTML report verbatim, because nothing
 * masking by name had a reason to look at {@code t}.
 */
public final class Redaction {

    /** What a masked value is written as — the same token {@code ServerMetricsJson} uses. */
    public static final String MASK = "***";

    /**
     * What a credential-carrying query parameter's name contains. One definition, read by
     * {@link #url} here and by the report's published-document mask, so the payload's
     * {@code resolvedUrl} and the annotations document written beside it mask the same parameters.
     */
    private static final String SECRET_PARAM_NAME = "token|key|secret|password|passwd|auth|credential|sig";

    private static final Pattern SECRET_PARAM =
            Pattern.compile("(?i)(^|[?&])([^=&]*(?:" + SECRET_PARAM_NAME + ")[^=&]*)=([^&]*)");

    private static final Pattern SECRET_PARAM_NAME_PATTERN = Pattern.compile("(?i)" + SECRET_PARAM_NAME);

    private Redaction() {
    }

    /** True for a query-parameter name {@link #url} masks the value of ({@code api_key}, {@code token}, {@code sig}…). */
    public static boolean isSecretParamName(String name) {
        return name != null && SECRET_PARAM_NAME_PATTERN.matcher(name).find();
    }

    /**
     * The URL with userinfo removed and secret-looking query parameters masked.
     *
     * <p>Userinfo is everything in the authority before its LAST {@code @}, read textually
     * whenever {@code URI} does not hand it over. {@code URI.getUserInfo()} is null for
     * {@code https://bob:p@ssw0rd@host} — a second {@code @} makes the authority registry-based —
     * so that URL went into {@code resolvedUrl} whole, password included; and the textual
     * fallback cut at the FIRST {@code @}, which left {@code ssw0rd@} behind. The mapper refuses
     * such a source since M7d X, so this is the last line, not the first.
     */
    public static String url(String url) {
        if (url == null) {
            return null;
        }
        String withoutUserInfo = url;
        try {
            URI u = new URI(url);
            if (u.getUserInfo() != null) {
                withoutUserInfo = new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(),
                        u.getQuery(), u.getFragment()).toString();
            } else {
                withoutUserInfo = stripAuthorityUserInfo(url);
            }
        } catch (URISyntaxException | NumberFormatException e) {
            // Not a URI we can take apart (NumberFormatException: an IPv4 byte past an int inside
            // an IPv6 literal); fall through to the textual rule, which still removes a user:pass@
            // and masks secret-looking parameters.
            withoutUserInfo = stripAuthorityUserInfo(url);
        }
        return SECRET_PARAM.matcher(withoutUserInfo).replaceAll("$1$2=" + MASK);
    }

    /**
     * {@code url} without the userinfo of a {@code scheme://} authority: everything from after
     * {@code ://} up to and including the last {@code @} before the first {@code /}, {@code ?} or
     * {@code #}. An {@code @} in the path or the query is not userinfo and is left alone.
     */
    static String stripAuthorityUserInfo(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        int at = url.lastIndexOf('@', end - 1);
        return at < start ? url : url.substring(0, start) + url.substring(at + 1);
    }

    /**
     * {@code text} with every one of {@code secrets} replaced by {@link #MASK}, wherever it
     * occurs. Every non-empty value is masked whatever its length: a one-character secret would
     * mask every occurrence of that character in a URL, which is a cosmetic cost on display text
     * — the alternative is a secret in every artifact the run produces.
     */
    public static String values(String text, Collection<String> secrets) {
        if (text == null || secrets == null || secrets.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                out = out.replace(secret, MASK);
            }
        }
        return out;
    }
}
