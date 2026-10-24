import json
import os
from pathlib import Path

import pytest

import fixtures as fx
import spark_fixture as sf
import analyze_spark
import diff_captures
from sneakydiag.diff import compare, render_diff
from sneakydiag.loader import load_capture
from sneakydiag.spark import analyze_profile
from sneakydiag.sparkwire import ProfileError, read_profile


# ------------------------------------------------------------------ spark

@pytest.fixture()
def profile(tmp_path):
    return sf.build_profile(tmp_path / "profile.sparkprofile")


def test_wire_decoder_reads_metadata_windows_and_tree(profile):
    p = read_profile(profile)
    assert p.metadata.interval_micros == 4000 and p.metadata.engine == "ASYNC" and p.metadata.mode == "EXECUTION"
    assert p.metadata.platform == "Paper"
    assert p.time_windows == [10, 11] and p.window_stats[11].players == 31
    assert p.thread_names == ["Server thread", "Worker-1"]
    assert len(p.thread.classes) == 6 and p.thread.roots == [0]
    assert list(p.thread.times[2]) == [50, 100] and p.thread.refs[1] == [2, 3]


def test_metadata_only_read_skips_stacks(profile):
    p = read_profile(profile, thread=None)
    assert p.thread is None and p.time_windows == [10, 11]


def test_truncated_or_oversized_profiles_are_refused(profile, tmp_path):
    cut = tmp_path / "cut.sparkprofile"
    cut.write_bytes(profile.read_bytes()[:-30])
    with pytest.raises(ProfileError):
        read_profile(cut)
    with pytest.raises(ProfileError, match="limit"):
        read_profile(profile, max_bytes=10)
    junk = tmp_path / "junk.sparkprofile"
    junk.write_bytes(b"\xff" * 64)
    with pytest.raises(ProfileError):
        read_profile(junk)


def test_prefix_attribution_counts_first_matching_frame_once(profile):
    r = analyze_profile(read_profile(profile), prefixes=["com.acme.", "net.minecraft."], matches=["force"],
                        windows=1, aggregate=False, top=5, report=None)
    (sel,) = r["selections"]
    acme = sel["prefixes"]["com.acme."]
    assert sel["windowIds"] == [11] and sel["threadSampledMillis"] == 200
    # run (100) contains recurse (60): only the outer frame counts for the inclusive share
    assert acme["firstFrameMillis"] == 100 and acme["pctOfThread"] == 50.0
    assert acme["selfMillis"] == 60 and acme["matchedFrames"] == 2  # self: run 40 + recurse 20
    assert sel["prefixes"]["net.minecraft."]["firstFrameMillis"] == 180
    assert sel["anyPrefix"]["firstFrameMillis"] == 180  # union, no double counting
    (path,) = sel["targetedPaths"]
    assert path["millis"] == 40 and path["path"][-1].endswith("FileChannelImpl.force")
    assert [m["method"] for m in sel["inclusiveMethods"]][:2] == ["java.lang.Thread.run", "net.minecraft.Server.tick"]
    own = {m["method"]: m["millis"] for m in sel["selfMethods"]}
    assert own["com.acme.Plugin.run"] == 40 and own["net.minecraft.Chunk.update"] == 80


def test_unmatched_prefix_is_warned(profile):
    r = analyze_profile(read_profile(profile), prefixes=["org.nothing."], matches=[], windows=1, aggregate=False,
                        top=5, report=None)
    assert any("org.nothing." in w for w in r["warnings"])


def test_ownership_and_overlap_from_spark_json(profile, tmp_path):
    report = {"captureStartedAt": "2026-01-01T12:00:00.000000000Z", "finishedAfterMillis": 5000, "owned": False,
              "coverage": "shared_history", "complete": True}
    r = analyze_profile(read_profile(profile), prefixes=[], matches=[], windows=2, aggregate=False, top=3, report=report)
    assert not r["ownership"]["dedicatedSession"] and "NOT a dedicated" in r["ownership"]["note"]
    first, last = r["selections"]
    assert first["overlapWithCapture"]["seconds"] == 5.0 and last["overlapWithCapture"]["seconds"] == 0.0
    r2 = analyze_profile(read_profile(profile), prefixes=[], matches=[], windows=1, aggregate=False, top=3, report=None)
    assert not r2["ownership"]["available"]
    agg = analyze_profile(read_profile(profile), prefixes=["com.acme."], matches=[], windows=1, aggregate=True, top=3,
                          report=None)["selections"][0]
    assert agg["threadSampledMillis"] == 300 and agg["prefixes"]["com.acme."]["firstFrameMillis"] == 150


def test_analyze_spark_cli(profile, tmp_path, capsys):
    out = tmp_path / "s.json"
    assert analyze_spark.main([str(profile), "--prefix", "com.acme.", "--match", "force", "--json", str(out)]) == 0
    text = capsys.readouterr().out
    assert "prefix com.acme.: first-frame 100.0 ms = 50.0%" in text and "OUTERMOST" in text
    assert json.loads(out.read_text())["selections"][0]["prefixes"]["com.acme."]["matchedFrames"] == 2
    assert analyze_spark.main([str(tmp_path / "nope.sparkprofile")]) == 2


