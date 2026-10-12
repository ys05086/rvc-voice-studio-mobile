package dev.virtualmic;

import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import android.os.Process;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Loopback-only, per-session token authenticated PCM16 mono 48k, length-prefixed blocks. */
final class AppBridge implements Closeable {
    private final ServerSocket server;
    private final String token;
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private final short[] queue = new short[48000];
    private final long[] positions = new long[48000];
    private int startDelayMs;
    private int head, queued;
    private long received, trimmed;
    private volatile long suppliedFrames;
    private volatile IOException failure;
    private volatile boolean closed;
    private volatile boolean resetPending;
    private final MicRelay.Trace converted;

    AppBridge(String name, int uid, String trace) throws IOException {
        token = name;
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(120000);
        converted = trace == null ? null : new MicRelay.Trace(trace + ".injected.wav");
        deadline.schedule(this::close, 120, TimeUnit.SECONDS);
        log("BRIDGE_LISTENING port=" + server.getLocalPort() + " converter_uid=" + uid);
    }

    void accept() throws IOException {
        socket = server.accept();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(45000);
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
        if (input.readInt() != 0x564d4932) throw new IOException("Invalid bridge protocol");
        if (!token.equals(input.readUTF())) throw new IOException("Invalid bridge token");
        startDelayMs = input.readInt();
        if (startDelayMs < 80 || startDelayMs > 1500) throw new IOException("Invalid start delay");
        output.writeInt(0x564d4932);
        output.flush();
        deadline.shutdownNow();
        Thread reader = new Thread(() -> {
            try {
                while (!closed) {
                    int n = input.readInt();
                    if (n == 0) continue; // Idle heartbeat; no PCM follows.
                    if (n == -1) { // Converter has discarded its old input/context/output.
                        synchronized (this) { head = 0; queued = 0; resetPending = false; }
                        continue;
                    }
                    if (n < 1 || n > 48000) throw new IOException("Invalid converted block length " + n);
                    long sourceStart = input.readLong();
                    if (!BridgeTiming.validPosition(sourceStart, suppliedFrames)) throw new IOException("Invalid source position");
                    byte[] pcm = new byte[n * 2];
                    input.readFully(pcm);
                    synchronized (this) {
                        int drop = Math.max(0, queued + n - queue.length);
                        head = (head + drop) % queue.length;
                        queued -= drop;
                        trimmed += drop;
                        for (int i = 0; i < n; i++) {
                            int at=(head+queued+i)%queue.length;
                            queue[at]=(short) ((pcm[2*i] & 255) | (pcm[2*i+1] << 8));
                            positions[at]=sourceStart+i;
                        }
                        queued += n;
                        received += n;
                    }
                }
            } catch (IOException error) { if (!closed) failure = error; }
        }, "converted-pcm");
        reader.setDaemon(true);
        reader.start();
        log("BRIDGE_CONNECTED start_delay_ms="+startDelayMs);
    }

    private synchronized int take(short[] block, long[] sourcePositions) {
        int n = Math.min(block.length, queued);
        for (int i = 0; i < n; i++) {
            block[i] = queue[(head + i) % queue.length];
            sourcePositions[i] = positions[(head+i)%queue.length];
        }
        head = (head + n) % queue.length;
        queued -= n;
        Arrays.fill(block, n, block.length, (short) 0);
        Arrays.fill(sourcePositions,n,block.length,-1);
        return n;
    }

