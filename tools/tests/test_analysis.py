import json

import fixtures as fx
from sneakydiag.analysis import Options, analyze
from sneakydiag.loader import load_capture
from sneakydiag.render import render
import analyze_capture


def run(path, **kw):
    return analyze(load_capture(path), Options(**kw))


def test_timeline_headline_numbers(tmp_path):
    t = run(fx.v2_capture(tmp_path))["timeline"]
    assert t["ticks"] == 4 * 20 + 6
    assert t["over50"] == 6 and t["over100"] == 3
    assert t["maxMs"] == 400.0
    assert t["observedTicksPerSecond"] == round(86 / 5, 2)
    assert t["gapWindows"] == []


def test_timeline_gap_is_reported_not_zero_filled(tmp_path):
    d = fx.v1_capture(tmp_path)
    doc = json.loads((d / "timeline-1.json").read_text())
    del doc["windows"][3]
    (d / "timeline-1.json").write_text(json.dumps(doc))
    t = run(d)["timeline"]
    assert t["gapWindows"] == [3]
    assert "EMPTY windows" in render(run(d))


def test_probe_rankings_separate_self_from_inclusive(tmp_path):
    probes = {"Alpha": fx.probe({"outer": (10, 5.0, 100.0), "inner": (10, 50.0, 50.0), "other": (3, 20.0, 20.0)})}
    p = run(fx.v2_capture(tmp_path, probes=probes))["providers"][0]["probe"]
    assert [r["name"] for r in p["topBySelf"]] == ["inner", "other", "outer"]
    assert [r["name"] for r in p["topByTotalInclusive"]] == ["outer", "inner", "other"]
    assert p["instrumentedSelfMs"] == 75.0  # additive self time, not the inclusive sum
    assert p["topBySelf"][0]["p95MsUpperBound"] == 0.524
    assert p["ownedEntities"].keys() == {"entities.owned", "entities.owned.item_display"}
    assert p["hasSeries"] and p["slowest"][0]["atTick"] == 142


def test_incomplete_provider_reasons_come_from_its_fields(tmp_path):
    probes = {"Alpha": fx.probe({"tick": (1, 1.0, 1.0)}, omitted=3)}
    r = run(fx.v2_capture(tmp_path, probes=probes, provider_complete=False))
    (inc,) = r["inventory"]["incompleteProviders"]
    assert inc["plugin"] == "Alpha" and any("omittedKeys=3" in w for w in inc["why"])
    assert r["inventory"]["indexIncompleteProviders"] == ["Alpha"]
    assert "INCOMPLETE PROVIDERS" in render(r)


def test_missing_provider_file_reports_unavailable(tmp_path):
    d = fx.v2_capture(tmp_path)
    (d / "plugins-alpha-1.json").unlink()
    r = run(d)
    assert r["providers"][0]["kind"] == "unavailable"
    text = render(r)
    assert "not zero cost" in text and any("missing" in w for w in r["warnings"])


def test_no_providers_note(tmp_path):
    r = run(fx.v1_capture(tmp_path, providers=[]))
    assert "no plugin measurements, not zero plugin cost" in r["inventory"]["noProvidersNote"]


def test_magicspells_recovery_groups_mixed_case_and_refuses_incomplete_detail(tmp_path):
    ok = run(fx.v1_capture(tmp_path / "a"))["providers"][0]["magicspells"]
    assert ok["recovery"]["valid"]
    top = ok["recovery"]["topBySelf"][0]
    assert top["spell"] == "fire-ball" and top["selfMs"] == 10.0 and top["calls"] == 15
    assert ok["recovery"]["mixedCaseRows"] == 1
    bad_prov = [{"plugin": "MagicSpells", "started": True, "reason": "completed", "complete": False,
                 "measurements": fx.magicspells(detail_complete=False)}]
    bad = run(fx.v1_capture(tmp_path / "b", providers=bad_prov))["providers"][0]["magicspells"]
    assert not bad["recovery"]["valid"] and "detailComplete is not true" in bad["recovery"]["blockers"]
    assert "topBySelf" not in bad["recovery"]


