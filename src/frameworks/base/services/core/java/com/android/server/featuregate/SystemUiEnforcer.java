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

import android.app.StatusBarManager;
import android.app.featuregate.FeatureGateManager;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBarService;

import java.util.Set;
import java.io.PrintWriter;

final class SystemUiEnforcer implements FeatureEnforcer{
    private static final String TAG = "FeatureGate.SystemUI";
    private final Context mContext;

    // Identifies FeatureGate's disable request inside StatusBarManagerService.
    private final IBinder mToken = new Binder();

    // Nav features currently blocked. Only touched on the service thread.
    private final ArraySet<String> mBlocked = new ArraySet<>();
    private IStatusBarService mStatusBar; // looked up lazily

    // Fields to remember what we sent last
    private int mLastFlag1 = StatusBarManager.DISABLE_NONE;
    private int mLastFlag2 = StatusBarManager.DISABLE2_NONE;

    SystemUiEnforcer(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "SystemUiEnforcer";
    }

    @Override
    public Set<String> getHandledFeatures() {
        return Set.of(
                FeatureGateManager.FEATURE_NAV_BACK,
                FeatureGateManager.FEATURE_NAV_HOME,
                FeatureGateManager.FEATURE_NAV_RECENTS,
                FeatureGateManager.FEATURE_NAV_ALL,
                FeatureGateManager.FEATURE_NOTIFICATION_SHADE
        );
    }

    @Override
    public void onFeatureStateChanged(String featureId, boolean allowed) {
        if (allowed) {
            mBlocked.remove(featureId);
        } else {
            mBlocked.add(featureId);
        }
        applyFlags();
    }

    //Recomputes the full flag mask from mBlocked and sends it with our token.
    private void applyFlags() {
        final boolean all = mBlocked.contains(FeatureGateManager.FEATURE_NAV_ALL);
        final boolean shade = mBlocked.contains(FeatureGateManager.FEATURE_NOTIFICATION_SHADE);
        int flags1 = StatusBarManager.DISABLE_NONE;
        if(all || mBlocked.contains(FeatureGateManager.FEATURE_NAV_BACK)) {
            flags1 |= StatusBarManager.DISABLE_BACK;
        }
        if(all || mBlocked.contains(FeatureGateManager.FEATURE_NAV_HOME)) {
            flags1 |= StatusBarManager.DISABLE_HOME;
        }
        if(all || mBlocked.contains(FeatureGateManager.FEATURE_NAV_RECENTS)) {
            flags1 |= StatusBarManager.DISABLE_RECENT;
        }
        if(shade) {
            flags1 |= StatusBarManager.DISABLE_EXPAND;
        }

        int flags2 = StatusBarManager.DISABLE2_NONE;
        if(shade) {
            flags2 |= StatusBarManager.DISABLE2_NOTIFICATION_SHADE | StatusBarManager.DISABLE2_QUICK_SETTINGS;
        }

        final IStatusBarService statusBar = getStatusBar();
        if(statusBar == null) {
            Slog.w(TAG, "Statusbar service not available yet");
            return;
        }
        try{
            // Sending 0 for both removes FeatureGate's record from SBMS
            // (explicit cleanup : Our token is a local Binder, so SBMS cannot rely on linkToDeath() for it.
            statusBar.disable(flags1, mToken, mContext.getOpPackageName());
            statusBar.disable2(flags2, mToken, mContext.getOpPackageName());
            mLastFlag1 = flags1;
            mLastFlag2 = flags2;
            Slog.i(TAG, "disable flags now 0x" + Integer.toHexString(flags1)
                    + " / 0x" + Integer.toHexString(flags2) + " blocked=" + mBlocked);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to update status bar " + e);
        }
    }

    private IStatusBarService getStatusBar() {
        if(mStatusBar == null) {
            mStatusBar = IStatusBarService.Stub.asInterface(ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        }
        return mStatusBar;
    }

    // Dump method to print to dumpsys
    @Override
    public void dump(PrintWriter pw) {
        pw.println("    " + getName() + ": blocked=" + mBlocked
                   + " flags1=0x" + Integer.toHexString(mLastFlag1)
                   + " flags2=0x" + Integer.toHexString(mLastFlag2));
    }
}
