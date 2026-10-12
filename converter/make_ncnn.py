"""Convert a voice's decoder (<name>.dec.onnx from rvc2phone.py) for the Android app's GPU path:
<name>.dec.ncnn.param and <name>.dec.ncnn.bin, run by ncnn on Vulkan in 32 bits. rvc2phone.py calls this itself.

  python make_ncnn.py out/<name>/<name>.dec.onnx [--check]

Steps:
  1. pnnx converts the ONNX decoder at the app's fixed piece size (200 frames; fp32 weights).
  2. The source's 1x1 MatMul (m_source.l_linear), which ncnn has no layer for, becomes a scalar multiply.
  3. Every Convolution1D/Deconvolution1D becomes the 2D layer with height 1, and the inputs are reshaped to
     (w, 1, c): ncnn's Vulkan 2D convolutions are ~1.5x faster here, with the same weights and results.
--check runs the result on the PC (ncnn on the CPU) against ONNX Runtime on random inputs.
Needs pnnx (pip install pnnx); --check also needs ncnn (pip install ncnn)."""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper
from model_format import read_geometry

FRAMES = 200  # Rvc.kt DEC_FRAMES


def linear_weight(model):
    """The scalar weight of the source's 1x1 MatMul (the only MatMul in the decoder)."""
    inits = {i.name: numpy_helper.to_array(i) for i in model.graph.initializer}
    for n in model.graph.node:
        if n.op_type == "MatMul":
            for name in n.input:
                if name in inits and inits[name].size == 1:
                    return float(inits[name].ravel()[0])
    raise SystemExit("no 1x1 MatMul found: not a decoder from export_onnx.py?")


def params(fields):
    return dict(f.split("=", 1) for f in fields)


def to_2d(text, weight):
    lines = text.strip().split("\n")
    out = []
    for line in lines[2:]:
        parts = line.split()
        kind, name, nin, nout = parts[0], parts[1], int(parts[2]), int(parts[3])
        blobs = parts[4:4 + nin + nout]
        p = params(parts[4 + nin + nout:])
        if kind == "torch.matmul":  # x @ [[w]] -> x * w
            kind, p = "BinaryOp", {"0": "2", "1": "1", "2": repr(weight)}
        elif kind in ("Convolution1D", "Deconvolution1D"):
            q = {"0": p["0"], "1": p["1"], "11": "1", "2": p.get("2", "1"), "12": "1", "3": p.get("3", "1"), "13": "1",
                 "4": p.get("4", "0"), "15": p.get("15", p.get("4", "0")), "14": "0", "16": "0"}
            if kind == "Deconvolution1D":
                q.update({"18": p.get("18", "0"), "19": "0"})
            q.update({"5": p.get("5", "0"), "6": p["6"]})
            for k in ("9", "-23310"):
                if k in p:
                    q[k] = p[k]
            kind, p = kind[:-2], q
        elif kind == "MemoryData":  # a per-channel constant: [c] -> (1, 1, c)
            p = {"0": "1", "1": "1", "2": p["1"]}
        out.append(" ".join([f"{kind:<24}", f"{name:<24}", str(nin), str(nout)] + blobs + [f"{k}={v}" for k, v in p.items()]))
    text = "\n".join(out)
    # z [w, h=192] -> (w, 1, 192); the source after its permute [w, 1] -> (w, 1, 1)
    text = re.sub(r"^(Convolution\s+\S+\s+1 1 )in0 ", r"\1in0r ", text, flags=re.M)
    text = text.replace("Input                    in0                      0 1 in0",
                        "Input                    in0                      0 1 in0\n"
                        f"Reshape                  reshape_in0              1 1 in0 in0r 0={FRAMES} 1=1 2=192", 1)
    text, n = re.subn(r"^(Permute\s+\S+\s+1 1 \S+ )(\S+)( 0=1)$",
                      r"\g<1>\g<2>_p\3\nReshape                  reshape_sine             1 1 \g<2>_p \g<2> 0=-1 1=1 2=1",
                      text, count=1, flags=re.M)
    if n != 1 or "in0r" not in text:
        raise SystemExit("unexpected decoder graph: the reshape points were not found")
    layers, blobs = map(int, lines[1].split())
    body = text.split("\n")
    return f"{lines[0]}\n{len(body)} {blobs + 2}\n" + "\n".join(body) + "\n"


