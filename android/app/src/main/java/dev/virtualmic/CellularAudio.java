package dev.virtualmic;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.telecom.TelecomManager;

/** Vendor-specific control validated on SM-S916N; never changes the call mode. */
final class CellularAudio {
    final AudioManager audio;
    final TelecomManager telecom;
    private int routeId = -1;
    CellularAudio(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < 31 || !"samsung".equalsIgnoreCase(Build.MANUFACTURER)
                || !"SM-S916N".equals(Build.MODEL)) throw new IllegalStateException("CELLULAR_UNSUPPORTED");
        audio = AudioManager.class.getConstructor(Context.class).newInstance(context);
        telecom = TelecomManager.class.getConstructor(Context.class).newInstance(context);
        if (!(Boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(audio))
            throw new IllegalStateException("CELLULAR_UNSUPPORTED");
    }
    void beginCall() {
        if (!inCall()) throw new IllegalStateException("CELLULAR_NO_CALL");
        AudioDeviceInfo route = audio.getCommunicationDevice();
        if (route == null || route.getType() != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            throw new IllegalStateException("CELLULAR_ROUTE");
        routeId = route.getId();
    }
    boolean inCall() { return audio.getMode() == AudioManager.MODE_IN_CALL && telecom.isInCall(); }
    void finish(boolean explicitStop) throws Exception {
        CallShutdown.finish(new CallShutdown.Controls() {
            // Mode changes alone are not proof that the telephone call ended.
            public boolean callPresent() { return telecom.isInCall() || audio.getMode() == AudioManager.MODE_IN_CALL; }
            public void blockOriginal() throws Exception { setTx(1); }
            @SuppressWarnings("deprecation") public void endCall() {
                boolean accepted = telecom.endCall();
                System.out.println("CALL_END_REQUEST accepted=" + accepted);
            }
            public void pause() throws Exception { Thread.sleep(100); }
            public void restore() throws Exception { restoreTx(); }
        }, explicitStop);
    }
    void checkRoute() {
        AudioDeviceInfo route = audio.getCommunicationDevice();
        if (route == null || route.getId() != routeId || route.getType() != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            throw new IllegalStateException("CELLULAR_ROUTE");
        if (audio.isMicrophoneMute()) throw new IllegalStateException("CELLULAR_MUTED");
    }
    void checkNeutral() throws Exception {
        if (txMode() != 0 || (Boolean) AudioManager.class.getMethod("semIsCallTranslationEnabled").invoke(audio))
            throw new IllegalStateException("CELLULAR_BUSY");
    }
    int txMode() throws Exception { return (Integer) AudioManager.class.getMethod("semGetVoiceTxControlMode").invoke(audio); }
    static void setTx(int mode) throws Exception {
        AudioManager.class.getMethod("setAudioServiceConfig", String.class)
            .invoke(null, "l_voice_tx_control_mode=" + mode);
    }
    static void restoreTx() throws Exception {
        setTx(0);
        String value = (String) AudioManager.class.getMethod("getAudioServiceConfig", String.class)
            .invoke(null, "l_voice_tx_control_mode");
        if (!"0".equals(value)) throw new IllegalStateException("TX restore readback=" + value);
    }
}
