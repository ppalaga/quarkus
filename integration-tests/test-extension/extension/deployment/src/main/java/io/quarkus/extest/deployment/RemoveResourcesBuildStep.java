package io.quarkus.extest.deployment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.RemovedResourceBuildItem;
import io.quarkus.extest.runtime.RemovedResource;
import io.quarkus.maven.dependency.ArtifactKey;

public class RemoveResourcesBuildStep {

    @BuildStep
    void removeCxfCoreResourceBundles(BuildProducer<RemovedResourceBuildItem> removed) {
        removed.produce(new RemovedResourceBuildItem(
                ArtifactKey.of("io.smallrye.common", "smallrye-common-io", null, "jar"),
                Set.of(RemovedResource.COMMON_IO_MESSAGES.resourceName())));
        removed.produce(new RemovedResourceBuildItem(
                ArtifactKey.of("io.smallrye.common", "smallrye-common-net", null, "jar"),
                Set.of(RemovedResource.COMMON_NET_MESSAGES.resourceName())));
    }

    @BuildStep
    void replaceCommonIoResourceBundle(BuildProducer<GeneratedResourceBuildItem> generated) {
        Properties props = new Properties();
        try (InputStream in = io.smallrye.common.io.Files2.class.getClassLoader()
                .getResourceAsStream(RemovedResource.COMMON_IO_MESSAGES.resourceName())) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not read from " + RemovedResource.COMMON_IO_MESSAGES.resourceName() + " in smallrye-common-io.jar",
                    e);
        }
        Map<String, String> overrides = Map.of(
                "newKey",
                "newValue");

        overrides.forEach((k, v) -> {
            props.put(k, v);
        });
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            props.store(baos, null);
            baos.close();

            byte[] content = baos.toByteArray();
            generated.produce(new GeneratedResourceBuildItem(RemovedResource.COMMON_IO_MESSAGES.resourceName(), content));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to ByteArrayOutputStream", e);
        }
    }

}