def test_freeform_timers_are_inclusive_and_waiting_is_labelled(tmp_path):
    ff = run(fx.v1_capture(tmp_path))["providers"][1]["freeform"]
    paths = [t["path"] for t in ff["timersByTotalInclusive"]]
    assert any("queueDelay" in p and "waiting time" in p for p in paths)
    assert any(f.startswith("unstarted") for f in run(fx.v1_capture(tmp_path / "x"))["providers"][1]["flags"])


def test_scheduler_and_ignite_summary(tmp_path):
    s = run(fx.v2_capture(tmp_path))["scheduler"]
    assert s["pending"]["max"] == 905 and s["snapshotCount"] == 5
    assert s["pendingByPlugin"][0]["plugin"] == "Alpha" and s["pendingByPlugin"][0]["syncMax"] == 900
    row = s["ignite"]["byOwnerEvent"][0]
    assert row["owner"] == "Alpha" and row["totalMsInclusive"] == 50.0 and row["event"] == "execution"


def test_entities_census_and_coverage_gap(tmp_path):
    d = fx.v1_capture(tmp_path)
    doc = json.loads((d / "entities-1.json").read_text())
    doc["coverage"].append({"world": "nether", "snapshotAvailable": False, "snapshotChunks": -1, "unvisitedChunks": -1})
    (d / "entities-1.json").write_text(json.dumps(doc))
    e = run(d)["entities"]
    assert e["entityCount"] == 4 and e["typesByCount"] == {"MARKER": 2, "ITEM_DISPLAY": 2}
    assert e["ownerHintsByCount"] == {"pdc:alpha": 4}
    assert e["worldsNotReached"] == ["nether"] and "not zero" in e["coverageNote"]
    assert "entityId" not in json.dumps(e)  # no per-entity identifiers in output


def test_correlation_uses_series_and_states_missing_series(tmp_path):
    r = run(fx.v2_capture(tmp_path, extra_providers={"Beta": fx.probe({"x": (1, 1.0, 1.0)}, series=False)}))
    c = r["correlation"]
    assert c["slowWindowCount"] == 1 and c["windows"][0]["index"] == 2
    alpha = next(p for p in c["windows"][0]["providers"] if p["plugin"] == "Alpha")
    top = alpha["sections"][0]
    assert top["section"] == "tick" and top["inclusiveMs"] == 90.0 and top["xMedian"] == 90.0
    assert [n["plugin"] for n in c["providersWithoutSeries"]] == ["Beta"]
    assert "NO series" in c["statement"] and "Beta" in c["statement"]
    snap = c["windows"][0]["scheduler"]["snapshots"][0]
    assert snap["observedAfterMillis"] == 2200 and snap["largestSyncPendingChanges"][0]["plugin"] == "Alpha"
    assert c["lift"]["available"] and c["lift"]["sections"][0]["section"] == "tick"
    text = render(r)
    assert "cannot be attributed" in text and "not additive" in text


def test_correlation_shows_counts_and_levels_of_the_slow_window(tmp_path):
    r = run(fx.v2_capture(tmp_path))
    alpha = next(p for p in r["correlation"]["windows"][0]["providers"] if p["plugin"] == "Alpha")
    assert alpha["counts"] == [{"count": "energy.arrived", "n": 5.0, "windowsWithCount": 2}]
    assert alpha["levels"] == [{"level": "placed", "value": 5.0, "medianSample": 3.0, "xMedian": 1.67}]
    assert alpha["omittedKeySeries"] == {"counts": [], "levels": ["entities.owned"]}
    pr = next(p for p in r["providers"] if p["plugin"] == "Alpha")["probe"]
    assert pr["keySeries"] == {"counts": ["energy.arrived"], "levels": ["placed"],
                               "omitted": {"counts": [], "levels": ["entities.owned"]}}
    text = render(r)
    assert "counts this second: energy.arrived=5" in text and "placed=5 (median 3)" in text
    assert "left out for the report budget (1): entities.owned" in text


