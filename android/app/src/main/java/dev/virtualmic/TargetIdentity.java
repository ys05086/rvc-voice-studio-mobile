package dev.virtualmic;

public final class TargetIdentity {
    private TargetIdentity() {}
    public static boolean matchesOtherProfile(int primaryUid, int observedUid, String expectedPackage, String observedPackage) {
        return primaryUid % 100000 >= 10000 && observedUid >= 100000
                && observedUid / 100000 != primaryUid / 100000
                && observedUid % 100000 == primaryUid % 100000
                && expectedPackage != null && expectedPackage.equals(observedPackage);
    }
}