    void run(AudioRecord record, AudioTrack track, int seconds, MicRelay.Trace raw,
             AudioManager manager, InputScope scope) throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        if (seconds > 0) watchdog.schedule(this::close, seconds + 5, TimeUnit.SECONDS);
        short[] mic = new short[480], out = new short[480];
        long[] sourcePositions = new long[480];
        BridgeTiming.Window captureToWrite = new BridgeTiming.Window(4096);
        AudioTimestamp timestamp = new AudioTimestamp();
        byte[] bytes = new byte[960];
        long captured = 0, written = 0, gaps = 0, lastReport = 0;
        double energy = 0;
        PlayoutGate gate = new PlayoutGate(startDelayMs, 4800);
        long recoverySilence = 0;
        short lastOutput = 0;
        boolean fadingIn = true;
        int pending = 0, offset = 0, missingTarget = 0;
        Method getUid = AudioRecordingConfiguration.class.getMethod("getClientUid");
        long start = System.nanoTime(), end = BridgeTiming.deadline(start, seconds);
        boolean persistent = seconds == 0, capturing = !persistent, targetActive = !persistent;
        long nextCheck = 0, lastHeartbeat = 0, segmentStart = start, segmentWritten = 0, segmentCaptured = 0;
        if (capturing) { track.play(); log("BRIDGE_BUFFERING: waiting for converted audio"); }
        else log("BRIDGE_IDLE: waiting for an app microphone; no start deadline");
        try {
            while (!closed && System.nanoTime() < end) {
                if (failure != null) throw failure;
                long stateNow = System.nanoTime();
                if (persistent && stateNow >= nextCheck) {
                    targetActive = false;
                    for (AudioRecordingConfiguration cfg : manager.getActiveRecordingConfigurations()) {
                        if (scope.matches((Integer) getUid.invoke(cfg))) { targetActive = true; break; }
                    }
                    nextCheck = stateNow + 250_000_000L;
                    if (capturing && !targetActive) {
                        record.stop(); track.pause(); track.flush(); capturing = false;
                        pending = offset = 0; gate.reset(); lastOutput = 0; fadingIn = true;
                        resetPending = true;
                        output.writeInt(-1); output.flush();
                        log("BRIDGE_IDLE: microphone stopped; waiting for next input");
                    }
                }
                if (persistent && !capturing) {
                    if (targetActive && !resetPending) {
                        synchronized (this) { head = 0; queued = 0; }
                        record.startRecording();
                        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                            throw new IOException("Physical microphone did not start");
                        segmentStart = System.nanoTime(); segmentWritten = written; segmentCaptured = captured;
                        track.play(); capturing = true;
                        log("BRIDGE_BUFFERING: waiting for fresh converted audio");
                    } else {
                        if (stateNow - lastHeartbeat >= 1_000_000_000L) {
                            output.writeInt(0); output.flush(); lastHeartbeat = stateNow;
                        }
                        Thread.sleep(20);
                        continue;
                    }
                }
                int n = record.read(mic, 0, mic.length, AudioRecord.READ_NON_BLOCKING);
                if (n < 0) throw new IOException("Microphone read " + n);
                if (n > 0) {
                    if (raw != null) raw.write(mic, n);
                    for (int i = 0; i < n; i++) {
                        bytes[2*i] = (byte) mic[i]; bytes[2*i+1] = (byte) (mic[i] >> 8);
                        double v = mic[i] / 32768.0; energy += v*v;
                    }
                    suppliedFrames = captured + n;
                    output.writeInt(n); output.write(bytes, 0, n*2); output.flush();
                    captured += n;
                }
                long now = System.nanoTime();
                if (pending == offset && written - segmentWritten < (now - segmentStart) * 48000 / 1_000_000_000L + 480) {
                    boolean wasReady = gate.isReady();
                    boolean play;
                    synchronized (this) { play = gate.canTake(now - segmentStart, queued); }
                    if (play) {
                        int got = take(out,sourcePositions); gaps += out.length - got;
                        if (!wasReady) {
                            log("BRIDGE_RUNNING: converted audio ready startup_ms=" + (now-segmentStart)/1e6);
                            fadingIn = true;
                        }
                        if (fadingIn) {
                            for (int i=0; i<out.length; i++) out[i] = (short) (out[i] * i / out.length);
                            fadingIn = false;
                        }
                        lastOutput = out[out.length-1];
                    } else {
                        if (gate.hasPlayed()) recoverySilence += out.length;
                        Arrays.fill(out, (short) 0); Arrays.fill(sourcePositions,-1);
                        if (wasReady) {
                            for (int i=0; i<out.length; i++) out[i] = (short) (lastOutput * (out.length-1-i) / out.length);
                            lastOutput = 0;
                            log("BRIDGE_BUFFERING: refilling converted audio recovery=" + gate.recoveries());
                        }
                    }
                    pending = out.length; offset = 0;
                }
                if (pending > offset) {
                    int accepted = track.write(out, offset, pending - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (accepted < 0) throw new IOException("Injection write " + accepted);
                    if (accepted > 0 && converted != null) converted.write(Arrays.copyOfRange(out, offset, offset + accepted), accepted);
                    if (accepted > 0 && sourcePositions[offset+accepted-1] >= 0
                            && record.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS) {
                        long captureTime=timestamp.nanoTime+(sourcePositions[offset+accepted-1]-segmentCaptured-timestamp.framePosition)*1_000_000_000L/48000;
                        double age=(System.nanoTime()-captureTime)/1e6;
                        if(age>=0 && age<10000) captureToWrite.add(age);
                    }
                    offset += accepted; written += accepted;
                }
                if (now - lastReport >= 1_000_000_000L) {
                    boolean active = false;
                    String silenced = "not-active";
                    for (AudioRecordingConfiguration cfg : manager.getActiveRecordingConfigurations()) {
                        if (scope.matches((Integer) getUid.invoke(cfg))) { active = true; silenced = "" + cfg.isClientSilenced(); }
                    }
                    missingTarget = active ? 0 : missingTarget + 1;
                    AudioRecordingConfiguration cfg = record.getActiveRecordingConfiguration();
                    synchronized (this) {
                        log("BRIDGE_STREAM elapsed_s=" + (now-start)/1e9 + " capture_frames=" + captured
                                + " state=" + (gate.isReady() ? "RUNNING" : "BUFFERING")
                                + " converted_frames=" + received + " queue_ms=" + queued/48.0
                                + " gap_ms=" + gaps/48.0 + " trimmed_frames=" + trimmed
                                + " recovery_silence_ms=" + recoverySilence/48.0 + " recoveries=" + gate.recoveries()
                                + " mic_silenced=" + (cfg != null && cfg.isClientSilenced())
                                + " target_silenced=" + silenced + " track_underruns=" + track.getUnderrunCount());
                    }
                    lastReport = now;
                    if (!persistent && missingTarget >= 2) { log("TARGET_STOPPED"); break; }
                }
                Thread.sleep(2);
            }
            log("BRIDGE_SUMMARY capture_frames=" + captured + " converted_frames=" + received
                    + " written_frames=" + written + " gap_ms=" + gaps/48.0 + " trimmed_frames=" + trimmed
                    + " recovery_silence_ms=" + recoverySilence/48.0 + " recoveries=" + gate.recoveries()
                    + " raw_rms_dbfs=" + (energy == 0 ? -240 : 10*Math.log10(energy/Math.max(1,captured))));
            if(captureToWrite.size()>0) log("CAPTURE_TO_INJECT_WRITE p50_ms="+captureToWrite.percentile(.50)
                    +" p95_ms="+captureToWrite.percentile(.95)
                    +" window=last_4096_samples scope=source_timestamps_and_SOLA_mapping_excludes_output_track_and_target_buffers");
            if (received == 0) throw new IOException("No converted PCM received");
        } finally { close(); watchdog.shutdownNow(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        if (converted != null) try { converted.close(); } catch (IOException ignored) {}
        deadline.shutdownNow();
    }
    private static void log(String s) { System.out.println("[virtual-mic] " + s); }
}
