package com.jmeterwebinsightreport.core.filter;

import java.util.regex.Pattern;

/**
 * Filters sampler names against configured include/exclude patterns.
 */
public class SamplerFilter {

    private final SamplerFilterConfig config;

    public SamplerFilter(SamplerFilterConfig config) {
        this.config = config;
    }

    /**
     * Check whether a sampler should be included in the report.
     *
     * @param samplerName the sampler label
     * @return true if the sampler passes the filter
     */
    public boolean accept(String samplerName) {
        if (samplerName == null || samplerName.isEmpty()) {
            return false;
        }

        // If include patterns are specified, sampler must match at least one
        if (!config.getIncludePatterns().isEmpty()) {
            boolean matchesInclude = config.getIncludePatterns().stream()
                    .anyMatch(pattern -> matchesPattern(samplerName, pattern));
            if (!matchesInclude) {
                return false;
            }
        }

        // If exclude patterns are specified, sampler must not match any
        if (!config.getExcludePatterns().isEmpty()) {
            boolean matchesExclude = config.getExcludePatterns().stream()
                    .anyMatch(pattern -> matchesPattern(samplerName, pattern));
            if (matchesExclude) {
                return false;
            }
        }

        return true;
    }

    /**
     * Match a sampler name against a glob pattern ({@code *} = any run, {@code ?} = one char).
     *
     * <p>Everything except the two wildcards is quoted. Escaping only {@code .} — as the
     * previous implementation did — left every other regex metacharacter live, so a pattern
     * containing {@code ( ) [ ] { } + ^ $ |} either threw {@link
     * java.util.regex.PatternSyntaxException} or matched something entirely different. That
     * is not exotic for JMeter labels: {@code GET /api/users/{id}} contains braces, and
     * query strings contain {@code +} and {@code $}. The naive {@code *} -> {@code .*}
     * expansion also opened the door to catastrophic backtracking on adversarial input.
     */
    private boolean matchesPattern(String name, String pattern) {
        return Pattern.matches(globToRegex(pattern), name);
    }

    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 16);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }
}
