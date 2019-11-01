package io.quarkus.arc.test.deployment;

import io.quarkus.arc.test.Context;
import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.runtime.RuntimeValue;

public final class ContextBuildItem extends SimpleBuildItem {
    private final RuntimeValue<Context> value;

    public ContextBuildItem(RuntimeValue<Context> value) {
        this.value = value;
    }

    public RuntimeValue<Context> getValue() {
        return value;
    }
}
