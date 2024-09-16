/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.buildcache;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.buildcache.xml.build.Scm;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.SessionData;
import org.slf4j.Logger;

import static org.apache.commons.lang3.StringUtils.removeStart;
import static org.apache.commons.lang3.StringUtils.trim;
import static org.apache.maven.artifact.Artifact.LATEST_VERSION;
import static org.apache.maven.artifact.Artifact.SNAPSHOT_VERSION;

/**
 * Cache Utils
 */
public class CacheUtils {
    public static boolean isPom(MavenProject project) {
        return project.getPackaging().equals("pom");
    }

    public static boolean isPom(Dependency dependency) {
        return dependency.getType().equals("pom");
    }

    public static boolean isSnapshot(String version) {
        return version.endsWith(SNAPSHOT_VERSION) || version.endsWith(LATEST_VERSION);
    }

    public static String normalizedName(Artifact artifact) {
        if (artifact.getFile() == null) {
            return null;
        }

        StringBuilder filename = new StringBuilder(artifact.getArtifactId());

        if (artifact.hasClassifier()) {
            filename.append("-").append(artifact.getClassifier());
        }

        final ArtifactHandler artifactHandler = artifact.getArtifactHandler();
        if (artifactHandler != null && StringUtils.isNotBlank(artifactHandler.getExtension())) {
            filename.append(".").append(artifactHandler.getExtension());
        }
        return filename.toString();
    }

    public static String mojoExecutionKey(MojoExecution mojo) {
        return StringUtils.join(
                Arrays.asList(
                        StringUtils.defaultIfEmpty(mojo.getExecutionId(), "emptyExecId"),
                        StringUtils.defaultIfEmpty(mojo.getGoal(), "emptyGoal"),
                        StringUtils.defaultIfEmpty(mojo.getLifecyclePhase(), "emptyLifecyclePhase"),
                        StringUtils.defaultIfEmpty(mojo.getArtifactId(), "emptyArtifactId"),
                        StringUtils.defaultIfEmpty(mojo.getGroupId(), "emptyGroupId")),
                ":");
    }

    public static Path getMultimoduleRoot(MavenSession session) {
        return session.getRequest().getMultiModuleProjectDirectory().toPath();
    }

    public static Scm readGitInfo(MavenSession session) throws IOException {
        final Scm scmCandidate = new Scm();
        final Path gitDir = getMultimoduleRoot(session).resolve(".git");
        if (Files.isDirectory(gitDir)) {
            final Path headFile = gitDir.resolve("HEAD");
            if (Files.exists(headFile)) {
                String headRef = readFirstLine(headFile, "<missing branch>");
                if (headRef.startsWith("ref: ")) {
                    String branch = trim(removeStart(headRef, "ref: "));
                    scmCandidate.setSourceBranch(branch);
                    final Path refPath = gitDir.resolve(branch);
                    if (Files.exists(refPath)) {
                        String revision = readFirstLine(refPath, "<missing revision>");
                        scmCandidate.setRevision(trim(revision));
                    }
                } else {
                    scmCandidate.setSourceBranch(headRef);
                    scmCandidate.setRevision(headRef);
                }
            }
        }
        return scmCandidate;
    }

    private static String readFirstLine(Path path, String defaultValue) throws IOException {
        return Files.lines(path, StandardCharsets.UTF_8).findFirst().orElse(defaultValue);
    }

    public static <T> T getLast(List<T> list) {
        int size = list.size();
        if (size > 0) {
            return list.get(size - 1);
        }
        throw new NoSuchElementException();
    }

    public static <T> T getOrCreate(MavenSession session, Object key, Supplier<T> supplier) {
        SessionData data = session.getRepositorySession().getData();
        while (true) {
            T t = (T) data.get(key);
            if (t == null) {
                t = supplier.get();
                if (data.set(key, null, t)) {
                    continue;
                }
            }
            return t;
        }
    }

    public static boolean isArchive(File file) {
        String fileName = file.getName();
        if (!file.isFile() || file.isHidden()) {
            return false;
        }
        return StringUtils.endsWithAny(fileName, ".jar", ".zip", ".war", ".ear");
    }

