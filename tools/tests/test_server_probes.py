"""Both Ignite server probes in one capture: tick phases (phases-1.json) and tracker sends (packets-1.json)."""
import fixtures as fx
from sneakydiag.analysis import Options, analyze
from sneakydiag.loader import load_capture
from sneakydiag.render import render


def run(path, **kw):
    return analyze(load_capture(path), Options(**kw))


def test_phases_and_tracker_sends_are_shown_side_by_side(tmp_path):
    r = run(fx.v2_capture(tmp_path, phases=fx.phases_report(), packets_report=fx.packets(),
                          timeline_edit=fx.with_current_ticks(0)))
    assert r["phases"]["available"] and r["tracker"]["available"]
    captured = {c["collector"] for c in r["inventory"]["collectors"]}
    assert {"phases", "packets"} <= captured
    assert not {"phases", "packets"} & set(r["inventory"]["collectorsNotCaptured"])
    # Window correlation shows both for the slow second.
    (w,) = [w for w in r["correlation"]["windows"] if w["index"] == 2]
    assert w["phases"]["ticks"] == 3 and w["phases"]["phases"][0]["phase"] == "level.world.main.entities"
    assert w["tracker"]["ms"] == 6.4
    text = render(r)
    assert text.index("Tick phases (exclusive time per tick phase)") < text.index("Tracker sends (packets-1.json)")
    window_text = text[text.index("tick phases this second (3 ticks)"):]
    assert "tracker sends: 6.4 ms" in window_text, "both appear in the same window block"
    leads = text.split("Leads")[1]
    assert "Tick phases:" in leads and "Tracker:" in leads


def test_capture_without_either_says_both_were_not_recorded(tmp_path):
    r = run(fx.v2_capture(tmp_path))
    assert r["phases"]["reason"].startswith("not recorded") and r["tracker"] is None
    assert {"phases", "packets"} <= set(r["inventory"]["collectorsNotCaptured"])
    text = render(r)
    phases = text[text.index("Tick phases (exclusive time per tick phase)"):]
    assert "  not recorded" in phases.split("Tracker sends (packets-1.json)")[0]
    assert "not recorded (no packets collector" in text[text.index("Tracker sends (packets-1.json)"):]
