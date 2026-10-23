package com.jmeterwebinsightreport.report.annotation;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.jmeterwebinsightreport.core.servermetrics.collect.Redaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code serverMetrics} block exactly as written in the file.
 *
 * <p>Deliberately dumb, like {@link ReportAnnotations.AssertionConfig}: every field is carried
 * through as typed, including a reducer kind that does not exist and a gate nobody declared.
 * {@link ServerMetricsMapper} turns it into a {@code ServerMetricsConfig} and produces a finding
 * for every problem it can name, against the KPI or source it was written on.
 *
 * <p>Unknown keys are captured at every level and written back untouched, for the reason the
 * top-level document does it: the report saves this file, and a save that dropped what it did
 * not understand is a lossy converter, not an editor. {@code NON_NULL} at every level so a
 * field the author never wrote does not come back as {@code "gates": null} on the next save.
 * Two consequences worth knowing: a written {@code null} means absent and is dropped on
 * write-back (a written {@code false}, {@code 0} or {@code ""} survives — this is NON_NULL,
 * not NON_EMPTY), and the typed keys are written in declaration order with unknown keys last,
 * so the block's key order is canonicalised on save. What the editor guarantees is no diff
 * between the document it loaded and the one it rebuilds, not byte-identity with the file.
 *
 * <p>Shape is the one fault this block does not collect: {@code "asserts": {}} or
 * {@code "warn": 640} fails Jackson before the mapper runs and is a whole-file load problem —
 * the same as the client's {@code warn}/{@code fail} maps, and loud (CONFIG ERROR, exit 1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerMetricsJson {

    /** What a masked secret is written as. */
    public static final String MASK = "***";

    /**
     * A copy safe to publish into a report: every shape {@link ServerMetricsMapper} refuses as
     * a credential is masked to {@link #MASK}.
     * <ul>
     *   <li>an {@code auth} value whose key does not end in {@code Env};</li>
     *   <li>the userinfo of a source {@code url} ({@code https://alice:s3cret@host} publishes as
     *       {@code https://***@host});</li>
     *   <li>the value of a source {@code url}'s query parameter whose name is a credential's
     *       ({@code ?api_key=…}, {@code ?token=…} — {@link #credentialParam}) and whose value is
     *       anything but a {@code ${secret.NAME}} reference;</li>
     *   <li>the value of a source-level key that looks like a credential ({@code token},
     *       {@code password}, {@code headers}… — {@link #isSecretLikeKey});</li>
     *   <li>an {@code environments.*.vars} value that carries either URL shape above
     *       ({@link #varCredential}), and any var a credential position reaches through
     *       {@code ${env.NAME}} — {@code https://${env.U}:${env.P}@host} or
     *       {@code ?api_key=${env.KEY}} put the secret in {@code vars}, which are published
     *       with the rest of the block.</li>
     * </ul>
     *
     * <p>The mapper refuses every one — but a refusal tells the author; it does not take the
     * value back out of a document that is about to be written into every HTML and sidecar the
     * run produces. Masking only the auth map left the other shapes published verbatim, refused
     * and printed at once; masking only userinfo left {@code ?api_key=} and a URL held in a var.
     * Each mask keeps the shape the mapper refuses (the key, the {@code @}, a literal in the
     * credential parameter), so a document saved from the report is
     * refused on its next load for the same reason rather than quietly turning into a valid
     * source that collects without its credentials.
     *
     * <p>Textual on purpose, like {@link #maskUserInfo}: a URL with a placeholder is not a URI
     * yet. The parameter-name rule is {@code Redaction}'s, so the payload's {@code resolvedUrl}
     * and this document mask the same parameters.
     *
     * <p>The env-var NAMES the file is supposed to carry are not secrets and pass through
     * untouched, so a correct file publishes as itself — not copied at all. Everything else is
     * shared by reference; only a source or environment that needs a mask is copied.
     */
    public ServerMetricsJson publishable() {
        if (unreadable != null) {
            return publishableUnreadable();
        }
        Set<String> credentialVars = credentialVarNames();
        boolean sourcesNeedMask = false;
        if (sources != null) {
            for (SourceJson s : sources.values()) {
                sourcesNeedMask |= needsMask(s);
            }
        }
        boolean varsNeedMask = false;
        if (environments != null) {
            for (EnvironmentJson e : environments.values()) {
                varsNeedMask |= needsMask(e, credentialVars);
            }
        }
        if (!sourcesNeedMask && !varsNeedMask) {
            return this;
        }
        ServerMetricsJson copy = new ServerMetricsJson();
        copy.components = components;
        copy.environments = varsNeedMask ? maskedEnvironments(credentialVars) : environments;
        copy.defaultEnv = defaultEnv;
        copy.requiresLoad = requiresLoad;
        copy.collection = collection;
        copy.asserts = asserts;
        copy.unknownKeys.putAll(unknownKeys);
        copy.sources = sourcesNeedMask ? maskedSources() : sources;
        return copy;
    }

    /**
     * {@link #publishable()} for a block {@code AnnotationLoader} isolated because its shape did not
     * bind: every key rides {@code unknownKeys} verbatim, the bound {@code sources} and
     * {@code environments} are null, and the masks above saw nothing - so a credential beside
     * {@code "asserts": {}} was written into every report as the file had it. Its {@code sources}
     * and {@code environments} are masked here by the same rules, over whatever shape they have
     * (the shape is what failed): under {@code sources} every string through {@link #maskUrl}, a
     * secret-like key's value and every non-reference value of an {@code auth} map to {@link #MASK};
     * under {@code environments} every string through {@code maskVar}, the credential vars being
     * every {@code ${env.NAME}} a source string names - wider than {@link #credentialVarNames()},
     * which costs nothing in a block that judges nothing. The original is untouched.
     */
    private ServerMetricsJson publishableUnreadable() {
        Object rawSources = unknownKeys.get("sources");
        Object rawEnvironments = unknownKeys.get("environments");
        if (rawSources == null && rawEnvironments == null) {
            return this;
        }
        Set<String> refs = new HashSet<>();
        envRefs(rawSources, refs);
        ServerMetricsJson copy = new ServerMetricsJson();
        copy.unreadable = unreadable;
        for (Map.Entry<String, Object> e : unknownKeys.entrySet()) {
            Object v = e.getValue();
            if (e.getKey().equals("sources")) {
                v = maskSourceTree(v);
            } else if (e.getKey().equals("environments")) {
                v = maskEnvironmentTree(null, v, refs);
            }
            copy.unknownKeys.put(e.getKey(), v);
        }
        return copy;
    }

    private static void envRefs(Object node, Set<String> out) {
        if (node instanceof String) {
            envNamesIn((String) node, out);
        } else if (node instanceof Map) {
            for (Object v : ((Map<?, ?>) node).values()) {
                envRefs(v, out);
            }
        } else if (node instanceof List) {
            for (Object v : (List<?>) node) {
                envRefs(v, out);
            }
        }
    }

    private static Object maskSourceTree(Object node) {
        if (node instanceof String) {
            return maskUrl((String) node);
        }
        if (node instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String key = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (isSecretLikeKey(key)) {
                    out.put(key, MASK);
                } else if (key.equals("auth")) {
                    out.put(key, maskAuthTree(v));
                } else {
                    out.put(key, maskSourceTree(v));
                }
            }
            return out;
        }
        if (node instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<?>) node) {
                out.add(maskSourceTree(v));
            }
            return out;
        }
        return node;
    }

    private static Object maskAuthTree(Object auth) {
        if (!(auth instanceof Map)) {
            return auth == null ? null : MASK;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> a : ((Map<?, ?>) auth).entrySet()) {
            String key = String.valueOf(a.getKey());
            out.put(key, isReferenceKey(key) ? a.getValue() : MASK);
        }
        return out;
    }

    private static Object maskEnvironmentTree(String name, Object node, Set<String> refs) {
        if (node instanceof String) {
            return maskVar(name, (String) node, refs);
        }
        if (node instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, maskEnvironmentTree(key, e.getValue(), refs));
            }
            return out;
        }
        if (node instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<?>) node) {
                out.add(maskEnvironmentTree(name, v, refs));
            }
            return out;
        }
        return node;
    }

    private Map<String, SourceJson> maskedSources() {
        Map<String, SourceJson> out = new LinkedHashMap<>();
        for (Map.Entry<String, SourceJson> e : sources.entrySet()) {
            SourceJson s = e.getValue();
            if (!needsMask(s)) {
                out.put(e.getKey(), s);
                continue;
            }
            SourceJson masked = new SourceJson();
            masked.type = s.type;
            masked.url = maskUrl(s.url);
            masked.path = s.path;
            masked.step = s.step;
            masked.clockOffsetMs = s.clockOffsetMs;
            for (Map.Entry<String, Object> u : s.unknownKeys.entrySet()) {
                masked.unknownKeys.put(u.getKey(), isSecretLikeKey(u.getKey()) ? MASK : u.getValue());
            }
            if (s.getAuth() != null) {
                masked.auth = new LinkedHashMap<>();
                for (Map.Entry<String, Object> a : s.getAuth().entrySet()) {
                    masked.auth.put(a.getKey(), isReferenceKey(a.getKey()) ? a.getValue() : MASK);
                }
            }
            out.put(e.getKey(), masked);
        }
        return out;
    }

    private Map<String, EnvironmentJson> maskedEnvironments(Set<String> credentialVars) {
        Map<String, EnvironmentJson> out = new LinkedHashMap<>();
        for (Map.Entry<String, EnvironmentJson> e : environments.entrySet()) {
            EnvironmentJson env = e.getValue();
            if (!needsMask(env, credentialVars)) {
                out.put(e.getKey(), env);
                continue;
            }
            EnvironmentJson masked = new EnvironmentJson();
            masked.unknownKeys.putAll(env.unknownKeys);
            masked.vars = new LinkedHashMap<>();
            for (Map.Entry<String, String> v : env.vars.entrySet()) {
                masked.vars.put(v.getKey(), maskVar(v.getKey(), v.getValue(), credentialVars));
            }
            out.put(e.getKey(), masked);
        }
        return out;
    }

    private static boolean needsMask(SourceJson s) {
        if (s == null) {
            return false;
        }
        if (s.getAuth() != null) {
            for (String key : s.getAuth().keySet()) {
                if (!isReferenceKey(key)) {
                    return true;
                }
            }
        }
        for (String key : s.getUnknownKeys().keySet()) {
            if (isSecretLikeKey(key)) {
                return true;
            }
        }
        return s.url != null && !s.url.equals(maskUrl(s.url));
    }

    private static boolean needsMask(EnvironmentJson env, Set<String> credentialVars) {
        if (env == null || env.vars == null) {
            return false;
        }
        for (Map.Entry<String, String> v : env.vars.entrySet()) {
            String value = v.getValue();
            if (value != null && !value.equals(maskVar(v.getKey(), value, credentialVars))) {
                return true;
            }
        }
        return false;
    }

    private static String maskVar(String name, String value, Set<String> credentialVars) {
        if (value == null) {
            return null;
        }
        if (credentialVars.contains(name)) {
            return MASK;
        }
        String out = carriesUserInfo(value) ? maskUserInfo(value) : value;
        return maskCredentialParams(out);
    }

    /**
     * The vars a credential position reaches through {@code ${env.NAME}}: a placeholder in a
     * source URL's userinfo, or in the value of a credential-named query parameter. The URL masks
     * the reference; the value it resolves to is in {@code vars}, and is masked there.
     */
    private Set<String> credentialVarNames() {
        Set<String> names = new HashSet<>();
        if (sources == null) {
            return names;
        }
        for (SourceJson s : sources.values()) {
            if (s == null || s.url == null) {
                continue;
            }
            int[] userInfo = userInfoSpan(s.url);
            if (userInfo != null) {
                envNamesIn(s.url.substring(userInfo[0], userInfo[1]), names);
            }
            for (String[] p : queryParams(s.url)) {
                if (isCredentialParam(p)) {
                    envNamesIn(p[1], names);
                }
            }
        }
        return names;
    }

    private static void envNamesIn(String text, Set<String> into) {
        Matcher m = ENV_REFERENCE.matcher(text);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    /** {@code ${env.NAME}} — the only placeholder whose value is published with the document. */
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{env\\.([A-Za-z0-9_.-]+)}");

    /**
     * One or more {@code ${secret.NAME}} references and nothing else: the one value a
     * credential-named URL parameter may take. The collector resolves it from the environment and
     * masks the value it substituted wherever the resolved URL is written; the document carries
     * only the name.
     */
    private static final Pattern SECRET_REFERENCE = Pattern.compile("(?:\\$\\{secret\\.[A-Za-z0-9_.-]+})+");

    /**
     * The name of the first query parameter of {@code url} that carries a credential — a name
     * {@code Redaction} masks ({@code api_key}, {@code token}, {@code sig}…) with a value that is
     * anything but a {@code ${secret.NAME}} reference — or null. An empty value carries nothing.
     * A {@code ${env.*}} value counts: it resolves from {@code vars}, which are published.
     */
    public static String credentialParam(String url) {
        for (String[] p : queryParams(url)) {
            if (isCredentialParam(p)) {
                return p[0];
            }
        }
        return null;
    }

    private static boolean isCredentialParam(String[] p) {
        return p[1] != null && !p[1].isEmpty() && Redaction.isSecretParamName(p[0])
                && !SECRET_REFERENCE.matcher(p[1]).matches();
    }

    /** {@code url} with the value of every {@link #credentialParam} replaced by {@link #MASK}. */
    static String maskCredentialParams(String url) {
        int[] q = querySpan(url);
        if (q == null) {
            return url;
        }
        String[] pieces = url.substring(q[0], q[1]).split("&", -1);
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) {
                query.append('&');
            }
            String[] p = param(pieces[i]);
            query.append(isCredentialParam(p) ? p[0] + "=" + MASK : pieces[i]);
        }
        return url.substring(0, q[0]) + query + url.substring(q[1]);
    }

    private static String[] param(String piece) {
        int eq = piece.indexOf('=');
        return eq < 0 ? new String[] {piece, null} : new String[] {piece.substring(0, eq), piece.substring(eq + 1)};
    }

    /** userinfo, then credential parameters — every shape a source URL is masked by. */
    public static String maskUrl(String url) {
        return maskCredentialParams(maskUserInfo(url));
    }

    /**
     * What the mapper refuses in an {@code environments.*.vars} value, as a phrase, or null: a URL
     * whose userinfo carries a password ({@code https://bob:pw@host}, or {@code bob:pw@host}), or
     * a credential parameter. Narrower than a source URL on purpose — a var is any text, and
     * {@code ops@corp.com} is an address, not a credential — so userinfo counts only in a URL
     * ({@code //}) or when it has a password half.
     */
    public static String varCredential(String value) {
        if (value == null) {
            return null;
        }
        if (carriesUserInfo(value)) {
            return "credentials in a URL (user:password@…)";
        }
        String param = credentialParam(value);
        if (param != null) {
            return "a credential in the URL parameter '" + param + "'";
        }
        return null;
    }

    private static boolean carriesUserInfo(String value) {
        int[] span = userInfoSpan(value);
        return span != null && (value.contains("//") || value.substring(span[0], span[1]).indexOf(':') >= 0);
    }

    /** Each {@code name=value} of {@code url}'s query, value null when there is no {@code =}. */
    private static List<String[]> queryParams(String url) {
        List<String[]> out = new ArrayList<>();
        int[] q = querySpan(url);
        if (q == null) {
            return out;
        }
        for (String piece : url.substring(q[0], q[1]).split("&")) {
            out.add(param(piece));
        }
        return out;
    }

    /** Where {@code url}'s query runs — after the first {@code ?}, up to a {@code #} — or null. */
    private static int[] querySpan(String url) {
        if (url == null) {
            return null;
        }
        int start = url.indexOf('?');
        if (start < 0) {
            return null;
        }
        int end = url.indexOf('#', start);
        return new int[] {start + 1, end < 0 ? url.length() : end};
    }

    /** The one shape an auth key may take: a name ending in {@code Env}, longer than the suffix. */
    public static boolean isReferenceKey(String key) {
        return key != null && key.trim().endsWith("Env") && key.trim().length() > 3;
    }

    /**
     * Source-level keys that look like a secret, or that would carry one ({@code headers} is not
     * a v1 key, and an {@code Authorization} header inside it is a credential). Only
     * {@code auth.*Env} may carry credentials.
     */
    private static final java.util.regex.Pattern SECRET_LIKE_KEY =
            java.util.regex.Pattern.compile("(?i)token|password|passwd|secret|apikey|api_key|authorization|credential|headers");

    /**
     * True for a key outside {@code auth} that the mapper refuses as a credential. One
     * definition, read by the mapper's refusal and by {@link #publishable()}'s mask, so the two
     * cannot disagree about what was a secret.
     */
    public static boolean isSecretLikeKey(String key) {
        return key != null && SECRET_LIKE_KEY.matcher(key).find() && !isReferenceKey(key);
    }

    /**
     * {@code url} with the userinfo of its authority replaced by {@link #MASK}, and nothing else
     * touched — or {@code url} itself when it carries none.
     *
     * <p>Textual on purpose. A URL with a {@code ${env.*}} placeholder is not a URI yet, and the
     * mapper refuses an {@code @} in one as well; taking the URL apart through {@code URI} would
     * also re-encode every other component, which a correct URL must not suffer. The authority
     * runs from after {@code ://} (or a leading {@code //}, or from the start when there is
     * neither) to the first {@code /}, {@code ?} or {@code #}; userinfo is everything in it
     * before the last {@code @}. An {@code @} in the path or the query is not userinfo and is
     * left alone.
     */
    public static String maskUserInfo(String url) {
        int[] span = userInfoSpan(url);
        return span == null ? url : url.substring(0, span[0]) + MASK + url.substring(span[1]);
    }

    /**
     * True when {@code url}'s authority holds an {@code @} — userinfo, as this class masks it.
     * The mapper refuses a source URL by this, not by {@code URI.getUserInfo()}, which is null for
     * {@code https://bob:p@ssw0rd@host} (a second {@code @} makes the authority registry-based) —
     * so the refusal and the mask see the same span.
     */
    public static boolean authorityHasUserInfo(String url) {
        return userInfoSpan(url) != null;
    }

    /** Where {@code url}'s userinfo runs — {@code [start, the last @ of the authority)} — or null. */
    private static int[] userInfoSpan(String url) {
        if (url == null || url.indexOf('@') < 0) {
            return null;
        }
        int scheme = url.indexOf("://");
        int start = scheme >= 0 ? scheme + 3 : url.startsWith("//") ? 2 : 0;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        int at = url.lastIndexOf('@', end - 1);
        return at < start ? null : new int[] {start, at};
    }

    /**
     * {@code serverMetrics.components} — what is being tested (plan C1). {@code gates} has no
     * binding any more: a file that still writes it lands among the unknown keys and the mapper
     * refuses it by name, pointing here.
     */
    private Map<String, ComponentJson> components;
    /**
     * Retired (C8: connection details are {@code wir-collect} flags) and refused by the mapper —
     * but still BOUND, for one reason: a credential left in a retired block is still a credential,
     * and {@link #publishable()} can only mask the shapes it can see. Unbound, it would be an
     * opaque unknown key written into every report verbatim.
     */
    private Map<String, SourceJson> sources;
    private Map<String, EnvironmentJson> environments;
    private String defaultEnv;
    private String requiresLoad;
    private CollectionJson collection;
    /**
     * {@code serverMetrics.asserts} — the key D25 gave the list. The class and accessor names
     * inside keep the internal word, as {@code ServerKpi} does; only what a user writes changed.
     * A leftover {@code kpis} key therefore lands among the unknown keys, where
     * {@link ServerMetricsMapper} refuses it by name rather than reading it as an empty list.
     */
    private List<KpiJson> asserts;
    private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

    public ServerMetricsJson() {
    }

    @JsonAnySetter
    public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

    @JsonAnyGetter
    public Map<String, Object> getUnknownKeys() { return unknownKeys; }

    /**
     * Why the block as written could not be bound, or null. Set by {@code AnnotationLoader} when
     * the rest of the file reads and only this block does not ({@code "asserts": {}}, a band that
     * is not an object): the block is then carried here with its keys verbatim, so a Download still
     * writes what the file said, and {@link ServerMetricsMapper} refuses it as one block problem —
     * a server-side problem, which costs the client rules nothing.
     */
    private String unreadable;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getUnreadable() { return unreadable; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setUnreadable(String unreadable) { this.unreadable = unreadable; }

    public Map<String, ComponentJson> getComponents() { return components; }
    public void setComponents(Map<String, ComponentJson> components) { this.components = components; }
    public Map<String, SourceJson> getSources() { return sources; }
    public void setSources(Map<String, SourceJson> sources) { this.sources = sources; }
    public Map<String, EnvironmentJson> getEnvironments() { return environments; }
    public void setEnvironments(Map<String, EnvironmentJson> environments) { this.environments = environments; }
    public String getDefaultEnv() { return defaultEnv; }
    public void setDefaultEnv(String defaultEnv) { this.defaultEnv = defaultEnv; }
    public String getRequiresLoad() { return requiresLoad; }
    public void setRequiresLoad(String requiresLoad) { this.requiresLoad = requiresLoad; }
    public CollectionJson getCollection() { return collection; }
    public void setCollection(CollectionJson collection) { this.collection = collection; }
    public List<KpiJson> getAsserts() { return asserts; }
    /**
     * A null entry is refused at bind, so {@code AnnotationLoader} isolates the block as it does
     * {@code "asserts": {}}, and the page's {@code blockShapeProblem} and the run agree that nothing
     * in it is judged. Bound as a Java null, it reached the mapper and its NullPointerException
     * lost the whole report, the client half included.
     */
    @com.fasterxml.jackson.annotation.JsonSetter(contentNulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
    public void setAsserts(List<KpiJson> asserts) { this.asserts = asserts; }

    /**
     * {@code serverMetrics.components.<id>} — the id is the map key. {@code failsBuild} has no
     * binding: every component builds (C6), and a leftover one is refused by the mapper.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComponentJson {
        private String type;
        private String description;
        private String match;
        private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

        public ComponentJson() {}

        @JsonAnySetter
        public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

        @JsonAnyGetter
        public Map<String, Object> getUnknownKeys() { return unknownKeys; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getMatch() { return match; }
        public void setMatch(String match) { this.match = match; }
    }

    /** {@code serverMetrics.sources.<id>}. {@code auth} is a map so a literal secret can be refused by name. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SourceJson {
        private String type;
        private String url;
        private String path;
        private Map<String, Object> auth;
        private Object step;
        private Object clockOffsetMs;
        private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

        public SourceJson() {}

        @JsonAnySetter
        public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

        @JsonAnyGetter
        public Map<String, Object> getUnknownKeys() { return unknownKeys; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public Map<String, Object> getAuth() { return auth; }
        public void setAuth(Map<String, Object> auth) { this.auth = auth; }
        public Object getStep() { return step; }
        public void setStep(Object step) { this.step = step; }
        public Object getClockOffsetMs() { return clockOffsetMs; }
        public void setClockOffsetMs(Object clockOffsetMs) { this.clockOffsetMs = clockOffsetMs; }
    }

    /** {@code serverMetrics.environments.<NAME>} — a bag of {@code vars}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EnvironmentJson {
        private Map<String, String> vars;
        private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

        public EnvironmentJson() {}

        @JsonAnySetter
        public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

        @JsonAnyGetter
        public Map<String, Object> getUnknownKeys() { return unknownKeys; }

        public Map<String, String> getVars() { return vars; }
        public void setVars(Map<String, String> vars) { this.vars = vars; }
    }

    /** {@code serverMetrics.collection} — the run-level knobs v1 keeps. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CollectionJson {
        private Object coverageFloor;
        private Boolean excludeRampUp;
        private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

        public CollectionJson() {}

        @JsonAnySetter
        public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

        @JsonAnyGetter
        public Map<String, Object> getUnknownKeys() { return unknownKeys; }

        public Object getCoverageFloor() { return coverageFloor; }
        public void setCoverageFloor(Object coverageFloor) { this.coverageFloor = coverageFloor; }
        public Boolean getExcludeRampUp() { return excludeRampUp; }
        public void setExcludeRampUp(Boolean excludeRampUp) { this.excludeRampUp = excludeRampUp; }
    }

    /** {@code serverMetrics.asserts[n]}, one server assert. {@code reduce}, {@code warn} and {@code fail} are maps so shape problems can be named. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KpiJson {
        private String id;
        private String description;
        private Boolean enabled;
        private String component;
        private String metric;
        private String query;
        private String unit;
        private Map<String, Object> reduce;
        private String across;
        private String requiresLoad;
        private String divideBy;
        private Map<String, Object> warn;
        private Map<String, Object> fail;
        private String pack;
        private final Map<String, Object> unknownKeys = new LinkedHashMap<>();

        public KpiJson() {}

        @JsonAnySetter
        public void setUnknownKey(String name, Object value) { unknownKeys.put(name, value); }

        @JsonAnyGetter
        public Map<String, Object> getUnknownKeys() { return unknownKeys; }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getComponent() { return component; }
        public void setComponent(String component) { this.component = component; }
        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public Map<String, Object> getReduce() { return reduce; }
        public void setReduce(Map<String, Object> reduce) { this.reduce = reduce; }
        public String getAcross() { return across; }
        public void setAcross(String across) { this.across = across; }
        public String getRequiresLoad() { return requiresLoad; }
        public void setRequiresLoad(String requiresLoad) { this.requiresLoad = requiresLoad; }
        public String getDivideBy() { return divideBy; }
        public void setDivideBy(String divideBy) { this.divideBy = divideBy; }
        public Map<String, Object> getWarn() { return warn; }
        public void setWarn(Map<String, Object> warn) { this.warn = warn; }
        public Map<String, Object> getFail() { return fail; }
        public void setFail(Map<String, Object> fail) { this.fail = fail; }
        public String getPack() { return pack; }
        public void setPack(String pack) { this.pack = pack; }
    }
}
