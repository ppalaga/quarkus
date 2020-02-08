package io.quarkus.deployment.pkg.steps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.NativeImageSystemPropertyBuildItem;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageSourceJarBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.pkg.steps.NativeImageCommands.VerifiedNativeImageCommand;
import io.quarkus.deployment.util.FileUtil;

public class NativeImageBuildStep {

    private static final Logger log = Logger.getLogger(NativeImageBuildStep.class);
    private static final String DEBUG_BUILD_PROCESS_PORT = "5005";
    private static final String GRAALVM_HOME = "GRAALVM_HOME";

    /**
     * Name of the <em>system</em> property to retrieve JAVA_HOME
     */
    private static final String JAVA_HOME_SYS = "java.home";

    /**
     * Name of the <em>environment</em> variable to retrieve JAVA_HOME
     */
    private static final String JAVA_HOME_ENV = "JAVA_HOME";

    private static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");

    /**
     * The name of the environment variable containing the system path.
     */
    private static final String PATH = "PATH";

    @BuildStep(onlyIf = NativeBuild.class)
    ArtifactResultBuildItem result(NativeImageBuildItem image) {
        return new ArtifactResultBuildItem(image.getPath(), PackageConfig.NATIVE, Collections.emptyMap());
    }

    @BuildStep
    public NativeImageBuildItem build(NativeConfig nativeConfig, NativeImageSourceJarBuildItem nativeImageSourceJarBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            PackageConfig packageConfig,
            List<NativeImageSystemPropertyBuildItem> nativeImageProperties) {
        Path runnerJar = nativeImageSourceJarBuildItem.getPath();
        log.info("Building native image from " + runnerJar);
        final Path outputDir = nativeImageSourceJarBuildItem.getPath().getParent();

        final String runnerJarName = runnerJar.getFileName().toString();

        HashMap<String, String> env = new HashMap<>(System.getenv());
        final NativeImageCommands.InitialNativeImageCommand.Builder nativeImage = NativeImageCommands.initialBuilder();
        nativeImage.outputDir(outputDir);
        String noPIE = "";

        if (nativeConfig.containerRuntime.isPresent() || nativeConfig.containerBuild) {
            String containerRuntime = nativeConfig.containerRuntime.orElse("docker");
            // E.g. "/usr/bin/docker run -v {{PROJECT_DIR}}:/project --rm quarkus/graalvm-native-image"

            String outputPath = outputDir.toAbsolutePath().toString();
            if (IS_WINDOWS) {
                outputPath = FileUtil.translateToVolumePath(outputPath);
            }
            nativeImage.containerRuntime(containerRuntime, outputPath);

            if (IS_LINUX) {
                if ("docker".equals(containerRuntime)) {
                    String uid = getLinuxID("-ur");
                    String gid = getLinuxID("-gr");
                    if (uid != null && gid != null && !"".equals(uid) && !"".equals(gid)) {
                        nativeImage.uidGid(uid, gid);
                    }
                } else if ("podman".equals(containerRuntime)) {
                    // Needed to avoid AccessDeniedExceptions
                    nativeImage.userNsKeepId();
                }
            }
            nativeImage.options(nativeConfig.containerRuntimeOptions);
            if (nativeConfig.debugBuildProcess && nativeConfig.publishDebugBuildProcessPort) {
                // publish the debug port onto the host if asked for
                nativeImage.publish(DEBUG_BUILD_PROCESS_PORT, DEBUG_BUILD_PROCESS_PORT);
            }
            nativeImage.removeContainerOnExit();
            nativeImage.builderImage(nativeConfig.builderImage);
        } else {
            if (IS_LINUX) {
                noPIE = detectNoPIE();
            }

            Optional<String> graal = nativeConfig.graalvmHome;
            File java = nativeConfig.javaHome;
            if (graal.isPresent()) {
                env.put(GRAALVM_HOME, graal.get());
            }
            if (java == null) {
                // try system property first - it will be the JAVA_HOME used by the current JVM
                String home = System.getProperty(JAVA_HOME_SYS);
                if (home == null) {
                    // No luck, somewhat a odd JVM not enforcing this property
                    // try with the JAVA_HOME environment variable
                    home = env.get(JAVA_HOME_ENV);
                }

                if (home != null) {
                    java = new File(home);
                }
            }
            nativeImage.nativeImageExecutable(getNativeImageExecutable(graal, java, env).getAbsoluteFile().toPath());
        }

        final VerifiedNativeImageCommand.Builder nativeImageBuilder = nativeImage.build().verifyVersion();

        try {

            Boolean enableSslNative = false;
            boolean enableAllTimeZones = false;

            /* Sort the properties by name so that the nativeImageBuilder gets deterministic inputs */
            nativeImageProperties = new ArrayList<>(nativeImageProperties);
            nativeImageProperties.sort((prop1, prop2) -> prop1.getKey().compareTo(prop2.getKey()));

            for (NativeImageSystemPropertyBuildItem prop : nativeImageProperties) {
                //todo: this should be specific build items
                if (prop.getKey().equals("quarkus.ssl.native") && prop.getValue() != null) {
                    enableSslNative = Boolean.parseBoolean(prop.getValue());
                } else if (prop.getKey().equals("quarkus.jni.enable") && prop.getValue().equals("false")) {
                    log.warn("Your application is setting the deprecated 'quarkus.jni.enable' configuration key to false."
                            + " Please consider removing this configuration key as it is ignored (JNI is always enabled) and it"
                            + " will be removed in a future Quarkus version.");
                } else if (prop.getKey().equals("quarkus.native.enable-all-security-services") && prop.getValue() != null) {
                    nativeConfig.enableAllSecurityServices |= Boolean.parseBoolean(prop.getValue());
                } else if (prop.getKey().equals("quarkus.native.enable-all-charsets") && prop.getValue() != null) {
                    nativeConfig.addAllCharsets |= Boolean.parseBoolean(prop.getValue());
                } else if (prop.getKey().equals("quarkus.native.enable-all-timezones") && prop.getValue() != null) {
                    enableAllTimeZones = Boolean.parseBoolean(prop.getValue());
                } else {
                    // todo maybe just -D is better than -J-D in this case
                    if (prop.getValue() == null) {
                        nativeImageBuilder.impact("-J-D" + prop.getKey());
                    } else {
                        nativeImageBuilder.impact("-J-D" + prop.getKey() + "=" + prop.getValue());
                    }
                }
            }
            if (enableSslNative) {
                nativeConfig.enableHttpsUrlHandler = true;
                nativeConfig.enableAllSecurityServices = true;
            }

            nativeConfig.additionalBuildArgs.ifPresent(l -> l.stream().map(String::trim).forEach(nativeImageBuilder::impact));
            nativeImageBuilder.impact("--initialize-at-build-time=");
            nativeImageBuilder
                    .vanish("-H:InitialCollectionPolicy=com.oracle.svm.core.genscavenge.CollectionPolicy$BySpaceAndTime"); //the default collection policy results in full GC's 50% of the time
            nativeImageBuilder.jar(runnerJarName);

            if (nativeConfig.enableFallbackImages) {
                nativeImageBuilder.impact("-H:FallbackThreshold=5");
            } else {
                //Default: be strict as those fallback images aren't very useful
                //and tend to cover up real problems.
                nativeImageBuilder.impact("-H:FallbackThreshold=0");
            }

            if (nativeConfig.reportErrorsAtRuntime) {
                nativeImageBuilder.impact("-H:+ReportUnsupportedElementsAtRuntime");
            }
            if (nativeConfig.reportExceptionStackTraces) {
                nativeImageBuilder.vanish("-H:+ReportExceptionStackTraces");
            }
            if (nativeConfig.debugSymbols) {
                nativeImageBuilder.impact("-g");
            }
            if (nativeConfig.debugBuildProcess) {
                nativeImageBuilder
                        .vanish("-J-Xrunjdwp:transport=dt_socket,address=" + DEBUG_BUILD_PROCESS_PORT + ",server=y,suspend=y");
            }
            if (nativeConfig.enableReports) {
                nativeImageBuilder.vanish("-H:+PrintAnalysisCallTree");
            }
            if (nativeConfig.dumpProxies) {
                nativeImageBuilder.vanish("-Dsun.misc.ProxyGenerator.saveGeneratedFiles=true");
                if (nativeConfig.enableServer) {
                    log.warn(
                            "Options dumpProxies and enableServer are both enabled: this will get the proxies dumped in an unknown external working directory");
                }
            }
            if (nativeConfig.nativeImageXmx.isPresent()) {
                nativeImageBuilder.vanish("-J-Xmx" + nativeConfig.nativeImageXmx.get());
            }
            List<String> protocols = new ArrayList<>(2);
            if (nativeConfig.enableHttpUrlHandler) {
                protocols.add("http");
            }
            if (nativeConfig.enableHttpsUrlHandler) {
                protocols.add("https");
            }
            if (nativeConfig.addAllCharsets) {
                nativeImageBuilder.impact("-H:+AddAllCharsets");
            } else {
                nativeImageBuilder.impact("-H:-AddAllCharsets");
            }
            if (enableAllTimeZones) {
                nativeImageBuilder.impact("-H:+IncludeAllTimeZones");
            }
            if (!protocols.isEmpty()) {
                nativeImageBuilder.impact("-H:EnableURLProtocols=" + String.join(",", protocols));
            }
            if (nativeConfig.enableAllSecurityServices) {
                nativeImageBuilder.impact("--enable-all-security-services");
            }
            if (!noPIE.isEmpty()) {
                nativeImageBuilder.impact("-H:NativeLinkerOption=" + noPIE);
            }

            if (!nativeConfig.enableIsolates) {
                nativeImageBuilder.impact("-H:-SpawnIsolates");
            }
            if (!nativeConfig.enableJni) {
                log.warn("Your application is setting the deprecated 'quarkus.native.enable-jni' configuration key to false."
                        + " Please consider removing this configuration key as it is ignored (JNI is always enabled) and it"
                        + " will be removed in a future Quarkus version.");
            }
            if (!nativeConfig.enableServer && !IS_WINDOWS) {
                nativeImageBuilder.vanish("--no-server");
            }
            if (nativeConfig.enableVmInspection) {
                nativeImageBuilder.impact("-H:+AllowVMInspection");
            }
            if (nativeConfig.autoServiceLoaderRegistration) {
                nativeImageBuilder.impact("-H:+UseServiceLoaderFeature");
                //When enabling, at least print what exactly is being added:
                nativeImageBuilder.vanish("-H:+TraceServiceLoaderFeature");
            } else {
                nativeImageBuilder.impact("-H:-UseServiceLoaderFeature");
            }
            if (nativeConfig.fullStackTraces) {
                nativeImageBuilder.impact("-H:+StackTrace");
            } else {
                nativeImageBuilder.impact("-H:-StackTrace");
            }
            String executableName = outputTargetBuildItem.getBaseName() + packageConfig.runnerSuffix;

            Files.list(nativeImageSourceJarBuildItem.getLibraryDir())
                    .filter(Files::isRegularFile)
                    .forEach(nativeImageBuilder::lib);

            nativeImageBuilder.resultingExecutableName(executableName);
            if (nativeConfig.useNativeImageCache) {
                nativeImageBuilder.cache(NativeImageCache.getDefault());
            }
            final VerifiedNativeImageCommand nativeImageCommand = nativeImageBuilder.build();
            if (nativeConfig.cleanupServer) {
                nativeImageCommand.shutDownServer();
            }
            nativeImageCommand.buildNativeImage();
            Path generatedImage = outputDir.resolve(executableName);
            Path finalPath = outputTargetBuildItem.getOutputDirectory().resolve(executableName);
            IoUtils.copy(generatedImage, finalPath);
            Files.delete(generatedImage);
            System.setProperty("native.image.path", finalPath.toAbsolutePath().toString());

            return new NativeImageBuildItem(finalPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build native image", e);
        }
    }

    private static File getNativeImageExecutable(Optional<String> graalVmHome, File javaHome, Map<String, String> env) {
        String imageName = IS_WINDOWS ? "native-image.cmd" : "native-image";
        if (graalVmHome.isPresent()) {
            File file = Paths.get(graalVmHome.get(), "bin", imageName).toFile();
            if (file.exists()) {
                return file;
            }
        }

        if (javaHome != null) {
            File file = new File(javaHome, "bin/" + imageName);
            if (file.exists()) {
                return file;
            }
        }

        // System path
        String systemPath = env.get(PATH);
        if (systemPath != null) {
            String[] pathDirs = systemPath.split(File.pathSeparator);
            for (String pathDir : pathDirs) {
                File dir = new File(pathDir);
                if (dir.isDirectory()) {
                    File file = new File(dir, imageName);
                    if (file.exists()) {
                        return file;
                    }
                }
            }
        }

        throw new RuntimeException("Cannot find the `" + imageName + "` in the GRAALVM_HOME, JAVA_HOME and System " +
                "PATH. Install it using `gu install native-image`");

    }

    private static String getLinuxID(String option) {
        Process process;

        try {
            StringBuilder responseBuilder = new StringBuilder();
            String line;

            ProcessBuilder idPB = new ProcessBuilder().command("id", option);
            idPB.redirectError(new File("/dev/null"));
            idPB.redirectInput(new File("/dev/null"));

            process = idPB.start();
            try (InputStream inputStream = process.getInputStream()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    while ((line = reader.readLine()) != null) {
                        responseBuilder.append(line);
                    }
                    safeWaitFor(process);
                    return responseBuilder.toString();
                }
            } catch (Throwable t) {
                safeWaitFor(process);
                throw t;
            }
        } catch (IOException e) { //from process.start()
            //swallow and return null id
            return null;
        }
    }

    static void safeWaitFor(Process process) {
        boolean intr = false;
        try {
            for (;;)
                try {
                    process.waitFor();
                    return;
                } catch (InterruptedException ex) {
                    intr = true;
                }
        } finally {
            if (intr)
                Thread.currentThread().interrupt();
        }
    }

    private static String detectNoPIE() {
        String argument = testGCCArgument("-no-pie");

        return argument.length() == 0 ? testGCCArgument("-nopie") : argument;
    }

    private static String testGCCArgument(String argument) {
        try {
            Process gcc = new ProcessBuilder("cc", "-v", "-E", argument, "-").start();
            gcc.getOutputStream().close();
            if (gcc.waitFor() == 0) {
                return argument;
            }

        } catch (IOException | InterruptedException e) {
            // eat
        }

        return "";
    }

}
