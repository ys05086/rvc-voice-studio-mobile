package dev.virtualmic;

/** An abnormal exit may restore the original uplink only after the call is gone. */
final class CallShutdown {
    interface Controls {
        boolean callPresent() throws Exception;
        void blockOriginal() throws Exception;
        void endCall() throws Exception;
        void pause() throws Exception;
        void restore() throws Exception;
    }
    static void finish(Controls controls, boolean explicitStop) throws Exception {
        if (!explicitStop && controls.callPresent()) {
            try { controls.blockOriginal(); }
            finally { controls.endCall(); }
            for (int i = 0; i < 20 && controls.callPresent(); i++) controls.pause();
            if (controls.callPresent()) throw new IllegalStateException("CELLULAR_HANGUP_FAILED");
        }
        controls.restore();
    }
}
