// No network or Discord login. Exercises the production injected script with Web Audio/WebRTC fakes.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/WebCallBridge.js'), 'utf8');

function browser(options = {}) {
    const messages = [], intervals = [], windowEvents = {}, documentEvents = {}, contexts = [];
    let nativeCalls = 0;
    class Track {
        constructor(kind = 'audio') { this.kind = kind; this.enabled = true; this.readyState = 'live'; }
        clone() { return new Track(this.kind); }
        stop() { this.readyState = 'ended'; }
    }
    class Stream {
        constructor(tracks = [new Track()]) { this.tracks = tracks; }
        getTracks() { return this.tracks; }
        getAudioTracks() { return this.tracks.filter(t => t.kind === 'audio'); }
    }
    class Param {
        constructor() { this.value = 0; this.events = []; }
        cancelScheduledValues(t) { this.events = this.events.filter(e => e[2] < t); }
        setValueAtTime(v, t) { this.events.push(['set', v, t]); }
        linearRampToValueAtTime(v, t) { this.events.push(['ramp', v, t]); }
    }
    class Node {
        constructor() { this.connections = []; }
        connect(other) { this.connections.push(other); return other; }
        disconnect() { this.connections = []; }
    }
    class Audio {
        constructor() {
            this.currentTime = 10; this.sampleRate = 48000; this.state = options.audioState || 'running';
            this.destination = new Node(); contexts.push(this);
        }
        createMediaStreamDestination() { this.target = new Node(); this.target.stream = new Stream(); return this.target; }
        createOscillator() {
            this.oscillator = new Node(); this.oscillator.frequency = new Param();
            this.oscillator.start = () => {}; this.oscillator.stop = () => { this.oscillator.stopped = true; };
            return this.oscillator;
        }
        createGain() { this.gain = new Node(); this.gain.gain = new Param(); return this.gain; }
        createBuffer(channels, frames, rate) {
            const data = new Float32Array(frames);
            return {sampleRate: rate, length: frames, getChannelData: () => data};
        }
        createBufferSource() {
            const source = new Node();
            source.start = at => { source.at = at; };
            source.stop = () => { source.stopped = true; };
            (this.scheduled ||= []).push(source);
            return source;
        }
        async resume() { this.state = 'running'; this.onstatechange?.(); }
        async close() { this.state = 'closed'; }
    }
    class Sender {
        constructor(track) { this.track = track; }
        async replaceTrack(track) { this.track = track; }
    }
    class PC {
        constructor() { this.senders = []; this.connectionState = 'new'; this.events = {}; }
        addEventListener(name, fn) { this.events[name] = fn; }
        addTrack(track) { const sender = new Sender(track); this.senders.push(sender); return sender; }
        addTransceiver(track) { return this.addTrack(typeof track === 'string' ? null : track); }
        getSenders() { return this.senders; }
        async getStats() { return new Map([['audio', {type: 'outbound-rtp', kind: 'audio', bytesSent: 120, packetsSent: 4}]]); }
        close() { this.connectionState = 'closed'; this.events.connectionstatechange?.(); }
    }
    const element = () => ({style: {}, appendChild() {}, attachShadow() { return this; }, remove() { this.removed = true; }});
    const nativeStream = new Stream();
    const document = {
        hidden: false, head: element(), body: element(), querySelector() { return null; },
        createElement: element, addEventListener(name, fn) { documentEvents[name] = fn; }
    };
    const sandbox = {
        location: {protocol: 'https:', hostname: options.host || 'discord.com', reload() {}},
        navigator: {mediaDevices: {
            async getUserMedia() { nativeCalls++; return options.nativePromise || nativeStream; },
            async enumerateDevices() { return [{kind: 'audioinput', deviceId: 'real-mic'}, {kind: 'audiooutput', deviceId: 'speaker'}]; }
        }},
        document, DOMException, atob: text => Buffer.from(text, 'base64').toString('binary'),
        AudioContext: Audio, MediaStream: Stream, RTCPeerConnection: PC, RTCRtpSender: Sender,
        MutationObserver: class { observe() {} disconnect() {} },
        setInterval(fn) { intervals.push(fn); return intervals.length; }, clearInterval() {},
        addEventListener(name, fn) { windowEvents[name] = fn; },
        VoiceStudioBridge: {postMessage(data) { messages.push(JSON.parse(data)); }},
        isSecureContext: true
    };
    sandbox.window = sandbox; sandbox.top = options.frame ? {} : sandbox;
    const context = vm.createContext(sandbox);
    const config = JSON.stringify({mode: options.mode || 'replacement', desktop: options.desktop ?? true,
        hosts: options.hosts || ['discord.com'], strictTracks: options.strictTracks ?? true});
    vm.runInContext(source.replace('__VS_WEB_CALL_CONFIG__', config), context);
    return {sandbox, messages, contexts, intervals, documentEvents, windowEvents, nativeStream,
        nativeCalls: () => nativeCalls, Track, Sender,
        gum: constraints => sandbox.navigator.mediaDevices.getUserMedia(constraints),
        api: sandbox.__voiceStudioWebCall};
}

