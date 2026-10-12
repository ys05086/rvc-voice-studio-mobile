package dev.virtualmic;

/** The same UID exclusions must be used by policy rules and active-input detection. */
public final class InputScope {
    public final int targetUid;
    public final boolean allApps;
    public final int captureUid, converterUid;

    public InputScope(int targetUid, boolean allApps, int captureUid, int converterUid) {
        this.targetUid = targetUid;
        this.allApps = allApps;
        this.captureUid = captureUid;
        this.converterUid = converterUid;
    }

    public boolean matches(int uid) {
        if (uid < 0 || uid == captureUid || uid == converterUid) return false;
        return allApps || uid == targetUid;
    }
}
