package io.quarkus.test.common;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps between builder test and application class directories.
 */
public final class PathTestHelper {
    private static final Map<String, String> TEST_TO_MAIN_DIR_FRAGMENTS = new HashMap<>();
    static {
        // eclipse
        TEST_TO_MAIN_DIR_FRAGMENTS.put(
                "bin" + File.separator + "test",
                "bin" + File.separator + "main");
        // gradle
        TEST_TO_MAIN_DIR_FRAGMENTS.put(
                "classes" + File.separator + "java" + File.separator + "test",
                "classes" + File.separator + "java" + File.separator + "main");
        // maven
        TEST_TO_MAIN_DIR_FRAGMENTS.put(
                File.separator + "test-classes",
                File.separator + "classes");
    }

    private PathTestHelper() {
    }

    public static Path getTestClassesLocation(Class<?> testClass) {
        String classFileName = testClass.getName().replace('.', File.separatorChar) + ".class";
        URL resource = testClass.getClassLoader().getResource(classFileName);

        /*
         * if (!isInTestDir(resource)) {
         * throw new RuntimeException("The test class " + testClass + " is not located in any of the directories "
         * + TEST_TO_MAIN_DIR_FRAGMENTS.keySet());
         * }
         */

        if (resource.getProtocol().equals("jar")) {
            try {
                resource = URI.create(resource.getFile().substring(0, resource.getFile().indexOf('!'))).toURL();
                return toPath(resource);
            } catch (MalformedURLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        Path path = toPath(resource);
        return path.getRoot().resolve(path.subpath(0, path.getNameCount() - Paths.get(classFileName).getNameCount()));
    }

    public static Path getAppClassLocation(Class<?> testClass) {
        String testClassPath = getTestClassesLocation(testClass).toString();
        if (testClassPath.endsWith(".jar")) {
            if (testClassPath.endsWith("-tests.jar")) {
                return Paths.get(testClassPath.substring(0, testClassPath.length() - "-tests.jar".length()) + ".jar");
            }
            return Paths.get(testClassPath);
        }
        return TEST_TO_MAIN_DIR_FRAGMENTS.entrySet().stream()
                .filter(e -> testClassPath.contains(e.getKey()))
                .map(e -> Paths.get(testClassPath.replace(e.getKey(), e.getValue())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unable to translate path for " + testClass.getName()));
    }

    public static boolean isTestClass(String className, ClassLoader classLoader) {
        String classFileName = className.replace('.', File.separatorChar) + ".class";
        URL resource = classLoader.getResource(classFileName);
        return resource != null
                && resource.getProtocol().startsWith("file")
                && isInTestDir(resource);
    }

    private static boolean isInTestDir(URL resource) {
        String path = toPath(resource).toString();
        return TEST_TO_MAIN_DIR_FRAGMENTS.keySet().stream()
                .anyMatch(path::contains);
    }

    private static Path toPath(URL resource) {
        try {
            return Paths.get(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Failed to convert URL " + resource, e);
        }
    }
}
