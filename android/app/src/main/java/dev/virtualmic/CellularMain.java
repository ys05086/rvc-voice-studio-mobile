package dev.virtualmic;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Waits without capturing until a cellular call connects. Never dials or changes audio mode. */
public final class CellularMain {
    private static volatile Session current;
    private static volatile AppBridge bridge;
    private static volatile boolean explicitStop, failed;
    private static void close() {
        Session active = current;
        if (active != null) active.close();
        AppBridge connection = bridge;
        if (connection != null) connection.close();
    }
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(CellularMain::close));
        Thread parent = new Thread(() -> {
            try {
                int command;
                while ((command = System.in.read()) != -1) {
                    if (command == 2) { explicitStop = true; break; }
                }
            } catch (Exception ignored) {}
            System.exit(explicitStop ? 0 : 1);
        }, "cellular-parent");
        parent.setDaemon(true); parent.start();
        int result = 0;
        try {
            if (args.length != 1 || !args[0].matches("virtual-mic-[a-zA-Z0-9-]{8,64}"))
                throw new IllegalArgumentException("Invalid bridge token");
            Context context = ShellRuntime.create();
            CellularAudio call = new CellularAudio(context);
            call.checkNeutral();
            int uid = context.getPackageManager().getApplicationInfo("com.voicestudio.phone", 0).uid;
            bridge = new AppBridge(args[0], uid, null);
            bridge.accept();
            while (true) {
                System.out.println("[virtual-mic] BRIDGE_IDLE: waiting for a cellular call; microphone released");
                while (!call.inCall()) { bridge.idleHeartbeat(); Thread.sleep(200); }
                Session session = new Session();
                session.call = call;
                current = session;
                call.beginCall(); call.checkRoute(); call.checkNeutral();
                // Block original speech before capture/converter buffering begins.
                session.armGuard();
            AudioDeviceInfo tx = null;
            for (AudioDeviceInfo d : session.call.audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
                if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) { tx = d; break; }
            if (tx == null) throw new IllegalStateException("CELLULAR_UNSUPPORTED");
            session.track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(7680).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (!session.track.setPreferredDevice(tx)) throw new IllegalStateException("CELLULAR_ROUTE");
            session.mic = new MicRelay(context, session.call.audio, -1, "voice-communication", null);
            if (!session.call.inCall()) throw new IllegalStateException("CELLULAR_NO_CALL");
            try { session.mic.runCellular(session.track, bridge, session); }
            catch (Exception e) { if (call.inCall()) throw e; }
                session.close(); current = null;
                if (session.guardFailed || session.fault) throw new IllegalStateException("CELLULAR_GUARD");
                bridge.resetCallContext();
            }
        } catch (Throwable e) {
            if (!explicitStop) {
                failed = true;
                System.out.println("[virtual-mic] FAILED stage=cellular message=" + e);
                result = 1;
            }
        } finally { close(); }
        System.exit(result);
    }
    static final class Session implements AppBridge.CallControl {
        CellularAudio call;
        AudioTrack track;
        MicRelay mic;
        java.lang.Process guard;
        volatile boolean guarded, guardReady, guardRestored, guardFailed, fault;
        Thread guardReader;
        boolean closed;
        long lastBeat, lastCheck;
        @Override public void failed() {
            if (!explicitStop) {
                fault = true;
                try { if (!call.inCall()) fault = false; } catch (Exception ignored) {}
            }
        }
        @Override public boolean tick() throws Exception {
            long now = System.nanoTime();
            if (now - lastCheck > 100_000_000L) {
                lastCheck = now;
                if (guardFailed) throw new IllegalStateException("CELLULAR_GUARD");
                if (!call.inCall()) { System.out.println("CALL_ENDED"); return false; }
                call.checkRoute();
                if (guarded && !guard.isAlive()) throw new IllegalStateException("CELLULAR_GUARD");
            }
            if (guarded && now - lastBeat > 250_000_000L) {
                guard.getOutputStream().write(1); guard.getOutputStream().flush(); lastBeat = now;
            }
            return true;
        }
        @Override public void convertedReady() {}
        synchronized void armGuard() throws Exception {
            if (guarded) return;
            if (closed || !call.inCall()) throw new IllegalStateException("CELLULAR_NO_CALL");
            call.checkRoute();
            call.checkNeutral();
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/", "dev.virtualmic.TxMuteGuard")
                .redirectErrorStream(true);
            builder.environment().put("CLASSPATH", System.getenv("CLASSPATH"));
            guard = builder.start(); guarded = true;
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(guard.getInputStream()))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        System.out.println(line);
                        if (line.equals("CALL_TX_MUTED")) guardReady = true;
                        if (line.equals("CALL_TX_RESTORED")) guardRestored = true;
                        if (line.startsWith("CALL_GUARD_FAILED")) guardFailed = true;
                    }
                } catch (Exception ignored) {}
            }, "cellular-guard-log");
            guardReader = reader;
            reader.setDaemon(true); reader.start();
            long end = System.nanoTime() + 2_000_000_000L;
            while (!guardReady && guard.isAlive() && System.nanoTime() < end) Thread.sleep(10);
            if (!guardReady) throw new IllegalStateException("CELLULAR_GUARD");
            lastBeat = 0;
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            boolean normal = explicitStop && !failed && !fault && !guardFailed;
            if (guard != null) {
                try {
                    if (normal) { guard.getOutputStream().write(2); guard.getOutputStream().flush(); }
                    guard.getOutputStream().close(); guard.waitFor(3, TimeUnit.SECONDS);
                    if (!guard.isAlive() && guardReader != null) guardReader.join(200);
                }
                catch (Exception ignored) {}
            }
            // Never restore on a fault until Telecom and AudioManager agree the call ended.
            try {
                if (!guardRestored) call.finish(normal && guard == null);
                System.out.println("CALL_TX_RESTORED_BACKUP");
            }
            catch (Exception e) { System.out.println("CALL_HANGUP_FAILED_OR_RESTORE_FAILED " + e); }
            if (mic != null) try { mic.close(); } catch (Exception ignored) {}
            if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
        }
    }
}
