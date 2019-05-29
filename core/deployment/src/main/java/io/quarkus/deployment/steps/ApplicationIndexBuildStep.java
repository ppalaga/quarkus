package io.quarkus.deployment.steps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.ArchiveRootBuildItem;

public class ApplicationIndexBuildStep {

    @BuildStep
    ApplicationIndexBuildItem build(ArchiveRootBuildItem root) throws IOException {

        Indexer indexer = new Indexer();
        Path rootPath = root.getPath();
        if (Files.isDirectory(rootPath)) {
            indexAppClasses(indexer, rootPath);
        } else {
            try (FileSystem fs = FileSystems.newFileSystem(rootPath, null)) {
                for (Path p : fs.getRootDirectories()) {
                    indexAppClasses(indexer, p);
                }
            }
        }
        Index appIndex = indexer.complete();
        return new ApplicationIndexBuildItem(appIndex);
    }

    private void indexAppClasses(Indexer indexer, Path rootPath) throws IOException {
        Files.walkFileTree(rootPath, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".class")) {
                    try (InputStream stream = Files.newInputStream(file)) {
                        indexer.index(stream);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
