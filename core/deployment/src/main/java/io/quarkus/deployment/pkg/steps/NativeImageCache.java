package io.quarkus.deployment.pkg.steps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

/**
 * A cache storing native image files.
 */
public class NativeImageCache {
    private static final Logger log = Logger.getLogger(NativeImageCache.class);

    private static final NativeImageCache DEFAUTL = new NativeImageCache(
            Paths.get(System.getProperty("user.home")).resolve(".quarkus/native-image-cache"),
            Clock.systemUTC());
    private final Path rootDir;
    private Instant nextGc;
    private final Clock clock;
    private final Object lock = new Object();
    private final CachePrefs prefs;

    public NativeImageCache(Path rootDir, Clock clock) {
        this.rootDir = rootDir;
        this.clock = clock;
        this.nextGc = readNextGc();
        this.prefs = CachePrefs.load(rootDir.resolve("cache.properties"));
    }

    /**
     * Lookup the a native image for the given {@link ArtifactInputs}.
     *
     * @param inputs the lookup key
     * @return {@link Optional} holding a {@link NativeImageCacheEntry} of an image is available in this
     *         {@link NativeImageCacheEntry} for the given {@link ArtifactInputs}; otherwise an empty {@link Optional}
     */
    public Optional<NativeImageCacheEntry> retrieve(ArtifactInputs inputs) {
        final EntryPaths entryPaths = new EntryPaths(inputs);
        if (prefs.enabled && entryPaths.exists()) {
            return Optional.of(new NativeImageCacheEntry(
                    entryPaths,
                    NativeImageCacheEntryMetadata.load(entryPaths.metadataFile, true)));
        } else {
            return Optional.empty();
        }
    }

    /**
     * Store the given {@code nativeImageFile} and {@code runnerJarFile} for the given {@link ArtifactInputs}
     *
     * @param inputs the {@link ArtifactInputs} used for building the given {@code nativeImageFile} and {@code runnerJarFile}
     * @param nativeImageFile the native image file to store
     * @param runnerJarFile the runner.jar file to store
     */
    public void store(ArtifactInputs inputs, Path nativeImageFile, Path runnerJarFile) {
        if (prefs.enabled) {
            final EntryPaths entryPaths = new EntryPaths(inputs);
            NativeImageCacheEntry.storeNew(entryPaths, inputs, nativeImageFile, runnerJarFile, Instant.now());
            gcIfNecessary();
        }
    }

