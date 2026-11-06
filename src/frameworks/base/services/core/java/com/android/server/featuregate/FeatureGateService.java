package com.android.server.featuregate;

import android.Manifest;
import android.app.featuregate.FeatureRule;
import android.app.featuregate.IFeatureGateListener;
import android.app.featuregate.IFeatureGateManager;
import android.os.ServiceSpecificException;
import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.IoThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.util.ArraySet;
import com.android.internal.util.DumpUtils;
import com.android.server.ServiceThread;
import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.server.LocalServices;
public final class FeatureGateService extends SystemService {
    private static final String TAG = "FeatureGateService";

    private final Object mLock = new Object();

    /** featureId -> rule. A feature with no rule is always allowed. */
    @GuardedBy("mLock")
    private final ArrayMap<String, FeatureRule> mRules = new ArrayMap<>();

    //Battery state. Real values arrive in Battery receiver.
    @GuardedBy("mLock")
    private int mBatteryPercent = 100;
    @GuardedBy("mLock")
    private boolean mCharging = false;
    @GuardedBy("mLock")
    private boolean mBatteryKnown = false;

    private final BinderService mBinderService = new BinderService();
    /** App listeners. Dead clients are removed automatically */
    private final RemoteCallbackList<IFeatureGateListener> mListeners = new RemoteCallbackList<>();

    /** App listeners. Dead clients are removed automatically. */
    private final RemoteCallbackList<IFeatureGateListener> mShellCallbacks =
            new RemoteCallbackList<>();

    /** Our own thread: battery handling and listener notifications run here. */
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;

    private final FeatureCatalog mCatalog;

