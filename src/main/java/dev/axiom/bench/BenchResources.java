package dev.axiom.bench;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;

/**
 * Copies a classpath resource directory to a temp dir. Works whether resources
 * are plain files (dev/test) or inside the packaged jar.
 */
final class BenchResources {

    private BenchResources() {}

    static Path copyToTemp(String resourcePath) {
        try {
            Path tmp = Files.createTempDirectory("axiom-bench-");
            if (resourcePath == null) {
                // A test command still needs somewhere to run.
                return tmp;
            }
            URL url = BenchResources.class.getResource(resourcePath);
            if (url == null) throw new BenchException("Missing resource: " + resourcePath);
            copyTree(sourcePath(url, resourcePath), tmp);
            return tmp;
        } catch (Exception e) {
            throw new BenchException("Failed to copy resource " + resourcePath, e);
        }
    }

    private static Path sourcePath(URL url, String resourcePath) throws IOException, URISyntaxException {
        if ("jar".equals(url.getProtocol())) {
            URI uri = url.toURI();
            FileSystem fs;
            try {
                fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
            } catch (FileSystemAlreadyExistsException e) {
                fs = FileSystems.getFileSystem(uri);
            }
            return fs.getPath(resourcePath);
        }
        return Path.of(url.toURI());
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