test('replacement never opens the real mic; repeated requests and clones remain usable', async () => {
    const b = browser();
    const first = await b.gum({audio: {deviceId: {exact: 'physical-device'}}, video: false});
    first.getTracks()[0].stop();
    const next = await b.gum({audio: true});
    assert.equal(next.getTracks()[0].readyState, 'live');
    assert.equal(b.nativeCalls(), 0);
    assert.notEqual(first, next);
    const peer = new b.sandbox.RTCPeerConnection();
    assert.doesNotThrow(() => peer.addTrack(next.getTracks()[0].clone()));
    assert.equal(b.contexts[0].gain.gain.value, 0);
    assert.deepEqual(b.contexts[0].gain.connections, [b.contexts[0].target]);
    assert.equal(b.messages.filter(m => m.event === 'replacement_supplied').length, 2);
});

test('site compatibility wrappers can wrap media APIs without aborting startup', async () => {
    const b = browser({mode: 'rvc'});
    // Safari shims and site instrumentation wrap these APIs during application startup.
    // A read-only own property throws in strict mode before the page can render.
    assert.doesNotThrow(() => vm.runInNewContext(`'use strict';
        const media = navigator.mediaDevices;
        const gum = media.getUserMedia.bind(media);
        media.getUserMedia = constraints => gum(constraints);
        const enumerate = media.enumerateDevices.bind(media);
        media.enumerateDevices = () => enumerate();
    `, b.sandbox));
    const stream = await b.gum({audio: true});
    assert.equal(b.nativeCalls(), 0);
    assert.equal(stream.getAudioTracks().length, 1);
    const devices = await b.sandbox.navigator.mediaDevices.enumerateDevices();
    assert.equal(devices[0].deviceId, 'voice-studio-input');
    b.api.stop();
    await assert.rejects(b.gum({audio: true}), {name: 'AbortError'});
});

test('raw audio is blocked at addTrack, addTransceiver and replaceTrack', async () => {
    const b = browser();
    const peer = new b.sandbox.RTCPeerConnection();
    const raw = new b.Track();
    assert.throws(() => peer.addTrack(raw), {name: 'NotAllowedError'});
    assert.throws(() => peer.addTransceiver(raw), {name: 'NotAllowedError'});
    const stream = await b.gum({audio: true});
    const sender = peer.addTrack(stream.getTracks()[0]);
    await assert.rejects(sender.replaceTrack(raw), {name: 'NotAllowedError'});
    await sender.replaceTrack(null);
    assert.equal(sender.track, null);
});

test('baseline uses the real microphone only after an explicit request', async () => {
    const b = browser({mode: 'microphone'});
    assert.equal(b.nativeCalls(), 0);
    const stream = await b.gum({audio: true});
    assert.equal(b.nativeCalls(), 1);
    const peer = new b.sandbox.RTCPeerConnection();
    peer.addTrack(stream.getTracks()[0]);
    b.api.stop();
    assert.equal(stream.getTracks()[0].readyState, 'ended');
    assert.equal(peer.connectionState, 'closed');
});

