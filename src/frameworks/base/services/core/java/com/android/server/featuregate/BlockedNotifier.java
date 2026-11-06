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

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.widget.Toast;

import com.android.server.UiThread;

// Shows "Blocked by Featuregate" toasts on system_server's shared UI thread.
// at most once per feature every MIN_INTERVAL_MS.
final class BlockedNotifier {
    private static final String TAG = "FeatureGate.BlockedNotifier";
    private static final long MIN_INTERVAL_MS = 3000;
    private final Context mContext;
    private final Handler mUiHandler = UiThread.getHandler();

    // featureId -> last time shown. Only touched on the UI thread, so no lock needed
    private final ArrayMap<String, Long> mLastShown = new ArrayMap<>();

    BlockedNotifier(Context context) {
        mContext = context;
    }

    // Safe to call from any thread
    void notifyBlocked(String featureId, String reason) {
        mUiHandler.post(() -> showOnUiThread(featureId, reason));
    }

    private void showOnUiThread(String featureId, String reason) {
        final long now = SystemClock.elapsedRealtime();
        final Long last = mLastShown.get(featureId);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            Slog.i(TAG, "Toast for " + featureId + " suppressed (rate limit)!");
            return;
        }
        mLastShown.put(featureId, now);
        final String text = "Blocked by FeatureGate: " + featureId + " (" + reason + ")";
        Toast.makeText(mContext, text, Toast.LENGTH_SHORT).show();
        Slog.i(TAG, "Toast shown: " + text);
    }
}
