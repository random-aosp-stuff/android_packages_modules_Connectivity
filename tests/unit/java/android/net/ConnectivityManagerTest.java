/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net;

import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOTA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_SUPL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkRequest.Type.BACKGROUND_REQUEST;
import static android.net.NetworkRequest.Type.REQUEST;
import static android.net.NetworkRequest.Type.TRACK_DEFAULT;
import static android.net.NetworkRequest.Type.TRACK_SYSTEM_DEFAULT;

import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager.NetworkCallback;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.ref.WeakReference;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(VERSION_CODES.R)
public class ConnectivityManagerTest {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();
    private static final int TIMEOUT_MS = 30_000;
    private static final int SHORT_TIMEOUT_MS = 150;

    @Mock Context mCtx;
    @Mock IConnectivityManager mService;
    @Mock NetworkPolicyManager mNpm;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    static NetworkCapabilities verifyNetworkCapabilities(
            int legacyType, int transportType, int... capabilities) {
        final NetworkCapabilities nc = ConnectivityManager.networkCapabilitiesForType(legacyType);
        assertNotNull(nc);
        assertTrue(nc.hasTransport(transportType));
        for (int capability : capabilities) {
            assertTrue(nc.hasCapability(capability));
        }

        return nc;
    }

    static void verifyUnrestrictedNetworkCapabilities(int legacyType, int transportType) {
        verifyNetworkCapabilities(
                legacyType,
                transportType,
                NET_CAPABILITY_INTERNET,
                NET_CAPABILITY_NOT_RESTRICTED,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);
    }

