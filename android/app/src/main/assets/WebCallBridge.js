/* Runs in the page world before the call site (Discord, Instagram, ...). No tokens, SDP, messages or audio are logged.
   Native substitutes this JSON literal: mode, desktop, the site's domains and its track policy; fixed for the
   lifetime of a document. */
(() => {
    'use strict';
    const config = __VS_WEB_CALL_CONFIG__;
    const domains = Array.isArray(config.hosts) ? config.hosts.filter(d => typeof d === 'string' && d.length) : [];
    const host = String(location.hostname).toLowerCase();
    if (location.protocol !== 'https:' || !domains.some(d => host === d || host.endsWith('.' + d)) ||
        window.top !== window) return;
    // Strict (Discord): an audio track the site makes itself is blocked. Otherwise it is allowed and counted: in the
    // replacement modes the page cannot reach the real microphone, so such a track is made from ours.
    const strictTracks = config.strictTracks !== false;

    const replacement = config.mode !== 'microphone';
    let stopped = false, context, destination, oscillator, gain, timer, unlock;
    let gumRequests = 0, suppliedTracks = 0, toneUntil = 0;
    let pcmToken = null, pcmTime = 0, pcmPackets = 0, pcmResets = 0;
    const pcmSources = new Set();
    const streams = new Set(), peers = new Set(), knownTracks = new WeakSet(), ownedTracks = new Set();
    const send = (event, data = {}) => {
        try { window.VoiceStudioBridge.postMessage(JSON.stringify({event, ...data})); } catch (_) {}
    };
    const errorName = e => String(e && e.name || 'Error').slice(0, 64);
    const fail = (name, message) => new DOMException(message, name);

    // Desktop identity for Android System WebView. The actual Chromium engine version stays in the user agent.
    if (config.desktop) {
        for (const [key, value] of [['platform', 'Linux x86_64'], ['maxTouchPoints', 0]]) {
            try { Object.defineProperty(navigator, key, {get: () => value, configurable: true}); }
            catch (_) { send('desktop_hint_failed', {feature: key}); }
        }
        const viewport = () => {
            if (!document.head) return;
            let meta = document.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta'); meta.name = 'viewport';
                document.head.appendChild(meta);
            }
            const value = 'width=1100'; // Keep pinch zoom available; do not disable accessibility scaling.
            if (meta.content !== value) meta.content = value;
        };
        const observer = new MutationObserver(viewport);
        observer.observe(document, {subtree: true, childList: true, attributes: true, attributeFilter: ['content']});
        document.addEventListener('DOMContentLoaded', viewport, {once: true});
        window.addEventListener('pagehide', () => observer.disconnect(), {once: true});
    }

    function markTrack(track) {
        if (knownTracks.has(track)) return track;
        knownTracks.add(track);
        ownedTracks.add(track);
        const clone = track.clone.bind(track);
        const stopTrack = track.stop.bind(track);
        Object.defineProperty(track, 'clone', {value: () => {
            if (stopped) throw fail('AbortError', 'Experiment stopped');
            return markTrack(clone());
        }});
        Object.defineProperty(track, 'stop', {value: () => { stopTrack(); ownedTracks.delete(track); }});
        return track;
    }

    function ensureAudio() {
        if (stopped) throw fail('AbortError', 'Web call experiment stopped');
        if (context) return;
        const Audio = window.AudioContext || window.webkitAudioContext;
        if (!Audio) throw fail('NotSupportedError', 'Web Audio unavailable');
        context = new Audio({latencyHint: 'interactive'});
        destination = context.createMediaStreamDestination();
        oscillator = context.createOscillator();
        gain = context.createGain();
        gain.gain.value = 0;
        oscillator.connect(gain).connect(destination);
        // Deliberately no connection to context.destination: the test does not monitor itself.
        oscillator.start();
        context.onstatechange = () => {
            if (unlock) unlock.hidden = context.state === 'running';
            // Interruptions can suspend the audio clock mid-tone; never replay the remainder on resume.
            if (context.state !== 'running') { quiet(); clearPCM(); }
            send('audio_state', {state: context.state, sampleRate: context.sampleRate});
        };
        send('audio_state', {state: context.state, sampleRate: context.sampleRate});
    }

    function quiet() {
        toneUntil = 0;
        if (gain && context) {
            gain.gain.cancelScheduledValues(context.currentTime);
            gain.gain.setValueAtTime(0, context.currentTime);
            oscillator?.frequency.cancelScheduledValues(context.currentTime);
        }
        send('tone', {active: false});
    }

    async function prepare() {
        ensureAudio();
        await context.resume();
        send('audio_state', {state: context.state, sampleRate: context.sampleRate});
        return context.state;
    }

    async function tone() {
        if (config.mode !== 'replacement') throw fail('InvalidStateError', 'Test audio requires test mode');
        ensureAudio();
        // A native button may not confer WebKit user activation. The in-page pill does.
        if (context.state !== 'running') throw fail('NotAllowedError', 'Tap the audio preparation button on the webpage');
        quiet();
        const start = context.currentTime;
        for (let i = 0; i < 6; i++) {
            const t = start + i * 0.5;
            oscillator.frequency.setValueAtTime(i % 2 ? 660 : 440, t);
            gain.gain.setValueAtTime(0, t);
            gain.gain.linearRampToValueAtTime(0.06, t + 0.015);
            gain.gain.setValueAtTime(0.06, t + 0.20);
            gain.gain.linearRampToValueAtTime(0, t + 0.23);
        }
        gain.gain.setValueAtTime(0, start + 3);
        toneUntil = start + 3;
        send('tone', {active: true});
        return 'scheduled';
    }

    function clearPCM() {
        for (const source of pcmSources) { source.stop(); source.disconnect(); }
        pcmSources.clear(); pcmTime = 0;
    }

    function beginPCM(token) {
        if (config.mode !== 'rvc' || typeof token !== 'string' || !token.length) throw fail('InvalidStateError', 'RVC mode required');
        ensureAudio();
        if (context.state !== 'running') throw fail('NotAllowedError', 'Prepare web audio first');
        clearPCM(); quiet(); pcmToken = token;
        return true;
    }

    function endPCM(token) {
        if (token !== pcmToken) return false;
        pcmToken = null; clearPCM(); return true;
    }

    function pushPCM(token, encoded) {
        if (stopped || document.hidden || config.mode !== 'rvc' || token !== pcmToken || !pcmToken) return false;
        if (!context || context.state !== 'running') { clearPCM(); return false; }
        // Float32 LE, mono, 48 kHz. Web Audio resamples to the actual device rate.
        if (typeof encoded !== 'string' || encoded.length > 153600) throw fail('DataError', 'PCM packet too large');
        const raw = atob(encoded);
        if (!raw.length || raw.length % 4 || raw.length > 115200) throw fail('DataError', 'Invalid PCM packet');
        const bytes = Uint8Array.from(raw, c => c.charCodeAt(0));
        const values = new DataView(bytes.buffer);
        const count = raw.length / 4;
        const buffer = context.createBuffer(1, count, 48000);
        const channel = buffer.getChannelData(0);
        for (let i = 0; i < count; i++) {
            const sample = values.getFloat32(i * 4, true);
            channel[i] = Number.isFinite(sample) ? Math.min(1, Math.max(-1, sample)) : 0;
        }
        const now = context.currentTime;
        // Stay near live speech after IPC stalls; never replay an ever-growing backlog.
        if (pcmTime + count / 48000 - now > 0.65) { clearPCM(); pcmResets++; }
        if (pcmTime < now) pcmTime = now + 0.04;
        const source = context.createBufferSource();
        source.buffer = buffer;
        source.connect(destination); // No local monitoring, no mic or receive audio in this graph.
        source.onended = () => { pcmSources.delete(source); source.disconnect(); };
        pcmSources.add(source);
        source.start(pcmTime);
        pcmTime += count / 48000;
        pcmPackets++;
        return true;
    }

    function stop() {
        if (stopped) return;
        stopped = true;
        pcmToken = null; clearPCM();
        quiet();
        clearInterval(timer);
        streams.forEach(s => s.getTracks().forEach(t => t.stop()));
        // Discord may clone a supplied track without retaining its original stream.
        ownedTracks.forEach(t => t.stop());
        ownedTracks.clear();
        peers.forEach(p => p.close());
        peers.clear(); streams.clear();
        if (destination) destination.stream.getTracks().forEach(t => t.stop());
        if (oscillator) oscillator.stop();
        if (context) void context.close().catch(() => {});
        if (unlock) unlock.remove();
        send('stopped');
    }

    function checkTrack(track) {
        if (replacement && strictTracks && track && track.kind === 'audio' && !knownTracks.has(track)) {
            send('unknown_audio_blocked');
            throw fail('NotAllowedError', 'Only Voice Studio test audio is allowed');
        }
    }

    try {
        const media = navigator.mediaDevices;
        if (!media || !media.getUserMedia) throw fail('NotSupportedError', 'getUserMedia unavailable');
        const original = media.getUserMedia.bind(media);
        if (replacement && media.enumerateDevices) {
            const enumerate = media.enumerateDevices.bind(media);
            const device = Object.freeze({deviceId: 'voice-studio-input', groupId: 'voice-studio',
                kind: 'audioinput', label: 'Voice Studio · converted voice',
                toJSON() { return {deviceId: this.deviceId, groupId: this.groupId, kind: this.kind, label: this.label}; }});
            Object.defineProperty(media, 'enumerateDevices', {configurable: true, writable: true,
                value: async () => [device, ...(await enumerate().catch(() => [])).filter(d => d.kind === 'audiooutput')]
            });
        }
        // Preserve normal API wrapping by Safari shims/site instrumentation. Freezing this
        // property throws during site startup. WebChromeClient independently denies real mic
        // access in replacement modes; a page-world patch is not a permission boundary.
        Object.defineProperty(media, 'getUserMedia', {configurable: true, writable: true,
            value: async function(constraints) {
                gumRequests++;
                send('input_request', {requests: gumRequests});
                if (stopped) throw fail('AbortError', 'Experiment stopped');
                if (!constraints || !constraints.audio || constraints.video) {
                    throw fail('NotSupportedError', 'This experiment supports audio-only calls');
                }
                try {
                    if (!replacement) {
                        const stream = await original(constraints);
                        if (stopped) {
                            stream.getTracks().forEach(t => t.stop());
                            throw fail('AbortError', 'Experiment stopped');
                        }
                        streams.add(stream);
                        send('microphone_supplied');
                        return stream;
                    }
                    ensureAudio();
                    // Fresh clones survive Discord stopping and reacquiring an earlier track.
                    const track = markTrack(destination.stream.getAudioTracks()[0].clone());
                    const stream = new MediaStream([track]);
                    streams.add(stream); suppliedTracks++;
                    send('replacement_supplied', {tracks: suppliedTracks, state: context.state});
                    return stream;
                } catch (e) { send('input_error', {name: errorName(e)}); throw e; }
            }
        });

        // Protect the ordinary WebRTC sender entry points as well as getUserMedia.
        const PC = window.RTCPeerConnection;
        if (!PC) throw fail('NotSupportedError', 'WebRTC unavailable');
        const addTrack = PC.prototype.addTrack;
        PC.prototype.addTrack = function(track, ...rest) {
            checkTrack(track); return addTrack.call(this, track, ...rest);
        };
        const addTransceiver = PC.prototype.addTransceiver;
        if (addTransceiver) PC.prototype.addTransceiver = function(track, ...rest) {
            if (typeof track !== 'string') checkTrack(track);
            return addTransceiver.call(this, track, ...rest);
        };
        if (window.RTCRtpSender) {
            const replace = RTCRtpSender.prototype.replaceTrack;
            RTCRtpSender.prototype.replaceTrack = function(track) {
                try { checkTrack(track); } catch (e) { return Promise.reject(e); }
                return replace.call(this, track);
            };
        }
        window.RTCPeerConnection = new Proxy(PC, {
            construct(Target, args) {
                if (stopped) throw fail('AbortError', 'Experiment stopped');
                const pc = new Target(...args);
                peers.add(pc);
                pc.addEventListener('connectionstatechange', () => {
                    send('peer_state', {state: pc.connectionState});
                    if (pc.connectionState === 'closed') peers.delete(pc);
                });
                return pc;
            }
        });

        if (replacement) document.addEventListener('DOMContentLoaded', () => {
            if (stopped) return;
            try { ensureAudio(); } catch (e) { send('bridge_error', {name: errorName(e)}); stop(); return; }
            // An actual DOM gesture is necessary on WebKit versions that suspend AudioContext.
            const host = document.createElement('div');
            host.style.cssText = 'position:fixed;right:20px;bottom:20px;z-index:2147483647';
            if (config.desktop) {
                // The 1100px desktop viewport must not shrink this touch target to a few points.
                const scale = Math.max(1, 1100 / (window.screen?.width || 390));
                host.style.transformOrigin = 'bottom right';
                host.style.transform = `scale(${scale})`;
            }
            const shadow = host.attachShadow({mode: 'closed'});
            const button = document.createElement('button');
            button.textContent = config.prepareAudioLabel || '♫ Prepare call audio';
            button.style.cssText = 'font:600 16px -apple-system,sans-serif;color:#514062;background:#eee5fa;border:1px solid #bda8d5;border-radius:24px;padding:14px 20px;box-shadow:0 4px 24px #0003;cursor:pointer';
            button.onclick = () => prepare().catch(e => send('audio_error', {name: errorName(e)}));
            shadow.appendChild(button); document.body.appendChild(host); unlock = host;
            unlock.hidden = context.state === 'running';
        }, {once: true});

        let sampling = false;
        timer = setInterval(async () => {
            if (stopped || sampling) return;
            streams.forEach(s => { if (s.getTracks().every(t => t.readyState === 'ended')) streams.delete(s); });
            ownedTracks.forEach(t => { if (t.readyState === 'ended') ownedTracks.delete(t); });
            if (toneUntil && context && context.currentTime >= toneUntil) quiet();
            sampling = true;
            let bytes = 0, packets = 0, audioSenders = 0, activeSenders = 0, unknownSenders = 0;
            try {
                for (const pc of peers) {
                    for (const sender of pc.getSenders()) {
                        if (sender.track && sender.track.kind === 'audio') {
                            audioSenders++;
                            if (replacement && !knownTracks.has(sender.track)) unknownSenders++;
                            if (replacement && strictTracks && !knownTracks.has(sender.track)) {
                                // Fail closed if the site's audio pipeline generated a new unapproved track.
                                sender.track.enabled = false;
                                await sender.replaceTrack(null);
                            } else if (pc.connectionState === 'connected' && sender.track.enabled && sender.track.readyState === 'live') {
                                activeSenders++;
                            }
                        }
                    }
                    const stats = await pc.getStats();
                    stats.forEach(s => {
                        if (s.type === 'outbound-rtp' && (s.kind === 'audio' || s.mediaType === 'audio')) {
                            bytes += s.bytesSent || 0; packets += s.packetsSent || 0;
                        }
                    });
                }
                if (!stopped) send('stats', {bytes, packets, audioSenders, activeSenders, unknownSenders,
                    pcmPackets, pcmResets, pcmQueuedMs: context ? Math.max(0, Math.round((pcmTime - context.currentTime) * 1000)) : 0});
            } catch (e) { if (!stopped) send('stats_error', {name: errorName(e)}); }
            finally { sampling = false; }
        }, 1000);

        Object.defineProperty(window, '__voiceStudioWebCall', {
            value: Object.freeze({tone, quiet, stop, beginPCM, endPCM, pushPCM}), configurable: false, writable: false
        });
        // Replies are bound to this document by AndroidX JavaScriptReplyProxy, never to a later navigation.
        window.VoiceStudioBridge.onmessage = e => {
            try {
                const m = JSON.parse(e.data);
                if (m.command === 'begin') send('pcm_started', {token: m.token, ok: beginPCM(m.token)});
                else if (m.command === 'end') endPCM(m.token);
                else if (m.command === 'pcm') send('pcm_ack', {token: m.token, sequence: m.sequence,
                    ok: pushPCM(m.token, m.data)});
            } catch (e) { send('pcm_error', {name: errorName(e)}); }
        };
        // A background tab must not resume a scheduled test tone when brought back later.
        document.addEventListener('visibilitychange', () => {
            if (document.hidden) { pcmToken = null; quiet(); clearPCM(); }
        });
        window.addEventListener('pagehide', stop, {once: true});
        window.addEventListener('pageshow', e => { if (e.persisted) location.reload(); });
        send('ready', {mode: config.mode, desktop: config.desktop, secure: window.isSecureContext});
    } catch (e) {
        send('bridge_error', {name: errorName(e)});
        stop(); // Native also denies capture; the page remains visible for diagnosis.
    }
})();