    public void gcIfNecessary() {
        final Instant now = Instant.now(clock);
        if (now.isAfter(nextGc) && Files.exists(rootDir)) {

            synchronized (lock) {
                final Path lockFilePath = rootDir.resolveSibling("cache.lock");
                RandomAccessFile lockFile = null;
                try {
                    lockFile = new RandomAccessFile(lockFilePath.toFile(), "rw");
                    final FileLock fsLock = lockFile.getChannel().tryLock();
                    if (fsLock != null) {
                        try {
                            // re-read nextGc
                            nextGc = readNextGc();
                            if (now.isAfter(nextGc)) {
                                nextGc = now.plus(prefs.gcInterval);
                                // store nextGc
                                Files.write(rootDir.resolve("next-gc.txt"), nextGc.toString().getBytes(StandardCharsets.UTF_8));

                                log.infof("Performing garbage collection in %s", rootDir);

                                //gc on the background
                                try (Stream<Path> files = Files.list(rootDir)) {
                                    final AtomicLong totalSize = new AtomicLong();
                                    final List<EntryPaths> entryPaths = new ArrayList<>();
                                    files
                                            .filter(Files::isDirectory)
                                            .map(path -> new EntryPaths(path))
                                            .filter(EntryPaths::exists)
                                            .forEach(paths -> {
                                                totalSize.addAndGet(paths.getSizeBytes());
                                                entryPaths.add(paths);
                                            });

                                    if (totalSize.get() > prefs.capacityBytes) {
                                        log.infof("Native image cache size %d MB exceeds the prefered capacity %d MB",
                                                totalSize.get() / 1024 / 1024, prefs.capacityBytes / 1020 / 1024);
                                        final List<NativeImageCacheEntry> entries = entryPaths.stream()
                                                .map(paths -> new NativeImageCacheEntry(
                                                        paths,
                                                        NativeImageCacheEntryMetadata.load(paths.metadataFile, false)))
                                                .sorted() // least used first
                                                .collect(Collectors.toList());

                                        final Iterator<NativeImageCacheEntry> it = entries.iterator();
                                        while (it.hasNext() && totalSize.get() > prefs.capacityBytes) {
                                            final NativeImageCacheEntry entry = it.next();
                                            final EntryPaths paths = entry.paths;
                                            paths.delete();
                                            totalSize.addAndGet(-paths.getSizeBytes());
                                        }
                                        log.infof("Native image cache purged down to %d MB", totalSize.get() / 1024 / 1024);
                                    } else {
                                        log.infof("Native image cache size %d MB still under the prefered capacity %d MB",
                                                totalSize.get() / 1024 / 1024, prefs.capacityBytes / 1020 / 1024);
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(
                                            "Could not perform garbage collection in the NativeImageCache " + rootDir);
                                }
                            }
                        } finally {
                            fsLock.close();
                        }
                    }
                } catch (OverlappingFileLockException ignored) {
                    // another process is probably gc-ing right now
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

        }
    }

    public static NativeImageCache getDefault() {
        return DEFAUTL;
    }

    Instant readNextGc() {
        final Path nextGcPath = rootDir.resolve("next-gc.txt");
        try {
            if (Files.exists(nextGcPath)) {
                final String str = new String(Files.readAllBytes(nextGcPath), StandardCharsets.UTF_8);
                try {
                    return Instant.parse(str.trim());
                } catch (Exception e) {
                    log.warnf(e, "Could not parse a timestamp from %s", nextGcPath);
                }
            }
            final Instant result = Instant.now(clock);
            Files.write(rootDir.resolve("next-gc.txt"), result.toString().getBytes(StandardCharsets.UTF_8));
            return result;
        } catch (IOException e) {
            return Instant.now(clock);
        }
    }

    static class CachePrefs {

        private static final String DEFAULT_CAPACITY = "300m";

        static final CachePrefs DEFAULTS = new CachePrefs(Duration.ofDays(7), 1024 * 1024 * 300, true);

        private final Duration gcInterval;
        private final long capacityBytes;
        private final boolean enabled;

        public CachePrefs(Duration gcInterval, long capacityBytes, boolean enabled) {
            this.gcInterval = gcInterval;
            this.capacityBytes = capacityBytes;
            this.enabled = enabled;
        }

        static CachePrefs load(Path path) {
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path.getParent());
                } catch (IOException e) {
                    throw new RuntimeException("Could not create " + path.getParent());
                }
                try (OutputStream out = Files.newOutputStream(path)) {
                    Properties props = new Properties();
                    props.setProperty("gcInterval", DEFAULTS.gcInterval.toString());
                    props.setProperty("capacity", DEFAULT_CAPACITY);
                    props.setProperty("enabled", String.valueOf(DEFAULTS.enabled));
                    props.store(out, " Native image cache preferences");
                } catch (IOException e) {
                    throw new RuntimeException("Could not write " + path);
                }
                return DEFAULTS;
            } else {
                try (InputStream in = Files.newInputStream(path)) {
                    Properties props = new Properties();
                    props.load(in);
                    final Duration gcInterval = Duration
                            .parse((String) props.getOrDefault("gcInterval", DEFAULTS.gcInterval.toString()));
                    String rawCapacity = props.getProperty("capacity");
                    if (rawCapacity == null || rawCapacity.trim().isEmpty()) {
                        rawCapacity = DEFAULT_CAPACITY;
                    }
                    rawCapacity = rawCapacity.trim();
                    long multiplier;
                    int suffixLength;
                    switch (rawCapacity.charAt(rawCapacity.length() - 1)) {
                        case 'b':
                        case 'B':
                            multiplier = 1;
                            suffixLength = 1;
                            break;
                        case 'k':
                        case 'K':
                            multiplier = 1024;
                            suffixLength = 1;
                            break;
                        case 'm':
                        case 'M':
                            multiplier = 1024 * 1024;
                            suffixLength = 1;
                            break;
                        case 'g':
                        case 'G':
                            multiplier = 1024 * 1024 * 1024;
                            suffixLength = 1;
                            break;
                        case '0':
                        case '1':
                        case '2':
                        case '3':
                        case '4':
                        case '5':
                        case '6':
                        case '7':
                        case '8':
                        case '9':
                            multiplier = 1;
                            suffixLength = 0;
                            break;
                        default:
                            log.warnf("Ignoring illegal native image cache capacity %s in %s", rawCapacity, path);
                            rawCapacity = DEFAULT_CAPACITY;
                            multiplier = 1024 * 1024;
                            suffixLength = 1;
                            break;
                    }
                    final long capacityBytes = multiplier
                            * Long.parseLong(rawCapacity.substring(0, rawCapacity.length() - suffixLength));
                    final boolean enabled = Boolean.parseBoolean((String) props.getOrDefault("enabled", "true"));
                    return new CachePrefs(gcInterval, capacityBytes, enabled);
                } catch (IOException e) {
                    throw new RuntimeException("Could not read " + path);
                }
            }
        }
    }

