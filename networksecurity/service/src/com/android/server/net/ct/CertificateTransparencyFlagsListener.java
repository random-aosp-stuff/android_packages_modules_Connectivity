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

import android.annotation.RequiresApi;
import android.content.Context;
import android.os.Build;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.Executors;

/** Listener class for the Certificate Transparency Phenotype flags. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CertificateTransparencyFlagsListener implements DeviceConfig.OnPropertiesChangedListener {

    private static final String TAG = "CertificateTransparencyFlagsListener";

    private final DataStore mDataStore;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    CertificateTransparencyFlagsListener(Context context) {
        mDataStore = new DataStore(Config.PREFERENCES_FILE);
        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(context, mDataStore);
    }

    void initialize() {
        mDataStore.load();
        mCertificateTransparencyDownloader.registerReceiver();
        DeviceConfig.addOnPropertiesChangedListener(
                Config.NAMESPACE_NETWORK_SECURITY, Executors.newSingleThreadExecutor(), this);
        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyFlagsListener initialized successfully");
        }
        // TODO: handle property changes triggering on boot before registering this listener.
    }

    @Override
    public void onPropertiesChanged(Properties properties) {
        if (!Config.NAMESPACE_NETWORK_SECURITY.equals(properties.getNamespace())) {
            return;
        }

        String newVersion =
                DeviceConfig.getString(Config.NAMESPACE_NETWORK_SECURITY, Config.FLAG_VERSION, "");
        String newContentUrl =
                DeviceConfig.getString(
                        Config.NAMESPACE_NETWORK_SECURITY, Config.FLAG_CONTENT_URL, "");
        String newMetadataUrl =
                DeviceConfig.getString(
                        Config.NAMESPACE_NETWORK_SECURITY, Config.FLAG_METADATA_URL, "");
        if (TextUtils.isEmpty(newVersion)
                || TextUtils.isEmpty(newContentUrl)
                || TextUtils.isEmpty(newMetadataUrl)) {
            return;
        }

        if (Config.DEBUG) {
            Log.d(TAG, "newVersion=" + newVersion);
            Log.d(TAG, "newContentUrl=" + newContentUrl);
            Log.d(TAG, "newMetadataUrl=" + newMetadataUrl);
        }

        String oldVersion = mDataStore.getProperty(Config.VERSION);
        String oldContentUrl = mDataStore.getProperty(Config.CONTENT_URL);
        String oldMetadataUrl = mDataStore.getProperty(Config.METADATA_URL);

        if (TextUtils.equals(newVersion, oldVersion)
                && TextUtils.equals(newContentUrl, oldContentUrl)
                && TextUtils.equals(newMetadataUrl, oldMetadataUrl)) {
            Log.i(TAG, "No flag changed, ignoring update");
            return;
        }

        // TODO: handle the case where there is already a pending download.

        mDataStore.setProperty(Config.VERSION_PENDING, newVersion);
        mDataStore.setProperty(Config.CONTENT_URL_PENDING, newContentUrl);
        mDataStore.setProperty(Config.METADATA_URL_PENDING, newMetadataUrl);
        mDataStore.store();

        mCertificateTransparencyDownloader.startMetadataDownload(newMetadataUrl);
    }
}