def test_reports_without_count_level_series_still_correlate(tmp_path):
    r = run(fx.v2_capture(tmp_path, probes={"Alpha": fx.probe({"tick": (100, 50.0, 90.0)}, key_series=False)}))
    alpha = next(p for p in r["correlation"]["windows"][0]["providers"] if p["plugin"] == "Alpha")
    assert alpha["counts"] is None and alpha["levels"] is None and alpha["sections"]
    assert next(p for p in r["providers"] if p["plugin"] == "Alpha")["probe"]["keySeries"] is None
    assert "counts this second" not in render(r)


def test_malformed_count_level_series_are_dropped_not_fatal():
    from sneakydiag.providers import key_series, level_at, level_median
    ks = key_series({"series": {
        "counts": {"a": {"windows": [None, "x", -1, 3600, True, 4], "n": [1, 1, 1, 1, 1, 2]}, "b": "junk"},
        "levels": {"l": {"from": ["bad", 0, 5, 9, 0], "to": [1, 10 ** 12, 4, 9, 2], "values": [1, 7, 7, "x", 3]}},
        "omitted": {"counts": 1, "levels": ["ok", 2]}}})
    assert ks["counts"] == {"a": {4: 2}}
    assert ks["levels"] == {"l": [(0, 2, 3)]}            # huge, reversed, non-numeric and non-integer runs are dropped
    assert ks["omitted"] == {"counts": [], "levels": ["ok"]}
    assert level_at([(0, 1, 3), (3, 3, 5)], 2) is None and level_at([(0, 1, 3), (3, 3, 5)], 3) == 5
    assert level_median([(0, 1, 3), (2, 2, 9)]) == 3 and level_median([(0, 0, 2), (1, 1, 4)]) == 3
    assert level_median([]) == 0.0


def test_probe_with_only_count_level_series_says_so(tmp_path):
    m = fx.probe({"tick": (1, 1.0, 1.0)})
    m["series"]["sections"] = {}
    text = render(run(fx.v2_capture(tmp_path, probes={"Alpha": m})))
    assert "no section series (only counts/levels" in text and "counts this second: energy.arrived=5" in text


def test_correlation_for_v1_freeform_says_no_series(tmp_path):
    c = run(fx.v1_capture(tmp_path))["correlation"]
    assert len(c["providersWithoutSeries"]) == 2 and "free-form" in c["providersWithoutSeries"][0]["why"]


def test_cli_prints_and_writes_json(tmp_path, capsys):
    d = fx.v2_capture(tmp_path)
    out = tmp_path / "out.json"
    assert analyze_capture.main([str(d), "--json", str(out), "--raw"]) == 0
    text = capsys.readouterr().out
    assert "Window correlation" in text and "schema v2" in text
    doc = json.loads(out.read_text())
    assert doc["captureId"] == fx.CAPTURE_ID and doc["providers"][0]["kind"] == "probe"
    assert analyze_capture.main([str(tmp_path / "missing")]) == 2


def test_failed_provider_without_measurements_is_unavailable_with_error(tmp_path):
    # Java omits `measurements` for a provider that failed and `error` when null.
    d = fx.v2_capture(tmp_path, extra_providers={"Broken": fx.probe({"x": (1, 1.0, 1.0)})}, failed_providers=("Broken",))
    cap = load_capture(d)
    assert cap.warnings == []
    broken = next(p for p in cap.providers if p.plugin == "Broken")
    assert broken.measurements is None and broken.measurements_status == "absent" and broken.error
    r = analyze(cap, Options())
    inc = next(p for p in r["inventory"]["incompleteProviders"] if p["plugin"] == "Broken")
    assert "provider did not start" in inc["why"] and any("start failed" in w for w in inc["why"])
    assert any(p["plugin"] == "Alpha" and p["kind"] == "probe" for p in r["providers"])


def test_manifest_complete_fallback_false_does_not_override_report_flags(tmp_path):
    d = fx.v2_capture(tmp_path)
    m = json.loads((d / "manifest.json").read_text())
    for f in m["files"]:
        f["complete"] = False  # Java falls back to false when a report carries no `complete`
    (d / "manifest.json").write_text(json.dumps(m))
    r = analyze(load_capture(d), Options())
    assert r["timeline"]["complete"] is True and r["warnings"] == []
