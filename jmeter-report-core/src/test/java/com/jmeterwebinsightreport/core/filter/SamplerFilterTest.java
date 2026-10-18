package com.jmeterwebinsightreport.core.filter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SamplerFilterTest {

    @Test
    void shouldAcceptAllWhenNoPatterns() {
        SamplerFilterConfig config = new SamplerFilterConfig();
        SamplerFilter filter = new SamplerFilter(config);
        assertTrue(filter.accept("HTTP Request"));
        assertTrue(filter.accept("Login API"));
    }

    @Test
    void shouldRejectNullAndEmpty() {
        SamplerFilterConfig config = new SamplerFilterConfig();
        SamplerFilter filter = new SamplerFilter(config);
        assertFalse(filter.accept(null));
        assertFalse(filter.accept(""));
    }

    @Test
    void shouldFilterByIncludePattern() {
        SamplerFilterConfig config = new SamplerFilterConfig();
        config.setIncludePatterns(Collections.singletonList("HTTP*"));
        SamplerFilter filter = new SamplerFilter(config);
        assertTrue(filter.accept("HTTP Request"));
        assertFalse(filter.accept("JDBC Request"));
    }

    @Test
    void shouldFilterByExcludePattern() {
        SamplerFilterConfig config = new SamplerFilterConfig();
        config.setExcludePatterns(Arrays.asList("Debug*", "BeanShell*"));
        SamplerFilter filter = new SamplerFilter(config);
        assertTrue(filter.accept("HTTP Request"));
        assertFalse(filter.accept("Debug Sampler"));
    }

    // --- Glob translation must survive real JMeter labels (SEC-9) ---

    @Test
    void globToRegex_quotesRegexMetacharacters() {
        // Only '.' used to be escaped, so braces, parens and '+' were live regex syntax:
        // these patterns either threw PatternSyntaxException or matched the wrong thing.
        SamplerFilterConfig config = new SamplerFilterConfig();
        config.setIncludePatterns(Arrays.asList("GET /api/users/{id}"));
        SamplerFilter filter = new SamplerFilter(config);

        assertTrue(filter.accept("GET /api/users/{id}"));
        assertFalse(filter.accept("GET /api/users/42"));
    }

    @Test
    void globToRegex_handlesPatternsThatUsedToThrow() {
        SamplerFilterConfig config = new SamplerFilterConfig();
        config.setIncludePatterns(Arrays.asList("POST /search?q=a+b(c)[d]"));
        SamplerFilter filter = new SamplerFilter(config);

        assertDoesNotThrow(() -> filter.accept("POST /search?q=a+b(c)[d]"));
        assertTrue(filter.accept("POST /search?q=a+b(c)[d]"));
    }

    @Test
    void globToRegex_wildcardsStillWork() {
        assertTrue("GET /api/users".matches(SamplerFilter.globToRegex("GET /api/*")));
        assertTrue("Debug Sampler".matches(SamplerFilter.globToRegex("Debug*")));
        assertTrue("a1c".matches(SamplerFilter.globToRegex("a?c")));
        assertFalse("abc".matches(SamplerFilter.globToRegex("GET /api/*")));
        // A wildcard between two metacharacter-bearing literals.
        assertTrue("{a}-x-{b}".matches(SamplerFilter.globToRegex("{a}-*-{b}")));
    }
}
