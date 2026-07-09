package io.quarkus.extest.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public enum RemovedResource {
    COMMON_IO_MESSAGES("io/smallrye/common/io/Messages.i18n.properties", io.smallrye.common.io.Files2.class),
    COMMON_NET_MESSAGES("io/smallrye/common/net/Messages.i18n.properties", io.smallrye.common.net.URIs.class);

    public static enum ClassLoaderKind {
        OWN_CLASS_LOADER() {
            ClassLoader cl(RemovedResource removedResource) {
                return removedResource.loadingClass.getClassLoader();
            }
        },
        CONTEXT_CLASS_LOADER() {
            ClassLoader cl(RemovedResource removedResource) {
                return Thread.currentThread().getContextClassLoader();
            }
        };

        abstract ClassLoader cl(RemovedResource removedResource);
    };

    RemovedResource(String resourceName, Class<?> loadingClass) {
        this.resourceName = resourceName;
        this.loadingClass = loadingClass;
    }

    private final String resourceName;
    private final Class<?> loadingClass;

    public String load(ClassLoaderKind clKind) {
        try (InputStream in = clKind.cl(this).getResourceAsStream(resourceName)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + resourceName, e);
        }
    }

    public String resourceName() {
        return resourceName;
    }

}
