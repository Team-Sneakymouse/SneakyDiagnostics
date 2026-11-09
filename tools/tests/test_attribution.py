"""Slow-call placement, MagicSpells series/slow operations and the coverage audit (synthetic fixtures only)."""
import fixtures as fx
from sneakydiag.analysis import Options, analyze
from sneakydiag.loader import load_capture
from sneakydiag.render import render


def run(path, **kw):
    return analyze(load_capture(path), Options(**kw))


def slow_window(r):
    (w,) = [w for w in r["correlation"]["windows"] if w["index"] == 2]
    return w


def test_slow_calls_are_placed_by_tick_and_nested_calls_are_not_double_counted(tmp_path):
    r = run(fx.v2_capture(tmp_path, slow=True))
    sl = slow_window(r)["slowCalls"]
    labels = {c["label"] for c in sl["calls"]}
    assert "Gamma MenuTask" in labels and "Delta OpenListener on InventoryOpenEvent" in labels
    # the 40 ms listener runs inside the 100 ms task; the probe's 3 ms slowest call ends at 2100 ms (inside too)
    assert sl["serverThreadCoveredMs"] == 100.0
    assert sl["byTick"][0]["tick"] == 142
    assert sl["otherThreadCalls"] == 0
    # the async task (no tick) ends in window 3: it is placed by its end offset, so it is not in this window
    assert "Gamma SaveTask" not in labels
    assert "slow task and listener calls are listed per window" in r["correlation"]["igniteNote"]


def test_ignite_slow_sections_report_capacity_omissions_and_running_calls(tmp_path):
    r = run(fx.v2_capture(tmp_path, slow=True))
    ig = r["scheduler"]["ignite"]
    assert ig["slowExecutions"]["evicted"] == 1 and ig["slowExecutions"]["complete"] is False
    assert ig["slowListeners"]["observedInvocations"] == 5000
    text = render(r)
    assert "INCOMPLETE: shorter slow calls were left out" in text
    assert "still running at stop: 1" in text


def test_old_companion_says_not_recorded_rather_than_zero(tmp_path):
    r = run(fx.v2_capture(tmp_path, gamma=True, enabled=("Alpha", "Gamma")))
    assert r["scheduler"]["ignite"]["slowExecutions"]["available"] is False
    assert "predates slow-call records" in r["correlation"]["igniteNote"]
    (gamma,) = [row for row in r["coverage"]["rows"] if row["plugin"] == "Gamma"]
    assert gamma["slowListenerCalls"] is None and gamma["slowTaskCalls"] is None
    assert gamma["igniteSyncTaskMs"] == 120.0


def test_coverage_audit_ranks_missing_providers_and_never_calls_them_zero_cost(tmp_path):
    r = run(fx.v2_capture(tmp_path, slow=True, enabled=("Alpha", "Gamma", "Delta", "Quiet")))
    c = r["coverage"]
    assert c["enabledPlugins"] == 4
    rows = {row["plugin"]: row for row in c["rows"]}
    assert rows["Alpha"]["provider"] == "complete" and "sections" in rows["Alpha"]["measured"]
    assert rows["Quiet"]["provider"] == "none"
    missing = [m["plugin"] for m in c["missingProvidersByMeasuredTime"]]
    assert missing == ["Gamma", "Delta"]  # Quiet has no measured time, so it is not ranked as a gap
    assert rows["Delta"]["slowListenerCalls"] == 1 and rows["Delta"]["slowListenerMs"] == 40.0
    assert "UNMEASURED, not cheap" in c["note"]
    assert "Coverage audit" in render(r)