    public static class NativeImageCacheEntryMetadata {
        private final Instant storedOn;
        private final Instant lastRetrievedOn;
        private final String runnerJarName;

        public NativeImageCacheEntryMetadata(String runnerJarName, Instant storedOn, Instant lastRetrievedOn) {
            this.runnerJarName = runnerJarName;
            this.storedOn = storedOn;
            this.lastRetrievedOn = lastRetrievedOn;
        }

        public static NativeImageCacheEntryMetadata load(Path metadataFile, boolean updateLastRetrieved) {
            final Properties props = new Properties();
            final String runnerJarName;
            Instant lastRetrievedOn;
            final Instant storedOn;
            try (InputStream in = Files.newInputStream(metadataFile)) {
                props.load(in);
                storedOn = Instant.parse(props.getProperty("storedOn"));
                lastRetrievedOn = Instant.parse(props.getProperty("lastRetrievedOn"));
                runnerJarName = props.getProperty("runnerJarName");
            } catch (IOException e) {
                throw new RuntimeException(String.format("Could not read %s", metadataFile), e);
            }
            if (updateLastRetrieved) {
                lastRetrievedOn = Instant.now();
                props.setProperty("lastRetrievedOn", lastRetrievedOn.toString());
                try (OutputStream out = Files.newOutputStream(metadataFile)) {
                    props.store(out, "");
                } catch (IOException e) {
                    throw new RuntimeException(String.format("Could not write to %s", metadataFile), e);
                }
            }
            return new NativeImageCacheEntryMetadata(runnerJarName, storedOn, lastRetrievedOn);
        }

        public void store(Path metadataFile) {
            try (OutputStream out = Files.newOutputStream(metadataFile)) {
                Properties props = new Properties();
                props.setProperty("storedOn", this.storedOn.toString());
                props.setProperty("lastRetrievedOn", this.lastRetrievedOn.toString());
                props.setProperty("runnerJarName", runnerJarName);
                props.store(out, "");
            } catch (IOException e) {
                throw new RuntimeException(String.format("Could not write to %s", metadataFile), e);
            }
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((lastRetrievedOn == null) ? 0 : lastRetrievedOn.hashCode());
            result = prime * result + ((storedOn == null) ? 0 : storedOn.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NativeImageCacheEntryMetadata other = (NativeImageCacheEntryMetadata) obj;
            if (lastRetrievedOn == null) {
                if (other.lastRetrievedOn != null)
                    return false;
            } else if (!lastRetrievedOn.equals(other.lastRetrievedOn))
                return false;
            if (storedOn == null) {
                if (other.storedOn != null)
                    return false;
            } else if (!storedOn.equals(other.storedOn))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "NativeImageCacheEntryMetadata [storedOn=" + storedOn + ", lastRetrievedOn=" + lastRetrievedOn + "]";
        }

        public Instant getStoredOn() {
            return storedOn;
        }

        public Instant getLastRetrievedOn() {
            return lastRetrievedOn;
        }

        public String getRunnerJarName() {
            return runnerJarName;
        }

    }

