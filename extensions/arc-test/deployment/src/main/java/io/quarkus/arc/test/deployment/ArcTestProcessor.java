package io.quarkus.arc.test.deployment;

import java.util.List;
import java.util.stream.Collectors;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.test.ArcTestRecorder;
import io.quarkus.arc.test.Context;
import io.quarkus.arc.test.Endpoint;
import io.quarkus.arc.test.Producers;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.runtime.RuntimeValue;

class ArcTestProcessor {

    private static final String FEATURE = "arc-test";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    ContextBuildItem context(
            ArcTestRecorder recorder,
            BeanContainerBuildItem beanContainer) {

        RuntimeValue<Context> context = recorder.createContext(beanContainer.getValue());

        return new ContextBuildItem(context);
    }

    @BuildStep
    List<EndpointBuildItem> collectEndpoints(CombinedIndexBuildItem index) {
        return index.getIndex()
                .getAllKnownImplementors(DotName.createSimple(Endpoint.class.getName()))
                .stream()
                .map(ClassInfo::toString)
                .map(EndpointBuildItem::new)
                .collect(Collectors.toList());
    }

    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep
    public void addEndpoints(ArcTestRecorder recorder, ContextBuildItem context, List<EndpointBuildItem> endpoints) {
        endpoints.forEach(item -> recorder.addEndpoint(item.getClassName(), context.getValue()));
    }

    @BuildStep
    void additionalBeans(CombinedIndexBuildItem index, BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(
                AdditionalBeanBuildItem.builder()
                        .addBeanClasses(index.getIndex()
                                .getAllKnownImplementors(DotName.createSimple(Endpoint.class.getName()))
                                .stream().map(ClassInfo::toString).collect(Collectors.toList()))
                        .setUnremovable()
                        .build());
        additionalBeans.produce(AdditionalBeanBuildItem.unremovableOf(Producers.class));
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void start(
            ArcTestRecorder recorder,
            ContextBuildItem context,
            List<ServiceStartBuildItem> startList) {

        recorder.start(context.getValue());
    }

}
