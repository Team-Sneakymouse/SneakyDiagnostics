"""Packet sections of packets-1.json: outbound classes, send causes, tracker owners, inbound, windows and diffs."""
import fixtures as fx
from sneakydiag.analysis import Options, analyze
from sneakydiag.diff import compare, render_diff
from sneakydiag.loader import load_capture, packets
from sneakydiag.render import render


def run(path, **kw):
    return analyze(load_capture(path), Options(**kw))


def test_loader_normalizes_every_section(tmp_path):
    p = packets(load_capture(fx.v2_capture(tmp_path, packets_report=fx.packets())))
    assert p["availability"] == "Ignite packets available, idle"
    assert p["outbound"]["serverThreadNanos"] == 13_000_000 and len(p["outbound"]["classes"]) == 5
    assert set(p["outbound"]["series"]["classes"]) == {
        "net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Pos",
        "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket",
        "net.minecraft.network.protocol.game.ClientboundBundlePacket"}
    other = next(r for r in p["causes"]["rows"] if r["cause"] == "other")
    assert other["broadcasts"] is None, "other has no call site; missing is not zero"
    assert p["trackerCauses"]["groups"][0]["causes"]["tracker.metadata"]["totalNanos"] == 4_000_000
    assert p["inbound"]["series"]["classes"]["net.minecraft.network.protocol.game.ServerboundUseItemPacket"]["nanos"][2] == 16_000_000


def test_three_rankings_classes_causes_with_fan_out_and_tracker_owners(tmp_path):
    pk = run(fx.v2_capture(tmp_path, packets_report=fx.packets()))["packets"]
    assert pk["available"]
    ob = pk["outbound"]
    assert ob["problems"] == [] and ob["serverThreadMs"] == 13.0 and ob["pctOfObservedWall"] == 0.26
    first = ob["topClasses"][0]
    assert first["packetClass"] == "game.ClientboundMoveEntityPacket$Pos", "prefix stripped, ranked by send time"
    assert first["sendsPerSec"] == 1200.0 and first["meanUs"] == 1.0 and first["p99MsUpper"] == 0.001
    keep_alive = next(r for r in ob["topClasses"] if r["packetClass"] == "common.ClientboundKeepAlivePacket")
    assert keep_alive["otherThreadSends"] == 150 and keep_alive["meanUs"] is None
    causes = pk["causes"]["rows"]
    assert [c["cause"] for c in causes[:3]] == ["tracker.update", "tracker.metadata", "tracker.spawn"]
    assert causes[0]["fanOut"] == 2.0 and causes[0]["sharePct"] == 46.15 and causes[0]["usPerBroadcast"] == 2.0
    despawn = next(c for c in causes if c["cause"] == "tracker.despawn")
    assert despawn["broadcasts"] == 20 and despawn["fanOut"] == 0.0
    owners = pk["trackerOwners"]
    g = owners["topGroups"][0]
    assert (g["entityType"], g["owner"]) == ("ITEM_DISPLAY", "plugin:SneakyWartime")
    assert g["sharePct"] == 61.54 and g["metadataMs"] == 4.0 and g["updateMs"] == 4.0 and g["fanOut"] == 1.33
    assert owners["byOwner"][1]["owner"] == "(player)" and owners["spawnsAttributed"] == 100
    ib = pk["inbound"]
    assert ib["topClasses"][0]["packetClass"] == "game.ServerboundUseItemPacket"
    assert ib["topClasses"][0]["sharePct"] == 88.89 and ib["betweenTicksMs"] == 0.5


def test_relations_tie_sends_to_the_tracker_section_and_phases(tmp_path):
    r = run(fx.v2_capture(tmp_path, packets_report=fx.packets(), phases=fx.phases_report(),
                          timeline_edit=fx.with_current_ticks(0)))
    rel = r["packets"]["relations"]
    assert rel["trackerSendChangesSendMs"] == 10.0 and rel["trackerSectionMs"] == 16.0
    assert rel["sendShareOfTrackerSectionPct"] == 62.5 and rel["trackerPairingSendMs"] == 3.0
    assert rel["trackerPhaseMs"] is not None and rel["outboundPctOfMeasuredTicks"] is not None
    assert "INCLUSIVE" in rel["note"] and "EXCLUSIVE level.<world>.tracker" in rel["note"]
    text = render(r)
    section = text[text.index("Packets (packets-1.json"):]
    assert "relations: outbound send 13.0 ms" in section and "of 16.0 ms Tracker sends (62.5%)" in section


