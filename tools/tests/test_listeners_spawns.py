"""Listener totals, spawn counts, census budget fields and their diff rows (synthetic fixtures only)."""
import fixtures as fx
from sneakydiag.analysis import Options, analyze
from sneakydiag.diff import compare, render_diff
from sneakydiag.loader import listener_totals, load_capture, spawns
from sneakydiag.render import render


def run(path, **kw):
    return analyze(load_capture(path), Options(**kw))


def full(tmp_path, name="v2", natural=False, **kw):
    return fx.v2_capture(tmp_path, name, slow=True, enabled=("Alpha", "Gamma", "Delta", "Shrine"),
                         scheduler_edit=fx.with_listener_totals, timeline_edit=fx.with_spawns(natural),
                         entities_edit=fx.census_budget, **kw)


def test_listeners_are_ranked_by_self_time_and_calls_are_accounted(tmp_path):
    r = run(full(tmp_path))
    li = r["listeners"]
    assert li["available"] and li["complete"] is True
    assert [row["owner"] for row in li["bySelf"]] == ["Delta", "Gamma", "Alpha"]
    delta = li["bySelf"][0]
    assert delta["selfMs"] == 45.0 and delta["totalMsInclusive"] == 60.0 and delta["event"] == "InventoryOpenEvent"
    assert delta["p99MsUpperBound"] == 0.131  # bucket 16 -> upper bound 2^17 ns
    assert li["tickEndSelfMs"] == 5.0
    assert li["accounting"]["difference"] == 0
    text = render(r)
    assert "Listeners (Ignite listener totals" in text and "OpenListener" in text


def test_listener_accounting_mismatch_is_called_out(tmp_path):
    def edit(doc):
        fx.with_listener_totals(doc)
        doc["ignite"]["slowListeners"]["observedInvocations"] = 5100
    r = run(fx.v2_capture(tmp_path, slow=True, scheduler_edit=edit))
    assert r["listeners"]["accounting"]["difference"] == 100
    assert "MISMATCH" in render(r)


def test_old_companion_has_no_listener_totals_and_says_so(tmp_path):
    r = run(fx.v2_capture(tmp_path, slow=True, enabled=("Alpha", "Gamma", "Delta")))
    assert r["listeners"]["available"] is False and "not recorded" in r["listeners"]["reason"]
    rows = {row["plugin"]: row for row in r["coverage"]["rows"]}
    assert rows["Delta"]["listenerSelfMs"] is None and rows["Delta"]["slowListenerMs"] == 40.0
    assert all(w.get("listeners") is None for w in r["correlation"]["windows"])


def test_coverage_audit_ranks_by_listener_self_time_when_totals_exist(tmp_path):
    r = run(full(tmp_path))
    rows = {row["plugin"]: row for row in r["coverage"]["rows"]}
    assert r["coverage"]["listenerTotals"] is True
    assert rows["Gamma"]["listenerSelfMs"] == 30.0 and rows["Gamma"]["listenerCalls"] == 4400
    # Gamma: 120 ms sync tasks + 30 ms listener self; Delta: 45 ms listener self (the 40 ms slow call is inside it)
    assert [m["plugin"] for m in r["coverage"]["missingProvidersByMeasuredTime"]] == ["Gamma", "Delta"]


def test_owner_listener_series_appear_in_the_slow_window_and_the_lift(tmp_path):
    r = run(full(tmp_path))
    (w,) = [w for w in r["correlation"]["windows"] if w["index"] == 2]
    owners = w["listeners"]["owners"]
    assert owners[0]["owner"] == "Delta" and owners[0]["selfMs"] == 40.0 and owners[0]["xMedian"] == 40.0
    assert w["listeners"]["selfMs"] == 47.0
    lift = {(row["plugin"], row["section"]) for row in r["correlation"]["lift"]["sections"]}
    assert ("Delta", "[listener self time]") in lift
    assert "Ignite listeners this second" in render(r)


def test_execution_groups_get_a_p99_from_their_histogram(tmp_path):
    r = run(full(tmp_path))
    groups = r["scheduler"]["ignite"]["topExecutionGroups"]
    assert all(g["p99MsUpperBound"] == 2.097 for g in groups)  # bucket 20 -> 2^21 ns
    old = run(fx.v2_capture(tmp_path, "old", slow=True))
    assert all(g["p99MsUpperBound"] is None for g in old["scheduler"]["ignite"]["topExecutionGroups"])


