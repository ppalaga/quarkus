package io.quarkus.camel.component.undertow.deployment;

import io.quarkus.camel.component.undertow.runtime.CamelUndertowHandlerWrapper;
import io.quarkus.camel.component.undertow.runtime.CamelUndertowTemplate;
import io.quarkus.camel.core.deployment.CamelRuntimeBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.undertow.deployment.HttpHandlerWrapperBuildItem;

class CamelUndertowProcessor {

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FeatureBuildItem.CAMEL_UNDERTOW);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    HttpHandlerWrapperBuildItem registerComponent(CamelUndertowTemplate template, CamelRuntimeBuildItem camelRuntime) {
        CamelUndertowHandlerWrapper wrapper = template.registerComponent(camelRuntime.getRuntime());
        return new HttpHandlerWrapperBuildItem(wrapper);
    }

}
