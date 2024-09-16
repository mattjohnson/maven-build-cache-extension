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

import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheUtilsTest {
    @Test
    void testToUnixMode() {
        Set<PosixFilePermission> permissions = new HashSet<>();
        permissions.add(PosixFilePermission.OWNER_READ);
        permissions.add(PosixFilePermission.OWNER_WRITE);
        permissions.add(PosixFilePermission.OWNER_EXECUTE);
        permissions.add(PosixFilePermission.GROUP_READ);
        permissions.add(PosixFilePermission.GROUP_EXECUTE);
        permissions.add(PosixFilePermission.OTHERS_READ);

        int mode = CacheUtils.toUnixMode(permissions);
        assertEquals(0754, mode);
    }

    @Test
    void testFromUnixMode() {
        int mode = 0754;
        Set<PosixFilePermission> permissions = CacheUtils.fromUnixMode(mode);

        Set<PosixFilePermission> expectedPermissions = new HashSet<>();
        expectedPermissions.add(PosixFilePermission.OWNER_READ);
        expectedPermissions.add(PosixFilePermission.OWNER_WRITE);
        expectedPermissions.add(PosixFilePermission.OWNER_EXECUTE);
        expectedPermissions.add(PosixFilePermission.GROUP_READ);
        expectedPermissions.add(PosixFilePermission.GROUP_EXECUTE);
        expectedPermissions.add(PosixFilePermission.OTHERS_READ);

        assertEquals(expectedPermissions, permissions);
    }
}
