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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.UserHandle;

import java.util.Set;
import java.util.TreeSet;

// Keeps one ongoing notification listening the blocked features. Runs on FeatureGate's handle.
final class RestrictionNotifier {
    private static final String TAG = "FeatureGate.Restriction";
    private static final String CHANNEL_ID = "feature_gate";
    private static final int NOTIFICATION_ID = 1;

    private final Context mContext;
    private NotificationManager mNm;

    RestrictionNotifier(Context context) {
        mContext = context;
    }

    void update(Set<String> blocked) {
        if (mNm == null) {
            mNm = mContext.getSystemService(NotificationManager.class);
            mNm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Feature restrictions", NotificationManager.IMPORTANCE_LOW));
        }

        if (blocked.isEmpty()) {
            mNm.cancelAsUser(TAG, NOTIFICATION_ID, UserHandle.ALL);
            return;
        }

        final Notification n = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Features restricted by FeatureGate")
                .setContentText(String.join(", " , new TreeSet<>(blocked)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        mNm.notifyAsUser(TAG, NOTIFICATION_ID, n, UserHandle.ALL);
    }
}