test('late microphone permission completion after stop cannot revive capture', async () => {
    let resolve;
    const pending = new Promise(r => { resolve = r; });
    const b = browser({mode: 'microphone', nativePromise: pending});
    const request = b.gum({audio: true});
    b.api.stop();
    resolve(b.nativeStream);
    await assert.rejects(request, {name: 'AbortError'});
    assert.equal(b.nativeStream.getTracks()[0].readyState, 'ended');
});

test('test sound is finite, remote-only and returns to silence', async () => {
    const b = browser();
    await b.gum({audio: true});
    await b.api.tone();
    const a = b.contexts[0];
    assert.deepEqual(a.gain.gain.events.at(-1), ['set', 0, 13]);
    assert.equal(a.gain.connections.includes(a.destination), false);
    a.currentTime = 13.1;
    await b.intervals[0]();
    assert.equal(b.messages.filter(m => m.event === 'tone').at(-1).active, false);
    await b.api.tone();
    b.sandbox.document.hidden = true;
    b.documentEvents.visibilitychange();
    assert.deepEqual(a.gain.gain.events.at(-1), ['set', 0, 13.1]);
});

test('suspended Web Audio needs a page gesture; no fallback microphone', async () => {
    const b = browser({audioState: 'suspended'});
    await assert.rejects(b.api.tone(), {name: 'NotAllowedError'});
    assert.equal(b.nativeCalls(), 0);
    assert.equal(b.contexts[0].gain.gain.value, 0);
});

test('stop closes synthetic sources and rejects subsequent requests', async () => {
    const b = browser();
    const stream = await b.gum({audio: true});
    const peer = new b.sandbox.RTCPeerConnection();
    peer.addTrack(stream.getTracks()[0]);
    b.api.stop(); b.api.stop();
    assert.equal(stream.getTracks()[0].readyState, 'ended');
    assert.equal(b.contexts[0].target.stream.getTracks()[0].readyState, 'ended');
    assert.equal(b.contexts[0].state, 'closed');
    assert.equal(peer.connectionState, 'closed');
    await assert.rejects(b.gum({audio: true}), {name: 'AbortError'});
    assert.throws(() => new b.sandbox.RTCPeerConnection(), {name: 'AbortError'});
});

test('camera requests and unrelated origins/frames do not gain mic access', async () => {
    const b = browser();
    await assert.rejects(b.gum({audio: true, video: true}), {name: 'NotSupportedError'});
    await assert.rejects(b.gum({video: true}), {name: 'NotSupportedError'});
    assert.equal(b.nativeCalls(), 0);
    assert.equal(browser({host: 'discord.com.example.org'}).api, undefined);
    assert.equal(browser({frame: true}).api, undefined);
});

test('the bridge installs only on the chosen site domains', () => {
    const insta = {hosts: ['instagram.com', 'facebook.com'], strictTracks: false};
    assert.notEqual(browser({host: 'www.instagram.com', ...insta}).api, undefined);
    assert.notEqual(browser({host: 'www.facebook.com', ...insta}).api, undefined);
    assert.equal(browser({host: 'discord.com', ...insta}).api, undefined);
    assert.equal(browser({host: 'instagram.com.example.org', ...insta}).api, undefined);
    assert.equal(browser({hosts: []}).api, undefined);
});

test('other sites keep the audio tracks they make from ours, counted, but still never get the real mic', async () => {
    const b = browser({host: 'www.instagram.com', hosts: ['instagram.com'], strictTracks: false});
    const peer = new b.sandbox.RTCPeerConnection();
    const processed = new b.Track();  // e.g. the site's own noise suppression output
    peer.addTrack(processed);
    peer.connectionState = 'connected';
    await b.intervals[0]();
    assert.equal(processed.enabled, true);
    assert.equal(peer.senders[0].track, processed);
    const stats = b.messages.find(m => m.event === 'stats');
    assert.equal(stats.unknownSenders, 1);
    assert.equal(stats.activeSenders, 1);
    await b.gum({audio: true});
    assert.equal(b.nativeCalls(), 0);
});

