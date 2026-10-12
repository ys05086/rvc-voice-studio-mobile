package dev.virtualmic;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Experimental shell-only injector. No APK install, root, or persistent policy edits. */
public final class Main {
    private static final String ROUTING = "android.permission.MODIFY_AUDIO_ROUTING";
    private static volatile String stage = "arguments";
    private static volatile boolean parentStopped;

    public static void main(String[] args) {
        int result = 0;
        try {
            // Private pipe from the Shizuku service: stop even if that parent is killed.
            if (java.util.Arrays.asList(args).contains("--watch-parent")) {
                Thread parent = new Thread(() -> {
                    try { while (System.in.read() != -1) {} } catch (java.io.IOException ignored) {}
                    parentStopped = true;
                    System.exit(0);
                }, "virtual-mic-parent");
                parent.setDaemon(true);
                parent.start();
            }
            run(Options.parse(args));
        } catch (Throwable error) {
            if (parentStopped) return;
            Throwable cause = unwrap(error);
            log("FAILED stage=" + stage + " type=" + cause.getClass().getName()
                    + " message=" + cause.getMessage());
            cause.printStackTrace(System.err);
            result = 1;
        }
        System.exit(result);
    }

    private static void run(Options options) throws Exception {
        stage = "identity";
        log("uid=" + Process.myUid() + " sdk=" + Build.VERSION.SDK_INT
                + " android=" + Build.VERSION.RELEASE + " model=" + Build.MODEL
                + " patch=" + Build.VERSION.SECURITY_PATCH);
        if (Process.myUid() != 2000) {
            throw new IllegalStateException("Run as adb shell (uid 2000); root is not required");
        }
        if (Build.VERSION.SDK_INT < 31) {
            throw new IllegalStateException("API 31+ required; this prototype targets Android 16");
        }
        stage = "shell-context";
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = new ShellContext(system.createPackageContext("com.android.shell", 0));
        // systemMain bootstraps an "android" Application. AudioTrack's no-context
        // constructor uses that Application's attribution. Bind our own process
        // Application to the actual shell package/UID, not the system identity.
        if (context.getApplicationInfo().uid != Process.myUid()
                || context.getAttributionSource().getUid() != Process.myUid()) {
            throw new IllegalStateException("Shell context does not match the process UID");
        }
        ShellApplication application = new ShellApplication(context);
        Field initialApplication = activityThread.getDeclaredField("mInitialApplication");
        initialApplication.setAccessible(true);
        initialApplication.set(thread, application);
        log("context=" + context.getPackageName() + " opPackage=" + context.getOpPackageName());

        stage = "permission";
        boolean routing = context.checkSelfPermission(ROUTING) == PackageManager.PERMISSION_GRANTED;
        log("MODIFY_AUDIO_ROUTING=" + (routing ? "GRANTED" : "DENIED"));
        if (!routing) throw new SecurityException("Shell has no MODIFY_AUDIO_ROUTING permission");

        stage = "api-discovery";
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        ruleBuilderClass.getMethod("setTargetMixRole", int.class);
        ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        policyClass.getMethod("createAudioTrackSource", mixClass);
        Method register = AudioManager.class.getMethod("registerAudioPolicy", policyClass);
        Method unregister = AudioManager.class.getMethod("unregisterAudioPolicy", policyClass);
        log("API_DISCOVERY_OK (not yet a successful injection)");
        if (options.probe) {
            log("PROBE_OK: permissions/APIs only; no audio policy registered");
            return;
        }

        stage = "target";
        int converterUid = context.getPackageManager().getApplicationInfo(options.bridgePackage, 0).uid;
        int targetUid = -1;
        if (!options.allApps) {
            ApplicationInfo target = context.getPackageManager().getApplicationInfo(options.packageName, 0);
            if (target.uid % 100000 < 10000 || target.uid == Process.myUid()) {
                throw new IllegalArgumentException("Target must be an installed application UID");
            }
            String[] shared = context.getPackageManager().getPackagesForUid(target.uid);
            if (shared == null || shared.length != 1 || !options.packageName.equals(shared[0])) {
                throw new IllegalArgumentException("Shared UID target rejected to keep scope to one app");
            }
            targetUid = target.uid;
            if (options.otherProfile) {
                Method uid = AudioRecordingConfiguration.class.getMethod("getClientUid");
                Method pkg = AudioRecordingConfiguration.class.getMethod("getClientPackageName");
                java.util.HashSet<Integer> matches = new java.util.HashSet<>();
                AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                for (AudioRecordingConfiguration recording : audio.getActiveRecordingConfigurations()) {
                    int observed = (Integer) uid.invoke(recording);
                    if (TargetIdentity.matchesOtherProfile(target.uid, observed, options.packageName,
                            (String) pkg.invoke(recording))) matches.add(observed);
                }
                if (matches.size() != 1) throw new IllegalStateException(matches.isEmpty()
                        ? "보안폴더에서 선택한 앱의 통화 또는 녹음을 먼저 시작해주세요. 활성 입력을 찾지 못했어요."
                        : "여러 공간에서 같은 앱이 마이크를 사용 중이에요. 사용할 입력 하나만 남겨주세요.");
                targetUid = matches.iterator().next();
            }
        }
        InputScope scope = new InputScope(targetUid, options.allApps, Process.myUid(), converterUid);
        log("target=" + (options.allApps ? "ALL_APPS" : options.packageName) + " uid=" + targetUid + " seconds=" + options.seconds);

        stage = "pcm-source";
        try (PcmSource source = options.wav == null ? new PcmSource.Tone() : PcmSource.Wav.open(options.wav)) {
            log("source=" + (options.mic ? "physical_MIC" : options.wav == null ? "1kHz_pulsed_tone" : "WAV_loop")
                    + " rate=" + source.rate + " channels=" + source.channels);
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(source.rate)
                    .setChannelMask(source.channels == 1 ? AudioFormat.CHANNEL_OUT_MONO
                            : AudioFormat.CHANNEL_OUT_STEREO).build();
            HandlerThread callbacks = new HandlerThread("virtual-mic-policy");
            callbacks.start();
            Session session = new Session((AudioManager) context.getSystemService(Context.AUDIO_SERVICE),
                    unregister, callbacks);
            Thread cleanup = new Thread(session::close, "virtual-mic-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanup);
            try {
                stage = "build-policy";
                Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
                ruleBuilderClass.getMethod("setTargetMixRole", int.class).invoke(ruleBuilder,
                        ruleClass.getField("MIX_ROLE_INJECTOR").getInt(null));
                if (options.allApps) {
                    Method exclude = ruleBuilderClass.getMethod("excludeMixRule", int.class, Object.class);
                    int uidRule = ruleClass.getField("RULE_MATCH_UID").getInt(null);
                    exclude.invoke(ruleBuilder, uidRule, Integer.valueOf(scope.captureUid));
                    exclude.invoke(ruleBuilder, uidRule, Integer.valueOf(scope.converterUid));
                    log("ALL_APPS_EXCLUDE capture_uid=" + scope.captureUid + " converter_uid=" + scope.converterUid);
                } else {
                    ruleBuilderClass.getMethod("addMixRule", int.class, Object.class).invoke(ruleBuilder,
                            ruleClass.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(targetUid));
                }
                Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);
                Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
                mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
                mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder,
                        mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
                Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);
                Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
                policyBuilderClass.getMethod("setLooper", Looper.class).invoke(policyBuilder, callbacks.getLooper());
                policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
                session.policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

                stage = "register-policy";
                int status = (Integer) register.invoke(session.manager, session.policy);
                log("registerAudioPolicy=" + status);
                if (status != 0) throw new IllegalStateException("Policy registration rejected: " + status);
                session.registered = true;
                stage = "create-track";
                session.track = (AudioTrack) policyClass.getMethod("createAudioTrackSource", mixClass)
                        .invoke(session.policy, mix);
                if (session.track == null || session.track.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IllegalStateException("Injection AudioTrack was not initialized");
                }
                if (options.mic) {
                    int requestedFrames = source.rate * options.trackBufferMs / 1000;
                    int effectiveFrames = session.track.setBufferSizeInFrames(requestedFrames);
                    if (effectiveFrames < 0) throw new IllegalStateException("Track resize rejected: " + effectiveFrames);
                    log("MIC_TRACK_BUFFER requested_ms=" + options.trackBufferMs
                            + " effective_frames=" + effectiveFrames
                            + " effective_ms=" + effectiveFrames * 1000.0 / source.rate);
                    stage = "create-microphone";
                    session.mic = new MicRelay(context, session.manager, targetUid, options.micSource, options.trace);
                    if (options.bridge != null) {
                        if (converterUid == targetUid) throw new IllegalArgumentException("Converter cannot be the target");
                        session.bridge = new AppBridge(options.bridge, converterUid, options.trace);
                        stage = "wait-converter";
                        session.bridge.accept();
                    }
                    stage = "wait-target-recording";
                    if (options.seconds != 0) MicRelay.awaitTarget(session.track, session.manager, targetUid);
                    stage = "microphone-relay";
                    if (session.bridge == null) session.mic.run(session.track, options.seconds);
                    else session.mic.runBridge(session.track, session.bridge, options.seconds, scope);
                } else {
                    log("READY: start a NEW recording in the target app now");
                    log("Policy/track success alone does NOT prove the target receives injected audio");
                    stage = "stream";
                    stream(session.track, source, options.seconds);
                }
                log("STREAM_FINISHED: verify the target recording separately");
            } finally {
                session.close();
                try { Runtime.getRuntime().removeShutdownHook(cleanup); }
                catch (IllegalStateException shuttingDown) { if (!parentStopped) throw shuttingDown; }
            }
        }
    }

    private static final class ShellApplication extends Application {
        ShellApplication(Context context) { attachBaseContext(context); }
    }

    private static final class ShellContext extends ContextWrapper {
        private final AttributionSource attribution = new AttributionSource.Builder(Process.myUid())
                .setPid(Process.myPid()).setPackageName("com.android.shell").build();

        ShellContext(Context base) { super(base); }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public AttributionSource getAttributionSource() { return attribution; }
        @Override public Context getApplicationContext() { return this; }
    }

    private static void stream(AudioTrack track, PcmSource source, int seconds) throws Exception {
        short[] block = new short[Math.max(1, source.rate / 100) * source.channels];
        int offset = block.length;
        long written = 0;
        long nextReport = 0;
        track.play();
        long start = System.nanoTime();
        long duration = seconds * 1_000_000_000L;
        while (System.nanoTime() - start < duration) {
            long elapsed = System.nanoTime() - start;
            // Never depend on an active reader to pace the remote-submix writer.
            long allowedFrames = elapsed * source.rate / 1_000_000_000L + source.rate / 50;
            long remaining = allowedFrames * source.channels - written;
            if (remaining <= 0) {
                Thread.sleep(2);
                continue;
            }
            if (offset == block.length) {
                source.fill(block);
                offset = 0;
            }
            int count = (int) Math.min(block.length - offset, remaining);
            count -= count % source.channels;
            if (count == 0) { Thread.sleep(2); continue; }
            int accepted = track.write(block, offset, count, AudioTrack.WRITE_NON_BLOCKING);
            if (accepted < 0) throw new IllegalStateException("AudioTrack.write returned " + accepted);
            if (accepted == 0) { Thread.sleep(5); continue; }
            offset += accepted;
            written += accepted;
            if (elapsed >= nextReport) {
                log("STREAM elapsed_s=" + elapsed / 1_000_000_000L
                        + " frames_accepted=" + written / source.channels);
                nextReport = elapsed + 5_000_000_000L;
            }
        }
        if (written == 0) throw new IllegalStateException("No PCM accepted during the test window");
    }

    private static final class Session {
        final AudioManager manager;
        final Method unregister;
        final HandlerThread callbacks;
        final AtomicBoolean closed = new AtomicBoolean();
        Object policy;
        boolean registered;
        AudioTrack track;
        MicRelay mic;
        AppBridge bridge;

        Session(AudioManager manager, Method unregister, HandlerThread callbacks) {
            this.manager = manager;
            this.unregister = unregister;
            this.callbacks = callbacks;
        }

        synchronized void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (bridge != null) bridge.close();
            if (mic != null) mic.close();
            if (track != null) {
                try { track.pause(); track.flush(); } catch (RuntimeException ignored) {}
                try { track.release(); } catch (RuntimeException error) { log("Track cleanup: " + error); }
            }
            if (registered) {
                try {
                    unregister.invoke(manager, policy);
                    log("POLICY_UNREGISTERED");
                } catch (Exception error) { log("Policy cleanup failed: " + unwrap(error)); }
            }
            callbacks.quitSafely();
        }
    }

    private static final class Options {
        boolean probe = true;
        String packageName;
        String wav;
        boolean mic;
        String trace;
        String micSource = "remote-submix";
        int trackBufferMs = 40;
        int seconds = 60;
        String bridge;
        String bridgePackage = "com.voicestudio.phone";
        boolean otherProfile;
        boolean allApps;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--probe".equals(arg)) continue;
                if ("--watch-parent".equals(arg)) continue;
                if ("--other-profile".equals(arg)) { options.otherProfile = true; continue; }
                if ("--all-apps".equals(arg)) { options.allApps = true; options.probe = false; continue; }
                if ("--mic".equals(arg)) { options.mic = true; continue; }
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value: " + arg);
                String value = args[++i];
                if ("--package".equals(arg)) { options.packageName = value; options.probe = false; }
                else if ("--wav".equals(arg)) options.wav = value;
                else if ("--seconds".equals(arg)) options.seconds = Integer.parseInt(value);
                else if ("--trace".equals(arg)) options.trace = value;
                else if ("--mic-source".equals(arg)) options.micSource = value;
                else if ("--track-buffer-ms".equals(arg)) options.trackBufferMs = Integer.parseInt(value);
                else if ("--bridge".equals(arg)) options.bridge = value;
                else if ("--bridge-package".equals(arg)) options.bridgePackage = value;
                else throw new IllegalArgumentException("Unknown option: " + arg);
            }
            if (options.seconds != 0 && (options.seconds < 5 || options.seconds > 300)) {
                throw new IllegalArgumentException("seconds must be 0 (continuous) or 5..300");
            }
            if (options.seconds == 0 && (options.bridge == null || !java.util.Arrays.asList(args).contains("--watch-parent")))
                throw new IllegalArgumentException("Continuous mode requires a supervised bridge");
            if (options.allApps && (options.seconds != 0 || options.otherProfile))
                throw new IllegalArgumentException("All-app mode requires continuous mode and excludes other-profile selection");
            if (options.wav != null && options.packageName == null) {
                throw new IllegalArgumentException("--wav requires --package");
            }
            if (options.mic && (options.wav != null || (options.packageName == null && !options.allApps))) {
                throw new IllegalArgumentException("--mic requires --package and excludes --wav");
            }
            if (options.trace != null && !options.mic) {
                throw new IllegalArgumentException("--trace requires --mic");
            }
            if (options.bridge != null && (!options.mic || !options.bridge.matches("virtual-mic-[a-zA-Z0-9-]{8,64}"))) {
                throw new IllegalArgumentException("--bridge requires --mic and a unique virtual-mic-* socket name");
            }
            if (!"mic".equals(options.micSource) && !"remote-submix".equals(options.micSource)) {
                throw new IllegalArgumentException("--mic-source must be mic or remote-submix");
            }
            if (options.trackBufferMs < 20 || options.trackBufferMs > 200) {
                throw new IllegalArgumentException("--track-buffer-ms must be 20..200");
            }
            if (options.packageName != null && !options.packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                throw new IllegalArgumentException("Invalid package name");
            }
            return options;
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static void log(String message) { System.out.println("[virtual-mic] " + message); }
}