    // featureId -> the enforcer responsible for it. Only modified in the constructor.
    private final ArrayMap<String, FeatureEnforcer> mEnforcers = new ArrayMap<>();
    private final BlockedNotifier mNotifier;
    private static final long WRITE_DELAY_MS = 500;
    private final FeatureGateStore mStore = new FeatureGateStore();
    private final Handler mIoHandler = IoThread.getHandler();
    private final Runnable mWriteRunnable = this::writeRulesNow;
    private RestrictionNotifier mRestrictionNotifier;
    private Boolean mBootCompleted;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onBatteryIntent(intent);
        }
    };

    public FeatureGateService(Context context) {
        super(context);
        registerEnforcer(new SystemUiEnforcer(context));
        registerEnforcer(new CameraEnforcer());
        mCatalog = new FeatureCatalog(context);
        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                false /* allowIo */);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mNotifier = new BlockedNotifier(context);
        mRestrictionNotifier = new RestrictionNotifier(context);
    }

    @Override
    public void onStart() {
        loadRules();
        publishBinderService(Context.FEATURE_GATE_SERVICE, mBinderService); //For apps
        publishLocalService(FeatureGateInternal.class, new LocalService()); //For system_server
        Slog.i(TAG, "FeatureGateService started");
    }

    private void loadRules() {
        final List<FeatureRule> saved = mStore.readRules();
        int skipped = 0;
        synchronized (mLock) {
            for (FeatureRule r : saved) {
                if (isValidSavedRule(r)) {
                    mRules.put(r.getFeatureId(), r);
                } else {
                    skipped++;
                }
            }
            if(skipped > 0) {
                Slog.w(TAG, "Skipped " + skipped + " invalid saved rules");
            }
        }
    }

    // A saved rule can go stale: feature removed in an update, app uninstalled, bad value.
    private boolean isValidSavedRule(FeatureRule r) {
        final int min = r.getMinBatteryPercent();
        if(min < 0 || min > 100) {
            return false;
        }
        try {
            mCatalog.enforceValid(r.getFeatureId()); // Use your method's exact signature
            return true;
        } catch (ServiceSpecificException e) {
            return false;
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            registerBatteryReceiver();
            mHandler.post(() -> {
                mBootCompleted = true;
                updateRestrictionNotification();
            });
        }
    }

    private void registerBatteryReceiver() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        // Sticky boradcast: returns the latest battery intent immediately.
        final Intent sticky = getContext().registerReceiver(mBatteryReceiver, filter,
                null /* broadcastPermission */, mHandler, Context.RECEIVER_NOT_EXPORTED);
        if (sticky != null) {
            mHandler.post(() -> onBatteryIntent(sticky));
        }
        Slog.i(TAG, "registerBatteryReceiver: Battery Receiver registered");
    }

    /** Runs on mHandler */
    private void onBatteryIntent(Intent intent) {
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        if (level < 0 || scale <= 0) {
            return;
        }

        final int percent = level * 100 / scale;
        final boolean charging = plugged != 0;
        final ArrayMap<String, Boolean> changes;

        synchronized (mLock) {
            if (mBatteryKnown && percent == mBatteryPercent && charging == mCharging) {
                return; // nothing we use changed (temperature/voltag-only update
            }

            final ArraySet<String> ids = new ArraySet<>(mRules.keySet());
            final ArrayMap<String, Boolean> before = snapshotLocked(ids);
            mBatteryPercent = percent;
            mCharging = charging;
            mBatteryKnown = true;
            changes = diffLocked(ids, before);
        }
        Slog.i(TAG, "onBatteryIntent: battery " + percent + "% charging " + charging);
        notifyListenerAsync(changes);
    }

    @GuardedBy("mLock")
    private boolean isAllowedLocked(String featureId) {
        final FeatureRule rule = mRules.get(featureId);
        if (rule == null) {
            Slog.i(TAG, "isAllowedLocked: Empty feature rule");
            return true;
        }
        Slog.i(TAG, "isAllowedLocked: Feature rule found, checking ....");
        return mBatteryPercent >= rule.getMinBatteryPercent() || (rule.getAllowWhenCharging()
                && mCharging);
    }

    @GuardedBy("mLock")
    private String blockedReasonLocked(String featureId) {
        final FeatureRule rule = mRules.get(featureId);
        if (rule == null) {
            return "no rule";
        }
        return "battery " + mBatteryPercent +"% < " + rule.getMinBatteryPercent() + "%" + (rule.getAllowWhenCharging() ? ", not charging" :"");
    }

    @GuardedBy("mLock")
    private ArrayMap<String, Boolean> snapshotLocked(ArraySet<String> ids) {
        final ArrayMap<String, Boolean> result = new ArrayMap<>();
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.valueAt(i);
            result.put(id, isAllowedLocked(id));
        }
        return result;
    }

    @GuardedBy("mLock")
    private ArrayMap<String, Boolean> diffLocked(ArraySet<String> ids,
            ArrayMap<String, Boolean> before) {
        final ArrayMap<String, Boolean> changes = new ArrayMap<>();
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.valueAt(i);
            final boolean now = isAllowedLocked(id);
            if (now != before.get(id)) changes.put(id, now);
        }
        return changes;
    }

    private void notifyListenerAsync(ArrayMap<String, Boolean> changes) {
        if (changes.isEmpty()) return;
        mHandler.post(() -> notifyListeners(changes));
    }

    /** Runs only on mHandler, so braodcasts never overlap and stay in order. */
    private void notifyListeners(ArrayMap<String, Boolean> changes) {
        dispatchToEnforcers(changes);
        for (int j = 0; j < changes.size(); j++) {
            Slog.i(TAG, "notifyListeners: " + changes.keyAt(j) + " -> " + (changes.valueAt(j)
                    ? "Allowed" : "Blocked"));
        }

        final int n = mListeners.beginBroadcast();

        try {
            for (int i = 0; i < n; i++) {
                final IFeatureGateListener listener = mListeners.getBroadcastItem(i);
                for (int j = 0; j < changes.size(); j++) {
                    try {
                        listener.onFeatureStateChanged(changes.keyAt(j), changes.valueAt(j));
                    } catch (RemoteException e) {
                        // client died : RemoteCallbackList removes it
                    }
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
        updateRestrictionNotification();
    }

    // Runs on mHandler, without mLock
    private void dispatchToEnforcers(ArrayMap<String, Boolean> changes) {
        for (int j = 0; j < changes.size(); j++) {
            final String featureId = changes.keyAt(j);
            final boolean allowed = changes.valueAt(j);
            final FeatureEnforcer enforcer = mEnforcers.get(featureId);
            if (enforcer == null) {
                Slog.i(TAG, "No enforcer yet for " + featureId + " (" + (allowed ? "ALLOWED" : "BLOCKED") +")");
                continue;
            }
            try {
                enforcer.onFeatureStateChanged(featureId, allowed);
                if(!allowed)
                    LocalServices.getService(FeatureGateInternal.class).notifyBlocked(featureId);
            } catch (RuntimeException e) {
                // One broken enforcer must not crash system_server or stop the others.
                Slog.e(TAG, "enforcer " + enforcer.getName() + " failed for " + featureId, e);
            }
        }
    }

    // Callers must hold MANAGE_FEATURE_GATES (root and system always pass).
    private void enforceManagePermission(String operation) {
        getContext().enforceCallingOrSelfPermission(Manifest.permission.MANAGE_FEATURE_GATES,operation);
    }

    // Call only from the constructor, before any battery or rule work is done.
    private void registerEnforcer(FeatureEnforcer enforcer) {
        for (String featureId : enforcer.getHandledFeatures()) {
            final FeatureEnforcer old = mEnforcers.put(featureId, enforcer);
            if (old != null) {
                throw new IllegalStateException(featureId + " handled by both " + old.getName() + " and " + enforcer.getName());
            }
        }
    }

    // Debounced: Many rules in a short time produce one write
    private void scheduleWrite() {
        mIoHandler.removeCallbacks(mWriteRunnable);
        mIoHandler.postDelayed(mWriteRunnable, WRITE_DELAY_MS);
    }

    // Runs on IoThread. Snapshot under the lock, write outside it.
    private void writeRulesNow() {
        final List<FeatureRule> snapshot;
        synchronized (mLock) {
            snapshot = new ArrayList<>(mRules.values());
        }
        mStore.writeRules(snapshot);
    }

    // Runs on mHandler. Posts, updates or cancels the ongoing notification.
    private void updateRestrictionNotification() {
        if (!mBootCompleted) {
            return;
        }

        final ArraySet<String> blocked = new ArraySet<>();
        synchronized (mLock) {
            for (FeatureRule rule : mRules.values()) {
                if (!isAllowedLocked(rule.getFeatureId())) {
                    blocked.add(rule.getFeatureId());
                }
            }
        }

        mRestrictionNotifier.update(blocked); // Outside the lock: It's a binder call to NMS
    }

    private final class LocalService extends FeatureGateInternal {
        @Override
        public boolean isFeatureAllowed(String featureId) {
            if (featureId == null) return true;
            synchronized (mLock) { // Leaflock. No outgoing calls inside
                return isAllowedLocked(featureId);
            }
        }

        @Override
        public void notifyBlocked(String featureId) {
            if (featureId == null) return;
            final String reason;
            synchronized (mLock) {
                reason = blockedReasonLocked(featureId);
            }
            mNotifier.notifyBlocked(featureId, reason);
        }
    }

    private final class BinderService extends IFeatureGateManager.Stub {
        @Override
        public boolean isFeatureAllowed(String featureId) {
            mCatalog.enforceValid(featureId);

            synchronized (mLock) {
                boolean isAllowed = isAllowedLocked(featureId);
                Slog.i(TAG, "isFeatureAllowed: " + isAllowed);
                return isAllowed;
            }
        }

        @Override
        public List<FeatureRule> getRules() {
            synchronized (mLock) {
                return new ArrayList<>(mRules.values());
            }
        }

        @Override
        public void setRule(FeatureRule rule) {
            enforceManagePermission("setRule"); // 1. Permission first
            if (rule == null || rule.getFeatureId() == null || rule.getFeatureId().isEmpty()
                    || rule.getMinBatteryPercent() < 0 || rule.getMinBatteryPercent() > 100) {
                throw new IllegalArgumentException("invalid rule: " + rule);
            }

            mCatalog.enforceValid(rule.getFeatureId()); // 2. Then validate, outside the lock

            final ArrayMap<String, Boolean> changes;

            synchronized (mLock) {
                final ArraySet<String> ids = new ArraySet<>(mRules.keySet());
                ids.add(rule.getFeatureId());
                final ArrayMap<String, Boolean> before = snapshotLocked(ids);
                mRules.put(rule.getFeatureId(), rule);
                changes = diffLocked(ids, before);
            }
            Slog.i(TAG, "setRule " + rule + " by uid " + Binder.getCallingUid());
            notifyListenerAsync(changes);
            scheduleWrite();
        }

        @Override // Binder call: admin only
        public void removeRule(String featureId) {
            enforceManagePermission("removeRule");
            mCatalog.enforceValid(featureId);

            final ArrayMap<String, Boolean> changes;

            synchronized (mLock) {
                final ArraySet<String> ids = new ArraySet<>(mRules.keySet());
                ids.add(featureId);
                final ArrayMap<String, Boolean> before = snapshotLocked(ids);
                mRules.remove(featureId);
                changes = diffLocked(ids, before);
            }
            Slog.i(TAG, "removeRule " + featureId + " by uid " + Binder.getCallingUid());
            notifyListenerAsync(changes);
            scheduleWrite();
        }

        @Override
        public List<String> getSupportedFeatures() {
            return mCatalog.getSupportedFeatures();
        }

        @Override // Binder call
        public void registerListener(IFeatureGateListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener is null");
            }
            mListeners.register(listener);
        }

        @Override // Binder call
        public void unregisterListener(IFeatureGateListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener is null");
            }
            mListeners.unregister(listener);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            synchronized (mLock) {
                pw.println("FeatureGate state: ");
                pw.println(" battery: " + (mBatteryKnown ? mBatteryPercent + "%" : "unknown")
                        + ", charging: " + mCharging);
                pw.println(" rules (" + mRules.size() + "):");
                for (int i = 0; i < mRules.size(); i++) {
                    final FeatureRule r = mRules.valueAt(i);
                    pw.println("    " + r + " -> " + (isAllowedLocked(r.getFeatureId()) ? "ALLOWED"
                            : "BLOCKED"));
                }
            }
            pw.println("  enforcers: " + (mEnforcers.isEmpty() ? "none yet" : ""));
            for (int i = 0; i < mEnforcers.size(); i++) {
                pw.println("    " + mEnforcers.keyAt(i) + " -> " + mEnforcers.valueAt(i).getName());
            }
            pw.println("  enforcer state:");
            final ArraySet<FeatureEnforcer> unique = new ArraySet<>(mEnforcers.values());
            for(int i = 0; i < unique.size(); i++) {
                unique.valueAt(i).dump(pw);
            }
            pw.println(" listeners: "+ mListeners.getRegisteredCallbackCount());
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new FeatureGateShellCommand().exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    private final class FeatureGateShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }

            final PrintWriter pw = getOutPrintWriter();
            try {
                switch (cmd) {
                    case "set-rule": {
                        final String id = getNextArgRequired();
                        final int min = Integer.parseInt(getNextArgRequired());
                        final boolean whenCharging = Boolean.parseBoolean(getNextArgRequired());
                        mBinderService.setRule(new FeatureRule(id, min, whenCharging));
                        pw.println("OK");
                        return 0;
                    }
                    case "remove-rule":
                        mBinderService.removeRule(getNextArgRequired());
                        pw.println("OK");
                        return 0;
                    case "check":
                        final String id = getNextArgRequired();
                        pw.println(id + ": " + (mBinderService.isFeatureAllowed(id) ? "ALLOWED"
                                : "BLOCKED"));
                        return 0;
                    case "features":
                        for (String f : mBinderService.getSupportedFeatures()) {
                            pw.println(f);
                        }
                        return 0;
                    case "internal-check":
                        final String Id = getNextArgRequired();
                        final FeatureGateInternal internal = LocalServices.getService(FeatureGateInternal.class);
                        pw.println(Id + " (via LocalServices):" + (internal.isFeatureAllowed(Id) ? "ALLOWED" : "BLOCKED"));
                        return 0;
                    case "notify-blocked":
                        final String ID = getNextArgRequired();
                        LocalServices.getService(FeatureGateInternal.class).notifyBlocked(ID);
                        pw.println("OK");
                        return 0;
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (IllegalArgumentException | SecurityException | ServiceSpecificException e){
                getErrPrintWriter().println("Error: " + e.getMessage());
                return -1;
            }
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("FeatureGate commands: ");
            pw.println("    set-rule <featureId> <minBatteryPercent> <allowWhenCharging>");
            pw.println("    remove-rule <featureId>");
            pw.println("    check <featureId>");
            pw.println("    features");
            pw.println("    internal-check <featureId>");
            pw.println("    notify-blocked <featureId>");
        }
    }
}