package dev.virtualmic;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

/** Dry physical microphone -> existing injection track. No speaker monitoring. */
final class MicRelay implements Closeable {
    private static final int RATE = 48000;
    private final AudioRecord record;
    private final Trace trace;
    private final AudioManager manager;
    private final int targetUid;
    private boolean closed;

    MicRelay(Context context, AudioManager manager, int targetUid, String sourceName, String tracePath) throws Exception {
        this.manager = manager;
        this.targetUid = targetUid;
        if (context.checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Shell RECORD_AUDIO permission denied");
        }
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("No supported microphone buffer size");
        int audioSource = MediaRecorder.AudioSource.MIC;
        if ("voice-communication".equals(sourceName)) audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        if ("remote-submix".equals(sourceName)) {
            if (context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT")
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("REMOTE_SUBMIX requires CAPTURE_AUDIO_OUTPUT");
            }
            audioSource = MediaRecorder.AudioSource.class.getField("REMOTE_SUBMIX").getInt(null);
        }
        AudioRecord.Builder recordBuilder = new AudioRecord.Builder().setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, 480 * 2 * 4));
        if (audioSource == MediaRecorder.AudioSource.MIC) recordBuilder.setPrivacySensitive(false);
        record = recordBuilder.build();
        Trace openedTrace = null;
        try {
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Physical AudioRecord not initialized");
            }
            AudioDeviceInfo builtIn = null;
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { builtIn = device; break; }
            }
            if (builtIn == null || !record.setPreferredDevice(builtIn)) {
                throw new IllegalStateException("Could not select built-in microphone");
            }
            if (tracePath != null) openedTrace = new Trace(tracePath);
            trace = openedTrace;
            log("MIC_CONFIG source=" + sourceName + " preferred_device=" + builtIn.getId()
                    + " record_buffer_frames=" + record.getBufferSizeInFrames()
                    + " buffer_capacity_ms=" + record.getBufferSizeInFrames() * 1000.0 / RATE);
        } catch (Exception error) {
            record.release();
            if (openedTrace != null) openedTrace.close();
            throw error;
        }
    }

    static void awaitTarget(AudioTrack track, AudioManager manager, int targetUid) throws Exception {
        Method getUid = AudioRecordingConfiguration.class.getMethod("getClientUid");
        short[] silence = new short[480];
        track.play();
        log("READY_WAIT_TARGET: start the target recording now; physical mic starts AFTER target");
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (AudioRecordingConfiguration config : manager.getActiveRecordingConfigurations()) {
                if (((Integer) getUid.invoke(config)) == targetUid) {
                    log("TARGET_RECORDING_DETECTED uid=" + targetUid);
                    track.pause();
                    track.flush();
                    return;
                }
            }
            int written = track.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) throw new IllegalStateException("Warmup AudioTrack.write=" + written);
            Thread.sleep(10);
        }
        throw new IllegalStateException("No target recording started within 30 seconds");
    }

    void run(AudioTrack track, int seconds) throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] block = new short[480];
        int length = 0, offset = 0;
        long readFrames = 0, writtenFrames = 0, nonzero = 0, zeroWrites = 0;
        long silenceChecks = 0, silencedChecks = 0;
        double energy = 0;
        ArrayList<Double> captureAge = new ArrayList<>();
        ArrayList<Double> trackQueue = new ArrayList<>();
        AudioTimestamp timestamp = new AudioTimestamp();
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Physical microphone did not start");
        }
        track.play();
        log("MIC_RELAY_RUNNING: physical microphone -> active target recording");
        Method getUid = AudioRecordingConfiguration.class.getMethod("getClientUid");
        long start = System.nanoTime(), nextReport = 0;
        long end = start + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            if (offset == length) {
                int count = record.read(block, 0, block.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("AudioRecord.read=" + count);
                if (count == 0) { Thread.sleep(2); continue; }
                length = count;
                offset = 0;
                for (int i = 0; i < count; i++) {
                    if (block[i] != 0) nonzero++;
                    double normalized = block[i] / 32768.0;
                    energy += normalized * normalized;
                }
                if (trace != null) trace.write(block, count);
                readFrames += count;
            }
            int count = track.write(block, offset, length - offset, AudioTrack.WRITE_NON_BLOCKING);
            if (count < 0) throw new IllegalStateException("AudioTrack.write=" + count);
            if (count == 0) { zeroWrites++; Thread.sleep(2); }
            offset += count;
            writtenFrames += count;
            long now = System.nanoTime();
            if (now >= nextReport) {
                AudioRecordingConfiguration config = record.getActiveRecordingConfiguration();
                boolean silenced = config != null && config.isClientSilenced();
                silenceChecks++;
                if (silenced) silencedChecks++;
                AudioDeviceInfo routed = record.getRoutedDevice();
                String targetSilenced = "not-active";
                for (AudioRecordingConfiguration active : manager.getActiveRecordingConfigurations()) {
                    if (((Integer) getUid.invoke(active)) == targetUid) {
                        targetSilenced = Boolean.toString(active.isClientSilenced());
                    }
                }
                double age = Double.NaN;
                if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    // Estimate capture -> app read time for the last frame read.
                    // This excludes the receiving app and is NOT end-to-end latency.
                    long captureTime = timestamp.nanoTime
                            + (readFrames - timestamp.framePosition) * 1_000_000_000L / RATE;
                    age = (now - captureTime) / 1_000_000.0;
                    if (age >= 0 && age < 10000) captureAge.add(age);
                }
                long played = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                double queued = Math.max(0, writtenFrames - played) * 1000.0 / RATE;
                trackQueue.add(queued);
                log("MIC_STREAM elapsed_s=" + (now - start) / 1_000_000_000L
                        + " read_frames=" + readFrames + " written_frames=" + writtenFrames
                        + " silenced=" + silenced + " target_silenced=" + targetSilenced
                        + " device_type=" + (routed == null ? -1 : routed.getType())
                        + " rms_dbfs=" + db(energy, readFrames)
                        + " capture_age_ms=" + age + " track_queue_ms=" + queued
                        + " track_underruns=" + track.getUnderrunCount());
                nextReport = now + 1_000_000_000L;
            }
        }
        log("MIC_SUMMARY read_frames=" + readFrames + " written_frames=" + writtenFrames
                + " nonzero_fraction=" + (readFrames == 0 ? 0 : nonzero / (double) readFrames)
                + " rms_dbfs=" + db(energy, readFrames)
                + " silenced_checks=" + silencedChecks + "/" + silenceChecks
                + " zero_writes=" + zeroWrites + " track_underruns=" + track.getUnderrunCount()
                + " capture_age_p50_ms=" + percentile(captureAge, .50)
                + " capture_age_p95_ms=" + percentile(captureAge, .95)
                + " track_queue_p50_ms=" + percentile(trackQueue, .50)
                + " track_queue_p95_ms=" + percentile(trackQueue, .95));
        log("LATENCY_SCOPE: capture_age and track_queue exclude target read/processing; not end-to-end");
        if (readFrames == 0) throw new IllegalStateException("No physical microphone frames received");
        if (nonzero == 0) throw new IllegalStateException("Physical microphone returned only digital silence");
    }

    void runBridge(AudioTrack track, AppBridge bridge, int seconds, InputScope scope) throws Exception {
        if (seconds != 0) {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Physical microphone did not start");
        }
        bridge.run(record, track, seconds, trace, manager, scope);
    }

    void runCellular(AudioTrack track, AppBridge bridge, AppBridge.CallControl control) throws Exception {
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
            throw new IllegalStateException("Physical microphone did not start");
        bridge.run(record, track, 0, null, manager, new InputScope(-1, false, 2000, -1), control);
    }

    private static double db(double energy, long samples) {
        return samples == 0 || energy == 0 ? -240 : 10 * Math.log10(energy / samples);
    }

    private static double percentile(ArrayList<Double> data, double quantile) {
        if (data.isEmpty()) return Double.NaN;
        Collections.sort(data);
        return data.get((int) Math.round((data.size() - 1) * quantile));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { record.stop(); } catch (IllegalStateException ignored) {}
        record.release();
        if (trace != null) {
            try { trace.close(); } catch (IOException error) { log("TRACE_CLOSE_FAILED " + error); }
        }
        log("MIC_RELEASED");
    }

    static final class Trace implements Closeable {
        private final RandomAccessFile file;
        private long bytes;
        Trace(String path) throws IOException {
            file = new RandomAccessFile(path, "rw");
            file.setLength(0);
            file.writeBytes("RIFF"); le32(0); file.writeBytes("WAVEfmt "); le32(16);
            le16(1); le16(1); le32(RATE); le32(RATE * 2); le16(2); le16(16);
            file.writeBytes("data"); le32(0);
        }
        void write(short[] block, int count) throws IOException {
            byte[] pcm = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                pcm[2 * i] = (byte) block[i]; pcm[2 * i + 1] = (byte) (block[i] >> 8);
            }
            file.write(pcm); bytes += pcm.length;
        }
        void le16(int value) throws IOException { file.write(value); file.write(value >> 8); }
        void le32(long value) throws IOException { le16((int) value); le16((int) (value >> 16)); }
        @Override public void close() throws IOException {
            try { file.seek(4); le32(bytes + 36); file.seek(40); le32(bytes); }
            finally { file.close(); }
        }
    }

    private static void log(String value) { System.out.println("[virtual-mic] " + value); }
}
