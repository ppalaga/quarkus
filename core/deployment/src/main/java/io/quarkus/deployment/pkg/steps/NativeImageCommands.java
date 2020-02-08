package io.quarkus.deployment.pkg.steps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.util.OS;
import io.quarkus.deployment.pkg.steps.BuilderImageMetadataStore.BuilderImageMetadata;
import io.quarkus.deployment.pkg.steps.NativeImageCache.NativeImageCacheEntry;
import io.quarkus.deployment.util.HashUtil;

public class NativeImageCommands {
    private final static List<String> OBSOLETE_GRAAL_VM_VERSION_PREFIXES = Collections
            .unmodifiableList(Arrays.asList("1.0.0", "19.0.", "19.1.", "19.2.", "19.3.0"));

    private NativeImageCommands() {
    }

    private static final Logger log = Logger.getLogger(NativeImageCommands.class);

    public static InitialNativeImageCommand.Builder initialBuilder() {
        return new InitialNativeImageCommand.Builder();
    }

    public static class InitialNativeImageCommand {
        protected final List<String> command;
        protected final ArtifactInputs inputs;
        protected final Path outputDir;
        private final String containerRuntime;
        private final String builderImage;
        protected final BuilderImageMetadataStore builderImageMetadataStore;

        private InitialNativeImageCommand(Path outputDir, List<String> command, String containerRuntime, String builderImage,
                ArtifactInputs inputs, BuilderImageMetadataStore builderImageMetadataStore) {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(outputDir, "outputDir");
            Objects.requireNonNull(outputDir, "builderImageMetadataStore");
            this.command = Collections.unmodifiableList(new ArrayList<>(command));
            this.inputs = inputs;
            this.outputDir = outputDir;
            this.containerRuntime = containerRuntime;
            this.builderImage = builderImage;
            this.builderImageMetadataStore = builderImageMetadataStore;
        }

        private InitialNativeImageCommand pullImageIfNecessary() {
            if (containerRuntime != null) {

                // we pull the docker image in order to give users an indication of which step the process is at
                // it's not strictly necessary we do this, however if we don't the subsequent version command
                // will appear to block and no output will be shown
                log.info("Pulling image " + builderImage);
                Process pullProcess = null;
                try {
                    pullProcess = new ProcessBuilder(Arrays.asList(containerRuntime, "pull", builderImage))
                            .inheritIO()
                            .start();
                    pullProcess.waitFor();
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException("Failed to pull builder image " + builderImage, e);
                } finally {
                    if (pullProcess != null) {
                        pullProcess.destroy();
                    }
                }
            }
            return this;
        }