def test_magicspells_series_and_slow_operations_place_spell_time_in_windows(tmp_path):
    d = fx.v2_capture(tmp_path, slow=True, extra_providers={"MagicSpells": fx.magicspells_v4()})
    r = run(d)
    (ms,) = [p for p in r["providers"] if p["plugin"] == "MagicSpells"]
    m = ms["magicspells"]
    assert m["hasSeries"] and m["seriesOperations"] == ["event", "task"]
    assert m["slowOperations"]["top"][0]["spell"] == "crafting2"
    assert m["taskLifecycle"]["incompleteReasons"] == ["end_observed_after_deadline", "unattributed_owner_events"]
    kinds = {row["kind"] for row in m["taskLifecycle"]["unattributedByOrigin"]}
    assert kinds == {"core/manager", "spell code"}
    w = slow_window(r)
    (spells,) = w["magicSpells"]
    assert spells["outermostScopesMs"] == 80.0 and spells["operationsBySelf"][0]["operation"] == "task"
    assert any(c["source"] == "MagicSpells" and c["tick"] == 143 for c in w["slowCalls"]["calls"])
    assert "MagicSpells" not in {n["plugin"] for n in r["correlation"]["providersWithoutSeries"]}
    (row,) = [row for row in r["coverage"]["rows"] if row["plugin"] == "MagicSpells"]
    assert any(lim.startswith("MATCH_CALLS: unobserved") for lim in row["limits"])
    text = render(r)
    assert "counter MATCH_CALLS: unobserved" in text and "end inventory 39 ms after the deadline" in text


# ---------------------------------------------------------------- tick identity, input bounds, excluded phases


def _set_ticks(sc, offset):
    """A scheduler edit: shift every Ignite record's tick by ``offset``."""
    for section in ("slowExecutions", "slowListeners"):
        for r in sc["ignite"].get(section, {}).get("records", []):
            if "tick" in r:
                r["tick"] += offset


def test_records_are_matched_by_current_tick_never_by_tick_number(tmp_path):
    # After an empty-server pause the current tick (records) runs 9000 ahead of the tick number (timeline windows).
    r = run(fx.v2_capture(tmp_path / "a", slow=True, timeline_edit=fx.with_current_ticks(9000),
                          scheduler_edit=lambda sc: _set_ticks(sc, 9000)))
    assert r["correlation"]["tickMatching"]["available"] is True
    sl = slow_window(r)["slowCalls"]
    assert {c["label"] for c in sl["calls"]} >= {"Gamma MenuTask", "Delta OpenListener on InventoryOpenEvent"}
    ignite = [c for c in sl["calls"] if c["source"].startswith("Ignite")]
    assert all(c["tick"] == 9142 for c in ignite)

    # A record tick that only matches a window's tick NUMBER (142 in 140..159) must not be placed by it: shifted to end
    # in window 4, it is placed there by offset, so window 2 (slow) no longer shows it.
    def move(sc):
        for r_ in sc["ignite"]["slowExecutions"]["records"]:
            r_["startOffsetMicros"] += 2_000_000
            r_["endOffsetMicros"] += 2_000_000
    r2 = run(fx.v2_capture(tmp_path / "b", slow=True, timeline_edit=fx.with_current_ticks(9000), scheduler_edit=move))
    assert "Gamma MenuTask" not in {c["label"] for c in slow_window(r2)["slowCalls"]["calls"]}


def test_hostile_tick_ranges_cost_nothing_and_inconsistent_ranges_disable_matching(tmp_path):
    def huge(doc):
        fx.with_current_ticks(0)(doc)
        doc["windows"][0]["firstCurrentTick"] = 0
        doc["windows"][0]["lastCurrentTick"] = 10 ** 12  # would be a trillion dict entries if expanded
    import time
    t0 = time.monotonic()
    r = run(fx.v2_capture(tmp_path / "a", slow=True, timeline_edit=huge))
    assert time.monotonic() - t0 < 30
    # the huge first range overlaps the others: matching is refused rather than guessed
    assert r["correlation"]["tickMatching"]["available"] is False
    assert "inconsistent" in r["correlation"]["tickMatching"]["reason"]
    assert "tick matching unavailable" in render(r)

    def inverted(doc):
        fx.with_current_ticks(0)(doc)
        doc["windows"][1]["firstCurrentTick"], doc["windows"][1]["lastCurrentTick"] = 10, 5
    r = run(fx.v2_capture(tmp_path / "b", slow=True, timeline_edit=inverted))
    assert r["correlation"]["tickMatching"]["available"] is False

    def bools(doc):
        fx.with_current_ticks(0)(doc)
        doc["windows"][2]["firstCurrentTick"] = True  # JSON true is not a tick
    r = run(fx.v2_capture(tmp_path / "c", slow=True, timeline_edit=bools))
    assert r["correlation"]["tickMatching"]["available"] is False