test('sender audit detaches unexpected audio and reports only counters', async () => {
    const b = browser();
    const peer = new b.sandbox.RTCPeerConnection();
    const track = new b.Track();
    peer.senders.push(new b.Sender(track));
    await b.intervals[0]();
    assert.equal(track.enabled, false);
    assert.equal(peer.senders[0].track, null);
    const stats = b.messages.find(m => m.event === 'stats');
    assert.equal(stats.unknownSenders, 1);
    assert.equal(stats.bytes, 120);
    assert.equal(stats.packets, 4);
    assert.equal('sdp' in stats, false);
});

test('desktop hints match the Chromium desktop identity and can be disabled', () => {
    const b = browser();
    assert.equal(b.sandbox.navigator.platform, 'Linux x86_64');
    assert.equal(b.sandbox.navigator.maxTouchPoints, 0);
    assert.equal(browser({desktop: false}).sandbox.navigator.platform, undefined);
});

test('replacement presents a named input while retaining output device selection', async () => {
    const b = browser();
    const devices = await b.sandbox.navigator.mediaDevices.enumerateDevices();
    assert.equal(devices.length, 2);
    assert.equal(devices[0].deviceId, 'voice-studio-input');
    assert.equal(devices[1].kind, 'audiooutput');
    assert.equal(b.nativeCalls(), 0);
});

test('page navigation closes tracks and DOM readiness cannot revive a stopped source', async () => {
    const b = browser();
    const stream = await b.gum({audio: true});
    b.windowEvents.pagehide();
    assert.equal(stream.getTracks()[0].readyState, 'ended');
    assert.doesNotThrow(() => b.documentEvents.DOMContentLoaded());
    assert.equal(b.contexts[0].state, 'closed');
});

test('all supplied-track descendants end on stop, even outside tracked streams', async () => {
    const b = browser();
    const stream = await b.gum({audio: true});
    const clone = stream.getAudioTracks()[0].clone().clone();
    b.api.stop();
    assert.equal(clone.readyState, 'ended');
    assert.throws(() => clone.clone(), {name: 'AbortError'});
});

test('an interrupted audio clock cancels the pending tone before resume', async () => {
    const b = browser();
    await b.gum({audio: true});
    await b.api.tone();
    const a = b.contexts[0];
    a.currentTime = 10.1;
    a.state = 'interrupted'; a.onstatechange();
    assert.deepEqual(a.gain.gain.events.at(-1), ['set', 0, 10.1]);
    assert.equal(a.oscillator.frequency.events.some(e => e[2] > 10.1), false);
    await a.resume();
    assert.equal(b.messages.filter(m => m.event === 'tone').at(-1).active, false);
});

test('ready-to-send count excludes disconnected, muted and ended tracks', async () => {
    const b = browser();
    const stream = await b.gum({audio: true});
    const track = stream.getAudioTracks()[0];
    const peer = new b.sandbox.RTCPeerConnection();
    peer.addTrack(track);
    const active = () => b.messages.filter(m => m.event === 'stats').at(-1).activeSenders;
    await b.intervals[0](); assert.equal(active(), 0);
    peer.connectionState = 'connected';
    await b.intervals[0](); assert.equal(active(), 1);
    track.enabled = false;
    await b.intervals[0](); assert.equal(active(), 0);
    track.enabled = true; track.stop();
    await b.intervals[0](); assert.equal(active(), 0);
});

test('initial audio state is reported without waiting for a state-change event', async () => {
    const b = browser({audioState: 'suspended'});
    await b.gum({audio: true});
    assert.equal(b.messages.find(m => m.event === 'audio_state').state, 'suspended');
});

function pcm(samples) {
    const bytes = Buffer.alloc(samples.length * 4);
    samples.forEach((sample, i) => bytes.writeFloatLE(sample, i * 4));
    return bytes.toString('base64');
}