        public VerifiedNativeImageCommand.Builder verifyVersion() {
            Optional<String> graalVMVersion = null;
            if (containerRuntime != null) {
                final Optional<BuilderImageMetadata> builderImageMetadata = builderImageMetadataStore.retrieve(builderImage);
                if (builderImageMetadata.isPresent()) {
                    graalVMVersion = Optional.of(builderImageMetadata.get().getNativeImageCommandVersion());
                }
            }
            if (graalVMVersion == null) {
                pullImageIfNecessary();
                try {
                    List<String> versionCommand = new ArrayList<>(command);
                    versionCommand.add("--version");

                    Process versionProcess = new ProcessBuilder(versionCommand.toArray(new String[0]))
                            .redirectErrorStream(true)
                            .start();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(versionProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        final String prefix = "GraalVM Version ";
                        graalVMVersion = reader.lines()
                                .filter(l -> l.startsWith(prefix))
                                .map(v -> v.substring(prefix.length()))
                                .findFirst();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to get GraalVM version", e);
                }
                if (graalVMVersion.isPresent() && containerRuntime != null) {
                    builderImageMetadataStore.store(builderImage, new BuilderImageMetadata(graalVMVersion.get()));
                }
            }

            if (graalVMVersion.isPresent()) {
                String version = graalVMVersion.get();
                log.info("Running Quarkus native-image plugin on " + version);
                final boolean vmVersionIsObsolete = OBSOLETE_GRAAL_VM_VERSION_PREFIXES.stream()
                        .anyMatch(v -> version.startsWith(v));
                if (vmVersionIsObsolete) {
                    throw new IllegalStateException(
                            "Out of date version of GraalVM detected: " + version + ". Please upgrade to GraalVM 19.3.1.");
                }
                return new VerifiedNativeImageCommand.Builder(outputDir, command, containerRuntime, builderImage, inputs,
                        version, builderImageMetadataStore);
            } else {
                throw new IllegalStateException("Unable to get GraalVM version from the native-image binary.");
            }

        }

        public static class Builder {

            private final List<String> command = new ArrayList<>();
            private final ArtifactInputs.Builder inputs = new ArtifactInputs.Builder();
            private Path outputDir;
            private String builderImage;
            private String containerRuntime;
            private BuilderImageMetadataStore builderImageMetadataStore = BuilderImageMetadataStore.getDefault();

            public Builder() {
            }

            public InitialNativeImageCommand build() {
                return new InitialNativeImageCommand(outputDir, command, containerRuntime, builderImage, inputs.build(),
                        builderImageMetadataStore);
            }

            public Builder containerRuntime(String containerRuntime, String outputPath) {
                this.containerRuntime = containerRuntime;
                Collections.addAll(command, containerRuntime, "run", "-v", outputPath + ":/project:z");
                inputs.entry("quarkus.native.os.type", "linux");
                inputs.entry("quarkus.native.os.arch", "amd64");
                return this;
            }

            public Builder uidGid(String uid, String gid) {
                Collections.addAll(command, "--user", uid + ":" + gid);
                return this;
            }

            public Builder userNsKeepId() {
                command.add("--userns=keep-id");
                return this;
            }

            public Builder options(Optional<List<String>> options) {
                /* We hope options contain no caching relevant inputs */
                options.ifPresent(command::addAll);
                return this;
            }

            public Builder publish(String containerPort, String hostPort) {
                command.add("--publish=" + containerPort + ":" + hostPort);
                return this;
            }

            public Builder removeContainerOnExit() {
                command.add("--rm");
                return this;
            }

            public Builder builderImage(String builderImage) {
                this.builderImage = builderImage;
                command.add(builderImage);
                return this;
            }

            public Builder nativeImageExecutable(Path nativeImageExecutable) {
                command.add(nativeImageExecutable.toString());
                inputs.entry("quarkus.native.os.type", OS.current().name());
                inputs.entry("quarkus.native.os.arch", System.getProperty("os.arch"));
                return this;
            }

            public Builder outputDir(Path outputDir) {
                this.outputDir = outputDir;
                return this;
            }
        }
    }

    public static class VerifiedNativeImageCommand extends InitialNativeImageCommand {
        private final String version;
        private final NativeImageCache cache;
        private final String resultingExecutableName;
        private final String runnerJarName;

        private VerifiedNativeImageCommand(Path outputDir, List<String> command,
                String containerRuntime, String builderImage,
                ArtifactInputs inputs,
                String version, String resultingExecutableName, String runnerJarName, NativeImageCache cache,
                BuilderImageMetadataStore builderImageMetadataStore) {
            super(outputDir, command, containerRuntime, builderImage, inputs, builderImageMetadataStore);
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(resultingExecutableName, "resultingExecutableName");
            this.version = version;
            this.resultingExecutableName = resultingExecutableName;
            this.runnerJarName = runnerJarName;
            this.cache = cache;
        }

        public String getVersion() {
            return version;
        }

