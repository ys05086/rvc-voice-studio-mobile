"""The voice's decoder for the Snapdragon NPU (Android): 8-bit weights and 16-bit activations ("W8A16", QDQ).

The NPU's own 16-bit float path roughens the voice (raised noise floor below and between harmonics); integer
weights and activations keep its speed and sound like the 32-bit decoder (Galaxy S23+: average spectrum 0.14 dB
apart in realtime, vs 1.21 dB for 16-bit float, against the 32-bit GPU decoder). 16-bit weights do not
work there (the upsampling layers are refused), so 8 it is.

Quantization needs real decoder inputs: recordings (WAV) go through HuBERT, RMVPE and the voice's encoder, and the
decoder is calibrated on 2 s pieces of them, at three pitch shifts (0, +6, +12) so any setting stays in range. The
more varied the recordings (talking, loud, quiet, laughing, breathing), the better; a few minutes are plenty.

  python npu_export.py OUT/NAME/NAME.dec.onnx --enc OUT/NAME/NAME.enc.onnx --common OUT/common --calib calib/
  (rvc2phone.py runs this itself when it finds recordings in calib/ or --calib)
"""
import argparse
from pathlib import Path
import struct
import wave

import numpy as np
from model_format import read_geometry

FRAMES, CONTEXT = 200, 16  # the app's decoder pieces (Rvc.DEC_FRAMES, DEC_CONTEXT)
PITCHES = (0.0, 6.0, 12.0)
F0_MIN, F0_MAX = 50.0, 1100.0
F0_MEL_MIN, F0_MEL_MAX = 1127 * np.log(1 + F0_MIN / 700), 1127 * np.log(1 + F0_MAX / 700)
CENTS = np.pad(20 * np.arange(360) + 1997.3794084376191, (4, 4))


def read_wav(path):
    """A PCM WAV (8/16/24/32-bit integers, any rate, any channels) -> mono float at its rate."""
    with wave.open(str(path)) as w:
        width, channels, rate = w.getsampwidth(), w.getnchannels(), w.getframerate()
        raw = w.readframes(w.getnframes())
    if width == 1:
        x = (np.frombuffer(raw, np.uint8).astype(np.float32) - 128) / 128
    elif width == 2:
        x = np.frombuffer(raw, "<i2").astype(np.float32) / 32768
    elif width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3).astype(np.int32)
        x = ((b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)) << 8 >> 8).astype(np.float32) / 8388608
    else:
        x = np.frombuffer(raw, "<i4").astype(np.float32) / 2147483648
    return x.reshape(-1, channels).mean(1), rate


def resample(x, src, dst):
    """Low-pass below the lower Nyquist (windowed sinc), then linear interpolation: plenty for calibration."""
    if src == dst:
        return x.astype(np.float32)
    cutoff = 0.45 * min(src, dst) / src  # cycles per input sample
    taps = np.arange(-64, 65)
    kernel = 2 * cutoff * np.sinc(2 * cutoff * taps) * np.hanning(len(taps))
    y = np.convolve(x, kernel / kernel.sum(), mode="same")
    t = np.arange(int(len(x) * dst / src)) * src / dst
    return np.interp(t, np.arange(len(x)), y).astype(np.float32)


def decode_f0(salience, thred=0.03):
    center = salience.argmax(1) + 4
    padded = np.pad(salience, ((0, 0), (4, 4)))
    idx = center[:, None] + np.arange(-4, 5)[None]
    s = np.take_along_axis(padded, idx, 1)
    cents = (s * CENTS[idx]).sum(1) / np.maximum(s.sum(1), 1e-9)
    cents[padded.max(1) <= thred] = 0
    f0 = 10 * 2 ** (cents / 1200)
    f0[f0 == 10] = 0
    return f0


def fill_short_gaps(f0, max_gap=4):
    voiced = f0 > 0
    if voiced.all() or not voiced.any():
        return f0
    out = f0.copy()
    filled = np.interp(np.arange(len(f0)), np.flatnonzero(voiced), f0[voiced])
    gap_start = None
    for i, v in enumerate(list(voiced) + [True]):
        if not v and gap_start is None:
            gap_start = i
        elif v and gap_start is not None:
            if i - gap_start <= max_gap:
                out[gap_start:i] = filled[gap_start:i]
            gap_start = None
    return out


def coarse(f0):
    mel = 1127 * np.log(1 + f0 / 700)
    voiced = mel > 0
    mel[voiced] = (mel[voiced] - F0_MEL_MIN) * 254 / (F0_MEL_MAX - F0_MEL_MIN) + 1
    return np.clip(np.round(mel), 1, 255).astype(np.int64)


def nsf_source(f0, rng, upp=480, sine_amp=0.1, noise_std=0.003, sample_rate=48000):
    f = np.repeat(np.asarray(f0, dtype=np.float64), upp)
    phase = np.cumsum(f / sample_rate) % 1.0
    voiced = f > 0
    noise_amp = np.where(voiced, noise_std, sine_amp / 3)
    out = sine_amp * np.sin(2 * np.pi * phase) * voiced + noise_amp * rng.standard_normal(len(f))
    return out.astype(np.float32)[None, :, None]


def audio_files(paths):
    files = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            files += sorted(f for f in p.iterdir() if f.suffix.lower() == ".wav")
        elif p.suffix.lower() == ".wav" and p.is_file():
            files.append(p)
    return files


