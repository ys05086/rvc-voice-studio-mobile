package com.voicestudio.phone;

interface IVirtualMicService {
    void destroy() = 16777114;
    String startMic(String targetPackage, int seconds, boolean otherProfile, boolean allApps, IBinder owner) = 1;
    String snapshot() = 2;
    void stopMic() = 3;
}
