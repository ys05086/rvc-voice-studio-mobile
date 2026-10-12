# Voice Studio usage

[한국어](USAGE.ko.md) · [Model converter walkthrough](../converter/USAGE.md)

## Choose a mode

One APK includes all modes. Shizuku is optional and is requested only when you start the virtual microphone.

| What you want | Mode | Shizuku | Background behavior |
| --- | --- | --- | --- |
| Convert a recording or audio file | Record / Convert | No | Keep the conversion screen open |
| Hear your converted microphone locally | Live | No | Microphone foreground service |
| Send converted voice to a website in the app | Live → Web calls | No | Forwarding disconnects when you leave the web screen |
| Send converted voice to an installed app | Live → Open virtual mic | Yes, running with shell privileges | Status notification; waits when no app uses the mic |

Web calls are an experimental alternative, not a way to change every installed app's mic without Shizuku. Site shortcuts do not mean the site's calls have been validated.

## First setup

1. Install Voice Studio and open it once.
2. Open **Settings → Download** to obtain the shared HuBERT and RMVPE models. If downloading fails, create them with the converter's **Common models** option or `--common`.
3. Follow the [converter walkthrough](../converter/USAGE.md) to turn an RVC v2, 32/48 kHz, f0 `.pth` into phone model files. The phone does not directly import `.pth`. 32 kHz needs Android 0.4.1 or later and `model.json` alongside the ONNX files; output audio stays 48 kHz.
4. Import the resulting voice ZIP or folder using **Import**, or use the converter's **Send to phone** function. Select that voice in the app.
5. Adjust pitch and formant on the Convert tab. Settings are saved per model. Virtual mic reads these settings when starting; restart it to apply later changes.
6. Allow the microphone when recording or starting live audio. Allow notifications to see background status and the Stop control.

The UI follows the device language: Korean for Korean, English otherwise. Android 13+ also supports **Settings → App language**, which opens the system's per-app language selector. Earlier Android versions follow the system language. Model names and existing recording filenames are preserved.

## Record and convert

1. In **Record**, tap the red button, speak, then tap it again to stop.
2. Tap **Convert this recording**. Alternatively, choose an existing audio file on the Convert tab.
3. Select a voice, adjust pitch/formant if needed, then tap **Convert**.
4. Compare the original and converted waveforms. Use **Save** for Music/VoiceStudio or **Share** to open Android's share sheet.

## Live monitoring

Connect headphones, choose a voice, then tap **Start** on the Live tab. This mode plays audio locally; starting it alone does not replace another app's input. Use **Stop** in the app or notification to end it. The optional live recording button saves original and converted takes to the Record tab.

The compute-device settings apply to file conversion and this live mode. Unsupported accelerators may fall back to CPU. If processing is slower than each audio block, increase the block size or use file conversion. Virtual mic currently uses a separate configuration tested on the S23+.

### Additional context

In **Live → Live settings**, set **Additional context** from 0 to 3 seconds. This is past audio analyzed together with each new block, not a timer or a fixed amount added to call latency. More history may improve consistency but also increases processing and initial buffering; reduce it if processing cannot keep up. The value is shared by both modes and applies when you next start conversion. Before you change it, the app keeps the previous 1.6-second total analysis window. Internally the window is rounded up to 20 ms.

## Web calls without Shizuku

1. Start **Live** conversion. Turn **Bypass conversion** off.
2. Open **Web calls**. Use **Choose site** or enter an HTTPS address.
3. Sign in on the website. Try **Desktop view** or **Mobile view** if its layout requires it.
4. Tap **Prepare call audio** on the page if shown, then **Connect voice to this site** in the purple control panel.
5. Join a call or voice channel on the website yourself. If offered an input choice, choose `Voice Studio · converted voice`.
6. Use the chevron beside the status badge to collapse/expand the controls. **More** contains back, forward, reload and sign-in clearing.
7. Tap **Disconnect voice** to stop forwarding. Leaving the web screen, locking the phone, navigating or reloading also disconnects it. Reconnect explicitly after returning. To stop the conversion engine too, return to Live and tap Stop or use the notification.

Use headphones. While connected, local monitoring of your own voice is muted; it returns after disconnecting. The web bridge supplies converted audio or silence, and denies websites direct physical microphone/camera access. Video capture is not supported. Signing out via **Clear sign-ins** clears all website login data in this app's browser, not just the displayed site. It does not clear external browser data.