def calibration_pieces(enc_path, common, files, per_file=24, seconds=20):
    """Native-rate decoder inputs from the recordings, as the app makes them."""
    import onnxruntime as ort
    spec = read_geometry(Path(enc_path).parent)
    hop = spec["hop_length"]
    load = lambda p: ort.InferenceSession(str(p), providers=["CPUExecutionProvider"])
    hubert, rmvpe, enc = load(Path(common) / "hubert.onnx"), load(Path(common) / "rmvpe.onnx"), load(enc_path)
    rng = np.random.default_rng(0)
    pieces = []
    for k, path in enumerate(files):
        try:
            x, rate = read_wav(path)
        except (wave.Error, EOFError, struct.error) as e:
            print(f"  skip {path.name}: {e} (PCM WAV only)")
            continue
        whole = resample(x, rate, 16000)
        got = 0
        for start in range(0, len(whole), seconds * 16000):  # the encoder's attention grows with the square of the length
            x16 = whole[start:start + seconds * 16000]
            x16 = x16[: len(x16) // 320 * 320]
            if len(x16) < 2 * FRAMES * 160 or np.abs(x16).max() < 1e-4:
                continue
            n = len(x16) // 160
            feats = hubert.run(None, {"audio": x16[None]})[0]
            feats = np.concatenate([feats, feats[:, -1:]], 1).repeat(2, axis=1)[:, :n]
            feats = np.pad(feats, ((0, 0), (0, n - feats.shape[1]), (0, 0)), mode="edge").astype(np.float32)
            f0 = decode_f0(rmvpe.run(None, {"audio": x16[None]})[0][0])[:n]
            f0 = fill_short_gaps(np.pad(f0, (0, n - len(f0))))
            stride = FRAMES - 2 * CONTEXT
            starts = list(range(0, n - FRAMES, stride))
            starts = starts[:: max(1, len(starts) // per_file)]
            for j, pitch in enumerate(PITCHES):
                shifted = f0 * 2 ** (pitch / 12)
                noise = rng.standard_normal((1, 192, n)).astype(np.float32)
                z = enc.run(None, {"phone": feats, "pitch": coarse(shifted)[None], "noise": noise})[0]
                sine = nsf_source(shifted, rng, upp=hop, sample_rate=spec["sample_rate"])
                for s in starts[j::len(PITCHES)]:  # each piece once, at one of the shifts
                    pieces.append((z[:, :, s:s + FRAMES].copy(), sine[:, s * hop:(s + FRAMES) * hop].copy()))
                    got += 1
        print(f"  {path.name}: {got} pieces", flush=True)
    return pieces


class _Reader:
    def __init__(self, pieces):
        self.items = iter(pieces)

    def get_next(self):
        item = next(self.items, None)
        return None if item is None else {"z": item[0], "sine": item[1]}


def quantize_decoder(dec_path, out_path, pieces):
    """QNN preprocessing, then QDQ with per-channel symmetric int8 weights and uint16 activations."""
    import shutil
    import tempfile
    from onnxruntime.quantization import QuantType, quantize
    from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
    with tempfile.TemporaryDirectory() as tmp:
        pre = Path(tmp) / "pre.onnx"
        if not qnn_preprocess_model(str(dec_path), str(pre)):
            shutil.copy(dec_path, pre)
        config = get_qnn_qdq_config(str(pre), _Reader(pieces), activation_type=QuantType.QUInt16,
                                    weight_type=QuantType.QInt8, per_channel=True)
        quantize(str(pre), str(out_path), config)


def check(dec_path, out_path, pieces):
    """The quantized decoder against the 32-bit one on calibration pieces with sound in them (ORT CPU, simulated):
    SNR per piece. Near-silent pieces (breaths between words) are left out: their tiny output makes any SNR low."""
    import onnxruntime as ort
    a = ort.InferenceSession(str(dec_path), providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(str(out_path), providers=["CPUExecutionProvider"])
    snr = []
    for z, sine in pieces[:: max(1, len(pieces) // 12)][:12]:
        ref = a.run(None, {"z": z, "sine": sine})[0].ravel()
        if np.sqrt((ref ** 2).mean()) < 0.01:  # quieter than -40 dBFS
            continue
        got = b.run(None, {"z": z, "sine": sine})[0].ravel()
        snr.append(10 * np.log10((ref ** 2).sum() / max(((ref - got) ** 2).sum(), 1e-20)))
    return snr


def export(dec_path, enc_path, common, calib, out_path=None):
    """NAME.dec.onnx -> NAME.dec.w8a16.onnx; returns its path, or None without recordings."""
    files = audio_files(calib)
    if not files:
        return None
    out_path = Path(out_path or Path(dec_path).with_name(Path(dec_path).name.replace(".dec.onnx", ".dec.w8a16.onnx")))
    print(f"NPU decoder (W8A16): calibrating on {len(files)} recording(s)", flush=True)
    pieces = calibration_pieces(enc_path, common, files)
    if len(pieces) < 8:
        print(f"  only {len(pieces)} pieces of 2 s: give more recordings (a minute or more); skipped")
        return None
    quantize_decoder(dec_path, out_path, pieces)
    snr = check(dec_path, out_path, pieces)
    print(f"{out_path.name}: calibrated on {len(pieces)} pieces; against 32-bit on the PC, median SNR "
          f"{np.median(snr):.1f} dB over {len(snr)} pieces with sound (the phone's NPU lands a few dB lower; "
          "around 25-35 dB sounds like 32-bit)", flush=True)
    return out_path


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("dec", type=Path)
    p.add_argument("--enc", type=Path, required=True)
    p.add_argument("--common", type=Path, required=True, help="folder with hubert.onnx and rmvpe.onnx")
    p.add_argument("--calib", nargs="+", default=[str(Path(__file__).resolve().parent / "calib")],
                   help="recordings (WAV files or folders)")
    p.add_argument("-o", "--out", type=Path)
    a = p.parse_args()
    if export(a.dec, a.enc, a.common, a.calib, a.out) is None:
        raise SystemExit("no recordings to calibrate on: put WAV files into calib/ or give --calib")


if __name__ == "__main__":
    main()
