package android.app.featuregate;

import android.app.featuregate.FeatureRule;
import android.app.featuregate.IFeatureGateListener;

/*
 * Binder interface of FeatureGateService (in system_server).
 * Apps use FeatureGateManager, never use this interface directly.
 */

/** @hide */
interface IFeatureGateManager {
    /** Error code for SystemSpecificException: no rule exists for this feature */
    const int ERROR_UNKNOWN_FEATURE = 1;

    //---- Quaries: Open to all apps ----
    boolean isFeatureAllowed(String featureId);
    List<FeatureRule> getRules();

    //---- Admin: Will require MANAGE_FEATURE_GATE ----
    void setRule(in FeatureRule rule);
    void removeRule(String featureId);

    //---- Live updates ----
    void registerListener(IFeatureGateListener listener);
    void unregisterListener(IFeatureGateListener listener);

    // ---- Catalog (added in step 5, at the END to keep transaction codes stable) ----
    List<String> getSupportedFeatures();
}
