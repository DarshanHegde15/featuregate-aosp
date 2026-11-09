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

package com.android.server.featuregate;

import android.app.featuregate.FeatureGateManager;
import android.content.Context;
import android.content.pm.SuspendDialogInfo;
import android.util.ArraySet;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Set;

// Suspends for "app:<package>" rules through PackageManager.
// Called on FeatureGate's handler thread, never with the service lock held.
final class AppEnforcer implements FeatureEnforcer{
    private static final String TAG = "FeatureGate.App";
    private final Context mContext;
    // Packages FeatureGate itself suspended. Touched only on the handler thread.
    private final ArraySet<String> mSuspended = new ArraySet<>();

    AppEnforcer(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "AppEnforcer";
    }

    // App ids are dynamic ("app:<pkg>"), so none are registered upfront; the service routes by prefix
    @Override
    public Set<String> getHandledFeatures() {
        return Collections.emptySet();
    }

    @Override
    public void onFeatureStateChanged(String featureId, boolean allowed) {
        final String pkg = featureId.substring(FeatureGateManager.APP_PREFIX.length());
        final SuspendDialogInfo dialog = allowed ? null : new SuspendDialogInfo.Builder().setMessage("Blocked by FeatureGate: Battery too low").build();
        final String[] failed;
        try {
            failed = mContext.getPackageManager().setPackagesSuspended(new String[] {pkg}, !allowed, null, null, dialog);
        } catch(RuntimeException e){
            Slog.w(TAG, "setPackageSuspended failed for " + pkg, e);;
            return;
        }
        if (failed != null && failed.length == 0) {
            Slog.w(TAG, "PackageManager refused to " + (allowed ? "unsuspend " : "suspend ") + pkg);
            return;
        }
        if (allowed) {
            mSuspended.remove(pkg);
        } else {
            mSuspended.add(pkg);
        }
        Slog.i(TAG, pkg + (allowed ? " unsuspended " : " suspended "));
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("    " + getName() + ": suspended=" + mSuspended);
    }
}
