package com.jmeterwebinsightreport;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code java -jar <jmeter>/lib/ext/jmeter-web-insight-report-X.Y.Z.jar --jtl ... --csv ...}: the
 * collector, with the same text on Windows, macOS and Linux and no bash (decision 13).
 *
 * <p>{@link WirCollect} needs JMeter's own libraries (Jackson, slf4j), which the plugin JAR does not
 * shade, so it cannot be this JAR's {@code Main-Class} by itself - that was the reason for "never
 * java -jar". This class uses the JDK only: it finds JMeter from the JAR's own place in
 * {@code <JMETER_HOME>/lib/ext} (or from {@code JMETER_HOME} when the JAR is elsewhere), refuses a
 * {@code lib/ext} that holds no plugin JAR or more than one, and runs {@code WirCollect} in-process
 * through a class loader over this JAR and {@code <JMETER_HOME>/lib/*.jar} - the classpath
 * {@code wir-collect.sh} builds, so nothing else in {@code lib/ext} is on it. Every refusal is one
 * {@code wir-collect:} sentence and exit 1, as the script's are.
 */
public final class WirCollectLauncher {

    private WirCollectLauncher() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Refusal r) {
            System.err.println("wir-collect: " + r.getMessage());
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    /** The launch; returns only on a refusal - WirCollect.main exits the JVM itself. */
    static int run(String[] args) throws Refusal {
        File jar = ownJar();
        File home = jmeterHome(jar, System.getenv("JMETER_HOME"));
        File plugin = onePluginJar(new File(home, "lib/ext"), jar);
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(plugin.toURI().toURL());
            File[] lib = new File(home, "lib").listFiles((d, n) -> n.endsWith(".jar"));
            if (lib == null || lib.length == 0) {
                throw new Refusal(home + " has no lib/*.jar - is JMETER_HOME a JMeter installation?");
            }
            for (File f : lib) {
                urls.add(f.toURI().toURL());
            }
        } catch (java.net.MalformedURLException e) {
            throw new Refusal("could not build the classpath: " + e.getMessage());
        }
        ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Method main = loader.loadClass("com.jmeterwebinsightreport.WirCollect").getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new Refusal(String.valueOf(cause));
        } catch (ReflectiveOperationException e) {
            throw new Refusal("could not start the collector: " + e);
        }
        return 0;
    }

    private static File ownJar() throws Refusal {
        try {
            return new File(WirCollectLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new Refusal("could not find the plugin JAR this runs from: " + e.getMessage());
        }
    }

    /**
     * JMeter's home: the JAR's {@code ../..} when it sits in a {@code lib/ext}, else {@code JMETER_HOME}.
     * A JMETER_HOME that is set and is not a JMeter installation is an error, never a reason to look
     * elsewhere - wir-collect.sh's rule.
     */
    static File jmeterHome(File jar, String env) throws Refusal {
        File ext = jar.getAbsoluteFile().getParentFile();
        if (ext != null && ext.getName().equals("ext") && ext.getParentFile() != null
                && ext.getParentFile().getName().equals("lib")) {
            return ext.getParentFile().getParentFile();
        }
        if (env == null || env.trim().isEmpty()) {
            throw new Refusal("the plugin JAR is not in a JMeter lib/ext and JMETER_HOME is unset - run the JAR"
                    + " from <JMETER_HOME>/lib/ext, or set JMETER_HOME");
        }
        File home = new File(env.trim());
        if (!new File(home, "lib/ext").isDirectory()) {
            throw new Refusal("JMETER_HOME is set to '" + env + "', which has no lib/ext - point it at the JMeter"
                    + " installation (the folder holding bin and lib)");
        }
        return home;
    }

    /** The one plugin JAR in lib/ext - refusing none (name the fix) and several (list them). */
    static File onePluginJar(File ext, File self) throws Refusal {
        File[] found = ext.listFiles((d, n) -> n.startsWith("jmeter-web-insight-report-") && n.endsWith(".jar"));
        if (found == null || found.length == 0) {
            if (self.isFile() && self.getName().startsWith("jmeter-web-insight-report-")) {
                return self;   // run from a release folder against JMETER_HOME: this JAR is the plugin
            }
            throw new Refusal("no jmeter-web-insight-report-*.jar in " + ext + " - copy the plugin JAR there");
        }
        if (found.length > 1) {
            StringBuilder names = new StringBuilder();
            for (File f : found) {
                names.append(names.length() == 0 ? "" : ", ").append(f.getName());
            }
            throw new Refusal(ext + " holds " + found.length + " plugin JARs (" + names + ") - JMeter would load"
                    + " whichever comes first; keep exactly one");
        }
        return found[0];
    }

    /** One refusal sentence; the launcher exits 1 with it. */
    static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;

        Refusal(String message) {
            super(message);
        }
    }
}