def check(onnx_path, param, binary):
    import ncnn
    import onnxruntime as ort
    rng = np.random.default_rng(0)
    z = rng.standard_normal((1, 192, FRAMES)).astype(np.float32)
    spec = read_geometry(Path(onnx_path).parent)
    t = np.arange(FRAMES * spec["hop_length"]) / spec["sample_rate"]
    sine = (0.1 * np.sin(2 * np.pi * 180 * t) + 0.003 * rng.standard_normal(t.size)).astype(np.float32)[None, :, None]
    ref = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"]).run(None, {"z": z, "sine": sine})[0].ravel()
    net = ncnn.Net()
    net.opt.use_vulkan_compute = False
    net.opt.use_fp16_packed = net.opt.use_fp16_storage = net.opt.use_fp16_arithmetic = False
    net.opt.use_bf16_storage = False
    net.load_param(str(param))
    net.load_model(str(binary))
    mz, ms = ncnn.Mat(np.ascontiguousarray(z[0])).clone(), ncnn.Mat(np.ascontiguousarray(sine[0])).clone()
    ex = net.create_extractor()
    ex.input("in0", mz)
    ex.input("in1", ms)
    _, out = ex.extract("out0")
    y = np.array(out).ravel().copy()
    del ex
    err = y - ref
    snr = 10 * np.log10((ref ** 2).sum() / max((err ** 2).sum(), 1e-30))
    print(f"check: ncnn vs ONNX Runtime {snr:.1f} dB (exact is > 80)")
    return snr


def pnnx_tool():
    """pnnx from the running Python's environment (pip install pnnx puts it next to python), else from PATH."""
    here = Path(sys.executable).parent
    for folder in (here, here / "Scripts", here / "bin"):
        exe = folder / ("pnnx.exe" if sys.platform == "win32" else "pnnx")
        if exe.is_file():
            return str(exe)
    found = shutil.which("pnnx")
    if not found:
        raise FileNotFoundError("pnnx is not installed")
    return found


def convert(src, verify=False):
    """<name>.dec.onnx -> <name>.dec.ncnn.param/.bin next to it."""
    src = Path(src).resolve()
    if not src.name.endswith(".dec.onnx"):
        raise SystemExit("expected <name>.dec.onnx")
    stem = src.name[: -len(".dec.onnx")]
    hop = read_geometry(src.parent)["hop_length"]
    weight = linear_weight(onnx.load(str(src)))
    pnnx = pnnx_tool()
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "dec.onnx"
        shutil.copy(src, work)
        run = subprocess.run([pnnx, str(work), f"inputshape=[1,192,{FRAMES}],[1,{FRAMES * hop},1]", "fp16=0"],
                             cwd=tmp, capture_output=True, text=True, errors="replace")
        if run.returncode != 0:  # pnnx is chatty: its log only matters when it fails
            print(run.stdout[-3000:], run.stderr[-3000:], sep="\n")
            raise SystemExit(f"pnnx failed (exit {run.returncode})")
        text = (Path(tmp) / "dec.ncnn.param").read_text()
        param = src.with_name(f"{stem}.dec.ncnn.param")
        binary = src.with_name(f"{stem}.dec.ncnn.bin")
        param.write_text(to_2d(text, weight))
        shutil.copy(Path(tmp) / "dec.ncnn.bin", binary)
    print(f"wrote {param.name} and {binary.name} ({binary.stat().st_size / 1e6:.0f} MB)")
    if verify and check(src, param, binary) < 80:
        raise SystemExit("the converted decoder does not match")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("decoder", type=Path, help="<name>.dec.onnx")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    convert(args.decoder, verify=args.check)


if __name__ == "__main__":
    main()
