package io.quarkus.arc.test.deployment;

import io.quarkus.builder.item.MultiBuildItem;

public final class EndpointBuildItem extends MultiBuildItem {
    private final String className;

    public EndpointBuildItem(String doableClassName) {
        super();
        this.className = doableClassName;
    }

    public String getClassName() {
        return className;
    }
}
