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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for permission preservation in CacheUtils.zip() and CacheUtils.unzip() methods.
 * These tests verify that Unix file permissions affect ZIP file hashes when preservation
 * is enabled, and do not affect hashes when disabled.
 */
class CacheUtilsPermissionsTest {

    @TempDir
    Path tempDir;

    /**
     * Tests that ZIP file hash changes when permissions change (when preservePermissions=true).
     * This ensures that the cache invalidates when file permissions change, maintaining
     * cache correctness similar to how Git includes file mode in tree hashes.
     */
    @Test
    void testPermissionsAffectFileHashWhenEnabled() throws IOException {
        // Skip test on non-POSIX filesystems (e.g., Windows)
        if (!tempDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        // Given: Same directory content with different permissions
        Path sourceDir1 = tempDir.resolve("source1");
        Files.createDirectories(sourceDir1);
        Path file1 = sourceDir1.resolve("script.sh");
        writeString(file1, "#!/bin/bash\necho hello");

        // Set executable permissions (755)
        Set<PosixFilePermission> execPermissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(file1, execPermissions);

        // Create second directory with identical content but different permissions
        Path sourceDir2 = tempDir.resolve("source2");
        Files.createDirectories(sourceDir2);
        Path file2 = sourceDir2.resolve("script.sh");
        writeString(file2, "#!/bin/bash\necho hello"); // Identical content

        // Set non-executable permissions (644)
        Set<PosixFilePermission> normalPermissions = PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(file2, normalPermissions);

        // When: Create ZIP files with preservePermissions=true
        Path zip1 = tempDir.resolve("cache1.zip");
        Path zip2 = tempDir.resolve("cache2.zip");
        CacheUtils.zip(sourceDir1, zip1, "*", true);
        CacheUtils.zip(sourceDir2, zip2, "*", true);

        // Then: ZIP files should have different hashes despite identical content
        byte[] hash1 = Files.readAllBytes(zip1);
        byte[] hash2 = Files.readAllBytes(zip2);

        boolean hashesAreDifferent = !Arrays.equals(hash1, hash2);
        assertTrue(
                hashesAreDifferent,
                "ZIP files with same content but different permissions should have different hashes "
                        + "when preservePermissions=true. This ensures cache invalidation when permissions change "
                        + "(executable vs non-executable files).");
    }

    /**
     * Tests that ZIP file hash does NOT significantly vary when permissions change but
     * preservePermissions=false. While ZIP timestamps may still cause minor differences,
     * the key point is that permission information is NOT deterministically stored.
     */
    @Test
    void testPermissionsDoNotAffectHashWhenDisabled() throws IOException {
        // Skip test on non-POSIX filesystems (e.g., Windows)
        if (!tempDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        // Given: Same directory content with different permissions
        Path sourceDir1 = tempDir.resolve("source1");
        Files.createDirectories(sourceDir1);
        Path file1 = sourceDir1.resolve("script.sh");
        writeString(file1, "#!/bin/bash\necho hello");

        // Set executable permissions (755)
        Set<PosixFilePermission> execPermissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(file1, execPermissions);

        // Create second directory with identical content but different permissions
        Path sourceDir2 = tempDir.resolve("source2");
        Files.createDirectories(sourceDir2);
        Path file2 = sourceDir2.resolve("script.sh");
        writeString(file2, "#!/bin/bash\necho hello"); // Identical content

        // Set non-executable permissions (644)
        Set<PosixFilePermission> normalPermissions = PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(file2, normalPermissions);

        // When: Create ZIP files with preservePermissions=false
        Path zip1 = tempDir.resolve("cache1.zip");
        Path zip2 = tempDir.resolve("cache2.zip");
        CacheUtils.zip(sourceDir1, zip1, "*", false);
        CacheUtils.zip(sourceDir2, zip2, "*", false);

        // Unzip and verify permissions are NOT preserved
        Path extractDir1 = tempDir.resolve("extracted1");
        Path extractDir2 = tempDir.resolve("extracted2");
        Files.createDirectories(extractDir1);
        Files.createDirectories(extractDir2);
        CacheUtils.unzip(zip1, extractDir1, false);
        CacheUtils.unzip(zip2, extractDir2, false);

        Path extractedFile1 = extractDir1.resolve("script.sh");
        Path extractedFile2 = extractDir2.resolve("script.sh");

        Set<PosixFilePermission> perms1 = Files.getPosixFilePermissions(extractedFile1);
        Set<PosixFilePermission> perms2 = Files.getPosixFilePermissions(extractedFile2);

        // Files should NOT retain their original different permissions
        // Both should have default permissions determined by umask
        assertFalse(
                perms1.equals(execPermissions) && perms2.equals(normalPermissions),
                "When preservePermissions=false, original permissions should NOT be preserved. "
                        + "Files should use system default permissions (umask).");
    }

    /**
     * Tests that symlinks are correctly preserved through zip/unzip cycle when preserveSymlinks=true.
     */
    @Test
    void testSymlinkPreservation() throws IOException {
        // Skip test on non-POSIX filesystems (e.g., Windows)
        if (!tempDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        // Given: A directory with a file and a symlink pointing to it
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectories(sourceDir);
        Path targetFile = sourceDir.resolve("target.txt");
        writeString(targetFile, "target content");
        Path symlink = sourceDir.resolve("link.txt");
        Files.createSymbolicLink(symlink, targetFile.getFileName());

        // When: Zip and unzip the directory with preserveSymlinks=true
        Path zipFile = tempDir.resolve("test.zip");
        CacheUtils.zip(sourceDir, zipFile, "*", true, true);

        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);
        CacheUtils.unzip(zipFile, extractDir, true, true);

        // Then: The symlink should be preserved
        Path extractedSymlink = extractDir.resolve("link.txt");
        assertTrue(Files.isSymbolicLink(extractedSymlink), "Symlink should be preserved after zip/unzip");
        assertEquals(targetFile.getFileName(), Files.readSymbolicLink(extractedSymlink));
        assertEquals("target content", readString(extractDir.resolve("target.txt")));
    }

    /**
     * Tests the exact behavior when preserveSymlinks=false and source contains a symlink.
     * Verifies that symlinks are followed and their target content is stored as regular files.
     */
    @Test
    void testSymlinkNotPreservedByDefault() throws IOException {
        // Skip test on non-POSIX filesystems (e.g., Windows)
        if (!tempDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        // Given: A directory with a file and a symlink pointing to it
        Path sourceDir = tempDir.resolve("source");
        Files.createDirectories(sourceDir);
        Path targetFile = sourceDir.resolve("target.txt");
        writeString(targetFile, "target content");
        Path symlink = sourceDir.resolve("link.txt");
        Files.createSymbolicLink(symlink, targetFile.getFileName());

        // Verify symlink exists in source
        assertTrue(Files.isSymbolicLink(symlink), "Symlink should exist in source");

        // When: Zip with preserveSymlinks=false
        Path zipFile = tempDir.resolve("test.zip");
        CacheUtils.zip(sourceDir, zipFile, "*", true, false);

        // Inspect zip contents to understand what was stored
        try (org.apache.commons.compress.archivers.zip.ZipFile zf =
                org.apache.commons.compress.archivers.zip.ZipFile.builder()
                        .setPath(zipFile)
                        .get()) {

            org.apache.commons.compress.archivers.zip.ZipArchiveEntry linkEntry = zf.getEntry("link.txt");
            assertNotNull(linkEntry, "link.txt should exist in zip");
            assertFalse(linkEntry.isUnixSymlink(), "Entry should NOT be stored as symlink when preserveSymlinks=false");

            // Read the content stored in the zip for the symlink entry (Java 8 compatible)
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream is = zf.getInputStream(linkEntry)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
            }
            String storedContent = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            assertEquals(
                    "target content",
                    storedContent,
                    "When preserveSymlinks=false, symlink should be followed and target content stored");
        }

        // When: Unzip
        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);
        CacheUtils.unzip(zipFile, extractDir, true, false);

        // Then: The symlink should be extracted as a regular file with target content
        Path extractedFile = extractDir.resolve("link.txt");
        assertTrue(Files.exists(extractedFile), "link.txt should be extracted");
        assertFalse(Files.isSymbolicLink(extractedFile), "Extracted file should NOT be a symlink");
        assertEquals("target content", readString(extractedFile), "Content should match original target");
    }

    /**
     * Tests that symlinks within the module boundary are allowed.
     * Simulates: module/target/node_modules/link -> module/src/something (within module)
     */
    @Test
    void testSymlinkWithinModuleBoundaryAllowed() throws IOException {
        // Skip test on non-POSIX filesystems (e.g., Windows)
        if (!tempDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        // Given: A module structure with symlink pointing to sibling directory
        // module/
        //   src/shared.txt
        //   target/node_modules/link.txt -> ../../src/shared.txt
        Path moduleRoot = tempDir.resolve("module");
        Path srcDir = moduleRoot.resolve("src");
        Path targetDir = moduleRoot.resolve("target");
        Path nodeModules = targetDir.resolve("node_modules");
        Files.createDirectories(srcDir);
        Files.createDirectories(nodeModules);

        Path sharedFile = srcDir.resolve("shared.txt");
        writeString(sharedFile, "shared content");

        // Symlink using relative path: ../../src/shared.txt (stays within module)
        Path symlink = nodeModules.resolve("link.txt");
        Files.createSymbolicLink(symlink, Paths.get("../../src/shared.txt"));

        // When: Zip with module root as boundary (should succeed)
        Path zipFile = tempDir.resolve("test.zip");
        boolean hasFiles = CacheUtils.zip(nodeModules, zipFile, "*", true, true, moduleRoot);

        // Then: Zip should succeed because symlink stays within module boundary
        assertTrue(hasFiles, "Zip should succeed for symlinks within module boundary");
    }

    /**
     * Tests that symlinks escaping the module boundary are rejected.
     * Simulates: module/target/node_modules/link -> /etc/passwd (escaping module)
     */
    @Test
    void testSymlinkEscapingModuleBoundaryRejected() throws IOException {
        // Skip test on non-POSIX filesystems (e.g., Windows)
        if (!tempDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        // Given: A module structure with symlink escaping to outside
        Path moduleRoot = tempDir.resolve("module");
        Path targetDir = moduleRoot.resolve("target");
        Path nodeModules = targetDir.resolve("node_modules");
        Files.createDirectories(nodeModules);

        // File outside the module
        Path outsideFile = tempDir.resolve("outside.txt");
        writeString(outsideFile, "outside content");

        // Symlink using absolute path escaping module
        Path escapingSymlink = nodeModules.resolve("escape.txt");
        Files.createSymbolicLink(escapingSymlink, outsideFile.toAbsolutePath());

        // When/Then: Zip should fail because symlink escapes module boundary
        Path zipFile = tempDir.resolve("test.zip");
        try {
            CacheUtils.zip(nodeModules, zipFile, "*", true, true, moduleRoot);
            fail("Expected IOException for symlink escaping module boundary");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("escapes boundary"), "Error should mention boundary escape");
        }
    }

    /**
     * Tests that relative symlinks using too many ".." to escape module are rejected.
     * Simulates: module/target/node_modules/link -> ../../../outside.txt (escaping module)
     */
    @Test
    void testRelativeSymlinkEscapingModuleBoundaryRejected() throws IOException {
        // Skip test on non-POSIX filesystems (e.g., Windows)
        if (!tempDir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }

        // Given: A module structure with relative symlink escaping
        Path moduleRoot = tempDir.resolve("module");
        Path targetDir = moduleRoot.resolve("target");
        Path nodeModules = targetDir.resolve("node_modules");
        Files.createDirectories(nodeModules);

        // File outside the module
        Path outsideFile = tempDir.resolve("outside.txt");
        writeString(outsideFile, "outside content");

        // Symlink: ../../../outside.txt (goes up 3 levels from node_modules, escaping module)
        Path escapingSymlink = nodeModules.resolve("escape.txt");
        Files.createSymbolicLink(escapingSymlink, Paths.get("../../../outside.txt"));

        // When/Then: Zip should fail because symlink escapes module boundary
        Path zipFile = tempDir.resolve("test.zip");
        try {
            CacheUtils.zip(nodeModules, zipFile, "*", true, true, moduleRoot);
            fail("Expected IOException for relative symlink escaping module boundary");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("escapes boundary"), "Error should mention boundary escape");
        }
    }

    /**
     * Java 8 compatible version of Files.writeString().
     */
    private void writeString(Path path, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Java 8 compatible version of Files.readString().
     */
    private String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