def test_tick_end_listener_time_is_outside_the_tick_duration_percentage(tmp_path):
    # A 200 ms ServerTickEndEvent listener in tick 142, and a 50 ms probe section nested inside it, against a window
    # whose timeline tick time is 6 x 160 ms. The tick duration Paper reports excludes tick-end listeners.
    def add_tick_end(sc):
        sc["ignite"]["slowListeners"]["records"].append({
            "owner": "Epsilon", "listenerClass": "com.epsilon.TickEnd",
            "eventClass": "com.destroystokyo.paper.event.server.ServerTickEndEvent", "priority": "NORMAL",
            "asyncEvent": False, "startOffsetMicros": 2_500_000, "endOffsetMicros": 2_700_000, "nanos": 200_000_000,
            "thread": "server", "tick": 142, "throwing": False, "endedAfterDeadline": False})
    probes = {"Alpha": fx.probe({"tick": (100, 40.0, 60.0)})}
    probes["Alpha"]["slowest"] = [{"section": "tick", "nanos": 50_000_000, "atMillis": 2650, "atTick": 142}]
    r = run(fx.v2_capture(tmp_path, slow=True, probes=probes, scheduler_edit=add_tick_end))
    sl = slow_window(r)["slowCalls"]
    assert sl["tickEndListenerMs"] == 200.0
    assert sl["serverThreadCoveredMs"] == 100.0  # the task (with its nested listener); the nested probe call is excluded
    assert sl["exceedsTickTime"] is False
    (tick142,) = [t for t in sl["byTick"] if t["tick"] == 142]
    assert tick142["coveredMs"] == 100.0 and tick142["tickEndListenerMs"] == 200.0
    assert "ServerTickEndEvent listeners (after the tick duration was measured" in render(r)


def test_magicspells_late_scopes_are_kept_apart_and_placed_only_when_they_fit_one_window():
    from types import SimpleNamespace
    from sneakydiag.correlate import _spells_in_window
    from sneakydiag.providers import magicspells_series

    def series(last_end):
        m = fx.magicspells_v4()
        m["series"]["late"] = {"rootNanos": 300_000_000, "rootCalls": 1, "lastEndOffsetMillis": last_end,
                               "operations": {"task": {"calls": 1, "selfNanos": 300_000_000}}}
        return magicspells_series(m)

    window5 = {"index": 5, "fromMillis": 5000, "ticks": 1, "meanMspt": 400.0}
    fits = _spells_in_window(SimpleNamespace(plugin="MagicSpells"), series(5300), window5)
    assert fits["outermostScopesMs"] == 300.0 and fits["operationsBySelf"][0]["operation"] == "task"
    spans = _spells_in_window(SimpleNamespace(plugin="MagicSpells"), series(6200), window5)
    assert spans["outermostScopesMs"] is None and "span more than this window" in spans["note"]
    # a regular window is unaffected by the late bucket
    window2 = {"index": 2, "fromMillis": 2000, "ticks": 6, "meanMspt": 160.0}
    assert _spells_in_window(SimpleNamespace(plugin="MagicSpells"), series(5300), window2)["outermostScopesMs"] == 80.0


# ---------------------------------------------------------------- R1/R2: measured tick intervals, endpoint validation


def test_present_but_invalid_tick_endpoints_disable_matching_while_absent_ones_do_not():
    from sneakydiag.correlate import TickRanges
    valid = {"index": 0, "firstCurrentTick": 1, "lastCurrentTick": 5}
    for broken in ({"firstCurrentTick": True, "lastCurrentTick": False},   # both present, both booleans
                   {"firstCurrentTick": "6", "lastCurrentTick": "9"},      # both present, both strings
                   {"firstCurrentTick": 6, "lastCurrentTick": None},       # partial
                   {"firstCurrentTick": 6.0, "lastCurrentTick": 9}):       # not an integer
        assert TickRanges([valid, {"index": 1, **broken}]).status["available"] is False, broken
    absent = TickRanges([valid, {"index": 1}, {"index": 2, "firstCurrentTick": None, "lastCurrentTick": None}])
    assert absent.status["available"] is True and absent.window(3) == 0 and absent.window(7) is None