# ------------------------------------------------------------------ diff

def cap(tmp_path, name, **kw):
    return load_capture(fx.v2_capture(tmp_path, name, **kw))


def test_diff_matches_sections_by_name_and_flags_added_removed(tmp_path):
    before = cap(tmp_path, "b", probes={"Alpha": fx.probe({"tick": (100, 40.0, 60.0), "gone": (5, 2.0, 2.0)})})
    after = cap(tmp_path, "a", probes={"Alpha": fx.probe({"tick": (100, 10.0, 30.0), "new": (5, 3.0, 3.0)})},
                capture_id="01900000-0000-7000-8000-000000000002")
    d = compare(before, after)
    rows = {r["name"]: r for r in d["sections"]["top"]}
    assert rows["tick"]["status"] == "changed" and rows["tick"]["selfDeltaMsPerSec"] == -6.0  # (10-40)/5s
    assert rows["gone"]["status"] == "removed" and rows["new"]["status"] == "added"
    assert d["sections"]["added"] == 1 and d["sections"]["removed"] == 1
    assert d["comparable"]
    assert not [w for w in d["warnings"] if w["severity"] == "blocker"]


def test_diff_warns_loudly_when_workload_differs(tmp_path):
    before = cap(tmp_path, "b", players=30, entity_total=1000)
    after = cap(tmp_path, "a", players=45, entity_total=1000, capture_id="01900000-0000-7000-8000-000000000002")
    d = compare(before, after)
    assert not d["comparable"]
    assert any("WORKLOAD DIFFERS: players 30 -> 45" in w["text"] for w in d["warnings"])
    text = render_diff(d)
    assert "MAY NOT BE VALID" in text and "!!! WORKLOAD DIFFERS" in text
    assert not any("entities" in w["text"] and "DIFFERS" in w["text"] for w in d["warnings"])  # within tolerance


def test_diff_warns_when_workload_unknown(tmp_path):
    before = cap(tmp_path, "b", with_workload=False)
    after = cap(tmp_path, "a", with_workload=True, capture_id="01900000-0000-7000-8000-000000000002")
    d = compare(before, after)
    assert any("WORKLOAD UNKNOWN for before" in w["text"] for w in d["warnings"])


def test_diff_warns_on_incomplete_provider_and_same_capture(tmp_path):
    before = cap(tmp_path, "b", provider_complete=False)
    after = cap(tmp_path, "a")
    d = compare(before, after)
    assert any("before: provider Alpha is INCOMPLETE" in w["text"] for w in d["warnings"])
    assert any("same capture" in w["text"] for w in d["warnings"])  # fixture ids are identical here
    assert not d["comparable"]


def test_diff_levels_counts_timeline_and_instrumentation_change(tmp_path):
    lv_a = {"placed": {"samples": 5, "mean": 9.0, "min": 1, "max": 12, "last": 9}}
    before = cap(tmp_path, "b")
    after = cap(tmp_path, "a", probes={"Alpha": fx.probe({"tick": (100, 40.0, 60.0)}, levels=lv_a)},
                capture_id="01900000-0000-7000-8000-000000000002", instrumentation="alpha-2")
    d = compare(before, after)
    placed = next(r for r in d["levels"] if r["level"] == "placed")
    assert placed["meanBefore"] == 3.0 and placed["meanAfter"] == 9.0
    assert any(r["status"] == "removed" and r["level"] == "entities.owned" for r in d["levels"])
    assert any("instrumentation version changed" in w["text"] for w in d["warnings"])
    assert {r["metric"] for r in d["timeline"]} >= {"observed ticks/s", "tick mean ms"}


def test_diff_v1_pair_and_cli(tmp_path, capsys):
    b = fx.v1_capture(tmp_path, "b1")
    a = fx.v1_capture(tmp_path, "a1")
    out = tmp_path / "d.json"
    assert diff_captures.main([str(b), str(a), "--json", str(out)]) == 0
    text = capsys.readouterr().out
    assert "WORKLOAD UNKNOWN" in text and "MagicSpells" in text
    assert json.loads(out.read_text())["comparable"] is False
    assert diff_captures.main([str(b), str(tmp_path / "missing")]) == 2


def test_diff_uses_spark_as_labelled_fallback_workload(tmp_path):
    d1 = fx.v1_capture(tmp_path, "b1")
    sf.build_profile(d1 / "profile.sparkprofile")
    d2 = fx.v1_capture(tmp_path, "a1")
    sf.build_profile(d2 / "profile.sparkprofile")
    res = compare(load_capture(d1), load_capture(d2))
    assert any("approximate" in w["text"] for w in res["warnings"])
    assert not any("WORKLOAD UNKNOWN" in w["text"] for w in res["warnings"])


# ------------------------------------------------------------------ opt-in real data

RAW = os.environ.get("SNEAKYDIAG_RAW")


@pytest.mark.skipif(not RAW or not Path(RAW).is_dir(), reason="set SNEAKYDIAG_RAW to a real capture directory")
def test_real_capture_smoke():
    from sneakydiag.analysis import Options, analyze
    r = analyze(load_capture(Path(RAW)), Options())
    assert r["captureId"] and r["inventory"]["files"]
