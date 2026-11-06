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

/**
 * In-process API for other system_server components (e.g the camera hook)
 * Get it with LocalServices.getService(FeatureGateInternal.class).
 * Not visible to apps: it is never registered in ServiceManager.
 */
public abstract class FeatureGateInternal {
    /*
    * Returns true if the feature is allowed right now. Features with no rules are allowed.
    * Fast, never does I/O or calls out, so it is safe to call while holding other locks
    */
    public abstract boolean isFeatureAllowed(String featureId);

    /*
    * Called by a preventive hook after it refused a feature, so FeatureGate can tell the
    * user why.
    */
    public abstract void notifyBlocked(String featureId);
}
