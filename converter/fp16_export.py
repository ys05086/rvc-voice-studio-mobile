"""16-bit versions of the fixed-length front models, so Core ML can put them on the Neural Engine.

The phone's compute-plan check showed the fixed-length 32-bit models whole on Core ML but planned on the GPU and
CPU only, with every unit allowed: ORT builds 32-bit Core ML programs, and the Neural Engine computes in 16 bits.
These files compute in 16 bits inside and keep 32-bit inputs and outputs (the app does not change), with the parts
that would overflow 16 bits left in 32:

  HuBERT  the first convolution's InstanceNormalization (its variance of raw-audio features exceeds 65504)
  RMVPE   the STFT and mel front (squared magnitudes, sqrt, the mel matmul, clamp, log) and the GRU
  enc     the Pow that stands for exp(logs_p)

Each file is compared with its 32-bit source on real speech.

  python fp16_export.py FOLDER...      (folders from static_export.py: common/ and one per voice)
writes <file>.f16.onnx next to hubert.s25600.onnx, rmvpe.s4960.onnx and <name>.enc.t160.onnx.
"""
import argparse
from pathlib import Path
import warnings

import wave

import numpy as np
import onnx
import onnxruntime as ort
from onnxconverter_common import float16

HERE = Path(__file__).resolve().parent
CENTS = np.pad(20 * np.arange(360) + 1997.3794084376191, (4, 4))


def decode_f0(salience, thred=0.03):
    """RMVPE salience [frames, 360] -> Hz, as pipeline.decode_f0 (the 9 bins around the peak)."""
    center = salience.argmax(1) + 4
    padded = np.pad(salience, ((0, 0), (4, 4)))
    idx = center[:, None] + np.arange(-4, 5)[None]
    s = np.take_along_axis(padded, idx, 1)
    cents = (s * CENTS[idx]).sum(1) / s.sum(1)
    cents[padded.max(1) <= thred] = 0
    f0 = 10 * 2 ** (cents / 1200)
    f0[f0 == 10] = 0
    return f0

warnings.filterwarnings("ignore")
RMVPE_FRONT = ["/Slice", "/Slice_1", "/Slice_2", "/Slice_3", "/Concat", "/Unsqueeze", "/Conv", "/Slice_4", "/Slice_5",
               "/Pow", "/Pow_1", "/Add", "/Sqrt", "/MatMul", "/Clip", "/Log"]


def speech(samples):
    """Real speech (the repo's test recording) at 16 kHz, a stretch from its middle."""
    with wave.open(str(HERE.parent / "test_speech40.wav")) as w:  # 48 kHz 16-bit mono
        x = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    taps = np.arange(-48, 49)
    lowpass = np.sinc(taps / 3) / 3 * np.hanning(len(taps))
    x16 = np.convolve(x, lowpass, mode="same")[::3].astype(np.float32)
    start = len(x16) // 2
    return x16[start:start + samples][None]


def convert(src, ops=(), nodes=()):
    dst = src.with_name(src.name[: -len(".onnx")] + ".f16.onnx")
    model = float16.convert_float_to_float16(onnx.load(str(src)), keep_io_types=True,
                                             op_block_list=float16.DEFAULT_OP_BLOCK_LIST + list(ops), node_block_list=list(nodes))
    onnx.save(model, str(dst))
    return dst


def run(path, feeds):
    return ort.InferenceSession(str(path), providers=["CPUExecutionProvider"]).run(None, feeds)[0]


def report(label, got, want):
    err = np.abs(got - want).max() / (np.abs(want).max() + 1e-9)
    cos = float((got * want).sum() / np.sqrt((got ** 2).sum() * (want ** 2).sum()))
    print(f"{label}: max relative difference {err:.1e}, cosine {cos:.6f}{', NaN!' if np.isnan(got).any() else ''}", flush=True)
    if np.isnan(got).any() or cos < 0.999:
        raise SystemExit(f"{label}: 16 bits changed the output too much")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folders", nargs="+", type=Path)
    for folder in ap.parse_args().folders:
        for src in sorted(folder.glob("*.onnx")):
            name = src.name
            if name == "hubert.s25600.onnx":
                dst = convert(src, ops=["InstanceNormalization"])
                x = speech(25600)
                report(dst.name, run(dst, {"audio": x}), run(src, {"audio": x}))
            elif name == "rmvpe.s4960.onnx":
                dst = convert(src, ops=["GRU"], nodes=RMVPE_FRONT)
                x = speech(4960)
                got, want = run(dst, {"audio": x}), run(src, {"audio": x})
                report(dst.name, got, want)
                f_got, f_want = decode_f0(got[0]), decode_f0(want[0])
                voiced = (f_got > 0) & (f_want > 0)
                cents = np.abs(1200 * np.log2(f_got[voiced] / f_want[voiced])) if voiced.any() else np.zeros(1)
                print(f"  pitch: {voiced.sum()} voiced frames, max {cents.max():.1f} cents apart, "
                      f"voicing differs in {int(((f_got > 0) != (f_want > 0)).sum())} frames")
            elif name.endswith(".enc.t160.onnx"):
                dst = convert(src, ops=["Pow"])
                rng = np.random.default_rng(0)
                feeds = {"phone": rng.standard_normal((1, 160, 768)).astype(np.float32),
                         "pitch": rng.integers(1, 255, (1, 160)).astype(np.int64),
                         "noise": rng.standard_normal((1, 192, 160)).astype(np.float32)}
                report(dst.name, run(dst, feeds), run(src, feeds))


if __name__ == "__main__":
    main()
