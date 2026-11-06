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
import android.app.featuregate.IFeatureGateManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.ServiceSpecificException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/*
 * The set of features FeatureGate knows how to gate, and validation of feature IDs.
 * Must NOT be called while holding FeatureGateService's lock (it calls PackageManager).
 */
final class FeatureCatalog {
    private static final Set<String> SYSTEM_FEATURES = Set.of(
            FeatureGateManager.FEATURE_CAMERA,
            FeatureGateManager.FEATURE_NAV_BACK,
            FeatureGateManager.FEATURE_NAV_HOME,
            FeatureGateManager.FEATURE_NAV_RECENTS,
            FeatureGateManager.FEATURE_NAV_ALL,
            FeatureGateManager.FEATURE_NOTIFICATION_SHADE,
            FeatureGateManager.FEATURE_HOTSPOT,
            FeatureGateManager.FEATURE_WIFI,
            FeatureGateManager.FEATURE_BLUETOOTH,
            FeatureGateManager.FEATURE_MOBILE_DATA
    );

    private final Context mContext;

    FeatureCatalog(Context context) {
        mContext = context;
    }

    // Sorted list of system features, plus the app pattern.
    List<String> getSupportedFeatures() {
        final List<String> result = new ArrayList<>(SYSTEM_FEATURES);
        Collections.sort(result);
        result.add(FeatureGateManager.APP_PREFIX + "<package>");
        return result;
    }

    // Throws if the feature ID is not in the catalog.
    void enforceValid(String featureId) {
        if(featureId == null || featureId.isEmpty()) {
            throw new IllegalArgumentException("featureId is empty");
        }

        if(SYSTEM_FEATURES.contains(featureId)) return;

        if(featureId.startsWith(FeatureGateManager.APP_PREFIX)) {
            final String pkg = featureId.substring(FeatureGateManager.APP_PREFIX.length());
            if(!pkg.isEmpty() && packageExists(pkg)) {
                return;
            }

            throw new ServiceSpecificException(IFeatureGateManager.ERROR_UNKNOWN_FEATURE, "unknown package: " + pkg);
        }

        throw new ServiceSpecificException(IFeatureGateManager.ERROR_UNKNOWN_FEATURE, "unknown feature: " + featureId);
    }

    private boolean packageExists(String pkg) {
        // Look up as system: the caller's package visibility must not affect validation.
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
