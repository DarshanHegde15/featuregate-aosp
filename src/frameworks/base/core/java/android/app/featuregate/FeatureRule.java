package android.app.featuregate;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/*
 * A rule saying when a feature is allowed based on battery percenntage. 
 * Custom Parcel implementation.
 */

/** @hide */
public final class FeatureRule implements Parcelable {

@NonNull private final String mFeatureId;
private final int mMinBatteryPercent;
private final boolean mAllowWhenCharging;

    public FeatureRule(@NonNull String featureId, int minBatteryPercent, boolean allowWhenCharging) {

        mFeatureId = Objects.requireNonNull(featureId, "featureId");
        mMinBatteryPercent = minBatteryPercent;
        mAllowWhenCharging = allowWhenCharging;

    }

    //Unpack: Must read in the same order as writeToParcel writes.
    private FeatureRule(@NonNull Parcel in) {

        mFeatureId = in.readString8();
        mMinBatteryPercent = in.readInt();
        mAllowWhenCharging = in.readBoolean();

    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {


        dest.writeString8(mFeatureId);
        dest.writeInt(mMinBatteryPercent);
        dest.writeBoolean(mAllowWhenCharging);

    }

    @Override
    public int describeContents() {

        return 0;

    }

    public static final @NonNull Creator<FeatureRule> CREATOR = new Creator<>() {

        @Override
        public FeatureRule createFromParcel(Parcel in) {

            return new FeatureRule(in);

        }

        @Override
        public FeatureRule[] newArray(int size) {

            	return new FeatureRule[size];

        }

    };

    @NonNull public String getFeatureId() { return mFeatureId; }
    public int getMinBatteryPercent() { return mMinBatteryPercent; }
    public boolean getAllowWhenCharging() { return mAllowWhenCharging; }

    @Override
    public boolean equals(Object o) {

        if(this == o) return true;
        if(!(o instanceof FeatureRule)) return false;
        FeatureRule other = (FeatureRule) o;
        return mFeatureId.equals(other.mFeatureId) &&
               mMinBatteryPercent == other.mMinBatteryPercent &&
               mAllowWhenCharging == other.mAllowWhenCharging;

    }

    @Override
    public int hashCode() {

        return Objects.hash(mFeatureId, mMinBatteryPercent, mAllowWhenCharging);

    }

    @Override
    public String toString() {

        return "FeatureRule{" + mFeatureId + ", minBattery = " + mMinBatteryPercent + "%" + ", allowWhenCharging = " + mAllowWhenCharging + "}";

    }

}
