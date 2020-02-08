package io.quarkus.deployment.pkg.steps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import io.quarkus.deployment.util.HashUtil;

/**
 * A set of inputs necessary for building an artifact.
 */
public class ArtifactInputs {

    private final Map<String, String> entries;

    public ArtifactInputs(Map<String, String> entries) {
        this.entries = new TreeMap<>(entries);
    }

    /**
     * Store thes {@link ArtifactInputs} into a file
     *
     * @param path where to store these {@link ArtifactInputs}
     */
    public void store(Path path) {
        try {
            Files.write(path, toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(String.format("Could not write to %s", path), e);
        }
    }

    /**
     * @param path the file to compare with
     * @return {@code true} if the given {@link Path} stores the same {@link ArtifactInputs} as the current ones.
     */
    public boolean equalsFile(Path path) {
        try {
            if (!Files.exists(path)) {
                return false;
            }
            final String fileContent = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return toString().equals(fileContent);
        } catch (IOException e) {
            throw new RuntimeException(String.format("Could not read %s", path), e);
        }
    }

    /**
     * @return a SHA1 hash of the string representation of this {@link ArtifactInputs}
     */
    public String getSha1() {
        return HashUtil.sha1(toString());
    }

    public static class Builder {
        private Map<String, String> entries = new TreeMap<>();

        public Builder entry(String key, String value) {
            entries.put(key, value);
            return this;
        }

        public ArtifactInputs build() {
            return new ArtifactInputs(entries);
        }

        public Builder entries(ArtifactInputs inputs) {
            this.entries.putAll(inputs.entries);
            return this;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (Entry<String, String> entry : entries.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((entries == null) ? 0 : entries.hashCode());
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
        ArtifactInputs other = (ArtifactInputs) obj;
        if (entries == null) {
            if (other.entries != null)
                return false;
        } else if (!entries.equals(other.entries))
            return false;
        return true;
    }
}
