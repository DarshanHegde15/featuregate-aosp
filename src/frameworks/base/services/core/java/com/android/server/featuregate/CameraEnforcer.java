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
import android.util.Slog;

import java.io.PrintWriter;
import java.util.Set;

/*
* Camera is enforced PREVENTIVELY: CameraServiceProxy.isCameraDisabled() asks
* FeatureGateInternal on every camera open. This enforcer only records state for
* logs and dumpsys; already-open cameras are not closed.
*/
final class CameraEnforcer implements FeatureEnforcer{
    private static final String TAG = "FeatureGate.Camera";

    private boolean mBlocked; // Only touched on the service thread

    @Override
    public String getName(){
        return "CameraEnforcer";
    }

    @Override
    public Set<String> getHandledFeatures(){
        return Set.of(FeatureGateManager.FEATURE_CAMERA);
    }

    @Override
    public void onFeatureStateChanged(String featureId, boolean allowed){
        mBlocked = !allowed;
        Slog.i(TAG, "camera now " + (allowed ? "ALLOWED" : "BLOCKED") + " (enforced at open time via CameraServiceProxy");
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("    " + getName() + ": blocked=" + mBlocked + " (preventive hook in CameraServiceProxy.isCameraDisabled)");
    }
}