    /**
     * Put every matching files of a directory in a zip.
     * @param dir directory to zip
     * @param zip zip to populate
     * @param glob glob to apply to filenames
     * @return true if at least one file has been included in the zip.
     * @throws IOException
     */
    public static boolean zip(final Path dir, final Path zip, final String glob) throws IOException {
        final MutableBoolean hasFiles = new MutableBoolean();

        try (ZipArchiveOutputStream zipOutputStream = new ZipArchiveOutputStream(zip)) {
            PathMatcher matcher =
                    "*".equals(glob) ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes)
                        throws IOException {
                    if (matcher == null || matcher.matches(path.getFileName())) {
                        final ZipArchiveEntry zipEntry = new ZipArchiveEntry(
                                path.toFile(), dir.relativize(path).toString());

                        if (basicFileAttributes.isSymbolicLink()) {
                            zipEntry.setUnixMode(UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
                        } else {
                            if (isPosixSupported()) {
                                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                                zipEntry.setUnixMode(toUnixMode(permissions));
                            }
                        }
                        zipOutputStream.putArchiveEntry(zipEntry);

                        if (!Files.isSymbolicLink(path)) {
                            Files.copy(path, zipOutputStream);
                        } else {
                            // write the target of the symlink
                            zipOutputStream.write(
                                    Files.readSymbolicLink(path).toString().getBytes());
                        }
                        hasFiles.setTrue();
                        zipOutputStream.closeArchiveEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return hasFiles.booleanValue();
    }

    public static void unzip(Path zip, Path out) throws IOException {
        ZipFile zipFile = ZipFile.builder().setPath(zip).get();
        Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            Path file = out.resolve(entry.getName());
            if (!file.normalize().startsWith(out.normalize())) {
                throw new RuntimeException("Bad zip entry");
            }
            if (entry.isDirectory()) {
                Files.createDirectory(file);
            } else {
                Path parent = file.getParent();
                Files.createDirectories(parent);
                if (isPosixSupported() && entry.isUnixSymlink()) {
                    Path target = Paths.get(zipFile.getUnixSymlink(entry));
                    Files.deleteIfExists(file);
                    Files.createSymbolicLink(file, target);
                } else {
                    Files.copy(zipFile.getInputStream(entry), file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!entry.isUnixSymlink()) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(entry.getTime()));
                if (isPosixSupported()) {
                    Files.setPosixFilePermissions(file, fromUnixMode(entry.getUnixMode()));
                }
            }
        }
    }

    public static boolean isPosixSupported() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    protected static int toUnixMode(final Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (PosixFilePermission permission : permissions) {
            switch (permission) {
                case OWNER_READ:
                    mode |= 0400;
                    break;
                case OWNER_WRITE:
                    mode |= 0200;
                    break;
                case OWNER_EXECUTE:
                    mode |= 0100;
                    break;
                case GROUP_READ:
                    mode |= 0040;
                    break;
                case GROUP_WRITE:
                    mode |= 0020;
                    break;
                case GROUP_EXECUTE:
                    mode |= 0010;
                    break;
                case OTHERS_READ:
                    mode |= 0004;
                    break;
                case OTHERS_WRITE:
                    mode |= 0002;
                    break;
                case OTHERS_EXECUTE:
                    mode |= 0001;
                    break;
                default:
                    break;
            }
        }
        return mode;
    }

    public static Set<PosixFilePermission> fromUnixMode(int mode) {
        Set<PosixFilePermission> permissions = new HashSet<>();

        if ((mode & 0400) != 0) {
            permissions.add(PosixFilePermission.OWNER_READ);
        }
        if ((mode & 0200) != 0) {
            permissions.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((mode & 0100) != 0) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if ((mode & 0040) != 0) {
            permissions.add(PosixFilePermission.GROUP_READ);
        }
        if ((mode & 0020) != 0) {
            permissions.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((mode & 0010) != 0) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if ((mode & 0004) != 0) {
            permissions.add(PosixFilePermission.OTHERS_READ);
        }
        if ((mode & 0002) != 0) {
            permissions.add(PosixFilePermission.OTHERS_WRITE);
        }
        if ((mode & 0001) != 0) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }

        return permissions;
    }

    public static <T> void debugPrintCollection(
            Logger logger, Collection<T> values, String heading, String elementCaption) {
        if (logger.isDebugEnabled() && values != null && !values.isEmpty()) {
            final int size = values.size();
            int i = 0;
            logger.debug("{} (total {})", heading, size);
            for (T value : values) {
                i++;
                logger.debug("{} {} of {} : {}", elementCaption, i, size, value);
            }
        }
    }
}