    public class EntryPaths {

        private final Path entryDir;
        private final Path inputsFile;
        private final Path nativeImageFile;
        private final Path metadataFile;
        private final Path runnerJarFile;
        private volatile long sizeBytes = -1;

        public EntryPaths(ArtifactInputs inputs) {
            this(rootDir.resolve(inputs.getSha1()));
        }

        public long getSizeBytes() {
            if (this.sizeBytes < 0) {
                this.sizeBytes = size(inputsFile) + size(nativeImageFile) + size(runnerJarFile);
            }
            return sizeBytes;
        }

        long size(Path path) {
            try {
                return Files.exists(path) ? Files.size(path) : 0L;
            } catch (IOException e) {
                return 0L;
            }
        }

        public EntryPaths(Path entryDir) {
            this.entryDir = entryDir;
            this.inputsFile = entryDir.resolve("inputs.properties");
            this.nativeImageFile = entryDir.resolve("native-image");
            this.runnerJarFile = entryDir.resolve("runner.jar");
            this.metadataFile = entryDir.resolve("metadata.properties");
        }

        public boolean exists() {
            return Files.exists(entryDir)
                    && Files.exists(inputsFile)
                    && Files.exists(nativeImageFile)
                    && Files.exists(metadataFile);
        }

        public void delete() {
            log.infof("Deleting %s", entryDir);
            try {
                Files.walkFileTree(entryDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(
                            Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException();
            }
        }
    }

    public static class NativeImageCacheEntry implements Comparable<NativeImageCacheEntry> {
        private final EntryPaths paths;
        private final NativeImageCacheEntryMetadata metadata;

        public NativeImageCacheEntry(EntryPaths paths,
                NativeImageCacheEntryMetadata metadata) {
            this.paths = paths;
            this.metadata = metadata;
        }

        public static void storeNew(EntryPaths entryPaths, ArtifactInputs inputs, Path nativeImageFile, Path runnerJarFile,
                Instant now) {
            if (!Files.exists(entryPaths.entryDir)) {
                try {
                    Files.createDirectories(entryPaths.entryDir);
                } catch (IOException e) {
                    throw new RuntimeException(String.format("Could not create directory %s", entryPaths.entryDir), e);
                }
            }
            try {
                Files.copy(nativeImageFile, entryPaths.nativeImageFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(
                        String.format("Could not copy from %s to %s", nativeImageFile, entryPaths.nativeImageFile), e);
            }
            try {
                Files.copy(runnerJarFile, entryPaths.runnerJarFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(
                        String.format("Could not copy from %s to %s", nativeImageFile, entryPaths.nativeImageFile), e);
            }
            inputs.store(entryPaths.inputsFile);
            final NativeImageCacheEntryMetadata md = new NativeImageCacheEntryMetadata(runnerJarFile.getFileName().toString(),
                    now, now);
            md.store(entryPaths.metadataFile);
        }

        public Path getNativeImageFile() {
            return paths.nativeImageFile;
        }

        public NativeImageCacheEntryMetadata getMetadata() {
            return metadata;
        }

        public Path getRunnerJarFile() {
            return paths.runnerJarFile;
        }

        @Override
        public int compareTo(NativeImageCacheEntry other) {
            return this.metadata.lastRetrievedOn.compareTo(other.metadata.lastRetrievedOn);
        }
    }

}
