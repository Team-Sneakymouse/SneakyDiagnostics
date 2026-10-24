import gzip
import json

import fixtures as fx
from sneakydiag.loader import load_capture


def test_v2_loads_manifest_gz_and_provider_files(tmp_path):
    cap = load_capture(fx.v2_capture(tmp_path, gz=("timeline", "scheduler")))
    assert cap.schema_version == 2
    assert cap.warnings == []
    assert (tmp_path / "v2" / "timeline-1.json.gz").exists()  # compressed on disk, read transparently
    assert cap.report("timeline")["windows"]
    assert cap.selected == "all"
    (prov,) = cap.providers
    assert prov.plugin == "Alpha" and prov.is_probe and prov.measurements["sections"]
    assert all(f.status == "ok" for f in cap.files if f.in_manifest)


def test_v1_embedded_providers_normalize_the_same_way(tmp_path):
    cap = load_capture(fx.v1_capture(tmp_path))
    assert cap.schema_version == 1 and cap.manifest is None
    assert cap.warnings == []
    assert [p.plugin for p in cap.providers] == ["MagicSpells", "SneakyConfigurableVoice"]
    assert all(p.measurements is not None and not p.is_probe for p in cap.providers)
    assert all(f.status == "unverified" for f in cap.files)


def test_hash_mismatch_is_reported_but_file_still_loads(tmp_path):
    d = fx.v2_capture(tmp_path)
    tl = d / "timeline-1.json.gz"
    p = d / "scheduler-1.json"
    doc = json.loads(p.read_text())
    doc["errors"] = 99  # same size is not required; hash must differ
    p.write_text(json.dumps(doc))
    cap = load_capture(d)
    assert any("scheduler-1.json" in w and "mismatch" in w for w in cap.warnings)
    assert next(f for f in cap.files if f.name == "scheduler-1.json").status in ("hash_mismatch", "size_mismatch")
    assert cap.report("scheduler") is not None
    assert tl.name  # silence unused


def test_missing_listed_file_is_a_warning_and_provider_is_unavailable_not_zero(tmp_path):
    d = fx.v2_capture(tmp_path)
    (d / "plugins-alpha-1.json").unlink()
    cap = load_capture(d)
    assert any("plugins-alpha-1.json" in w and "missing" in w for w in cap.warnings)
    (prov,) = cap.providers
    assert prov.measurements is None and prov.measurements_status == "missing"


def test_unlisted_file_and_orphan_provider_file_warn(tmp_path):
    d = fx.v2_capture(tmp_path)
    (d / "plugins-ghost-1.json").write_text(json.dumps(fx.header("plugins", 2, plugin="Ghost")))
    cap = load_capture(d)
    assert any("plugins-ghost-1.json" in w and "not listed" in w for w in cap.warnings)
    assert any("not referenced" in w for w in cap.warnings)


def test_size_limit_stops_gzip_bombs(tmp_path):
    d = fx.v1_capture(tmp_path)
    (d / "timeline-1.json").unlink()
    (d / "timeline-1.json.gz").write_bytes(gzip.compress(b'{"windows": [' + b"0," * 2_000_000 + b"0]}"))
    cap = load_capture(d, max_bytes=100_000)
    assert cap.report("timeline") is None
    assert any("timeline-1.json.gz" in w and "limit" in w for w in cap.warnings)


def test_corrupt_and_nonfinite_json_do_not_crash(tmp_path):
    d = fx.v1_capture(tmp_path)
    (d / "scheduler-1.json").write_text("{not json")
    (d / "entities-1.json").write_text('{"entityCount": NaN}')
    cap = load_capture(d)
    assert cap.report("scheduler") is None and cap.report("entities") is None
    assert sum("not loaded" in w for w in cap.warnings) == 2


def test_truncated_gzip_is_unreadable_warning(tmp_path):
    d = fx.v1_capture(tmp_path)
    data = gzip.compress(json.dumps(fx.timeline(1)).encode())
    (d / "timeline-1.json").unlink()
    (d / "timeline-1.json.gz").write_bytes(data[: len(data) // 2])
    cap = load_capture(d)
    assert cap.report("timeline") is None and any("timeline-1.json.gz" in w for w in cap.warnings)


def test_capture_id_mismatch_and_not_a_directory(tmp_path):
    d = fx.v2_capture(tmp_path)
    p = d / "scheduler-1.json"
    doc = json.loads(p.read_text())
    doc["captureId"] = "other"
    p.write_text(json.dumps(doc))
    assert any("different captureIds" in w for w in load_capture(d, verify=False).warnings)
    try:
        load_capture(tmp_path / "nope")
    except NotADirectoryError:
        pass
    else:
        raise AssertionError("expected NotADirectoryError")