def test_natural_spawns_are_flagged_loudly(tmp_path):
    r = run(full(tmp_path, natural=True))
    s = r["spawns"]
    assert s["available"] and s["naturalFinding"].startswith("NATURAL-TYPE SPAWNS: 6 spawned and 1 cancelled")
    assert {row["reason"] for row in s["natural"]} == {"NATURAL", "CHUNK_GEN"}
    assert s["byPlugin"][0] == {"plugin": "Shrine", "spawned": 30, "cancelled": 0}
    text = render(r)
    assert "!!! NATURAL-TYPE SPAWNS" in text
    assert "Spawns: natural-type spawning is active" in text


def test_plugin_only_spawns_have_no_finding_and_old_timelines_say_not_recorded(tmp_path):
    r = run(full(tmp_path))
    assert r["spawns"]["naturalFinding"] is None and r["spawns"]["spawned"] == 43
    assert "no natural-type spawns" in render(r)
    old = run(fx.v2_capture(tmp_path, "old"))
    assert old["spawns"]["available"] is False and "not recorded" in old["spawns"]["reason"]


def test_census_budget_and_order_are_shown_in_the_inventory(tmp_path):
    r = run(full(tmp_path))
    (ent,) = [c for c in r["inventory"]["collectors"] if c["collector"] == "entities"]
    assert ent["detail"].startswith("census budget 50 ms per wall second") and "players-first" in ent["detail"]
    old = run(fx.v2_capture(tmp_path, "old"))
    (ent,) = [c for c in old["inventory"]["collectors"] if c["collector"] == "entities"]
    assert "2 ms per tick" in ent["detail"]


def test_loader_tolerates_malformed_additions(tmp_path):
    def bad_scheduler(doc):
        doc["ignite"]["listenerTotals"] = {"rows": [None, {"owner": "X", "calls": True, "histogramLog2": "x"}],
                                           "series": {"owners": {"X": {"totalNanos": [1], "selfNanos": [1, 2]}}}}
    def bad_timeline(doc):
        doc["spawns"] = {"rows": [{"reason": "NATURAL", "windows": [0, "1", -2], "n": [1, 1, 1]}, 7]}
    cap = load_capture(fx.v2_capture(tmp_path, scheduler_edit=bad_scheduler, timeline_edit=bad_timeline))
    lt = listener_totals(cap)
    assert lt["rows"][0]["calls"] == 0 and lt["rows"][0]["histogramLog2"] is None
    assert lt["series"]["owners"] == {}
    sp = spawns(cap)
    assert sp["rows"][0]["windows"] == {0: 1}
    r = analyze(cap, Options())
    assert r["spawns"]["naturalFinding"]


def test_diff_compares_listener_and_spawn_rows(tmp_path):
    before = load_capture(full(tmp_path, "before"))

    def louder(doc):
        fx.with_listener_totals(doc)
        doc["ignite"]["listenerTotals"]["rows"][0]["selfNanos"] = 95_000_000
    after = load_capture(fx.v2_capture(tmp_path, "after", slow=True, capture_id=fx.CAPTURE_ID[:-1] + "2",
                                       scheduler_edit=louder, timeline_edit=fx.with_spawns(True)))
    d = compare(before, after)
    top = d["listeners"]["top"][0]
    assert top["provider"] == "Delta" and top["name"] == "OpenListener on InventoryOpenEvent [NORMAL]"
    assert top["selfDeltaMsPerSec"] == 10.0  # (95 - 45) ms over 5 s
    reasons = {(row["provider"], row["count"]) for row in d["spawns"]}
    assert ("NATURAL", "ZOMBIE") in reasons and ("CUSTOM", "ZOMBIE by Shrine") in reasons
    assert any("after: natural-type spawns" in w["text"] for w in d["warnings"])
    text = render_diff(d)
    assert "Listeners (Ignite listener totals" in text and "Spawns (timeline" in text


def test_diff_with_an_older_capture_says_not_compared(tmp_path):
    d = compare(load_capture(fx.v2_capture(tmp_path, "old")),
                load_capture(full(tmp_path, "new", capture_id=fx.CAPTURE_ID[:-1] + "3")))
    assert d["listeners"] is None and "not recorded in before" in d["listenersNote"]
    assert d["spawns"] is None and "not zero" in d["spawnsNote"]
