"""Fixed-length, Core ML-friendly versions of the phone models, so Core ML can take them whole (and the Neural
Engine can take them at all: it runs fixed shapes only).

With variable lengths ORT handed Core ML the front models in pieces: Core ML's ML Program format has no Gather,
Pad, Where, Exp or BatchNormalization, and every refused op cut the graph (the phone's compute-plan check showed
RMVPE in 16 Core ML pieces and enc in 109, all planned on the CPU). Here every input has the length the app uses,
so the shape arithmetic folds into constants, and the rest is rewritten with ops Core ML has:

  F.pad (zeros)            -> Concat with constant zero blocks
  masked_fill (enc masks)  -> nothing: at a fixed length every frame is real, the masks are all ones
  exp(logs_p)              -> Pow(e, logs_p)
  BatchNorm2d (RMVPE)      -> x * scale + shift
  reflect pad (RMVPE STFT) -> flipped slices concatenated

Left as they are: RMVPE's Log and BiGRU, and enc's pitch embedding (one Gather at the very start).
Each file is checked against the variable-length model on the same input.

  python static_export.py --common [--out OUT] [--file-lengths]   hubert.s25600, rmvpe.s4960 (+ s96320 each)
  python static_export.py VOICE.pth --name NAME [--out OUT] [--file-lengths]
                                                           NAME.enc.t160 (+ t602, NAME.dec.n200, NAME.dec_a.n200)

Every file keeps its weights inside: ORT's Core ML path cannot read external data (it looked for
"hubert.s25600.onnx/hubert.weights" on the phone and the models fell back to the CPU). So each length is a full
copy; by default only the realtime lengths are written, --file-lengths adds the file-conversion ones.

Lengths: the realtime window is 160 frames (1.6 s, 25600 samples at 16 kHz) and RMVPE's realtime input 4960
samples; file conversion windows are 602 frames (96320 samples: 4 s + 20 ms + 2 x 1 s); the decoder's file pieces
are 200 frames.
"""
import argparse
import contextlib
import hashlib
import math
from pathlib import Path
import shutil
import sys
import tempfile

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn.functional as F

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import rvc2phone as base  # noqa: E402

REALTIME_FRAMES = 160
FILE_FRAMES = 602
RMVPE_REALTIME = 4960
DEC_FRAMES = 200
_pad = F.pad