Website login restrictions, WebView support and WebRTC behavior vary. If a page is not ready, update Android System WebView, reload, prepare call audio again and reconnect. Native Discord success with Shizuku is separate from web call compatibility; end-to-end web call audio has not been validated in this change.

## Virtual microphone with Shizuku

### Start Shizuku

Install Shizuku from its [official site](https://shizuku.rikka.app/). The tested path uses wireless debugging, without root. On Android 11+, pair from Shizuku, open **Developer options → Wireless debugging → Pair device with pairing code**, then enter the six-digit code in Shizuku's notification. Return to Shizuku and tap **Start**. Confirm that it says it is running. Repeat the start step after rebooting. See the [official setup guide](https://shizuku.rikka.app/guide/setup/) for device-specific instructions.

Installing Shizuku is not the same as starting it. This implementation requires Shizuku running as Android's shell user; root-mode Shizuku is not supported.

**Root is not required; a running Shizuku service is required for virtual mic.** Start Shizuku again after every reboot or if its service has stopped. Pairing normally only needs to be done once. While Shizuku remains running, you can start another virtual-mic session without restarting or pairing Shizuku again. Voice Studio's virtual mic must still be turned on for each new session. Recording, file conversion, live monitoring and web calls work without Shizuku.

### Start a session

1. Stop ordinary Live conversion and any Voice Studio recording.
2. Open **Live → Open virtual mic**. The Shizuku badge opens Shizuku; **Getting started** opens an in-app guide.
3. Choose your voice. Either choose one destination app or enable **Replace input for all apps**.
4. Tap **Start virtual mic**. Approve Voice Studio in Shizuku, then allow microphone and notification permissions as requested.
5. Wait for **Waiting for microphone input**. Open the destination app and start its recording, mic test or voice call. There is no 30-second start deadline or five-minute session limit.
6. The status changes to **Sending your voice**. The notification shows preparation, waiting, buffering or conversion plus elapsed session time. Expand it to see the target, model and Stop button; tap its body to return to the app.
7. When the destination releases its microphone, capture and inference pause. A later input automatically resumes with fresh audio context.
8. Stop from the app or notification. The audio policy is removed and apps return to their normal mic. Their calls are not ended by this button.

**All-app mode:** routes supported inputs without selecting one app. It excludes Voice Studio and the shell capture process. This is not a guarantee that every Android input path is replaced.

**Secure Folder / other profile, single-app mode:** install the same package in the main profile too, select it, enable the other-profile option, then start the mic inside that profile **before** starting virtual mic. The active input identifies the correct profile. Open Secure Folder yourself; Voice Studio does not browse its private app list. All-app mode does not require selecting that profile separately.

**No sound or original voice:** confirm Shizuku is running and authorized, the correct model/target is selected, and the destination actually has an active mic. Close and reopen its mic test after preparing virtual mic. Muting a call may leave its microphone active; leave the test/call input to trigger idle. Check the input/output meters and processing details. If permissions were denied, use Shizuku's authorized apps or Android app settings. The notification hint opens notification settings.

## Verified scope and limits

Galaxy S23+ / Android 16 was used for the rootless Shizuku tests. Native Discord and Secure Folder KakaoTalk received converted voice according to user listening checks; switching between them in all-app mode was also checked. Instagram is selectable but not yet verified by listening. Cellular calls, Bluetooth input and other chipsets remain unverified.

The status-notification test included approximately 146 seconds of Discord input and 40 seconds with the screen off. Audio was confirmed after waking; input pauses released capture and the CPU wake lock, and notification Stop removed the policy/service. It recorded no skipped inputs or AudioTrack underruns, but buffer recovery inserted a total of 220 ms of silence. The approximately 653 ms capture-to-injection estimate excludes app buffering and network latency. This was a short test with USB/ADB attached, not a battery-only endurance test.

Conversion stays on the phone. Connected destination apps or websites transmit audio according to their own behavior. Virtual mic saves bounded diagnostic timing/log files in the app's `virtual_mic/` directory, not raw or converted audio by default. Explicit recording/export actions are separate. Shizuku supplies session privileges; the app is not permanently granted system permissions.

Website cookies and sign-in sessions stay in the installed app's private browser data; they are not part of its source code or built APK. Updating the app preserves that data. Use **Web calls → More → Clear sign-ins** to remove the app browser's website sign-ins.

The integrated build passed 46 Android unit tests, 25 web bridge tests and checks for 378 matching English/Korean string resources. Korean and English controls were inspected on the phone, and a further native Discord listening test confirmed converted audio. These checks do not establish end-to-end web call compatibility.
