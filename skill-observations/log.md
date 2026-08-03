# Skill Observation Log

Observations captured during task-oriented work. Each entry identifies a
potential skill improvement or new skill opportunity.

**Status key:** OPEN = not yet actioned | ACTIONED = skill updated/created |
DECLINED = user decided not to pursue

---

## 2026-08-02 — FtyCamPro / NMCamera reverse-engineering repo bootstrap

### Observation 1: Negative network-scan results were treated as protocol evidence without ruling out link-layer isolation

**Status:** OPEN
**Date:** 2026-08-02
**Session context:** Bootstrapping a repo to reverse-engineer a Beken-based
"Mini DV" IP camera. The user supplied a scan summary (ARP resolves, ping
fails, full TCP scan finds zero open ports, UDP top-50 silent) and drew the
conclusion that the device must therefore use a proprietary cloud/P2P
protocol.
**Skill:** New skill candidate: `network-recon-triage`
**Type:** open-source
**Phase/Area:** Evidence interpretation / diagnostic ordering

**Issue:** The evidence pattern "ARP succeeds but ICMP and all unicast TCP
fail" is the textbook signature of AP/client isolation on the wireless
access point — a layer-2 forwarding policy, not a property of the target
device. Under isolation, every unicast scan result is meaningless because
no unicast packet ever reaches the target, so a scan producing "all closed
or filtered" carries no information about what the device is running. The
conclusion drawn (proprietary P2P) may well be correct, but it was reached
by treating an untestable measurement as a negative result. A second, more
subtle version of the same error: `nmap`'s UDP scan sends empty or
generic-payload probes, and connectionless services that only answer a
magic byte sequence are invisible to it by construction — "no response" is
the expected output for a working service, not evidence of absence.

**Suggested improvement:** Create a `network-recon-triage` skill that
front-loads a validity check before any interpretation of scan output. Core
rule: before concluding anything from a negative scan, establish that the
measurement path is live — confirm bidirectional reachability with a
positive control (a known-responsive host on the same AP/VLAN), and
distinguish "no service" from "no path" and from "no matching probe."
Include a signature table mapping observed evidence patterns to their most
common causes (ARP-yes/ICMP-no → client isolation or host firewall;
UDP silent → wrong probe payload rather than closed port; TCP
filtered-vs-closed → upstream policy vs host stack). Require the skill to
state its confidence and the disconfirming test for every conclusion.

**Principle:** A negative result is only evidence when the measurement was
capable of producing a positive one. Before interpreting silence, prove the
channel works — otherwise you are reading the properties of your own
instrument and attributing them to the target.

---

### Observation 2: Phased build request needs an explicit blocked-vs-unblocked split before work starts

**Status:** OPEN
**Date:** 2026-08-02
**Session context:** The user requested a seven-phase project (repo setup,
APK analysis, traffic capture, protocol identification, CLI proof of
concept, full Android app, tests and docs) in a single message, while
explicitly asking to "start by" generating structure and first steps.
Phases 2–5 all require artifacts the agent cannot obtain autonomously (the
vendor APK, a rooted or capture-capable phone, the physical camera).
**Skill:** New skill candidate: `phased-delivery-triage`
**Type:** open-source
**Phase/Area:** Scope negotiation at task start

**Issue:** When a multi-phase request mixes work that is fully autonomous
with work that is hard-blocked on user-supplied artifacts, the natural
failure modes are symmetrical and both bad: either the agent stops at the
first blocked phase and delivers far less than it could, or it fabricates
progress on blocked phases by writing speculative findings as though they
were results. The correct move is to partition the phases up front by what
gates them, build everything on the unblocked side to completion, and
convert each blocked phase into an executable artifact — a script, a
checklist, a template — that the user can run the moment they supply the
missing input.

**Suggested improvement:** Create a `phased-delivery-triage` skill that
runs a partition pass before any building: for each phase, name the
required inputs and mark it autonomous, user-gated, or environment-gated.
Deliver all autonomous phases fully. For every gated phase, deliver the
executable scaffold plus a single named artifact the user must provide to
unblock it. Prohibit writing speculative results into deliverable
locations; hypotheses belong in a clearly-labelled hypotheses document with
a stated confidence level and a disconfirming test, never in a findings
document.

