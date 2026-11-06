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

import android.app.featuregate.FeatureRule;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Saves FeatureGate rules to /data/system/featuregate/rules.xml (available before user logs in, at boot)
// Must be called on IoThread, never with FeatureGate's lock held.
final class FeatureGateStore {
    private static final String TAG = "FeatureGate.Store";
    private static final int VERSION = 1;
    private static final String TAG_RULES = "rules";
    private static final String TAG_RULE = "rule";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_ID = "id";
    private static final String ATTR_MIN = "minBattery";
    private static final String ATTR_CHARGING = "allowWhenCharging";

    private final File mDir = new File(Environment.getDataSystemDirectory(), "featuregate");
    private final AtomicFile mFile = new AtomicFile(new File(mDir, "rules.xml"));

    void writeRules(List<FeatureRule> rules) {
        mDir.mkdirs();
        FileOutputStream fos = null;
        try {
            fos = mFile.startWrite();
            final TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_RULES);
            out.attributeInt(null, ATTR_VERSION, VERSION);
            for (FeatureRule r : rules) {
                out.startTag(null, TAG_RULE);
                out.attribute(null, ATTR_ID, r.getFeatureId());
                out.attributeInt(null, ATTR_MIN, r.getMinBatteryPercent());
                out.attributeBoolean(null, ATTR_CHARGING, r.getAllowWhenCharging());
                out.endTag(null, TAG_RULE);
            }
            out.endTag(null, TAG_RULES);
            out.endDocument();
            mFile.finishWrite(fos);
            Slog.i(TAG, "Saved " + rules.size() + " rules");
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save rules", e);
            if(fos != null) {
                mFile.failWrite(fos);
            }
        }
    }

    // Returns the saved rules, or an empty list if the file is missing or unreadable
    List<FeatureRule> readRules() {
        final List<FeatureRule> rules = new ArrayList<>();
        try (FileInputStream fis = mFile.openRead()) {
            final TypedXmlPullParser in = Xml.resolvePullParser(fis);
            int type;
            while ((type = in.next()) != TypedXmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) continue;
                if (TAG_RULES.equals(in.getName())) {
                    final int version = in.getAttributeInt(null, ATTR_VERSION, -1);
                    if(version > VERSION) {
                        Slog.w(TAG, "Rules file version: " + version + " is newer than " + VERSION + ", ignoring it");
                        return new ArrayList<>();
                    }
                } else if (TAG_RULE.equals(in.getName())) {
                    final String id = in.getAttributeValue(null, ATTR_ID);
                    final int min = in.getAttributeInt(null, ATTR_MIN, -1);
                    final boolean charging = in.getAttributeBoolean(null, ATTR_CHARGING, false);
                    if(id != null){
                        rules.add(new FeatureRule(id, min, charging));
                    }
                }
            }
            Slog.i(TAG, "Loaded " + rules.size() + " rules");
        } catch (FileNotFoundException e) {
            Slog.i(TAG, "No rules file yet, starting empty");
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            Slog.e(TAG, "Rules file unreadable, starting empty", e);
            rules.clear();
        }
        return rules;
    }
}
