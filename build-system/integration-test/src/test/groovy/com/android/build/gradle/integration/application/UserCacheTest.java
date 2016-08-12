/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.application;

import static com.android.build.gradle.integration.common.truth.TruthHelper.assertThat;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp;
import com.android.build.gradle.integration.common.utils.TestFileUtils;
import com.android.utils.FileUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Integration tests for user cache. */
public class UserCacheTest {

    @Rule
    public GradleTestProject project =
            GradleTestProject.builder()
                    .fromTestApp(HelloWorldApp.forPlugin("com.android.application"))
                    .create();

    @Before
    public void setUp() throws IOException {
        // Add a dependency on an external library (guava)
        TestFileUtils.appendToFile(
                project.getBuildFile(),
                "\ndependencies {\n    compile 'com.google.guava:guava:17.0'\n}\n");
    }

    @Test
    public void testUserCacheEnabled() throws IOException {
        File userCacheDir = new File(project.getTestDir(), "user-cache");
        FileUtils.deletePath(userCacheDir);

        project.executor()
                .withProperty("android.enableUserCache", "true")
                .withProperty("android.userCacheDir", userCacheDir.getAbsolutePath())
                .run("clean", "assembleDebug");

        File preDexDir = FileUtils.join(project.getIntermediatesDir(), "pre-dexed", "debug");
        List<File> dexFiles = Arrays.asList(preDexDir.listFiles());
        List<File> cachedFiles = Arrays.asList(userCacheDir.listFiles());

        assertThat(dexFiles).hasSize(2);
        assertThat(cachedFiles).hasSize(1);

        // Assert that the pre-dexed file of the guava library is stored in the cache. Depending on
        // the cache implementation, either the output file is created first and copied to the
        // cache, or the cached file is created first and copied to the output file. In either way,
        // we check their timestamps to make sure we actually copied one to the other and did not
        // accidentally run pre-dexing twice to create the two files.
        File cachedGuavaDexFile = cachedFiles.get(0);
        File guavaDexFile;
        File projectDexFile;
        if (dexFiles.get(0).getName().contains("guava")) {
            guavaDexFile = dexFiles.get(0);
            projectDexFile = dexFiles.get(1);
        } else {
            guavaDexFile = dexFiles.get(1);
            projectDexFile = dexFiles.get(0);
        }
        long cachedGuavaTimestamp = cachedGuavaDexFile.lastModified();
        long projectTimestamp = projectDexFile.lastModified();

        assertThat(guavaDexFile).wasModifiedAt(cachedGuavaTimestamp);

        project.executor()
                .withProperty("android.enableUserCache", "true")
                .withProperty("android.userCacheDir", userCacheDir.getAbsolutePath())
                .run("clean", "assembleDebug");

        assertThat(preDexDir.list()).hasLength(2);
        assertThat(userCacheDir.list()).hasLength(1);

        // Assert that the cached file is unchanged and the pre-dexed file of the guava library is
        // copied from the cache
        assertThat(cachedGuavaDexFile).wasModifiedAt(cachedGuavaTimestamp);
        assertThat(guavaDexFile).wasModifiedAt(cachedGuavaTimestamp);
        assertThat(projectDexFile).isNewerThan(projectTimestamp);
    }

    @Test
    public void testUserCacheDisabled() throws IOException {
        File userCacheDir = new File(project.getTestDir(), "user-cache");
        FileUtils.deletePath(userCacheDir);

        project.executor()
                .withProperty("android.enableUserCache", "false")
                .withProperty("android.userCacheDir", userCacheDir.getAbsolutePath())
                .run("clean", "assembleDebug");
        assertThat(userCacheDir).doesNotExist();
    }
}
