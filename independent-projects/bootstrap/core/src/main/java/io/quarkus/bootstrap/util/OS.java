package io.quarkus.bootstrap.util;

/**
 * Enum to classify the os.name system property
 */
public enum OS {
    WINDOWS,
    LINUX,
    MAC,
    OTHER;

    private static final OS CURRENT;

    static {
        final String osName = System.getProperty("os.name").toLowerCase();
        final OS os;
        if (osName.contains("windows")) {
            os = OS.WINDOWS;
        } else if (osName.contains("linux")
                || osName.contains("freebsd")
                || osName.contains("unix")
                || osName.contains("sunos")
                || osName.contains("solaris")
                || osName.contains("aix")) {
            os = OS.LINUX;
        } else if (osName.contains("mac os")) {
            os = OS.MAC;
        } else {
            os = OS.OTHER;
        }
        CURRENT = os;
    }

    private OS() {
        this.version = System.getProperty("os.version");
    }

    private final String version;

    public String getVersion() {
        return version;
    }

    public static OS current() {
        return CURRENT;
    }
}