test('Android document reply transport starts PCM, acknowledges packets, and rejects stale session data', () => {
    const b = browser({mode: 'rvc'});
    const command = data => b.sandbox.VoiceStudioBridge.onmessage({data: JSON.stringify(data)});
    command({command: 'begin', token: 'android'});
    assert.equal(b.messages.at(-1).event, 'pcm_started');
    assert.equal(b.messages.at(-1).ok, true);
    command({command: 'pcm', token: 'android', sequence: 12, data: pcm([.2])});
    assert.equal(b.messages.at(-1).sequence, 12);
    assert.equal(b.messages.at(-1).ok, true);
    command({command: 'end', token: 'android'});
    command({command: 'pcm', token: 'android', sequence: 13, data: pcm([.2])});
    assert.equal(b.messages.at(-1).ok, false);
    assert.equal(b.nativeCalls(), 0);
});

test('hiding the page invalidates its PCM token, including delayed messages after return', () => {
    const b = browser({mode: 'rvc'});
    b.api.beginPCM('foreground'); b.api.pushPCM('foreground', pcm([.2]));
    b.sandbox.document.hidden = true; b.documentEvents.visibilitychange();
    assert.equal(b.contexts[0].scheduled[0].stopped, true);
    assert.equal(b.api.pushPCM('foreground', pcm([.2])), false);
    b.sandbox.document.hidden = false;
    assert.equal(b.api.pushPCM('foreground', pcm([.2])), false);
    b.api.beginPCM('reconnected');
    assert.equal(b.api.pushPCM('reconnected', pcm([.2])), true);
});

test('RVC accepts continuous float PCM only into the synthetic remote destination', async () => {
    const b = browser({mode: 'rvc'});
    await b.gum({audio: true});
    assert.equal(b.api.beginPCM('session'), true);
    assert.equal(b.api.pushPCM('session', pcm([0.5, -0.25, NaN, 2])), true);
    const a = b.contexts[0];
    const source = a.scheduled[0];
    assert.deepEqual([...source.buffer.getChannelData(0)], [0.5, -0.25, 0, 1]);
    assert.equal(source.buffer.sampleRate, 48000);
    assert.deepEqual(source.connections, [a.target]);
    assert.equal(b.nativeCalls(), 0);
    await assert.rejects(b.api.tone(), {name: 'InvalidStateError'});
});

test('old in-flight PCM cannot restart sound after stop or a newer conversion session', () => {
    const b = browser({mode: 'rvc'});
    b.api.beginPCM('old'); b.api.pushPCM('old', pcm([0.5]));
    b.api.endPCM('old');
    assert.equal(b.contexts[0].scheduled[0].stopped, true);
    assert.equal(b.api.pushPCM('old', pcm([0.5])), false);
    b.api.beginPCM('new');
    assert.equal(b.api.endPCM('old'), false);
    assert.equal(b.api.pushPCM('old', pcm([0.5])), false);
    assert.equal(b.api.pushPCM('new', pcm([0.25])), true);
    b.api.stop();
    assert.equal(b.api.pushPCM('new', pcm([0.25])), false);
});

test('PCM runs past 45 seconds, but bounds queued latency and clears on interruption', () => {
    const b = browser({mode: 'rvc'});
    b.api.beginPCM('session');
    const a = b.contexts[0];
    const block = pcm(Array(9600).fill(0.1));
    for (let i = 0; i < 300; i++) {
        a.currentTime += 0.2;
        assert.equal(b.api.pushPCM('session', block), true);
    }
    for (let i = 0; i < 5; i++) b.api.pushPCM('session', block);
    const last = a.scheduled.at(-1);
    assert.ok(last.at + 0.2 - a.currentTime <= 0.65);
    a.state = 'interrupted'; a.onstatechange();
    assert.equal(last.stopped, true);
    assert.equal(b.api.pushPCM('session', block), false);
});

test('PCM methods reject test/raw modes and malformed or oversized payloads', () => {
    assert.throws(() => browser().api.beginPCM('session'), {name: 'InvalidStateError'});
    assert.throws(() => browser({mode: 'microphone'}).api.beginPCM('session'), {name: 'InvalidStateError'});
    const b = browser({mode: 'rvc'}); b.api.beginPCM('session');
    assert.throws(() => b.api.pushPCM('session', 'AA=='), {name: 'DataError'});
    assert.throws(() => b.api.pushPCM('session', 'A'.repeat(153604)), {name: 'DataError'});
});
