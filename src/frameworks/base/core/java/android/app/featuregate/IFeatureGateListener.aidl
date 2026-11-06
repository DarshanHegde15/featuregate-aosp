/*
 * Listener for featuregate state changes
 */

package android.app.featuregate;

/*
 * Called by FeatureGateService when a feature becomes allowed or blocked..
 * oneway: system_server must never wait on an app.
 */

/** @hide */
oneway interface IFeatureGateListener {
    void onFeatureStateChanged(String featureId, boolean allowed);
}
