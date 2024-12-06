/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.net.ct;

import android.annotation.SuppressLint;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** Installer of CT log lists. */
public class CertificateTransparencyInstaller {

    private static final String TAG = "CertificateTransparencyInstaller";
    private static final String CT_DIR_NAME = "/data/misc/keychain/ct/";

    static final String LOGS_DIR_PREFIX = "logs-";
    static final String LOGS_LIST_FILE_NAME = "log_list.json";
    static final String CURRENT_DIR_SYMLINK_NAME = "current";

    private final File mCertificateTransparencyDir;
    private final File mCurrentDirSymlink;

    CertificateTransparencyInstaller(File certificateTransparencyDir) {
        mCertificateTransparencyDir = certificateTransparencyDir;
        mCurrentDirSymlink = new File(certificateTransparencyDir, CURRENT_DIR_SYMLINK_NAME);
    }

    CertificateTransparencyInstaller() {
        this(new File(CT_DIR_NAME));
    }

    /**
     * Install a new log list to use during SCT verification.
     *
     * @param newContent an input stream providing the log list
     * @param version the version of the new log list
     * @return true if the log list was installed successfully, false otherwise.
     * @throws IOException if the list cannot be saved in the CT directory.
     */
    public boolean install(InputStream newContent, String version) throws IOException {
        // To support atomically replacing the old configuration directory with the new there's a
        // bunch of steps. We create a new directory with the logs and then do an atomic update of
        // the current symlink to point to the new directory.
        // 1. Ensure that the update dir exists and is readable.
        makeDir(mCertificateTransparencyDir);

        File newLogsDir = new File(mCertificateTransparencyDir, LOGS_DIR_PREFIX + version);
        // 2. Handle the corner case where the new directory already exists.
        if (newLogsDir.exists()) {
            // If the symlink has already been updated then the update died between steps 6 and 7
            // and so we cannot delete the directory since it is in use.
            if (newLogsDir.getCanonicalPath().equals(mCurrentDirSymlink.getCanonicalPath())) {
                deleteOldLogDirectories();
                return false;
            }
            // If the symlink has not been updated then the previous installation failed and this is
            // a re-attempt. Clean-up leftover files and try again.
            deleteContentsAndDir(newLogsDir);
        }
        try {
            // 3. Create /data/misc/keychain/ct/logs-<new_version>/ .
            makeDir(newLogsDir);

            // 4. Move the log list json file in logs-<new_version>/ .
            File logListFile = new File(newLogsDir, LOGS_LIST_FILE_NAME);
            if (Files.copy(newContent, logListFile.toPath()) == 0) {
                throw new IOException("The log list appears empty");
            }
            setWorldReadable(logListFile);

            // 5. Create temp symlink. We rename this to the target symlink to get an atomic update.
            File tempSymlink = new File(mCertificateTransparencyDir, "new_symlink");
            try {
                Os.symlink(newLogsDir.getCanonicalPath(), tempSymlink.getCanonicalPath());
            } catch (ErrnoException e) {
                throw new IOException("Failed to create symlink", e);
            }

            // 6. Update the symlink target, this is the actual update step.
            tempSymlink.renameTo(mCurrentDirSymlink.getAbsoluteFile());
        } catch (IOException | RuntimeException e) {
            deleteContentsAndDir(newLogsDir);
            throw e;
        }
        Log.i(TAG, "CT log directory updated to " + newLogsDir.getAbsolutePath());
        // 7. Cleanup
        deleteOldLogDirectories();
        return true;
    }

    private void makeDir(File dir) throws IOException {
        dir.mkdir();
        if (!dir.isDirectory()) {
            throw new IOException("Unable to make directory " + dir.getCanonicalPath());
        }
        setWorldReadable(dir);
    }

    // CT files and directories are readable by all apps.
    @SuppressLint("SetWorldReadable")
    private void setWorldReadable(File file) throws IOException {
        if (!file.setReadable(true, false)) {
            throw new IOException("Failed to set " + file.getCanonicalPath() + " readable");
        }
    }

    private void deleteOldLogDirectories() throws IOException {
        if (!mCertificateTransparencyDir.exists()) {
            return;
        }
        File currentTarget = mCurrentDirSymlink.getCanonicalFile();
        for (File file : mCertificateTransparencyDir.listFiles()) {
            if (!currentTarget.equals(file.getCanonicalFile())
                    && file.getName().startsWith(LOGS_DIR_PREFIX)) {
                deleteContentsAndDir(file);
            }
        }
    }

    static boolean deleteContentsAndDir(File dir) {
        if (deleteContents(dir)) {
            return dir.delete();
        } else {
            return false;
        }
    }

    private static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete " + file);
                    success = false;
                }
            }
        }
        return success;
    }
}
