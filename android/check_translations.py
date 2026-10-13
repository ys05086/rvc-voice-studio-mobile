"""Validate resource completeness and format arguments without an Android device."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent / "app/src/main"
FORMAT = re.compile(r"%(?:(\d+)\$)?[-+0 #]*\d*(?:\.\d+)?([sdf])")


def strings(folder):
    entries = [entry for path in sorted((ROOT / "res" / folder).glob("*.xml"))
               for entry in ET.parse(path).getroot().findall("string")]
    result = {e.attrib["name"]: "".join(e.itertext()) for e in entries}
    assert len(entries) == len(result), f"Duplicate keys in {folder}"
    assert all(result.values()), f"Empty translation in {folder}"
    return result


def arguments(value):
    found = FORMAT.findall(value.replace("%%", ""))
    if len(found) > 1:
        assert all(index for index, _ in found), f"Unnumbered format arguments: {value}"
    return sorted((index or "1", kind) for index, kind in found)


english, korean = strings("values"), strings("values-ko")
assert english.keys() == korean.keys(), f"Missing translations: {english.keys() ^ korean.keys()}"
for key in english:
    assert arguments(english[key]) == arguments(korean[key]), f"Format mismatch: {key}"
    assert not re.search("[가-힣]", english[key]), f"Untranslated English resource: {key}"

for path in ROOT.rglob("*"):
    if path.suffix not in (".kt", ".xml"):
        continue
    text = path.read_text(encoding="utf-8")
    for key in re.findall(r"(?<!android\.)R\.string\.(\w+)|@string/(\w+)", text):
        name = key[0] or key[1]
        assert name in english, f"Missing resource {name} in {path.name}"

print(f"English/Korean: {len(english)} complete resources; format arguments and references valid")