def _measured(ticks_and_spans):
    """A scheduler edit adding Ignite measuredTicks: [(tick, start_ms, end_ms), ...]."""
    def edit(sc):
        sc["ignite"]["measuredTicks"] = {
            "tick": [t for t, _, _ in ticks_and_spans],
            "startOffsetMicros": [int(a * 1000) for _, a, _ in ticks_and_spans],
            "endOffsetMicros": [int(b * 1000) for _, _, b in ticks_and_spans],
            "capacity": 200, "dropped": 0}
    return edit


# Window 2 has 6 ticks (current ticks 140..159 with offset 0); tick 142 was measured over 2000..2150 ms.
WINDOW2 = [(140, 1700.0, 1800.0), (141, 1850.0, 1990.0), (142, 2000.0, 2150.0),
           (143, 2200.0, 2300.0), (144, 2400.0, 2500.0), (145, 2600.0, 2700.0)]


def _probe_inside_tick_end():
    # a 0.5 ms provider call at 2155.5..2156 ms in tick 142: after the measured interval, inside its tick-end phase,
    # whose listener (< 1 ms, or evicted, or still running at stop) left no record
    probes = {"Alpha": fx.probe({"tick": (100, 40.0, 60.0)})}
    probes["Alpha"]["slowest"].append({"section": "tick.labels", "nanos": 500_000, "atMillis": 2156, "atTick": 142})
    return probes


def test_work_in_an_unrecorded_tick_end_listener_is_excluded_exactly_by_the_measured_interval(tmp_path):
    def edit(sc):
        _measured(WINDOW2)(sc)
    r = run(fx.v2_capture(tmp_path, slow=True, probes=_probe_inside_tick_end(),
                          timeline_edit=fx.with_current_ticks(0), scheduler_edit=edit))
    sl = slow_window(r)["slowCalls"]
    assert sl["coverageAssurance"] == "exact"
    assert sl["serverThreadCoveredMs"] == 100.0       # the task (listener and probe max nested in it); not the 0.5 ms
    assert sl["outsideMeasuredTickMs"] == 0.5
    assert sl["tickEndListenerMs"] == 0                # no tick-end listener record exists at all
    (t142,) = [t for t in sl["byTick"] if t["tick"] == 142]
    assert t142["assurance"] == "exact" and t142["coveredMs"] == 100.0
    assert "exact against the measured tick intervals" in render(r)


def test_without_all_measured_intervals_coverage_is_an_explicit_upper_bound(tmp_path):
    # no measuredTicks (old companion): the same 0.5 ms is counted, and the figure is labelled an upper bound
    r = run(fx.v2_capture(tmp_path / "a", slow=True, probes=_probe_inside_tick_end(),
                          timeline_edit=fx.with_current_ticks(0)))
    sl = slow_window(r)["slowCalls"]
    assert sl["coverageAssurance"] == "upper-bound" and sl["serverThreadCoveredMs"] == 100.5
    assert sl["outsideMeasuredTickMs"] is None
    assert "UPPER BOUND" in render(r)
    # one of the window's six ticks lacks an interval (dropped at capacity, say): not exact either
    r = run(fx.v2_capture(tmp_path / "b", slow=True, probes=_probe_inside_tick_end(),
                          timeline_edit=fx.with_current_ticks(0), scheduler_edit=_measured(WINDOW2[:-1])))
    assert slow_window(r)["slowCalls"]["coverageAssurance"] == "upper-bound"
    # inconsistent interval data (end before start) is not used at all
    bad = [(t, b, a) if t == 143 else (t, a, b) for t, a, b in WINDOW2]
    r = run(fx.v2_capture(tmp_path / "c", slow=True, probes=_probe_inside_tick_end(),
                          timeline_edit=fx.with_current_ticks(0), scheduler_edit=_measured(bad)))
    assert r["correlation"]["measuredTicks"]["available"] is False
    assert slow_window(r)["slowCalls"]["coverageAssurance"] == "upper-bound"
