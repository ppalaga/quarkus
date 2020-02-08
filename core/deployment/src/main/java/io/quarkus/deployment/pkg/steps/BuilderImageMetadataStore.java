package io.quarkus.deployment.pkg.steps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;

import org.jboss.logging.Logger;

public class BuilderImageMetadataStore {
    private static final Logger log = Logger.getLogger(BuilderImageMetadataStore.class);

    private static final BuilderImageMetadataStore DEFAULT = new BuilderImageMetadataStore(
            Paths.get(System.getProperty("user.home")).resolve(".quarkus/builder-image-metadata"));

    private final Path rootDir;

    public BuilderImageMetadataStore(Path rootDir) {
        this.rootDir = rootDir;
    }

    public Optional<BuilderImageMetadata> retrieve(String builderImageName) {
        final Path path = rootDir.resolve(toPath(builderImageName) + "/metadata.properties");
        if (Files.exists(path)) {
            return BuilderImageMetadata.load(path);
        } else {
            return Optional.empty();
        }
    }

    public void store(String builderImageName, BuilderImageMetadata metadata) {
        metadata.store(rootDir.resolve(toPath(builderImageName) + "/metadata.properties"));
    }

    public static BuilderImageMetadataStore getDefault() {
        return DEFAULT;
    }

    private static String toPath(String builderImageName) {
        return builderImageName.replace(':', '/');
    }

    static class BuilderImageMetadata {
        private final String nativeImageCommandVersion;

        public BuilderImageMetadata(String nativeImageCommandVersion) {
            this.nativeImageCommandVersion = nativeImageCommandVersion;
        }

        void store(Path path) {

            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e1) {
                log.warnf("Could not create directory %s", path.getParent());
                return;
            }

            Properties props = new Properties();
            props.setProperty("nativeImageCommandVersion", nativeImageCommandVersion);
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, null);
            } catch (IOException e) {
                log.warnf("Could not write into %s", path);
            }
        }

        static Optional<BuilderImageMetadata> load(Path path) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
                final String nativeImageCommandVersion = props.getProperty("nativeImageCommandVersion");
                if (nativeImageCommandVersion == null) {
                    return Optional.empty();
                } else {
                    return Optional.of(new BuilderImageMetadata(nativeImageCommandVersion));
                }
            } catch (IOException e) {
                log.warnf("Could not read from %s", path);
                return Optional.empty();
            }
        }

        public String getNativeImageCommandVersion() {
            return nativeImageCommandVersion;
        }
    }
}