    static void verifyRestrictedMobileNetworkCapabilities(int legacyType, int capability) {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                legacyType,
                TRANSPORT_CELLULAR,
                capability,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobile() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE, TRANSPORT_CELLULAR);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileCbs() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_CBS, NET_CAPABILITY_CBS);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileDun() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_DUN, NET_CAPABILITY_DUN);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileFota() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_FOTA, NET_CAPABILITY_FOTA);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileHipri() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_HIPRI, TRANSPORT_CELLULAR);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileIms() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_IMS, NET_CAPABILITY_IMS);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileMms() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_MMS,
                TRANSPORT_CELLULAR,
                NET_CAPABILITY_MMS,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileSupl() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_SUPL,
                TRANSPORT_CELLULAR,
                NET_CAPABILITY_SUPL,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeWifi() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_WIFI, TRANSPORT_WIFI);
    }

    @Test
    public void testNetworkCapabilitiesForTypeWifiP2p() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_WIFI_P2P,
                TRANSPORT_WIFI,
                NET_CAPABILITY_NOT_RESTRICTED, NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED, NET_CAPABILITY_WIFI_P2P);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeBluetooth() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_BLUETOOTH, TRANSPORT_BLUETOOTH);
    }

    @Test
    public void testNetworkCapabilitiesForTypeEthernet() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_ETHERNET, TRANSPORT_ETHERNET);
    }

    @Test
    public void testCallbackRelease() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        NetworkRequest request = makeRequest(1);
        NetworkCallback callback = mock(ConnectivityManager.NetworkCallback.class,
                CALLS_REAL_METHODS);
        Handler handler = new Handler(Looper.getMainLooper());
        ArgumentCaptor<Messenger> captor = ArgumentCaptor.forClass(Messenger.class);

        // register callback
        when(mService.requestNetwork(anyInt(), any(), anyInt(), captor.capture(), anyInt(), any(),
                anyInt(), anyInt(), any(), nullable(String.class), anyInt())).thenReturn(request);
        manager.requestNetwork(request, callback, handler);

        // callback triggers
        captor.getValue().send(makeMessage(request, ConnectivityManager.CALLBACK_AVAILABLE));
        verify(callback, timeout(TIMEOUT_MS).times(1)).onAvailable(any(Network.class),
                any(NetworkCapabilities.class), any(LinkProperties.class), anyBoolean());

        // unregister callback
        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(request);

        // callback does not trigger anymore.
        captor.getValue().send(makeMessage(request, ConnectivityManager.CALLBACK_LOSING));
        verify(callback, after(SHORT_TIMEOUT_MS).never()).onLosing(any(), anyInt());
    }

    @Test
    public void testCallbackRecycling() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        NetworkRequest req1 = makeRequest(1);
        NetworkRequest req2 = makeRequest(2);
        NetworkCallback callback = mock(ConnectivityManager.NetworkCallback.class,
                CALLS_REAL_METHODS);
        Handler handler = new Handler(Looper.getMainLooper());
        ArgumentCaptor<Messenger> captor = ArgumentCaptor.forClass(Messenger.class);

        // register callback
        when(mService.requestNetwork(anyInt(), any(), anyInt(), captor.capture(), anyInt(), any(),
                anyInt(), anyInt(), any(), nullable(String.class), anyInt())).thenReturn(req1);
        manager.requestNetwork(req1, callback, handler);

        // callback triggers
        captor.getValue().send(makeMessage(req1, ConnectivityManager.CALLBACK_AVAILABLE));
        verify(callback, timeout(TIMEOUT_MS).times(1)).onAvailable(any(Network.class),
                any(NetworkCapabilities.class), any(LinkProperties.class), anyBoolean());

        // unregister callback
        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(req1);

        // callback does not trigger anymore.
        captor.getValue().send(makeMessage(req1, ConnectivityManager.CALLBACK_LOSING));
        verify(callback, after(SHORT_TIMEOUT_MS).never()).onLosing(any(), anyInt());

        // callback can be registered again
        when(mService.requestNetwork(anyInt(), any(), anyInt(), captor.capture(), anyInt(), any(),
                anyInt(), anyInt(), any(), nullable(String.class), anyInt())).thenReturn(req2);
        manager.requestNetwork(req2, callback, handler);

        // callback triggers
        captor.getValue().send(makeMessage(req2, ConnectivityManager.CALLBACK_LOST));
        verify(callback, timeout(TIMEOUT_MS).times(1)).onLost(any());

        // unregister callback
        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(req2);
    }

    // TODO: turn on this test when request  callback 1:1 mapping is enforced
    //@Test
    private void noDoubleCallbackRegistration() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        NetworkRequest request = makeRequest(1);
        NetworkCallback callback = new ConnectivityManager.NetworkCallback();
        ApplicationInfo info = new ApplicationInfo();
        // TODO: update version when starting to enforce 1:1 mapping
        info.targetSdkVersion = VERSION_CODES.N_MR1 + 1;

        when(mCtx.getApplicationInfo()).thenReturn(info);
        when(mService.requestNetwork(anyInt(), any(), anyInt(), any(), anyInt(), any(), anyInt(),
                anyInt(), any(), nullable(String.class), anyInt())).thenReturn(request);

        Handler handler = new Handler(Looper.getMainLooper());
        manager.requestNetwork(request, callback, handler);

        // callback is already registered, reregistration should fail.
        Class<IllegalArgumentException> wantException = IllegalArgumentException.class;
        expectThrowable(() -> manager.requestNetwork(request, callback), wantException);

        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(request);

        // unregistering the callback should make it registrable again.
        manager.requestNetwork(request, callback);
    }

    @Test
    public void testDefaultNetworkActiveListener() throws Exception {
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        final ConnectivityManager.OnNetworkActiveListener listener =
                mock(ConnectivityManager.OnNetworkActiveListener.class);
        assertThrows(IllegalArgumentException.class,
                () -> manager.removeDefaultNetworkActiveListener(listener));
        manager.addDefaultNetworkActiveListener(listener);
        verify(mService, times(1)).registerNetworkActivityListener(any());
        manager.removeDefaultNetworkActiveListener(listener);
        verify(mService, times(1)).unregisterNetworkActivityListener(any());
        assertThrows(IllegalArgumentException.class,
                () -> manager.removeDefaultNetworkActiveListener(listener));
    }

    @Test
    public void testArgumentValidation() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);

        NetworkRequest request = mock(NetworkRequest.class);
        NetworkCallback callback = mock(NetworkCallback.class);
        Handler handler = mock(Handler.class);
        NetworkCallback nullCallback = null;
        PendingIntent nullIntent = null;

        mustFail(() -> manager.requestNetwork(null, callback));
        mustFail(() -> manager.requestNetwork(request, nullCallback));
        mustFail(() -> manager.requestNetwork(request, callback, null));
        mustFail(() -> manager.requestNetwork(request, callback, -1));
        mustFail(() -> manager.requestNetwork(request, nullIntent));

        mustFail(() -> manager.requestBackgroundNetwork(null, callback, handler));
        mustFail(() -> manager.requestBackgroundNetwork(request, null, handler));
        mustFail(() -> manager.requestBackgroundNetwork(request, callback, null));

        mustFail(() -> manager.registerNetworkCallback(null, callback, handler));
        mustFail(() -> manager.registerNetworkCallback(request, null, handler));
        mustFail(() -> manager.registerNetworkCallback(request, callback, null));
        mustFail(() -> manager.registerNetworkCallback(request, nullIntent));

        mustFail(() -> manager.registerDefaultNetworkCallback(null, handler));
        mustFail(() -> manager.registerDefaultNetworkCallback(callback, null));

        mustFail(() -> manager.registerSystemDefaultNetworkCallback(null, handler));
        mustFail(() -> manager.registerSystemDefaultNetworkCallback(callback, null));

        mustFail(() -> manager.registerBestMatchingNetworkCallback(null, callback, handler));
        mustFail(() -> manager.registerBestMatchingNetworkCallback(request, null, handler));
        mustFail(() -> manager.registerBestMatchingNetworkCallback(request, callback, null));

        mustFail(() -> manager.unregisterNetworkCallback(nullCallback));
        mustFail(() -> manager.unregisterNetworkCallback(nullIntent));
        mustFail(() -> manager.releaseNetworkRequest(nullIntent));
    }

    static void mustFail(Runnable fn) {
        try {
            fn.run();
            fail();
        } catch (Exception expected) {
        }
    }

    @Test
    public void testRequestType() throws Exception {
        final String testPkgName = "MyPackage";
        final String testAttributionTag = "MyTag";
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        when(mCtx.getOpPackageName()).thenReturn(testPkgName);
        when(mCtx.getAttributionTag()).thenReturn(testAttributionTag);
        final NetworkRequest request = makeRequest(1);
        final NetworkCallback callback = new ConnectivityManager.NetworkCallback();

        manager.requestNetwork(request, callback);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(request.networkCapabilities),
                eq(REQUEST.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag), anyInt());
        reset(mService);

        // Verify that register network callback does not calls requestNetwork at all.
        manager.registerNetworkCallback(request, callback);
        verify(mService, never()).requestNetwork(anyInt(), any(), anyInt(), any(), anyInt(), any(),
                anyInt(), anyInt(), any(), any(), anyInt());
        verify(mService).listenForNetwork(eq(request.networkCapabilities), any(), any(), anyInt(),
                eq(testPkgName), eq(testAttributionTag), anyInt());
        reset(mService);

        Handler handler = new Handler(ConnectivityThread.getInstanceLooper());

        manager.registerDefaultNetworkCallback(callback);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(null),
                eq(TRACK_DEFAULT.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag), anyInt());
        reset(mService);

        manager.registerDefaultNetworkCallbackForUid(42, callback, handler);
        verify(mService).requestNetwork(eq(42), eq(null),
                eq(TRACK_DEFAULT.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag), anyInt());

        manager.requestBackgroundNetwork(request, callback, handler);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(request.networkCapabilities),
                eq(BACKGROUND_REQUEST.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag), anyInt());
        reset(mService);

        manager.registerSystemDefaultNetworkCallback(callback, handler);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(null),
                eq(TRACK_SYSTEM_DEFAULT.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag), anyInt());
        reset(mService);
    }

    static Message makeMessage(NetworkRequest req, int messageType) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(NetworkRequest.class.getSimpleName(), req);
        // Pass default objects as we don't care which get passed here
        bundle.putParcelable(Network.class.getSimpleName(), new Network(1));
        bundle.putParcelable(NetworkCapabilities.class.getSimpleName(), new NetworkCapabilities());
        bundle.putParcelable(LinkProperties.class.getSimpleName(), new LinkProperties());
        Message msg = Message.obtain();
        msg.what = messageType;
        msg.setData(bundle);
        return msg;
    }

    static NetworkRequest makeRequest(int requestId) {
        NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        return new NetworkRequest(request.networkCapabilities, ConnectivityManager.TYPE_NONE,
                requestId, NetworkRequest.Type.NONE);
    }

    static void expectThrowable(Runnable block, Class<? extends Throwable> throwableType) {
        try {
            block.run();
        } catch (Throwable t) {
            if (t.getClass().equals(throwableType)) {
                return;
            }
            fail("expected exception of type " + throwableType + ", but was " + t.getClass());
        }
        fail("expected exception of type " + throwableType);
    }

    private static class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return mock(Context.class);
        }
    }

    private WeakReference<Context> makeConnectivityManagerAndReturnContext() {
        // Mockito may have an internal reference to the mock, creating MockContext for testing.
        final Context c = new MockContext(mock(Context.class));

        new ConnectivityManager(c, mService);

        return new WeakReference<>(c);
    }

    private void forceGC() {
        // First GC ensures that objects are collected for finalization, then second GC ensures
        // they're garbage-collected after being finalized.
        System.gc();
        System.runFinalization();
        System.gc();
    }

    @Test
    public void testConnectivityManagerDoesNotLeakContext() throws Exception {
        final WeakReference<Context> ref = makeConnectivityManagerAndReturnContext();

        final int attempts = 600;
        final long waitIntervalMs = 50;
        for (int i = 0; i < attempts; i++) {
            forceGC();
            if (ref.get() == null) break;

            Thread.sleep(waitIntervalMs);
        }

        assertNull("ConnectivityManager weak reference still not null after " + attempts
                    + " attempts", ref.get());
    }

    @Test
    public void testDeclaredMethodsFlag_requestWithMixedMethods_RegistrationFlagsMatch()
            throws Exception {
        doReturn(ConnectivityManager.FEATURE_USE_DECLARED_METHODS_FOR_CALLBACKS)
                .when(mService).getEnabledConnectivityManagerFeatures();
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);

        final NetworkRequest request = new NetworkRequest.Builder().build();
        final NetworkCallback callback1 = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onPreCheck(@NonNull Network network) {}
            @Override
            public void onAvailable(@NonNull Network network) {}
            @Override
            public void onLost(@NonNull Network network) {}
            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                    @NonNull NetworkCapabilities networkCapabilities) {}
            @Override
            public void onLocalNetworkInfoChanged(@NonNull Network network,
                    @NonNull LocalNetworkInfo localNetworkInfo) {}
            @Override
            public void onNetworkResumed(@NonNull Network network) {}
            @Override
            public void onBlockedStatusChanged(@NonNull Network network, int blocked) {}
        };
        manager.requestNetwork(request, callback1);

        final InOrder inOrder = inOrder(mService);
        inOrder.verify(mService).requestNetwork(
                anyInt(), any(), anyInt(), any(), anyInt(), any(), anyInt(), anyInt(), any(), any(),
                eq(1 << ConnectivityManager.CALLBACK_PRECHECK
                        | 1 << ConnectivityManager.CALLBACK_AVAILABLE
                        | 1 << ConnectivityManager.CALLBACK_LOST
                        | 1 << ConnectivityManager.CALLBACK_CAP_CHANGED
                        | 1 << ConnectivityManager.CALLBACK_LOCAL_NETWORK_INFO_CHANGED
                        | 1 << ConnectivityManager.CALLBACK_RESUMED
                        | 1 << ConnectivityManager.CALLBACK_BLK_CHANGED));
    }

    @Test
    public void testDeclaredMethodsFlag_listenWithMixedMethods_RegistrationFlagsMatch()
            throws Exception {
        final NetworkRequest request = new NetworkRequest.Builder().build();
        doReturn(ConnectivityManager.FEATURE_USE_DECLARED_METHODS_FOR_CALLBACKS)
                .when(mService).getEnabledConnectivityManagerFeatures();
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);

        final NetworkCallback callback2 = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLosing(@NonNull Network network, int maxMsToLive) {}
            @Override
            public void onUnavailable() {}
            @Override
            public void onLinkPropertiesChanged(@NonNull Network network,
                    @NonNull LinkProperties linkProperties) {}
            @Override
            public void onNetworkSuspended(@NonNull Network network) {}
        };
        manager.registerNetworkCallback(request, callback2);
        // Call a second time with the same callback to exercise caching
        manager.registerNetworkCallback(request, callback2);

        verify(mService, times(2)).listenForNetwork(
                any(), any(), any(), anyInt(), any(), any(),
                eq(1 << ConnectivityManager.CALLBACK_LOSING
                        // AVAILABLE calls IP_CHANGED and SUSPENDED so it gets added
                        | 1 << ConnectivityManager.CALLBACK_AVAILABLE
                        | 1 << ConnectivityManager.CALLBACK_UNAVAIL
                        | 1 << ConnectivityManager.CALLBACK_IP_CHANGED
                        | 1 << ConnectivityManager.CALLBACK_SUSPENDED));
    }

    @Test
    public void testDeclaredMethodsFlag_requestWithHiddenAvailableCallback_RegistrationFlagsMatch()
            throws Exception {
        doReturn(ConnectivityManager.FEATURE_USE_DECLARED_METHODS_FOR_CALLBACKS)
                .when(mService).getEnabledConnectivityManagerFeatures();
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);

        final NetworkCallback hiddenOnAvailableCb = new ConnectivityManager.NetworkCallback() {
            // This overload is @hide but might still be used by (bad) apps
            @Override
            public void onAvailable(@NonNull Network network,
                    @NonNull NetworkCapabilities networkCapabilities,
                    @NonNull LinkProperties linkProperties, boolean blocked) {}
        };
        manager.registerDefaultNetworkCallback(hiddenOnAvailableCb);

        verify(mService).requestNetwork(
                anyInt(), any(), anyInt(), any(), anyInt(), any(), anyInt(), anyInt(), any(), any(),
                eq(1 << ConnectivityManager.CALLBACK_AVAILABLE));
    }

    public static class NetworkCallbackWithOnLostOnly extends NetworkCallback {
        @Override
        public void onLost(@NonNull Network network) {}
    }

    @Test
    public void testDeclaredMethodsFlag_requestWithoutAvailableCallback_RegistrationFlagsMatch()
            throws Exception {
        doReturn(ConnectivityManager.FEATURE_USE_DECLARED_METHODS_FOR_CALLBACKS)
                .when(mService).getEnabledConnectivityManagerFeatures();
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        final Handler handler = new Handler(Looper.getMainLooper());

        final NetworkCallback noOnAvailableCb = new NetworkCallbackWithOnLostOnly();
        manager.registerSystemDefaultNetworkCallback(noOnAvailableCb, handler);

        verify(mService).requestNetwork(
                anyInt(), any(), anyInt(), any(), anyInt(), any(), anyInt(), anyInt(), any(), any(),
                eq(1 << ConnectivityManager.CALLBACK_LOST));
    }

    @Test
    public void testDeclaredMethodsFlag_listenWithMock_OptimizationDisabled()
            throws Exception {
        doReturn(ConnectivityManager.FEATURE_USE_DECLARED_METHODS_FOR_CALLBACKS)
                .when(mService).getEnabledConnectivityManagerFeatures();
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        final Handler handler = new Handler(Looper.getMainLooper());

        final NetworkRequest request = new NetworkRequest.Builder().build();
        manager.registerNetworkCallback(request, mock(NetworkCallbackWithOnLostOnly.class),
                handler);

        verify(mService).listenForNetwork(
                any(), any(), any(), anyInt(), any(), any(),
                // Mock that does not call the constructor -> do not use the optimization
                eq(~0));
    }

    @Test
    public void testDeclaredMethodsFlag_requestWitNoCallback_OptimizationDisabled()
            throws Exception {
        doReturn(ConnectivityManager.FEATURE_USE_DECLARED_METHODS_FOR_CALLBACKS)
                .when(mService).getEnabledConnectivityManagerFeatures();
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        final Handler handler = new Handler(Looper.getMainLooper());

        final NetworkRequest request = new NetworkRequest.Builder().build();
        final NetworkCallback noCallbackAtAll = new ConnectivityManager.NetworkCallback() {};
        manager.requestBackgroundNetwork(request, noCallbackAtAll, handler);

        verify(mService).requestNetwork(
                anyInt(), any(), anyInt(), any(), anyInt(), any(), anyInt(), anyInt(), any(), any(),
                // No callbacks overridden -> do not use the optimization
                eq(~0));
    }
}