**Principle:** In a phased request, the deliverable for a blocked phase is
the thing that makes the phase runnable, not a guess at its output. Partition
by what gates each phase before building, so that blocking on one input
never silently caps the scope of the whole delivery.

---

### Observation 3: A diagnostic tool encoded its own false premise and reported a confident wrong verdict

**Status:** OPEN
**Date:** 2026-08-03
**Session context:** Building `tools/isolation_check.py`, a script whose whole
purpose is to prevent premature conclusions from network scans. When first run
against the real camera it declared "AP / client isolation ... every scan result is
void" for a host that was answering ICMP pings in the very same output.
**Skill:** New skill candidate: `network-recon-triage`
**Type:** open-source
**Phase/Area:** Tool logic vs. the principle the tool enforces

**Issue:** The script's reachability verdict keyed on TCP RST alone
(`stack_responded = refused or accepted`), ignoring the ICMP result it had just
collected and printed. So a host that answered ICMP but (normally) stayed silent on
closed TCP ports was classified "NO RESPONSE" and then, with a control host behaving
the same way, "client isolation". The irony is exact: a tool written to stop people
reading their instrument's properties as the target's properties did that itself,
because one signal (ICMP) was gathered, displayed, and then not fed into the
decision. The fix was to make `stack_responded` a disjunction over *every* positive
signal (ICMP OR RST OR accepted), and to give the verdict distinct branches for
"TCP answered" vs "only ICMP answered".

**Suggested improvement:** In `network-recon-triage`, state as a rule: a
reachability decision must consume every signal the tool collected, not a
convenient subset; if a signal is worth displaying it is worth being in the
verdict. Add a worked example of the ICMP-yes/TCP-silent host, because it is the
common real case (embedded firmware and Android both drop rather than reset) and is
exactly where a RST-only rule produces a confident false negative. Include a
pre-ship check for any diagnostic: enumerate the positive signals it can observe,
then confirm each one can flip the verdict.

**Principle:** A diagnostic that gathers multiple signals must let any of them reach
the conclusion. Encoding a single-signal shortcut recreates, inside the tool, the
exact error the tool exists to catch — and a wrong answer delivered by an
authoritative-looking script is worse than no script, because it is trusted.

### Observation 4: Running the tooling against reality corrected two "known" facts that would have derailed the build

**Status:** OPEN
**Date:** 2026-08-03
**Session context:** The task supplied device facts as settled (camera at
`192.168.29.214`; scan implies proprietary/unreachable). Rather than build purely on
the brief, the tooling was executed against the live network as it was written.
**Skill:** New skill candidate: `network-recon-triage`
**Type:** open-source
**Phase/Area:** Trusting the brief vs. measuring

**Issue:** Two premises in the brief were stale or misleading. (1) The camera had
been reassigned by DHCP from `.214` to `.24`; a project built to talk to `.214`
would have failed against an empty address with no obvious cause. (2) The "all
ports closed/filtered, ping fails" summary had been read as "unreachable/proprietary
cloud", but live testing showed the device answers ICMP and is trivially reachable —
it simply has no TCP listeners, and it answers PPPP discovery on UDP 32108 with its
UID in cleartext. Both corrections came only from running the instruments against
the real target, not from reasoning about the brief. A bonus correction: the MAC
observed (`ae-6e-84-...`) has the locally-administered bit set, so the "Beken OUI"
provenance did not hold for this address and the vendor claim had to be demoted.

**Suggested improvement:** `network-recon-triage` should open with a "re-verify the
target exists and is who you think" step before any analysis: confirm the address
still resolves to the device (identify P2P cameras by UID, not IP, since DHCP
moves them), and re-derive vendor/identity from first-hand signals rather than
inherited notes. Treat every fact in the brief as a hypothesis with a one-command
check, especially addresses and prior scan conclusions.

**Principle:** Inherited "known facts" decay — DHCP leases lapse, scans get
misread, MACs randomise. When the instruments are already in hand, spending one
command to re-confirm each load-bearing premise against reality is far cheaper than
discovering mid-build that the foundation moved.