def test_window_correlation_shows_packet_send_time_per_slow_second(tmp_path):
    r = run(fx.v2_capture(tmp_path, packets_report=fx.packets()))
    w = next(w for w in r["correlation"]["windows"] if w["index"] == 2)
    pw = w["packets"]
    assert pw["ms"] == 3.4 and pw["sends"] == 2020 and pw["inboundMs"] == 17.0
    assert pw["pctOfWindowTickTime"] == round(100 * 3.4 / (6 * 160.0), 2)
    assert pw["classes"][0]["packetClass"] == "game.ClientboundSetEntityDataPacket"
    assert pw["causes"][0]["cause"] == "tracker.metadata"
    lift = r["correlation"]["lift"]
    assert any(s["plugin"] == "[packet sends]" and s["section"] == "tracker.metadata" for s in lift["sections"])
    assert "packet sends: 3.4 ms" in render(r)


def test_render_shows_rankings_and_leads(tmp_path):
    text = render(run(fx.v2_capture(tmp_path, packets_report=fx.packets())))
    section = text[text.index("Packets (packets-1.json"):text.index("Entity census")]
    assert "packet classes by server-thread send time" in section
    assert "send causes by send time (fan-out" in section
    assert "tracker causes by entity type x owner" in section and "plugin:SneakyWartime" in section
    assert "inbound: 5,500 packets" in section
    leads = text.split("Leads")[1]
    assert "Packet sends: cause 'tracker.update'" in leads and "Inbound packets: game.ServerboundUseItemPacket" in leads


def test_tracker_only_companion_reports_packet_sections_as_unmeasured(tmp_path):
    r = run(fx.v2_capture(tmp_path, packets_report=fx.packets(sections=False)))
    assert r["tracker"]["available"] and r["packets"]["available"] is False
    out = render(r)
    assert "Packet counts and send time are unmeasured, not zero" in out
    assert "Packet sends:" not in out.split("Leads")[1]
    w = next(w for w in r["correlation"]["windows"] if w["index"] == 2)
    assert w["packets"] is None


def test_capture_without_packets_report_says_not_recorded(tmp_path):
    r = run(fx.v2_capture(tmp_path))
    assert r["packets"] is None
    assert "packet counts and send time are absent, not zero" in render(r)


def test_missing_companion_is_unmeasured(tmp_path):
    r = run(fx.v2_capture(tmp_path, packets_report=fx.packets(available=False)))
    assert r["packets"]["available"] is False


def test_incomplete_sections_are_flagged(tmp_path):
    rep = fx.packets()
    rep["outbound"] = {**rep["outbound"], "classOverflow": True, "complete": False, "nestedSends": 5, "serverThreadSends": 1}
    pk = run(fx.v2_capture(tmp_path, packets_report=rep))["packets"]
    assert pk["outbound"]["classOverflow"] and "class sends differ from the outbound total" in pk["outbound"]["problems"]
    text = render(run(fx.v2_capture(tmp_path / "again", packets_report=rep)))
    assert "GAP classOverflow" in text and "5 sends nested in other sends" in text


def test_diff_compares_classes_and_causes_per_second(tmp_path):
    before = load_capture(fx.v2_capture(tmp_path, "before", packets_report=fx.packets()))
    faster = fx.packets()
    faster["causes"]["rows"][1] = {**faster["causes"]["rows"][1], "totalNanos": 1_000_000, "broadcasts": 1000}
    faster["outbound"]["classes"][1] = {**faster["outbound"]["classes"][1], "totalNanos": 1_000_000}
    after = load_capture(fx.v2_capture(tmp_path, "after", packets_report=faster, capture_id="01900000-0000-7000-8000-000000000002"))
    d = compare(before, after)
    cause = next(r for r in d["packetCauses"]["top"] if r["name"] == "tracker.metadata")
    assert cause["totalMsPerSecBefore"] == 0.8 and cause["totalMsPerSecAfter"] == 0.2 and cause["totalDeltaMsPerSec"] == -0.6
    assert cause["fanOutBefore"] == 1.0 and cause["fanOutAfter"] == 4.0
    cls = d["packetClasses"]["top"][0]
    assert cls["name"] == "game.ClientboundSetEntityDataPacket" and cls["totalDeltaMsPerSec"] == -0.6
    assert d["inboundClasses"]["matched"] == 2
    text = render_diff(d)
    assert "Packet send causes" in text and "Outbound packet classes" in text and "Inbound packet classes" in text


def test_diff_without_packet_sections_says_not_compared(tmp_path):
    before = load_capture(fx.v2_capture(tmp_path, "before", packets_report=fx.packets(sections=False)))
    after = load_capture(fx.v2_capture(tmp_path, "after", packets_report=fx.packets(),
                                       capture_id="01900000-0000-7000-8000-000000000002"))
    d = compare(before, after)
    assert d["packetClasses"] is None and "not compared" in d["packetClassesNote"]
