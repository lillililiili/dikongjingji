package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Present the same SQL bytes under this database's original names. All other migrations retain the published filename. */
final class LegacyIdentityResources implements ResourceProvider {
    private final Map<String, LoadableResource> byName = new LinkedHashMap<>();

    LegacyIdentityResources() {
        try {
            Resource[] sources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/identity/*");
            for (Resource source : sources) {
                String canonical = source.getFilename();
                if (canonical == null) continue;
                String filename = IdentityMigrationHistory.LEGACY.stream()
                        .filter(entry -> entry.canonicalScript().equals(canonical)).map(IdentityMigrationHistory.Entry::script)
                        .findFirst().orElse(canonical);
                if (byName.putIfAbsent(filename, new SqlResource(filename, source)) != null) {
                    throw new IllegalStateException("Duplicate identity migration resource " + filename);
                }
            }
            for (var entry : IdentityMigrationHistory.LEGACY) {
                if (!byName.containsKey(entry.script())) throw new IllegalStateException("Missing identity migration: " + entry.script());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read identity migrations", e);
        }
    }

    @Override
    public LoadableResource getResource(String name) {
        return byName.get(name);
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        return byName.values().stream().filter(resource -> resource.getFilename().startsWith(prefix))
                .filter(resource -> Arrays.stream(suffixes).anyMatch(resource.getFilename()::endsWith)).toList();
    }

    private static final class SqlResource extends LoadableResource {
        private final String filename;
        private final Resource source;

        SqlResource(String filename, Resource source) {
            this.filename = filename;
            this.source = source;
        }

        @Override public String getFilename() { return filename; }
        @Override public String getRelativePath() { return filename; }
        @Override public String getAbsolutePath() { return source.getDescription(); }
        @Override public String getAbsolutePathOnDisk() { return null; }
        @Override public Reader read() {
            try {
                return new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not open " + filename, e);
            }
        }
    }
}
