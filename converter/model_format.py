"""Native decoder geometry shared by export, calibration and transfer."""

import json
import math
from pathlib import Path

SUPPORTED_RATES = (32000, 48000)


def sample_rate(value):
    rate = (
        {"32k": 32000, "48k": 48000}.get(value, value)
        if isinstance(value, str)
        else value
    )
    if type(rate) is not int or rate not in SUPPORTED_RATES:
        raise ValueError(f"Unsupported model sample rate: {value}")
    return rate


def geometry(rate=48000, hidden_hop=None):
    rate = sample_rate(rate)
    hop = rate // 100
    hidden_hop = hop // 2 if hidden_hop is None else hidden_hop
    if type(hidden_hop) is not int or hidden_hop <= 0 or hop % hidden_hop:
        raise ValueError("Invalid split decoder geometry")
    return dict(
        format_version=1,
        sample_rate=rate,
        hop_length=hop,
        split_hidden_hop=hidden_hop,
        min_android_version_code=5 if rate == 32000 else 2,
    )


def checkpoint_info(data):
    config = data.get("config", []) if isinstance(data, dict) else []
    if (
        not isinstance(config, (list, tuple))
        or len(config) != 18
        or "weight" not in data
    ):
        raise ValueError("Not an RVC inference checkpoint")
    raw_rate = config[-1]
    rate = (
        {"32k": 32000, "48k": 48000, "40k": 40000}.get(raw_rate, raw_rate)
        if isinstance(raw_rate, str)
        else raw_rate
    )
    supported = (
        data.get("version", "v1") == "v2"
        and data.get("f0", 1) == 1
        and rate in SUPPORTED_RATES
    )
    if supported:
        supported = (
            config[2] == 192
            and isinstance(config[12], (list, tuple))
            and len(config[12]) > 3
        )
        if supported:
            supported = (
                all(type(n) is int and n > 0 for n in config[12])
                and math.prod(config[12]) == rate // 100
            )
    return dict(
        version=data.get("version", "v1"),
        rate=rate,
        pitch=data.get("f0", 1),
        supported=supported,
    )


def read_geometry(folder):
    """Exports made before metadata existed are 48 kHz. Invalid metadata must not silently fall back."""
    path = Path(folder) / "model.json"
    if not path.exists():
        return geometry()
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("format_version") != 1:
        raise ValueError("Unsupported model.json format")
    if type(value.get("split_hidden_hop")) is not int:
        raise ValueError("Missing or invalid split decoder geometry")
    result = geometry(value.get("sample_rate"), value["split_hidden_hop"])
    if value.get("hop_length") != result["hop_length"]:
        raise ValueError("Model sample rate and hop length disagree")
    return result
