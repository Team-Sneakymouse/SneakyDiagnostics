import fixtures as fx
from sneakydiag.analysis import Options, analyze
from sneakydiag.loader import load_capture
from sneakydiag.render import render


def run(path, **kw):
    return analyze(load_capture(path), Options(**kw))


def test_tracker_groups_are_ranked_with_metadata_split_and_aggregated(tmp_path):
    t = run(fx.v2_capture(tmp_path, packets_report=fx.packets()))["tracker"]
    assert t["available"] and t["problems"] == [] and t["coverageFlags"] == []
    assert t["totalMs"] == 16.0 and t["metadataMs"] == 5.0 and t["otherMs"] == 11.0
    assert t["pctOfObservedWall"] == 0.32
    first = t["topGroups"][0]
    assert (first["entityType"], first["owner"]) == ("PLAYER", "class:net.citizensnpcs.EntityHumanNPC")
    assert first["callsPerEntity"] == 100.0 and first["meanUs"] == 45.0 and first["p99MsUpper"] == 0.008
    assert [r["entityType"] for r in t["byType"]] == ["PLAYER", "ITEM_DISPLAY", "ZOMBIE"]
    assert t["peakWindow"] == {"window": 2, "ms": 6.4, "medianMs": 2.4}


def test_tracker_window_shows_its_share_of_tick_time_and_largest_groups(tmp_path):
    r = run(fx.v2_capture(tmp_path, packets_report=fx.packets()))
    w = next(w for w in r["correlation"]["windows"] if w["index"] == 2)
    assert w["tracker"]["ms"] == 6.4
    assert w["tracker"]["pctOfWindowTickTime"] == round(100 * 6.4 / (6 * 160.0), 2)
    assert w["tracker"]["groups"][0]["entityType"] == "PLAYER"
    assert "tracker sends: 6.4 ms" in render(r)


def test_missing_companion_is_unmeasured_not_zero(tmp_path):
    r = run(fx.v2_capture(tmp_path, packets_report=fx.packets(available=False)))
    assert r["tracker"]["available"] is False
    out = render(r)
    assert "unmeasured, not zero" in out
    assert "Tracker:" not in out.split("Leads")[1]


def test_capture_without_packets_report_says_so_and_lists_it_not_captured(tmp_path):
    r = run(fx.v2_capture(tmp_path))
    assert r["tracker"] is None
    assert "packets" in r["inventory"]["collectorsNotCaptured"]
    assert "not recorded (no packets collector" in render(r)


def test_incomplete_tracker_and_inconsistent_sums_are_flagged(tmp_path):
    rep = fx.packets(overflowEntities=3, complete=False, calls=9999)
    t = run(fx.v2_capture(tmp_path, packets_report=rep))["tracker"]
    assert "overflowEntities=3" in t["coverageFlags"] and "complete=false" in t["coverageFlags"]
    assert "group sums differ from the tracker totals" in t["problems"]
