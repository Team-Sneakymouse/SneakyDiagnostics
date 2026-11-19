"""Tick phases (phases-1.json): table, spawning finding, per-window breakdown and diff rows (synthetic fixtures only)."""
import fixtures as fx
from sneakydiag.analysis import Options, analyze
from sneakydiag.diff import compare, render_diff
from sneakydiag.loader import load_capture, phases, split_phase
from sneakydiag.render import render

OTHER_ID = fx.CAPTURE_ID[:-1] + "4"


def run(path, **kw):
    return analyze(load_capture(path), Options(**kw))


def capture(tmp_path, name="v2", exact=True, capture_id=fx.CAPTURE_ID, **kw):
    """A v2 capture with tick phases; ``exact`` gives the timeline current-tick ranges (placement by tick)."""
    return fx.v2_capture(tmp_path, name, phases=fx.phases_report(**kw), capture_id=capture_id,
                         timeline_edit=fx.with_current_ticks(0) if exact else None)


def test_phase_table_shares_and_other(tmp_path):
    r = run(capture(tmp_path))
    p = r["phases"]
    assert p["available"] and p["complete"] is True
    assert p["ticks"] == 15 and p["measuredTicks"] == 14 and p["measuredMs"] == 466.4
    top = p["inside"][0]
    assert top["phase"] == "level.world.main.entities" and top["world"] == "world.main"
    assert top["totalMs"] == 300.0 and top["pctOfMeasured"] == 62.18
    other = p["inside"][-1]
    assert other["phase"] == "other" and other["totalMs"] == 14.0 and "unknown for 1 ticks" in other["note"]
    assert p["consistency"] == {"checked": True, "ticks": 14, "mismatchedTicks": 0}
    assert p["diagnostics"] == {"insideMs": 7.5, "outsideMs": 3.0, "pctOfMeasured": 1.5}
    assert {row["phase"] for row in p["outside"]} == {"tick-end", "diagnostics"}
    assert any("ticks without a measured duration" in w and "stop interrupted" in w for w in p["why"])
    text = render(r)
    assert "Tick phases (exclusive time per tick phase)" in text and "level.world.main.tracker" in text
    assert "Tick phases: 'level.world.main.entities' takes the most measured tick time" in text


def test_mob_cap_bookkeeping_alone_is_a_finding(tmp_path):
    p = run(capture(tmp_path))["phases"]
    sp = p["spawning"]
    assert sp["finding"].startswith("NATURAL SPAWNING TIME: 22.5 ms")
    assert "only the mob-cap bookkeeping ran" in sp["finding"]
    assert sp["worlds"][0]["stateCalls"] == 15 and sp["worlds"][0]["spawnChunkCalls"] == 0


def test_actual_spawning_is_told_apart(tmp_path):
    r = run(capture(tmp_path, spawn_chunks=True))
    sp = r["phases"]["spawning"]
    assert "per-chunk spawning (spawnForChunk) 12.0 ms over 30 calls, 4 of which tried to spawn (5 entities attempted)" in sp["finding"]
    assert "natural spawning attempted to spawn entities" in sp["finding"]
    text = render(r)
    assert "!!! NATURAL SPAWNING TIME" in text and "Spawning: natural-spawning code ran" in text


def test_per_window_breakdown_by_current_tick(tmp_path):
    r = run(capture(tmp_path))
    (w,) = [w for w in r["correlation"]["windows"] if w["index"] == 2]
    ph = w["phases"]
    assert ph["ticks"] == 3 and ph["placedByOffset"] == 0 and ph["measuredMs"] == 217.8
    assert ph["phases"][0] == {"phase": "level.world.main.entities", "ms": 180.0, "pctOfMeasured": 82.64,
                               "medianWindowMs": 30.0, "xMedian": 6.0}
    lift = {(row["plugin"], row["section"]): row for row in r["correlation"]["lift"]["sections"]}
    assert lift[("[tick phase]", "level.world.main.entities")]["diffMs"] == 150.0
    assert "tick phases this second (3 ticks)" in render(r)


def test_per_window_breakdown_falls_back_to_start_offsets(tmp_path):
    r = run(capture(tmp_path, exact=False))
    (w,) = [w for w in r["correlation"]["windows"] if w["index"] == 2]
    assert w["phases"]["placedByOffset"] == 3 and w["phases"]["phases"][0]["ms"] == 180.0
    assert "3 placed by start offset" in render(r)


def test_missing_and_unavailable_phases_are_not_zero(tmp_path):
    old = run(fx.v2_capture(tmp_path, "old"))
    assert old["phases"] == {"available": False, "reason": old["phases"]["reason"]}
    assert old["phases"]["reason"].startswith("not recorded")
    assert "phases" in old["inventory"]["collectorsNotCaptured"]
    assert all(w.get("phases") is None for w in old["correlation"]["windows"])
    text = render(old)
    assert "Tick phases (exclusive time per tick phase)\n" in text and "  not recorded" in text
    off = run(fx.v2_capture(tmp_path, "off", phases=fx.phases_report(available=False)))
    assert off["phases"]["available"] is False and off["phases"]["reason"].startswith("unavailable")
    (inv,) = [c for c in off["inventory"]["collectors"] if c["collector"] == "phases"]
    assert inv["detail"].startswith("tick phases unavailable") and inv["complete"] is False
    assert all(w["phases"]["available"] is False for w in off["correlation"]["windows"])


def test_loader_drops_malformed_columns_and_splits_dotted_worlds(tmp_path):
    report = fx.phases_report()
    report["phases"]["scheduler"] = [1, 2]
    report["phases"]["connections"] = ["x"] * 15
    report["other"] = "nope"
    cap = load_capture(fx.v2_capture(tmp_path, phases=report))
    ph = phases(cap)
    assert set(ph["malformed"]) == {"scheduler", "connections"}
    assert ph["otherValid"] is False and ph["other"] == [None] * 15
    r = analyze(cap, Options())
    assert any("malformed phase lists ignored" in w for w in r["phases"]["why"])
    assert r["phases"]["measuredTicks"] == 0
    assert split_phase("level.world.main.spawning.state", ["world", "world.main"]) == ("world.main", "spawning.state")
    assert split_phase("level.world.main.spawning.state", []) == (None, "level.world.main.spawning.state")
    bad = fx.phases_report()
    bad["ticks"]["tick"] = [1, "2"]
    r = run(fx.v2_capture(tmp_path, "bad", phases=bad))
    assert r["phases"]["available"] is False and "no valid tick rows" in r["phases"]["reason"]


def test_diff_compares_phases_per_tick(tmp_path):
    before = load_capture(capture(tmp_path, "before"))
    after = load_capture(capture(tmp_path, "after", capture_id=OTHER_ID, entities_slow_us=120_000))
    d = compare(before, after)
    top = d["phases"]["top"][0]
    assert top["phase"] == "level.world.main.entities"
    assert (top["usPerTickBefore"], top["usPerTickAfter"], top["deltaUsPerTick"]) == (20_000.0, 32_000.0, 12_000.0)
    assert d["phases"]["meanMeasuredMsPerTick"]["before"] == 33.314
    text = render_diff(d)
    assert "Tick phases (exclusive" in text
    old = compare(load_capture(fx.v2_capture(tmp_path, "old")), after)
    assert old["phases"] is None and "tick phases not recorded in before" in old["phasesNote"]
