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

import java.util.Set;
import java.io.PrintWriter;

/*
* Applies or removes the real block for some features.
* Called on FeatureGate's service thread, never while FeatureGate's lock is held.
*/
interface FeatureEnforcer {
    // Short names for log and dumpsys.
    String getName();

    // The catalog featureIds this enforcer is responsible for.
    Set<String> getHandledFeatures();

    // A handle feature became allowed (remove the block) or blocked (apply it).
    void onFeatureStateChanged(String featureId, boolean allowed);

    // Print enforcer state for dumpsys. Default - does nothing
    default void dump(PrintWriter pw) {}
}
