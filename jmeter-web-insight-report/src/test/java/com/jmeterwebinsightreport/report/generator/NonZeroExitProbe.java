package com.jmeterwebinsightreport.report.generator;

/**
 * How many times {@link NonZeroExit#arrange} has been called, for a test in another package that must
 * prove a path never arranges an exit (the listener panel's Rebuild button). Lives beside
 * {@code NonZeroExit} in the test sources so the count stays package-private in the product.
 */
public final class NonZeroExitProbe {

    private NonZeroExitProbe() {
    }

    public static int calls() {
        return NonZeroExit.calls();
    }
}
