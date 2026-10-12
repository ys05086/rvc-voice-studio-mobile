"""RVC v2 voice model (.pth) -> files for the Voice Studio phone app (Android and iPhone).

  python rvc2phone.py VOICE.pth [--name NAME] [--out OUT]   one voice: OUT/NAME/ and OUT/NAME.zip
  python rvc2phone.py --common [--out OUT]                  the shared models: OUT/common/hubert.onnx, rmvpe.onnx

A voice becomes NAME.enc.onnx and NAME.dec.onnx (both phones) plus NAME.dec.ncnn.param/.bin (the Android GPU
decoder, made when pnnx is installed) and a settings.json. Import the folder or the zip in the app.
It also writes the decoder in two pieces, NAME.dec_a.onnx (the first three upsampling stages, 88% of the work,
which survive 16-bit: the phone runs them on the NPU) and NAME.dec_b.onnx (the last stage and the output layer,
which do not: they stay 32-bit on the GPU). Measured on the PC, 16 bits up to there changed the spectrum by
0.07-0.17 dB, the last stage alone by 0.6-0.8 dB and the output layer alone by 3.4 dB.
Only RVC v2 models at 48 kHz with pitch (f0) work for now; v1, 40k/32k and no-pitch models are refused.

For Android's NPU it also makes NAME.dec.w8a16.onnx: the decoder with 8-bit weights and 16-bit activations, which
sounds like the 32-bit decoder at the NPU's speed (the app uses it when it is there). That needs a few minutes of
recordings to calibrate on: WAV files in calib/ next to this script (or --calib); without them it is skipped.
--common also writes hubert.htp.onnx, HuBERT as Android's NPU takes it whole (GELUs fused).

The shared models (the app can also download these itself) are built from the original weights, downloaded once
into downloads/ and checked against their SHA-256: ContentVec (huggingface.co/lengyue233/content-vec-best) and
RMVPE (huggingface.co/lj1995/VoiceConversionWebUI), both MIT.

Every export is checked against PyTorch on random input.

  hubert.onnx      16 kHz audio [1, N]                         -> content features [1, N//320, 768] (50 fps)
  rmvpe.onnx       16 kHz audio [1, N]                         -> pitch salience [1, N//160 + 1, 360] (100 fps)
  NAME.enc.onnx    features [1, T, 768] (100 fps), coarse pitch [1, T] int64, noise [1, 192, T] -> z [1, 192, T]
  NAME.dec.onnx    z [1, 192, n], NSF excitation [1, n * 480, 1] -> 48 kHz audio [1, 1, n * 480]
  NAME.dec_a.onnx  z [1, 192, n], NSF excitation [1, n * 480, 1] -> hidden [1, 64, n * 240]
  NAME.dec_b.onnx  hidden [1, 64, n * 240], NSF excitation [1, n * 480, 1] -> 48 kHz audio [1, 1, n * 480]
  NAME.dec.w8a16.onnx  as NAME.dec.onnx, quantized (QDQ) for the Snapdragon NPU
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import urllib.request
import zipfile

import numpy as np
import torch
import torch.nn.functional as F

HERE = Path(__file__).resolve().parent
DOWNLOADS = HERE / "downloads"
OPSET = 17
CONTENT_REV = "ab04aa7067b99ee05cc82499bc64916b980a1967"
RMVPE_REV = "e6d0c1a17da07c33557852f9dfa2bd44cc75737d"
# (file under downloads/, URL, SHA-256)
SOURCES = {
    "config.json": (f"https://huggingface.co/lengyue233/content-vec-best/resolve/{CONTENT_REV}/config.json",
                    "2ddde063b795d38d9051a7215a092fecf4cfe148b54251e38de51d88d356898b"),
    "pytorch_model.bin": (f"https://huggingface.co/lengyue233/content-vec-best/resolve/{CONTENT_REV}/pytorch_model.bin",
                          "d8dd400e054ddf4e6be75dab5a2549db748cc99e756a097c496c099f65a4854e"),
    "rmvpe.pt": (f"https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/{RMVPE_REV}/rmvpe.pt",
                 "6d62215f4306e3ca278246188607209f09af3dc77ed4232efdd069798c4ec193"),
}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(name):
    """downloads/<name>, downloaded first if missing; refuses a file whose checksum differs."""
    url, want = SOURCES[name]
    path = DOWNLOADS / name
    if path.is_file() and sha256(path) == want:
        return path
    DOWNLOADS.mkdir(exist_ok=True)
    part = path.with_suffix(path.suffix + ".part")
    print(f"downloading {url}", flush=True)
    with urllib.request.urlopen(url, timeout=60) as response, open(part, "wb") as out:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        while chunk := response.read(1 << 20):
            out.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {done / 1e6:.0f} / {total / 1e6:.0f} MB", end="", flush=True)
    print()
    if sha256(part) != want:
        part.unlink()
        raise SystemExit(f"{name}: checksum mismatch, download again")
    part.replace(path)
    return path


def check(name, torch_out, onnx_path, feeds, tol):
    import onnxruntime as ort
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    got = session.run(None, {k: v.numpy() for k, v in feeds.items()})[0]
    want = torch_out.detach().numpy()
    err = np.abs(got - want).max() / (np.abs(want).max() + 1e-9)
    size = onnx_path.stat().st_size / 2**20
    print(f"{name}: {size:.0f} MB, shape {got.shape}, max relative error {err:.2e}", flush=True)
    if err > tol:
        raise SystemExit(f"{name}: ONNX output differs from PyTorch ({err:.2e} > {tol})")


# ---------------------------------------------------------------- shared models

class Hubert(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, audio):
        return self.model(input_values=audio, return_dict=True).last_hidden_state  # RVC v2: the 12th layer


def export_hubert(out):
    sys.modules.setdefault("sklearn", None)  # transformers would import it if present; nothing here needs it
    from transformers import HubertModel

    class HubertModelWithFinalProj(HubertModel):  # the checkpoint's class: HuBERT plus an unused final_proj
        def __init__(self, config):
            super().__init__(config)
            self.final_proj = torch.nn.Linear(config.hidden_size, config.classifier_proj_size)

    folder = DOWNLOADS / "content-vec-best"
    folder.mkdir(parents=True, exist_ok=True)
    for name in ("config.json", "pytorch_model.bin"):
        if not (folder / name).is_file():
            shutil.copy(fetch(name), folder / name)
    model = HubertModelWithFinalProj.from_pretrained(str(folder), local_files_only=True,
                                                     attn_implementation="eager").float().eval()
    model.requires_grad_(False)  # with grads on, the export bakes the weight-norm'd pos_conv wrongly (output ~100% off)
    wrapper, x = Hubert(model), torch.randn(1, 16000 * 3) * 0.1
    path = out / "hubert.onnx"
    with torch.no_grad():
        want = wrapper(x)  # run once first: the positional conv's weight norm is only materialized by a forward
        torch.onnx.export(wrapper, (x,), str(path), input_names=["audio"], output_names=["features"], opset_version=OPSET,
                          dynamic_axes={"audio": {1: "samples"}, "features": {1: "frames"}}, dynamo=False)
        check("hubert", want, path, {"audio": x}, 1e-3)


def stft_basis(n_fft=1024):
    """RMVPE's STFT as convolution weights: rows are the real then imaginary DFT parts, periodic Hann windowed."""
    n = np.arange(n_fft)
    k = np.arange(n_fft // 2 + 1)[:, None]
    window = 0.5 - 0.5 * np.cos(2 * np.pi * n / n_fft)
    angle = 2 * np.pi * k * n / n_fft
    return np.vstack([np.cos(angle), -np.sin(angle)]) * window


def mel_basis(sr=16000, n_fft=1024, n_mels=128, fmin=30.0, fmax=8000.0):
    """librosa.filters.mel(htk=True, norm="slaney"), as RMVPE builds it, without needing librosa."""
    def hz_to_mel(f):
        return 2595.0 * np.log10(1.0 + f / 700.0)

    def mel_to_hz(m):
        return 700.0 * (10.0 ** (m / 2595.0) - 1.0)

    fft_freqs = np.linspace(0, sr / 2, 1 + n_fft // 2)
    mel_f = mel_to_hz(np.linspace(hz_to_mel(fmin), hz_to_mel(fmax), n_mels + 2))
    fdiff = np.diff(mel_f)
    ramps = np.subtract.outer(mel_f, fft_freqs)
    weights = np.zeros((n_mels, len(fft_freqs)))
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0, np.minimum(lower, upper))
    weights *= (2.0 / (mel_f[2:n_mels + 2] - mel_f[:n_mels]))[:, None]
    return weights.astype(np.float32)


class Rmvpe(torch.nn.Module):
    """16 kHz audio -> salience, mel included: a convolution STFT (torch.stft's complex output does not export)
    with RMVPE's reflect padding and periodic Hann window."""

    def __init__(self, net):
        super().__init__()
        self.register_buffer("basis", torch.from_numpy(stft_basis()).float().reshape(-1, 1, 1024))
        self.register_buffer("mel_basis", torch.from_numpy(mel_basis()))
        self.model = net

    def forward(self, audio):
        x = F.pad(audio[:, None, :], (512, 512), mode="reflect")
        spec = F.conv1d(x, self.basis, stride=160)
        real, imag = spec[:, :513], spec[:, 513:]
        mel = torch.log(torch.clamp(torch.matmul(self.mel_basis, torch.sqrt(real ** 2 + imag ** 2)), min=1e-5))
        frames = mel.shape[-1]
        mel = F.pad(mel, (0, (32 - frames % 32) % 32))  # the network wants a multiple of 32 frames
        return self.model(mel)[:, :frames]


def export_rmvpe(out):
    from rvc.rmvpe_net import E2E
    net = E2E(4, 1, (2, 2))
    net.load_state_dict(torch.load(fetch("rmvpe.pt"), map_location="cpu", weights_only=True))
    wrapper = Rmvpe(net.float().eval()).eval().requires_grad_(False)
    t = torch.arange(16000 * 3) / 16000
    x = (0.2 * torch.sin(2 * np.pi * 180 * t) + 0.01 * torch.randn_like(t))[None]
    path = out / "rmvpe.onnx"
    with torch.no_grad():
        want = wrapper(x)
        torch.onnx.export(wrapper, (x,), str(path), input_names=["audio"], output_names=["salience"], opset_version=OPSET,
                          dynamic_axes={"audio": {1: "samples"}, "salience": {1: "frames"}}, dynamo=False)
        check("rmvpe", want, path, {"audio": x}, 1e-3)


# ---------------------------------------------------------------- one voice

class Encoder(torch.nn.Module):
    """enc_p + flow of SynthesizerTrnMs768NSFsid.infer, speaker 0, with the z noise as an input."""

    def __init__(self, net_g):
        super().__init__()
        self.net_g = net_g

    def forward(self, phone, pitch, noise):
        g = self.net_g.emb_g(torch.zeros(1, dtype=torch.long)).unsqueeze(-1)
        lengths = torch.full((1,), phone.shape[1], dtype=torch.long)
        m_p, logs_p, x_mask = self.net_g.enc_p(phone, pitch, lengths)
        z_p = (m_p + torch.exp(logs_p) * noise * 0.66666) * x_mask
        return self.net_g.flow(z_p, x_mask, g=g, reverse=True)


class Decoder(torch.nn.Module):
    """GeneratorNSF without its sine generator: the app passes the excitation in. SineGen's float32 phase drifts
    over long inputs, and built outside it is float64 and exact; it also keeps random ops out of the model."""

    def __init__(self, net_g):
        super().__init__()
        self.net_g = net_g

    def forward(self, z, sine):
        dec = self.net_g.dec
        g = self.net_g.emb_g(torch.zeros(1, dtype=torch.long, device=z.device)).unsqueeze(-1)
        har_source = dec.m_source.l_tanh(dec.m_source.l_linear(sine)).transpose(1, 2)
        x = dec.conv_pre(z) + dec.cond(g)
        for i, (ups, noise_convs) in enumerate(zip(dec.ups, dec.noise_convs)):
            x = F.leaky_relu(x, dec.lrelu_slope)
            x = ups(x) + noise_convs(har_source)
            xs = None
            for j in range(i * dec.num_kernels, (i + 1) * dec.num_kernels):
                xs = dec.resblocks[j](x) if xs is None else xs + dec.resblocks[j](x)
            x = xs / dec.num_kernels
        return torch.tanh(dec.conv_post(F.leaky_relu(x)))


SPLIT = 3  # dec_a runs the upsampling stages before this one


class DecoderA(Decoder):
    """The decoder's first SPLIT upsampling stages (with conv_pre): z, excitation -> hidden."""

    def forward(self, z, sine):
        dec = self.net_g.dec
        g = self.net_g.emb_g(torch.zeros(1, dtype=torch.long)).unsqueeze(-1)
        har_source = dec.m_source.l_tanh(dec.m_source.l_linear(sine)).transpose(1, 2)
        x = dec.conv_pre(z) + dec.cond(g)
        for i in range(SPLIT):
            x = self.stage(dec, i, x, har_source)
        return x

    @staticmethod
    def stage(dec, i, x, har_source):
        x = F.leaky_relu(x, dec.lrelu_slope)
        x = dec.ups[i](x) + dec.noise_convs[i](har_source)
        xs = None
        for j in range(i * dec.num_kernels, (i + 1) * dec.num_kernels):
            xs = dec.resblocks[j](x) if xs is None else xs + dec.resblocks[j](x)
        return xs / dec.num_kernels


class DecoderB(Decoder):
    """The rest: hidden, excitation -> audio."""

    def forward(self, hidden, sine):
        dec = self.net_g.dec
        har_source = dec.m_source.l_tanh(dec.m_source.l_linear(sine)).transpose(1, 2)
        x = hidden
        for i in range(SPLIT, len(dec.ups)):
            x = DecoderA.stage(dec, i, x, har_source)
        return torch.tanh(dec.conv_post(F.leaky_relu(x)))


def load_voice(path):
    from rvc.models import SynthesizerTrnMs768NSFsid
    cpt = torch.load(str(path), map_location="cpu", weights_only=True)
    if "weight" not in cpt or "config" not in cpt:
        raise SystemExit(f"{path.name}: not an RVC voice model (a training checkpoint? use the small .pth from weights/)")
    if cpt.get("version", "v1") != "v2" or cpt.get("f0", 1) != 1 or cpt["config"][-1] != 48000:
        raise SystemExit(f"{path.name}: version {cpt.get('version', 'v1')}, {cpt['config'][-1]} Hz, pitch {cpt.get('f0', 1)}; "
                         "only RVC v2 48 kHz models with pitch are supported for now")
    cpt["config"][-3] = cpt["weight"]["emb_g.weight"].shape[0]
    net_g = SynthesizerTrnMs768NSFsid(*cpt["config"], is_half=False)
    del net_g.enc_q
    net_g.load_state_dict(cpt["weight"], strict=False)
    net_g.remove_weight_norm()
    return net_g.float().eval().requires_grad_(False)


def traceable_attention():
    """RVC's relative-position attention turns lengths into Python ints (int(), max()), which tracing freezes to the
    example's length: the exported encoder then fails on any other length. These versions keep every length a traced
    size. They assume length > window_size + 1 (12 frames, 0.12 s), true for any real input."""
    from rvc.attentions import MultiHeadAttention

    def get_relative_embeddings(self, relative_embeddings, length):
        pad = length - (self.window_size + 1)
        return F.pad(relative_embeddings, [0, 0, pad, pad, 0, 0])[:, : 2 * length - 1]

    def relative_to_absolute(self, x):
        batch, heads, length, _ = x.size()
        x = F.pad(x, [0, 1, 0, 0, 0, 0, 0, 0]).reshape(batch, heads, length * 2 * length)
        x = F.pad(x, [0, length - 1, 0, 0, 0, 0])
        return x.reshape(batch, heads, length + 1, 2 * length - 1)[:, :, :length, length - 1:]

    def absolute_to_relative(self, x):
        batch, heads, length, _ = x.size()
        x = F.pad(x, [0, length - 1, 0, 0, 0, 0, 0, 0]).reshape(batch, heads, length * length + length * (length - 1))
        x = F.pad(x, [length, 0, 0, 0, 0, 0])
        return x.reshape(batch, heads, length, 2 * length)[:, :, :, 1:]

    MultiHeadAttention._get_relative_embeddings = get_relative_embeddings
    MultiHeadAttention._relative_position_to_absolute_position = relative_to_absolute
    MultiHeadAttention._absolute_position_to_relative_position = absolute_to_relative


def export_voice(out, name, net_g):
    traceable_attention()
    T = 300
    phone, pitch, noise = torch.randn(1, T, 768), torch.randint(1, 255, (1, T)), torch.randn(1, 192, T)
    enc, dec = Encoder(net_g).eval(), Decoder(net_g).eval()
    path = out / f"{name}.enc.onnx"
    with torch.no_grad():
        torch.onnx.export(enc, (phone, pitch, noise), str(path), input_names=["phone", "pitch", "noise"], output_names=["z"],
                          opset_version=OPSET, dynamic_axes={"phone": {1: "T"}, "pitch": {1: "T"}, "noise": {2: "T"}, "z": {2: "T"}},
                          dynamo=False)
        z = enc(phone, pitch, noise)
        check(f"{name}.enc", z, path, {"phone": phone, "pitch": pitch, "noise": noise}, 1e-3)
        for t in (37, 1702):  # other lengths than the traced one
            feeds = {"phone": torch.randn(1, t, 768), "pitch": torch.randint(1, 255, (1, t)), "noise": torch.randn(1, 192, t)}
            check(f"{name}.enc T={t}", enc(**feeds), path, feeds, 1e-3)
    sine = torch.randn(1, T * 480, 1) * 0.1
    path = out / f"{name}.dec.onnx"
    with torch.no_grad():
        torch.onnx.export(dec, (z, sine), str(path), input_names=["z", "sine"], output_names=["audio"], opset_version=OPSET,
                          dynamic_axes={"z": {2: "n"}, "sine": {1: "samples"}, "audio": {2: "samples"}}, dynamo=False)
        check(f"{name}.dec", dec(z, sine), path, {"z": z, "sine": sine}, 1e-3)
        z2, sine2 = torch.randn(1, 192, 57), torch.randn(1, 57 * 480, 1) * 0.1
        check(f"{name}.dec n=57", dec(z2, sine2), path, {"z": z2, "sine": sine2}, 1e-3)
    export_split(out, name, net_g, z, sine)
    return path


def export_split(out, name, net_g, z, sine):
    """The decoder in two pieces; the pieces chained must give the whole decoder's output."""
    dec_a, dec_b = DecoderA(net_g).eval(), DecoderB(net_g).eval()
    path_a, path_b = out / f"{name}.dec_a.onnx", out / f"{name}.dec_b.onnx"
    with torch.no_grad():
        hidden = dec_a(z, sine)
        torch.onnx.export(dec_a, (z, sine), str(path_a), input_names=["z", "sine"], output_names=["hidden"],
                          opset_version=OPSET, dynamic_axes={"z": {2: "n"}, "sine": {1: "samples"}, "hidden": {2: "m"}},
                          dynamo=False)
        check(f"{name}.dec_a", hidden, path_a, {"z": z, "sine": sine}, 1e-3)
        torch.onnx.export(dec_b, (hidden, sine), str(path_b), input_names=["hidden", "sine"], output_names=["audio"],
                          opset_version=OPSET, dynamic_axes={"hidden": {2: "m"}, "sine": {1: "samples"}, "audio": {2: "samples"}},
                          dynamo=False)
        whole = Decoder(net_g).eval()(z, sine)
        check(f"{name}.dec_a + dec_b vs dec", whole, path_b, {"hidden": hidden, "sine": sine}, 1e-4)


def npu_decoder(args, folder, name, dec):
    """NAME.dec.w8a16.onnx, calibrated on the recordings; HuBERT and RMVPE come from out/common (made if missing)."""
    import npu_export
    if not npu_export.audio_files(args.calib):
        print("\nno NPU decoder: put a few minutes of voice recordings (WAV) into "
              f"{Path(args.calib[0])} and convert again (Android's NPU then sounds like the 32-bit decoder)")
        return
    common = args.common_dir or args.out / "common"
    if not ((common / "hubert.onnx").is_file() and (common / "rmvpe.onnx").is_file()):
        print("\nthe NPU decoder's calibration needs HuBERT and RMVPE: building the shared models first", flush=True)
        common.mkdir(exist_ok=True)
        export_hubert(common)
        export_rmvpe(common)
    npu_export.export(dec, folder / f"{name}.enc.onnx", common, args.calib)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("voice", nargs="?", type=Path, help="RVC v2 48 kHz voice model (.pth)")
    parser.add_argument("--name", help="name in the app (default: the file name)")
    parser.add_argument("--out", type=Path, default=HERE / "out", help="output folder (default: out/ next to this script)")
    parser.add_argument("--common", action="store_true", help="also (or only) build hubert.onnx and rmvpe.onnx")
    parser.add_argument("--no-ncnn", action="store_true", help="skip the Android GPU decoder")
    parser.add_argument("--calib", nargs="+", default=[str(HERE / "calib")],
                        help="recordings (WAV files or folders) to calibrate the NPU decoder on (default: calib/)")
    parser.add_argument("--no-npu", action="store_true", help="skip the NPU decoder (NAME.dec.w8a16.onnx)")
    parser.add_argument("--common-dir", type=Path, help="reuse shared models from this folder for NPU calibration")
    parser.add_argument("--progress-events", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    def stage(name):
        if args.progress_events:
            print('@rvc2phone ' + json.dumps({'stage': name}), flush=True)
    if not args.voice and not args.common:
        parser.error("give a voice model (.pth), --common, or both")
    sys.path.insert(0, str(HERE))
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    # scratch files (pnnx, quantization: up to a few GB) next to the output, not on the system drive, which may be full
    import tempfile
    tempfile.tempdir = str(args.out / "tmp")
    Path(tempfile.tempdir).mkdir(exist_ok=True)
    if args.common:
        common = args.out / "common"
        common.mkdir(exist_ok=True)
        stage('hubert')
        export_hubert(common)
        stage('rmvpe')
        export_rmvpe(common)
        from htp_export import fuse
        stage('hubert_npu')
        fuse(common / "hubert.onnx", common / "hubert.htp.onnx")
        print(f"\nshared models: {common} (put hubert.onnx, hubert.htp.onnx and rmvpe.onnx into the app's common folder)")
    if args.voice:
        name = args.name or args.voice.stem
        from workflow import safe_name
        name = safe_name(name)
        folder = args.out / name
        folder.mkdir(exist_ok=True)
        stage('onnx')
        dec = export_voice(folder, name, load_voice(args.voice))
        (folder / "settings.json").write_text(json.dumps({"pitch": 0, "formant": 0}, indent=1), encoding="utf-8")
        if not args.no_ncnn:
            stage('gpu')
            try:
                from make_ncnn import convert
                convert(dec)
            except FileNotFoundError as e:
                print(f"no Android GPU decoder ({e}); the app then decodes on the CPU. pip install pnnx to make it.")
        if not args.no_npu:
            stage('npu')
            npu_decoder(args, folder, name, dec)
        stage('archive')
        archive = args.out / f"{name}.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_STORED) as z:
            for f in sorted(folder.iterdir()):
                z.write(f, f"{name}/{f.name}")
        print(f"\nvoice: {folder}\n       {archive} (import either in the app)")


if __name__ == "__main__":
    main()
