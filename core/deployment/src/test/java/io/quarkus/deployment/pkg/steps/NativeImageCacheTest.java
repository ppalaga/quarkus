package io.quarkus.deployment.pkg.steps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.deployment.pkg.steps.NativeImageCache.NativeImageCacheEntry;
import io.quarkus.deployment.pkg.steps.NativeImageCache.NativeImageCacheEntryMetadata;

public class NativeImageCacheTest {

    @Test
    void metadata() throws IOException {
        final Instant storedOn = Instant.parse("2007-12-03T10:15:30.00Z");
        final Instant lastRetrievedOn = Instant.parse("2007-12-20T12:15:30.00Z");
        final String jarName = "foo.jar";
        NativeImageCache.NativeImageCacheEntryMetadata md = new NativeImageCacheEntryMetadata(jarName, storedOn,
                lastRetrievedOn);
        final Path metadataFile = Files.createTempFile("NativeImageCacheEntryMetadata", ".properties");
        md.store(metadataFile);
        final NativeImageCacheEntryMetadata mdLoaded = NativeImageCacheEntryMetadata.load(metadataFile, true);
        Assertions.assertEquals(md.getStoredOn(), mdLoaded.getStoredOn());
        Assertions.assertTrue(mdLoaded.getLastRetrievedOn().isAfter(md.getLastRetrievedOn()));
        Assertions.assertEquals(jarName, mdLoaded.getRunnerJarName());
    }

    @Test
    void storeRetrieve() throws IOException {
        final Path root = Files.createTempDirectory("NativeImageCache").resolve("cache");
        final NativeImageCache cache = new NativeImageCache(root, Clock.systemUTC());
        final String COFE_BABE = "cofe babe";
        final String DEAD_BEEF = "dead beef";
        final Path runnerJar = Files.createTempFile("runner", ".jar");
        Files.write(runnerJar, DEAD_BEEF.getBytes(StandardCharsets.UTF_8));

        {
            final ArtifactInputs inputs = new ArtifactInputs.Builder().entry("k1", "v1").entry("k2", "v2").build();

            Assertions.assertFalse(cache.retrieve(inputs).isPresent());

            final Path nativeImage = Files.createTempFile("native-image", ".bin");
            Files.write(nativeImage, COFE_BABE.getBytes(StandardCharsets.UTF_8));
            cache.store(inputs, nativeImage, runnerJar);
        }

        {
            final ArtifactInputs inputs = new ArtifactInputs.Builder().entry("k1", "v1").entry("k2", "v2").build();
            final Optional<NativeImageCacheEntry> result = cache.retrieve(inputs);
            Assertions.assertTrue(result.isPresent());
            final NativeImageCacheEntry cachedEntry = result.get();

            final Path foundPath = cachedEntry.getNativeImageFile();
            final String foundContent = new String(Files.readAllBytes(foundPath), StandardCharsets.UTF_8);
            Assertions.assertEquals(COFE_BABE, foundContent);

            final Path foundJarPath = cachedEntry.getRunnerJarFile();
            final String foundJarContent = new String(Files.readAllBytes(foundJarPath), StandardCharsets.UTF_8);
            Assertions.assertEquals(DEAD_BEEF, foundJarContent);

            Assertions.assertNotNull(cachedEntry.getMetadata().getStoredOn());
            Assertions.assertNotNull(cachedEntry.getMetadata().getLastRetrievedOn());

            Assertions.assertEquals(runnerJar.getFileName().toString(), cachedEntry.getMetadata().getRunnerJarName());
        }
    }

}