def zero_pad(x, pad, mode="constant", value=0.0):
    """F.pad with zeros as Concat with constant blocks; anything else goes to the real F.pad."""
    if mode != "constant" or value not in (0, 0.0):
        return _pad(x, pad, mode=mode, value=value)
    for i in range(len(pad) // 2):
        dim = x.dim() - 1 - i
        left, right = int(pad[2 * i]), int(pad[2 * i + 1])
        if left < 0:
            x = x.narrow(dim, -left, int(x.shape[dim]) + left)
            left = 0
        if right < 0:
            x = x.narrow(dim, 0, int(x.shape[dim]) + right)
            right = 0
        parts = []
        for width in (left, None, right):
            if width is None:
                parts.append(x)
            elif width > 0:
                shape = [int(s) for s in x.shape]
                shape[dim] = width
                parts.append(torch.zeros(shape, dtype=x.dtype))
        if len(parts) > 1:
            x = torch.cat(parts, dim)
    return x


def encoder_forward(self, x, x_mask):
    """attentions.Encoder.forward without the attention masks (all ones at a fixed length)."""
    x = x * x_mask
    for attn, norm1, ffn, norm2 in zip(self.attn_layers, self.norm_layers_1, self.ffn_layers, self.norm_layers_2):
        x = norm1(x + self.drop(attn(x, x, None)))
        x = norm2(x + self.drop(ffn(x, x_mask)))
    return x * x_mask


@contextlib.contextmanager
def core_ml_friendly():
    from rvc import attentions
    saved = (F.pad, attentions.Encoder.forward)
    F.pad = zero_pad
    attentions.Encoder.forward = encoder_forward
    try:
        yield
    finally:
        F.pad, attentions.Encoder.forward = saved


class StaticEncoder(base.Encoder):
    def forward(self, phone, pitch, noise):
        g = self.net_g.emb_g(torch.zeros(1, dtype=torch.long)).unsqueeze(-1)
        lengths = torch.full((1,), phone.shape[1], dtype=torch.long)
        m_p, logs_p, x_mask = self.net_g.enc_p(phone, pitch, lengths)
        z_p = (m_p + torch.pow(torch.tensor(math.e), logs_p) * noise * 0.66666) * x_mask
        return self.net_g.flow(z_p, x_mask, g=g, reverse=True)


class Affine(torch.nn.Module):
    """An eval-mode BatchNorm2d as a multiply and an add."""

    def __init__(self, bn):
        super().__init__()
        scale = bn.weight / torch.sqrt(bn.running_var + bn.eps)
        self.register_buffer("scale", scale.reshape(1, -1, 1, 1).detach().clone())
        self.register_buffer("shift", (bn.bias - bn.running_mean * scale).reshape(1, -1, 1, 1).detach().clone())

    def forward(self, x):
        return x * self.scale + self.shift


def without_batchnorm(module):
    for name, child in module.named_children():
        if isinstance(child, torch.nn.BatchNorm2d):
            setattr(module, name, Affine(child))
        else:
            without_batchnorm(child)
    return module


class StaticRmvpe(base.Rmvpe):
    def forward(self, audio):
        n = int(audio.shape[1])
        left = torch.flip(audio[:, 1:513], [1])
        right = torch.flip(audio[:, n - 513:n - 1], [1])
        x = torch.cat([left, audio, right], 1)[:, None, :]
        spec = F.conv1d(x, self.basis, stride=160)
        real, imag = spec[:, :513], spec[:, 513:]
        mel = torch.log(torch.clamp(torch.matmul(self.mel_basis, torch.sqrt(real ** 2 + imag ** 2)), min=1e-5))
        frames = int(mel.shape[-1])
        mel = zero_pad(mel, (0, (32 - frames % 32) % 32))
        return self.model(mel)[:, :frames]


def export(module, args, names, outputs, path):
    with torch.no_grad():
        torch.onnx.export(module, args, str(path), input_names=names, output_names=outputs,
                          opset_version=base.OPSET, dynamo=False)


def compare(label, static_path, reference_path, feeds):
    run = lambda p: ort.InferenceSession(str(p), providers=["CPUExecutionProvider"]).run(None, feeds)[0]
    got, want = run(static_path), run(reference_path)
    err = np.abs(got - want).max() / (np.abs(want).max() + 1e-9)
    refused = refused_ops(static_path)
    print(f"{label}: max relative error vs the variable-length model {err:.1e}; Core ML refuses {refused or 'nothing'}", flush=True)
    if err > 2e-3:
        raise SystemExit(f"{label}: differs from the variable-length model")


def refused_ops(path):
    sys.path.insert(0, str(HERE.parent / "research"))
    from coreml_ops import SUPPORTED
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    out = Path(tempfile.mkdtemp()) / "o.onnx"
    so.optimized_model_filepath = str(out)
    ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    counts = {}
    for n in onnx.load(str(out), load_external_data=False).graph.node:
        if n.op_type not in SUPPORTED:
            counts[n.op_type] = counts.get(n.op_type, 0) + 1
    return counts


def share_weights(paths, folder, weights):
    """Same-model variants into `folder`: every initializer that is identical in all of them (the weights) goes once
    into one external file they all point at; length-dependent constants stay inside each graph."""
    from onnx.external_data_helper import set_external_data
    models = [onnx.load(str(p)) for p in paths]
    tables = [{t.name: t for t in m.graph.initializer} for m in models]
    names = sorted(set.intersection(*(set(t) for t in tables)))
    shared = [n for n in names if len(tables[0][n].raw_data) >= 1024
              and all(t[n].raw_data == tables[0][n].raw_data and list(t[n].dims) == list(tables[0][n].dims) for t in tables)]
    offset = 0
    with open(folder / weights, "wb") as f:
        for n in shared:
            raw = tables[0][n].raw_data
            for t in tables:
                set_external_data(t[n], location=weights, offset=offset, length=len(raw))
                t[n].ClearField("raw_data")
                t[n].data_location = onnx.TensorProto.EXTERNAL
            f.write(raw)
            pad = (-len(raw)) % 64
            f.write(b"\0" * pad)
            offset += len(raw) + pad
    for p, m in zip(paths, models):
        onnx.save_model(m, str(folder / p.name))
        # the re-saved graph with its shared weights must still give the same output
        rng = np.random.default_rng(0)
        feeds = {}
        for i in m.graph.input:
            shape = [d.dim_value for d in i.type.tensor_type.shape.dim]
            if i.type.tensor_type.elem_type == onnx.TensorProto.INT64:
                feeds[i.name] = rng.integers(1, 255, shape).astype(np.int64)
            else:
                feeds[i.name] = (rng.standard_normal(shape) * 0.1).astype(np.float32)
        run = lambda q: ort.InferenceSession(str(q), providers=["CPUExecutionProvider"]).run(None, feeds)[0]
        if not np.array_equal(run(p), run(folder / p.name)):
            raise SystemExit(f"{p.name}: the shared-weights copy gives a different output")
    sizes = ", ".join(f"{p.name} {(folder / p.name).stat().st_size / 1e6:.1f} MB" for p in paths)
    print(f"  {weights} {offset / 1e6:.0f} MB shared by {sizes}", flush=True)


def export_common(out, file_lengths):
    sys.modules.setdefault("sklearn", None)
    from transformers import HubertModel

    class HubertModelWithFinalProj(HubertModel):
        def __init__(self, config):
            super().__init__(config)
            self.final_proj = torch.nn.Linear(config.hidden_size, config.classifier_proj_size)

    work = Path(tempfile.mkdtemp())
    model = HubertModelWithFinalProj.from_pretrained(str(base.DOWNLOADS / "content-vec-best"), local_files_only=True,
                                                     attn_implementation="eager").float().eval().requires_grad_(False)
    hubert = base.Hubert(model)
    reference = work / "hubert.onnx"
    with torch.no_grad():
        hubert(torch.zeros(1, 16000))  # materialize the weight-normed positional conv first
    base.export_hubert(work)  # the variable-length model, to compare against
    for samples in (REALTIME_FRAMES * 160, FILE_FRAMES * 160)[:2 if file_lengths else 1]:
        x = torch.randn(1, samples) * 0.1
        p = out / f"hubert.s{samples}.onnx"
        export(hubert, (x,), ["audio"], ["features"], p)
        compare(p.name, p, reference, {"audio": x.numpy()})

    from rvc.rmvpe_net import E2E
    net = E2E(4, 1, (2, 2))
    net.load_state_dict(torch.load(base.fetch("rmvpe.pt"), map_location="cpu", weights_only=True))
    rmvpe = StaticRmvpe(without_batchnorm(net.float().eval())).eval().requires_grad_(False)
    base.export_rmvpe(work)
    for samples in (RMVPE_REALTIME, FILE_FRAMES * 160)[:2 if file_lengths else 1]:
        t = torch.arange(samples) / 16000
        x = (0.2 * torch.sin(2 * np.pi * 180 * t) + 0.01 * torch.randn_like(t))[None]
        p = out / f"rmvpe.s{samples}.onnx"
        with core_ml_friendly():
            export(rmvpe, (x,), ["audio"], ["salience"], p)
        compare(p.name, p, work / "rmvpe.onnx", {"audio": x.numpy()})


def export_voice(out, name, voice, file_lengths):
    work = Path(tempfile.mkdtemp())
    net_g = base.load_voice(voice)
    if net_g.sample_rate != 48000:
        raise ValueError("Static/iOS export supports 48 kHz only; use rvc2phone.py for Android 32 kHz models")
    base.traceable_attention()
    base.export_voice(work, name, net_g)  # the variable-length models, to compare against
    enc = StaticEncoder(net_g).eval()
    for frames in (REALTIME_FRAMES, FILE_FRAMES)[:2 if file_lengths else 1]:
        feeds = {"phone": torch.randn(1, frames, 768), "pitch": torch.randint(1, 255, (1, frames)),
                 "noise": torch.randn(1, 192, frames)}
        p = out / f"{name}.enc.t{frames}.onnx"
        with core_ml_friendly():
            export(enc, tuple(feeds.values()), list(feeds), ["z"], p)
        compare(p.name, p, work / f"{name}.enc.onnx", {k: v.numpy() for k, v in feeds.items()})
    if not file_lengths:
        return
    z, sine = torch.randn(1, 192, DEC_FRAMES), torch.randn(1, DEC_FRAMES * 480, 1) * 0.1
    for module, label, outputs in ((base.Decoder(net_g).eval(), "dec", ["audio"]), (base.DecoderA(net_g).eval(), "dec_a", ["hidden"])):
        p = out / f"{name}.{label}.n{DEC_FRAMES}.onnx"
        export(module, (z, sine), ["z", "sine"], outputs, p)
        compare(p.name, p, work / f"{name}.{label}.onnx", {"z": z.numpy(), "sine": sine.numpy()})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("voice", nargs="?", type=Path)
    ap.add_argument("--name")
    ap.add_argument("--out", type=Path, default=HERE / "out")
    ap.add_argument("--common", action="store_true")
    ap.add_argument("--file-lengths", action="store_true", help="also the file-conversion lengths (full copies)")
    args = ap.parse_args()
    torch.manual_seed(0)
    if args.common:
        folder = args.out / "common"
        folder.mkdir(parents=True, exist_ok=True)
        export_common(folder, args.file_lengths)
    if args.voice:
        name = args.name or args.voice.stem
        folder = args.out / name
        folder.mkdir(parents=True, exist_ok=True)
        export_voice(folder, name, args.voice, args.file_lengths)


if __name__ == "__main__":
    main()
