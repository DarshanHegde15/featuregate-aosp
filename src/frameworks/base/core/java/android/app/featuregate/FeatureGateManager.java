/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app.featuregate;

import android.annotation.NonNull;
import android.content.Context;
import android.app.featuregate.IFeatureGateManager;
import android.app.featuregate.IFeatureGateListener;
import android.os.RemoteException;

import java.util.List;
import java.util.Objects;

import android.annotation.CallbackExecutor;
import android.os.Binder;
import android.util.ArrayMap;
import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

import android.os.ServiceSpecificException;

/**
 * Client API for FeatureGateService.
 * Get it with {@code context.getSystemService(FeatureGateManager.class)}.
 * @hide
 */
public class FeatureGateManager {
    private final Context mContext;
    private final IFeatureGateManager mService;

    private final Object mLock = new Object();
    ;
    @GuardedBy("mLock")
    private final ArrayMap<Listener, ListenerTransport> mTransports = new ArrayMap<>();

    // ---- Feature catalog (the only IDs FeatureGateService accepts) -----
    public static final String FEATURE_CAMERA = "camera";
    public static final String FEATURE_NAV_BACK = "nav_back";
    public static final String FEATURE_NAV_HOME = "nav_home";
    public static final String FEATURE_NAV_RECENTS = "nav_recents";
    public static final String FEATURE_NAV_ALL = "nav_all";
    public static final String FEATURE_NOTIFICATION_SHADE = "notification_shade";
    public static final String FEATURE_HOTSPOT = "hotspot";
    public static final String FEATURE_WIFI = "wifi";
    public static final String FEATURE_BLUETOOTH = "bluetooth";
    public static final String FEATURE_MOBILE_DATA = "mobile_data";
    /** Prefix for app features, e.g "app:com.android.settings." */
    public static final String APP_PREFIX = "app:";


    /** @hide */
    public FeatureGateManager(@NonNull Context context, @NonNull IFeatureGateManager service) {
        mContext = context;
        mService = service;
    }

    /** Returns true if the feature is allowed right now. */
    public boolean isFeatureAllowed(@NonNull String featureId) {
        Objects.requireNonNull(featureId, "featureId");
        try {
            return mService.isFeatureAllowed(featureId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw translate(e);
        }
    }

    /** Returns all current rules. */
    @NonNull
    public List<FeatureRule> getRules() {
        try {
            return mService.getRules();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Adds or replaces a rule. Admin only. */
    public void setRule(@NonNull FeatureRule rule) {
        Objects.requireNonNull(rule, "rule");
        try {
            mService.setRule(rule);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (ServiceSpecificException e) {
            throw translate(e);
        }
    }

    /** Removes the rule for a feature. Admin only. */
    public void removeRule(@NonNull String featureId) {
        Objects.requireNonNull(featureId, "featureId");
        try {
            mService.removeRule(featureId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }catch (ServiceSpecificException e) {
            throw translate(e);
        }
    }

    /** Returns the feature IDs the service accepts. (plus the "app:<package>" form) */
    @NonNull
    public List<String> getSupportedFeatures() {
        try{
            return mService.getSupportedFeatures();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Turns the service's error into a normal Java exception for apps. */
    private static RuntimeException translate(ServiceSpecificException e) {
        if(e.errorCode == IFeatureGateManager.ERROR_UNKNOWN_FEATURE) {
            return new IllegalArgumentException(e.getMessage());
        }
        return e;
    }

    /** Receives feature state changes. Plain Java interface: apps never see AIDL. */
    public interface Listener {
        void onFeatureGateChanged(@NonNull String featureId, boolean allowed);
    }

    /** Registers a listener; callbacks are delivered on the ginen executor. */
    public void registerListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull Listener listener) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(listener, "listener");
        final ListenerTransport transport;

        synchronized (mLock) {
            if (mTransports.containsKey(listener)) {
                return; //already registered;
            }
            transport = new ListenerTransport(executor, listener);
            mTransports.put(listener, transport);
        }
        try {
            mService.registerListener(transport);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Unregisters a listener. */
    public void unregisterListener(@NonNull Listener listener) {
        Objects.requireNonNull(listener, "listener");
        final ListenerTransport transport;
        synchronized (mLock) {
            transport = mTransports.remove(listener);
        }
        if (transport == null) return;
        try {
            mService.unregisterListener(transport);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** The AIDL stub system_server actually calls. Hidden inside the Manager. */
    private static final class ListenerTransport extends IFeatureGateListener.Stub {
        private final Executor mExecutor;
        private final Listener mListener;

        ListenerTransport(Executor executor, Listener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onFeatureStateChanged(@NonNull String featureId, boolean allowed) {
            // Arrives on an app binder thread. Hand it to the app's chosen executor.
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mListener.onFeatureGateChanged(featureId, allowed));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