        public void shutDownServer() {
            final List<String> cleanup = new ArrayList<>(command);
            cleanup.add("--server-shutdown");
            try {
                ProcessBuilder pb = new ProcessBuilder(cleanup.toArray(new String[0]));
                pb.directory(outputDir.toFile());
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                Process process = pb.start();
                process.waitFor();
            } catch (IOException e) {
                throw new RuntimeException(String.format("Could not execute %s", cleanup), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.errorf("Interrupted %s", cleanup);
            }
        }

        @Override
        public String toString() {
            return String.join(" ", command);
        }

        public void buildNativeImage() {
            if (cache != null && cachedUsed()) {
                return;
            }

            log.info(this.toString());
            final CountDownLatch errorReportLatch = new CountDownLatch(1);

            final ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(outputDir.toFile())
                    .inheritIO();
            try {
                final Process process = processBuilder.start();
                ExecutorService executor = Executors.newSingleThreadExecutor();
                executor.submit(new ErrorReplacingProcessReader(process.getErrorStream(), outputDir.resolve("reports").toFile(),
                        errorReportLatch));
                executor.shutdown();
                errorReportLatch.await();
                if (process.waitFor() != 0) {
                    throw new RuntimeException("Image generation failed. Exit code: " + process.exitValue());
                }

                if (cache != null) {
                    cache.store(inputs, outputDir.resolve(resultingExecutableName), outputDir.resolve(runnerJarName));
                }
            } catch (IOException e) {
                throw new RuntimeException(String.format("Could not execute %s", toString()), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(String.format("Could not execute %s", toString()), e);
            }
        }

        private boolean cachedUsed() {

            final Optional<NativeImageCacheEntry> cacheEntry = cache.retrieve(inputs);
            if (cacheEntry.isPresent()) {
                final Path cachedNativeFile = cacheEntry.get().getNativeImageFile();
                final Path copyTarget = outputDir.resolve(resultingExecutableName);
                try {
                    Files.copy(cachedNativeFile, copyTarget, StandardCopyOption.REPLACE_EXISTING);
                    log.infof("Reusing cached native image instead of rebuilding it anew: %s", cachedNativeFile);
                    return true;
                } catch (IOException e) {
                    log.warnf(e, "Could not copy from %s to %s", cachedNativeFile, copyTarget);
                }
            }
            return false;
        }

        public static class Builder {
            private List<String> command;
            private ArtifactInputs.Builder inputs;
            private final Path outputDir;
            private final String version;
            private int impactCounter = 0;
            private String resultingExecutableName;
            private NativeImageCache cache;
            private final String builderImage;
            private final String containerRuntime;
            private String runnerJarName;
            private BuilderImageMetadataStore builderImageMetadataStore;

            public Builder(Path outputDir, List<String> command, String containerRuntime, String builderImage,
                    ArtifactInputs inputs, String version, BuilderImageMetadataStore builderImageMetadataStore) {
                Objects.requireNonNull(command, "command");
                Objects.requireNonNull(inputs, "inputs");
                Objects.requireNonNull(version, "version");
                Objects.requireNonNull(outputDir, "outputDir");
                this.command = new ArrayList<>(command);
                this.inputs = new ArtifactInputs.Builder()
                        .entries(inputs)
                        .entry("quarkus.native.native-image.version", version);
                this.outputDir = outputDir;
                this.version = version;
                this.containerRuntime = containerRuntime;
                this.builderImage = builderImage;
                this.builderImageMetadataStore = builderImageMetadataStore;
            }

            public Builder vanish(String arg) {
                command.add(arg);
                return this;
            }

            public Builder impact(String arg) {
                command.add(arg);
                inputs.entry("quarkus.native.native-image.arg[" + String.format("%03d", impactCounter++) + "]", arg);
                return this;
            }

            public Builder jar(String runnerJarName) {
                command.add("-jar");
                command.add(runnerJarName);
                inputs.entry("quarkus.native.native-image.jar.sha1", HashUtil.sha1(outputDir.resolve(runnerJarName)));
                this.runnerJarName = runnerJarName;
                return this;
            }

            public VerifiedNativeImageCommand build() {
                return new VerifiedNativeImageCommand(outputDir, command,
                        containerRuntime, builderImage,
                        inputs.build(), version, resultingExecutableName,
                        runnerJarName, cache, builderImageMetadataStore);
            }

            public String getVersion() {
                return version;
            }

            public Builder resultingExecutableName(String resultingExecutableName) {
                this.resultingExecutableName = resultingExecutableName;
                return vanish(resultingExecutableName);
            }

            public Builder lib(Path lib) {
                inputs.entry("quarkus.native.native-image.lib[" + lib.getFileName().toString() + "].sha1",
                        HashUtil.sha1(outputDir.resolve(lib)));
                return this;
            }

            public Builder cache(NativeImageCache cache) {
                this.cache = cache;
                return this;
            }

        }

    }
}
