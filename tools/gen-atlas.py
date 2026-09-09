#!/usr/bin/env python3
"""gen-atlas.py — regenerate LEARNING-PATH.html, the "Systems Atlas" page.

The page is a hand-designed HTML/CSS/JS view of the curriculum. Everything on
it that is data — the stages, the cards in curriculum order, the prerequisite
graph behind the trace/jump interactions, the load-bearing twelve, the
"learned before their foundations" list and the gaps — is derived from two
files, so the page can be rebuilt whenever they change:

    LEARNING-PATH.md   the order. `### Stage N — Title` headings (the first
                       paragraph under each is the stage blurb), one lab per
                       line  `N. [`slug`](NN-slug/README.md) ↳K — hook`,
                       the key table (Regenerated date, link count) and the
                       Gaps table (its notes appear on the gap cards).
    KNOWLEDGE-MAP.md   one `## slug — learned DATE — lab: folder/` block per
                       lab. The `Builds on:` segment gives the prerequisites
                       (`[slug]` or `[[slug]]`; `[name?]` suggestions and the
                       Related:/Next: segments are ignored; a `[name]` that is
                       not a lab slug is reported and not drawn). `[name?]`
                       anywhere in a block is a suggested-not-learned topic
                       (the gaps; spelling variants such as `observability-slos`
                       fold into the topic named in the Gaps table). The first
                       claude.ai artifact URL in a block — or, failing that,
                       in the lab's README — is the lab's published
                       visualization and becomes the card's "Open" link.

Usage (from the workspace root):

    python tools/gen-atlas.py                 # rebuild LEARNING-PATH.html, then validate it
    python tools/gen-atlas.py --check         # build in memory and validate; write nothing
    python tools/gen-atlas.py -o other.html   # write somewhere else

Card links point at the numbered folder named in LEARNING-PATH.md
(`39-paxos/README.md`). Which files to link (README, EXERCISES, SOLUTIONS,
visualization.html) is decided by looking on disk — in the numbered folder if
it exists, otherwise in the plain `slug/` folder — so the script works both
before and after `tools/renumber.py --apply`.

The CSS and JS below are the hand-designed page's, unchanged; the only
generated CSS is the per-stage `--hue` block (see STAGE_HUES). Validation
after every build: card count and order, stage count, every relative href
names a numbered lab folder and a file that exists, href == data-path, the
graph has no dangling names, and `node --check` on the extracted script
(skipped when node is not installed). WARN lines are informational (the
two source files disagree on a count); FAIL lines stop the write and set
exit status 1.
"""
import html
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PATH_MD = os.path.join(ROOT, "LEARNING-PATH.md")
MAP_MD = os.path.join(ROOT, "KNOWLEDGE-MAP.md")
OUT_HTML = os.path.join(ROOT, "LEARNING-PATH.html")
FOLDER_RE = re.compile(r"^(\d{2}(?:\.\d+)?)-([a-z0-9-]+)$")

# Stage colours (light, dark) in stage order. Add a pair for a ninth stage.
STAGE_HUES = [
    ("#2A6698", "#71AEE4"),  # s1
    ("#0F6E63", "#45C1B0"),  # s2
    ("#55645F", "#849690"),  # s3 — the JVM block; the old parallel-track grey
    ("#8A3E7A", "#DE8FCB"),  # s4
    ("#AE2F39", "#E4737C"),  # s5
    ("#A2621A", "#DFA05C"),  # s6
    ("#6F6A25", "#BFB863"),  # s7
    ("#6C4FA3", "#A98BE0"),  # s8
]

# Labels under the spine dots, keyed by stage title. Unknown titles are split
# automatically into two balanced lines.
SPINE_LABELS = {
    "The Ground Floor": ("The Ground", "Floor"),
    "One Machine, Done Right": ("One Machine,", "Done Right"),
    "The JVM Underneath: One Machine, Many Threads": ("The JVM", "Underneath"),
    "Why Distribution Is Hard": ("Why Distribution", "Is Hard"),
    "Copies and Agreement": ("Copies and", "Agreement"),
    "Work That Spans Services": ("Work That Spans", "Services"),
    "Running It In Production": ("Running It In", "Production"),
    "Whole Systems": ("Whole Systems",),
}

SPINE_CAPTION = ("The main line is a dependency order, not a difficulty ramp. The JVM block is stage 3 "
                 "&#8212; still one machine, but with several threads &#8212; and nothing after it depends on "
                 "it until production, where tuning meets queueing.")

# Gap blurbs carried over from the hand-written page, used when the Gaps
# table in LEARNING-PATH.md has no note for the topic.
GAP_TEXT = {
    "observability": "Metrics vs logs vs traces, cardinality, and SLOs as a budget.",
    "tail-latency-and-hedging": "The p99 is a different system from the mean. Hedged requests, and the cost of asking twice.",
    "database-indexing": "B-tree and LSM gave you the structures. This is choosing them: selectivity, covering indexes, and why the planner ignored yours.",
    "deterministic-databases": "The Calvin line — order first, then execute. The one design that makes distributed commit cheap.",
}

LOAD_BEARING_N = 12
LATE_N = 8
GAPS_N = 10
CHIPS_ON_CARD = 4
CHIPS_ON_GAP = 8

# ---------------------------------------------------------------- helpers

def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()


def esc(s):
    return html.escape(s, quote=True)


def plain(s):
    return s.replace("**", "").replace("`", "")


def inline(s):
    """Hook text -> HTML: escape, `code` -> <code>, bold markers dropped."""
    return re.sub(r"`([^`]+)`", r"<code>\1</code>", esc(s.replace("**", "")))


ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen "
        "fourteen fifteen sixteen seventeen eighteen nineteen").split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def words(n):
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else "-" + ONES[n % 10])
    return str(n)


MONTHS = "January February March April May June July August September October November December".split()


def date_range(a, b):
    ya, ma, da = a.split("-")
    yb, mb, db = b.split("-")
    if (ya, ma) == (yb, mb):
        return f"{int(da)}&#8211;{int(db)} {MONTHS[int(ma) - 1]} {ya}"
    if ya == yb:
        return f"{int(da)} {MONTHS[int(ma) - 1]} &#8211; {int(db)} {MONTHS[int(mb) - 1]} {ya}"
    return f"{int(da)} {MONTHS[int(ma) - 1]} {ya} &#8211; {int(db)} {MONTHS[int(mb) - 1]} {yb}"


def folder_for(number, slug):
    """Same rule as tools/renumber.py: 01-slug, 03.5-slug."""
    if "." in number:
        i, d = number.split(".", 1)
        return f"{int(i):02d}.{d}-{slug}"
    return f"{int(number):02d}-{slug}"


def index_for(number):
    return folder_for(number, "x")[:-2]


def resolve_folder(numbered, slug):
    """The folder that currently holds the lab: numbered, plain, or any NN-slug."""
    for cand in (numbered, slug):
        if os.path.isdir(os.path.join(ROOT, cand)):
            return cand
    for d in os.listdir(ROOT):
        m = FOLDER_RE.match(d)
        if m and m.group(2) == slug and os.path.isdir(os.path.join(ROOT, d)):
            return d
    return None


def split_label(title):
    if len(title) <= 14:
        return (title,)
    ws = title.split()
    best = None
    for k in range(1, len(ws)):
        a, b = " ".join(ws[:k]), " ".join(ws[k:])
        score = (max(len(a), len(b)), -len(a))
        if best is None or score < best[0]:
            best = (score, (a, b))
    return best[1]


# ---------------------------------------------------------------- parsing

STAGE_RE = re.compile(r"^### Stage (\d+) — (.+?)\s*$")
# `N. [`slug`](target) ↳K [· I★★ · L★★★] — hook`. The two star ratings are
# optional: they are written by tools/mark-importance.py and are a judgement
# call, unlike ↳K which is measured from the graph.
LAB_RE = re.compile(r"^(\d+(?:\.\d+)?)\.\s+\[`([a-z0-9-]+)`\]\(([^)]+)\)\s+↳(\d+)"
                    r"(?:\s+·\s+I(★+)\s+·\s+L(★+))?\s+—\s+(.*?)\s*$")
NOTE_RE = re.compile(r"^(.*?)\s*\*\*←\s*(.*?)\*\*\s*$")
GAP_ROW_RE = re.compile(r"^\|\s*\*\*([a-z0-9-]+)\*\*\s*\|\s*(\d+)\s+labs?\s*\|\s*(.*?)\s*\|\s*$")
REGEN_RE = re.compile(r"^\|\s*Regenerated\s*\|\s*(\d{4}-\d{2}-\d{2})", re.M)
LINKS_RE = re.compile(r"^\|[^|\n]*Builds on[^|\n]*\|\s*(\d+)\s*\|", re.M)


def parse_path():
    text = read(PATH_MD)
    stages, labs, gaps_table = [], [], []
    cur = None
    for line in text.splitlines():
        m = STAGE_RE.match(line)
        if m:
            cur = {"n": int(m.group(1)), "key": f"s{m.group(1)}", "cls": f"st-s{m.group(1)}",
                   "title": m.group(2).strip(), "desc": "", "labs": []}
            stages.append(cur)
            continue
        if line.startswith("## "):
            cur = None
        m = LAB_RE.match(line)
        if m and cur is not None:
            number, slug, target, k, iv, lr, hook = m.groups()
            note = ""
            n = NOTE_RE.match(hook)
            if n:
                hook, note = n.group(1), "← " + n.group(2)
            lab = {"number": number, "slug": slug, "target": target, "k": int(k), "hook": hook,
                   "interview": iv or "", "learning": lr or "",
                   "note": note, "idx": index_for(number), "folder": folder_for(number, slug),
                   "stage": cur, "pos": len(labs) + 1}
            cur["labs"].append(lab)
            labs.append(lab)
            continue
        if cur is not None and not cur["desc"] and line.strip() and not line.startswith("#"):
            cur["desc"] = line.strip()
        g = GAP_ROW_RE.match(line)
        if g:
            gaps_table.append((g.group(1), int(g.group(2)), g.group(3)))
    m = REGEN_RE.search(text)
    regen = m.group(1) if m else ""
    m = LINKS_RE.search(text)
    links_stated = int(m.group(1)) if m else None
    seen = set()
    for lab in labs:
        if lab["slug"] in seen:
            sys.exit(f"ERROR: {lab['slug']} listed twice in LEARNING-PATH.md")
        seen.add(lab["slug"])
    return stages, labs, gaps_table, regen, links_stated


HEAD_RE = re.compile(r"^## ([a-z0-9-]+) — (.+?) — lab: ([^\s/]+)/?(?:\s.*)?$")
LINK_RE = re.compile(r"\[\[?([a-z0-9-]+)\]\]?")
SUGGEST_RE = re.compile(r"\[\[?([a-z0-9-]+)\?\]\]?")
ART_RE = re.compile(r"https://claude\.ai/code/artifact/[A-Za-z0-9-]+")
CUT_RE = re.compile(r"\*{0,2}(?:Related|Next)\*{0,2}:")


def parse_map():
    """{slug: {date, builds, suggests, artifact, order}} — order 0 = top of file (newest)."""
    blocks = {}
    cur = None
    for line in read(MAP_MD).splitlines():
        m = HEAD_RE.match(line)
        if m:
            date = m.group(2).strip()
            date = re.sub(r"^learned\s+", "", date)
            cur = {"slug": m.group(1), "date": date, "builds": [], "suggests": set(),
                   "artifact": None, "order": len(blocks), "lines": []}
            blocks[m.group(1)] = cur
            continue
        if cur is None:
            continue
        cur["lines"].append(line)
        if "Builds on" in line and not cur["builds"]:
            seg = line[line.find("Builds on") + len("Builds on"):]
            seg = seg.split(":", 1)[1] if ":" in seg else seg
            seg = CUT_RE.split(seg, 1)[0]
            cur["builds"] = LINK_RE.findall(seg)
        cur["suggests"].update(SUGGEST_RE.findall(line))
        if cur["artifact"] is None:
            a = ART_RE.search(line)
            if a:
                cur["artifact"] = a.group(0)
    for b in blocks.values():
        del b["lines"]
    return blocks


def first_learned(datetext):
    """Sort key for calendar order: (ISO date, 0 if 'before' that date, else 1).

    Header dates come as `learned 2026-09-07`, `learned before 2026-08-27` or
    `relearned 2026-09-01 (first learned 2026-08-27)`; the first-learned date
    is the one that places a lab on the calendar.
    """
    m = re.search(r"first learned (\d{4}-\d{2}-\d{2})", datetext)
    if m:
        d = m.group(1)
    else:
        m = re.search(r"\d{4}-\d{2}-\d{2}", datetext)
        d = m.group(0) if m else "0000-00-00"
    return (d, 0 if "before" in datetext else 1)


def card_date(datetext):
    """What the card shows: `2026-08-27` or `before 2026-08-27`."""
    d, after = first_learned(datetext)
    return d if after else "before " + d


# ---------------------------------------------------------------- model

def build_model():
    stages, labs, gaps_table, regen, links_stated = parse_path()
    blocks = parse_map()
    slugs = [l["slug"] for l in labs]
    slugset = set(slugs)
    warnings = []
    for s in slugs:
        if s not in blocks:
            warnings.append(f"{s} has no block in KNOWLEDGE-MAP.md")
    for s in blocks:
        if s not in slugset:
            warnings.append(f"KNOWLEDGE-MAP.md block {s} is not in LEARNING-PATH.md")

    # graph: p = Builds-on prerequisites that are labs, d = inverse
    D = {s: {"s": None, "p": [], "d": []} for s in slugs}
    unknown = {}
    for lab in labs:
        s = lab["slug"]
        D[s]["s"] = lab["stage"]["key"]
        b = blocks.get(s, {"builds": []})
        pre = []
        for t in b["builds"]:
            if t == s:
                continue
            if t in slugset:
                if t not in pre:
                    pre.append(t)
            else:
                unknown.setdefault(t, []).append(s)
        D[s]["p"] = sorted(pre)
    for s in slugs:
        for p in D[s]["p"]:
            D[p]["d"].append(s)
    for s in slugs:
        D[s]["d"].sort()
    edges = sum(len(D[s]["p"]) for s in slugs)
    links_written = sum(len(blocks[s]["builds"]) for s in slugs if s in blocks)
    unresolved = sorted(unknown.items())
    for t, froms in unresolved:
        warnings.append(f"Builds on names [{t}] (not a lab slug) in: {', '.join(froms)} — written, not drawn")
    if links_stated is not None and links_written != links_stated:
        warnings.append(f"KNOWLEDGE-MAP.md has {links_written} Builds-on links; LEARNING-PATH.md's key table says {links_stated}")
    for lab in labs:
        got = len(D[lab["slug"]]["d"])
        if got != lab["k"]:
            warnings.append(f"{lab['slug']}: LEARNING-PATH.md says ↳{lab['k']}, graph says {got}")

    # cycles (Builds-on edges point at earlier labs, so there should be none)
    cycles = find_cycles(D, slugs)

    # per-lab: date, folder, files, artifact, calendar position
    for lab in labs:
        b = blocks.get(lab["slug"], {})
        lab["date"] = card_date(b.get("date", ""))
        lab["datetext"] = b.get("date", "")
        lab["artifact"] = b.get("artifact")
        lab["order"] = b.get("order", 0)
        lab["disk"] = resolve_folder(lab["folder"], lab["slug"])
        lab["files"] = set()
        if lab["disk"]:
            for f in ("README.md", "EXERCISES.md", "SOLUTIONS.md", "visualization.html"):
                if os.path.isfile(os.path.join(ROOT, lab["disk"], f)):
                    lab["files"].add(f)
            if lab["artifact"] is None and "README.md" in lab["files"]:
                a = ART_RE.search(read(os.path.join(ROOT, lab["disk"], "README.md")))
                if a:
                    lab["artifact"] = a.group(0)
        else:
            warnings.append(f"{lab['slug']}: no folder on disk ({lab['folder']} or {lab['slug']})")

    # calendar order (oldest first), then how far each lab moved
    cal = sorted(labs, key=lambda l: (first_learned(l["datetext"]), -l["order"]))
    for i, lab in enumerate(cal, 1):
        lab["cal"] = i
        lab["delta"] = i - lab["pos"]
    late = sorted([l for l in labs if l["delta"] > 0], key=lambda l: (-l["delta"], l["slug"]))[:LATE_N]
    late_set = {l["slug"] for l in late}

    # load-bearing
    load = sorted(slugs, key=lambda s: (-len(D[s]["d"]), s))[:LOAD_BEARING_N]

    # gaps: suggested topics that are not labs, counted by how many distinct
    # labs ask (that is what a card's chips show). Spelling variants fold into
    # the topic LEARNING-PATH.md's Gaps table names: observability-slos and
    # observability-and-tracing -> observability, calvin-deterministic-databases
    # -> deterministic-databases. The table itself counts occurrences, so its
    # numbers can run higher; that difference is reported, not treated as an error.
    table_topics = [t for t, _, _ in gaps_table]

    def canon(t):
        for g in table_topics:
            if t == g or t.startswith(g + "-") or t.endswith("-" + g) or ("-" + g + "-") in t:
                return g
        return t

    ask = {}
    for lab in labs:
        for t in blocks.get(lab["slug"], {"suggests": set()})["suggests"]:
            if t not in slugset:
                ask.setdefault(canon(t), set()).add(lab["slug"])
    table_pos = {t: i for i, t in enumerate(table_topics)}
    table_note = {t: note for t, _, note in gaps_table}
    for t, n, _ in gaps_table:
        if t not in ask:
            warnings.append(f"gap {t} is in LEARNING-PATH.md's table but no lab suggests it")
        elif len(ask[t]) != n:
            warnings.append(f"gap {t}: LEARNING-PATH.md's table says {n} (occurrences); the page shows {len(ask[t])} distinct labs")
    gaps = sorted(ask, key=lambda t: (-len(ask[t]), table_pos.get(t, 10 ** 6), t))[:GAPS_N]
    gap_rows = []
    for t in gaps:
        text = table_note.get(t) or GAP_TEXT.get(t) or f"Named as a next step by {words(len(ask[t]))} labs and never opened."
        gap_rows.append({"topic": t, "n": len(ask[t]), "text": text, "by": sorted(ask[t])})

    dates = sorted(first_learned(l["datetext"])[0] for l in labs if first_learned(l["datetext"])[0] != "0000-00-00")
    return {"stages": stages, "labs": labs, "D": D, "edges": edges, "links_written": links_written,
            "unresolved": unresolved, "cycles": cycles, "late": late, "late_set": late_set, "load": load,
            "gaps": gap_rows, "n_gaps": len(ask), "regen": regen,
            "dates": (dates[0], dates[-1]) if dates else ("", ""), "warnings": warnings}


def find_cycles(D, slugs):
    WHITE, GREY, BLACK = 0, 1, 2
    colour = {s: WHITE for s in slugs}
    found = []

    def visit(s, path):
        colour[s] = GREY
        path.append(s)
        for p in D[s]["p"]:
            if colour[p] == GREY:
                found.append(path[path.index(p):] + [p])
            elif colour[p] == WHITE:
                visit(p, path)
        path.pop()
        colour[s] = BLACK

    for s in slugs:
        if colour[s] == WHITE:
            visit(s, [])
    return found


# ---------------------------------------------------------------- html

def hue_css(stages):
    if len(stages) > len(STAGE_HUES):
        sys.exit(f"ERROR: {len(stages)} stages but only {len(STAGE_HUES)} colours in STAGE_HUES")
    light = "".join(f"  .{st['cls']}{{--hue:{STAGE_HUES[i][0]}}}\n" for i, st in enumerate(stages))
    media = "".join(f':root:not([data-theme="light"]) .{st["cls"]}{{--hue:{STAGE_HUES[i][1]}}}\n'
                    for i, st in enumerate(stages))
    attr = "".join(f':root[data-theme="dark"] .{st["cls"]}{{--hue:{STAGE_HUES[i][1]}}}\n'
                   for i, st in enumerate(stages))
    return (CSS_TEMPLATE.replace("@@HUES_LIGHT@@\n", light)
            .replace("@@HUES_DARK_MEDIA@@\n", media)
            .replace("@@HUES_DARK_ATTR@@\n", attr))


def spine_svg(stages):
    n = len(stages)
    x0, x1 = 80.0, 1079.6
    step = (x1 - x0) / (n - 1) if n > 1 else 0
    out = [f'<line class="spine-rule" x1="{x0:.1f}" y1="64" x2="{x1:.1f}" y2="64"/>']
    for i, st in enumerate(stages):
        x = x0 + i * step
        lines = SPINE_LABELS.get(st["title"]) or split_label(st["title"])
        out.append(f'<g class="spine-node {st["cls"]}" data-goto="{st["key"]}" tabindex="0" role="link" '
                   f'aria-label="Stage {st["n"]}: {esc(st["title"])}">')
        out.append(f'<text class="spine-count" x="{x:.1f}" y="34">{len(st["labs"])} labs</text>')
        out.append(f'<circle class="spine-hit" cx="{x:.1f}" cy="64" r="30"/>')
        out.append(f'<circle class="spine-dot" cx="{x:.1f}" cy="64" r="17"/>')
        out.append(f'<text class="spine-num" x="{x:.1f}" y="69">{st["n"]}</text>')
        for j, l in enumerate(lines):
            out.append(f'<text class="spine-lbl" x="{x:.1f}" y="{100 + 14 * j}">{esc(l)}</text>')
        out.append("</g>")
    return "\n".join(out)


def chips(names, limit):
    out = "".join(f'<button class="chip" data-jump="{n}" type="button">{n}</button>' for n in names[:limit])
    if len(names) > limit:
        out += f'<span class="chip chip-more">+{len(names) - limit}</span>'
    return out


def card_html(lab, D, late_set):
    s = lab["slug"]
    node = D[s]
    text = plain(f"{s} {lab['hook']}" + (f" {lab['note']}" if lab["note"] else ""))
    out = [f'<article class="card {lab["stage"]["cls"]}" id="c-{s}" data-name="{s}" data-text="{esc(text)}" tabindex="0">',
           f' <div class="c-top"><span class="c-idx">{esc(lab["idx"])}</span><h3 class="c-name">{s}</h3></div>',
           f' <p class="c-hook">{inline(lab["hook"])}</p>']
    flags = []
    if lab["note"]:
        flags.append(inline(lab["note"]))
    if s in late_set:
        flags.append(f"Learned {lab['delta']} places later than its foundation slot &#8212; good re-read")
    out.append(" " + "".join(f'<div class="flag">{f}</div>' for f in flags))
    rating = ""
    if lab["interview"] or lab["learning"]:
        # A judgement call, unlike the dependant count beside it — so label both.
        rating = (f'<span class="c-dot">&#183;</span>'
                  f'<span class="c-rate" title="Interview importance: {len(lab["interview"])} of 3">'
                  f'I{esc(lab["interview"])}</span>'
                  f'<span class="c-rate" title="Learning value: {len(lab["learning"])} of 3">'
                  f'L{esc(lab["learning"])}</span>')
    out.append(f' <div class="c-meta"><span class="c-date">{esc(lab["date"])}</span><span class="c-dot">&#183;</span>'
               f'<span class="c-dep">{len(node["d"])} build on it</span>{rating}</div>')
    if node["p"]:
        out.append(f' <div class="c-pre"><span class="c-prelbl">after</span>{chips(node["p"], CHIPS_ON_CARD)}</div>')
    else:
        out.append(" ")
    links = []
    for fname, label in (("README.md", "Read"), ("EXERCISES.md", "Exercises"),
                         ("SOLUTIONS.md", "Solutions"), ("visualization.html", "Visual")):
        if fname in lab["files"]:
            p = f"{lab['folder']}/{fname}"
            links.append(f'<a class="lk" href="{p}" data-path="{p}">{label}</a>')
    if lab["artifact"]:
        a = lab["artifact"]
        links.append(f'<a class="lk" href="{a}" data-path="{a}" target="_blank" rel="noopener">Open</a>')
    out.append(f' <div class="c-links">{"".join(links)}</div>')
    out.append("</article>")
    return "\n".join(out)


def band_html(st, D, late_set):
    cards = "\n".join(card_html(l, D, late_set) for l in st["labs"])
    return (f'<section class="band {st["cls"]}" id="{st["key"]}">\n'
            f' <header class="b-head">\n'
            f'  <span class="b-num">{st["n"]}</span>\n'
            f'  <div class="b-txt"><h2>{esc(st["title"])}</h2><p>{inline(st["desc"])}</p></div>\n'
            f'  <div class="b-count"><b>{len(st["labs"])}</b><span>labs</span></div>\n'
            f' </header>\n'
            f' <div class="grid">{cards}</div>\n'
            f'</section>')


def render(M):
    stages, labs, D = M["stages"], M["labs"], M["D"]
    n = len(labs)
    nstages = len(stages)
    top = M["load"][0]
    top_n = len(D[top]["d"])
    zero = "zab" if "zab" in D and not D["zab"]["d"] else next((s for s in sorted(D) if not D[s]["d"]), top)
    top_gap = M["gaps"][0] if M["gaps"] else {"n": 0}
    bymap = {l["slug"]: l for l in labs}

    if M["cycles"]:
        c = M["cycles"][0]
        acyclic = (f"The graph has {words(len(M['cycles']))} cycle{'s' if len(M['cycles']) != 1 else ''} "
                   f"(for example <code>{' → '.join(c)}</code>), so no order satisfies every link; the stages break the ties.")
    else:
        acyclic = "The dependency graph is acyclic, so a valid order exists."
    if M["unresolved"]:
        names = ", ".join(f"<code>[{esc(t)}]</code> under <code>{esc(froms[0])}</code>" for t, froms in M["unresolved"])
        k = M["links_written"] - M["edges"]
        drawn = (f" {M['edges']} of them name another lab by its exact folder name and are drawn; the other "
                 f"{words(k)} ({names}) match no lab and are not.")
    else:
        drawn = ""

    head = (
        '<meta charset="utf-8">\n'
        '<title>Systems Atlas</title>\n'
        '<link rel="preconnect" href="https://fonts.googleapis.com">\n'
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n'
        '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@500;600;700&family=IBM+Plex+Mono:wght@400;500;600&family=Source+Sans+3:wght@400;600&display=swap">\n'
        '<style>\n' + hue_css(stages) + '</style>\n'
    )

    bar = (
        '\n<div class="bar"><div class="bar-in">\n'
        f'  <span class="mark">Systems Atlas <span>&#183; {n} labs</span></span>\n'
        f'  <label class="search"><input id="q" type="search" placeholder="Filter {n} labs&#8230;" aria-label="Filter labs"></label>\n'
        '  <button class="btn" id="clear" type="button">Clear trace</button>\n'
        '  <button class="btn" id="theme" type="button">Theme</button>\n'
        '</div></div>\n'
    )

    hero = (
        '\n<div class="wrap">\n<header class="top">\n'
        f'  <div class="eyebrow">{n} labs &#183; learned {date_range(*M["dates"])} &#183; {M["links_written"]} prerequisite links &#183; regenerated {M["regen"]}</div>\n'
        '  <h1>The order to study<br>what you already learned.</h1>\n'
        '  <p class="lede">You built these labs in the order curiosity took you &#8212; <strong>ACID on day four, CAP on day one</strong>. '
        f'That is a fine way to learn and a bad way to remember. This is the same {n} labs re-sequenced into the order the ideas '
        'actually depend on each other, so a second pass compounds instead of repeating.</p>\n'
        '  <div class="stats">\n'
        f'    <div class="stat"><b>{n}</b><span>labs completed</span></div>\n'
        f'    <div class="stat"><b>{nstages}</b><span>stages, one numbered sequence</span></div>\n'
        f'    <div class="stat"><b>{top_n}</b><span>labs rest on <code>{top}</code></span></div>\n'
        f'    <div class="stat"><b>{M["n_gaps"]}</b><span>topics your labs asked for, never opened</span></div>\n'
        '  </div>\n\n'
        '  <div class="spine">\n'
        f'    <svg viewBox="0 0 1160 130" role="img" aria-label="{words(nstages).capitalize()} sequential stages, numbered in reading order">\n'
        + spine_svg(stages) + '\n'
        '    </svg>\n'
        f'    <p class="spine-cap">{SPINE_CAPTION}</p>\n'
        '  </div>\n\n'
        '  <div class="ways">\n'
        f'    <a class="way" href="#{stages[0]["key"]}"><h3>Walk the {words(nstages)} stages</h3><p>The full re-read, foundations first. Each stage says why it comes where it does.</p></a>\n'
        f'    <a class="way" href="#loadbearing"><h3>Short on time? {words(LOAD_BEARING_N).capitalize()} labs</h3><p>The concepts the other {words(n - LOAD_BEARING_N)} lean on. Re-read only these and most of the map re-lights.</p></a>\n'
        f'    <a class="way" href="#gaps"><h3>What to learn next</h3><p>Ranked by how many finished labs stopped at its edge. One topic is asked for {words(top_gap["n"])} times.</p></a>\n'
        '  </div>\n'
        '</header>\n\n'
        '<div class="tracebar"><div id="tracetext"></div></div>\n\n'
    )

    bands = "\n".join(band_html(st, D, M["late_set"]) for st in stages) + "\n"

    mx = max(1, len(D[M["load"][0]]["d"]))
    bars = []
    for s in M["load"]:
        c = len(D[s]["d"])
        bars.append(f'<button class="bar-row" data-jump="{s}" type="button" title="{c} labs list {s} as a prerequisite">\n'
                    f'  <span class="bar-name">{s}</span>\n'
                    f'  <span class="bar-track"><span class="bar-fill" style="width:{c / mx * 100:.1f}%"></span></span>\n'
                    f'  <span class="bar-val">{c}</span></button>')
    late = []
    for l in M["late"]:
        late.append(f'<li class="{l["stage"]["cls"]}"><button data-jump="{l["slug"]}" type="button">'
                    f'<span class="lt-name">{l["slug"]}</span><span class="lt-d">+{l["delta"]}</span></button></li>')

    two = (
        '\n<div class="empty">Nothing matches that filter.</div>\n\n'
        '<div class="two">\n'
        '  <section id="loadbearing">\n'
        f'    <h2 class="sec-h">The load-bearing {words(LOAD_BEARING_N)}</h2>\n'
        '    <p class="sec-p">How many of the other labs name each one as a prerequisite. This is not importance in the abstract &#8212; '
        f'it is measured from your own notes. Forgetting <code>{top}</code> quietly weakens {words(top_n)} later labs; '
        f'forgetting <code>{zero}</code> weakens none. Click any bar to trace it through the map.</p>\n'
        '    <div class="bars">\n' + "\n".join(bars) + '\n    </div>\n'
        '  </section>\n'
        '  <section>\n'
        '    <h2 class="sec-h">Learned before their foundations</h2>\n'
        '    <p class="sec-p">These sat late in your calendar and early in the dependency order &#8212; you met the consequence before the cause. '
        'They are the highest-value re-reads, because this time the ground under them exists. The number is how many places each moves '
        'when the calendar is re-sorted into the curriculum.</p>\n'
        '    <ul class="late">\n' + "\n".join(late) + '\n    </ul>\n'
        '  </section>\n'
        '</div>\n'
    )

    gap_cards = []
    for g in M["gaps"]:
        gap_cards.append('<article class="gap">\n'
                         f' <div class="g-head"><span class="g-n">{g["n"]}</span><div><h3>{g["topic"]}</h3><span class="g-sub">labs stop at its edge</span></div></div>\n'
                         f' <p>{inline(g["text"])}</p>\n'
                         f' <div class="g-src"><span class="c-prelbl">asked for by</span>{chips(g["by"], CHIPS_ON_GAP)}</div>\n'
                         '</article>')
    gaps = (
        '\n<section class="gaps-sec" id="gaps">\n'
        f'  <h2 class="sec-h">The {words(len(M["gaps"]))} gaps worth closing</h2>\n'
        f'  <p class="sec-p">Every lab ends by naming what it did not cover. {words(M["n_gaps"]).capitalize()} of those suggestions were never '
        f'taken up; these {words(len(M["gaps"]))} were asked for by the most labs. The count is the number of finished labs that pointed here and stopped.</p>\n'
        '  <div class="gaps">\n' + "\n".join(gap_cards) + '\n  </div>\n'
        '</section>\n'
    )

    method = (
        '\n<section class="method">\n'
        '  <h2>How this order was derived, and where to distrust it</h2>\n'
        '  <p><b>The data is real.</b> Every prerequisite link, date, and count on this page is parsed from the <code>Builds on</code> lines '
        f'in your own <code>KNOWLEDGE-MAP.md</code> &#8212; {M["links_written"]} links across {n} labs.{drawn} {acyclic}</p>\n'
        '  <p><b>But a plain topological sort would be useless here</b>, and this page does not use one. Those links were written at learning time, '
        'so they can only ever point at labs you had already finished &#8212; sorting by them just replays your calendar. '
        f'The {words(nstages)} stages are a curriculum judgement laid over the graph, written down in <code>LEARNING-PATH.md</code>: '
        'group by what the ideas are about, then order the groups so nothing needs a word it has not met.</p>\n'
        '  <p><b>What that means for you.</b> Stage membership is arguable &#8212; <code>tls</code> and <code>ssh</code> could sit in stage 1, '
        'and <code>idempotency</code> genuinely belongs in three places at once. The counts, dates, and prerequisite chips are not arguable; '
        'those are measured. When the two disagree, trust the counts.</p>\n'
        '  <p><b>How to rebuild it:</b> <code>python tools/gen-atlas.py</code> regenerates this page from <code>LEARNING-PATH.md</code> and '
        f'<code>KNOWLEDGE-MAP.md</code>; it edits neither. Last regenerated {M["regen"]}.</p>\n'
        '</section>\n'
        '</div>\n'
    )

    graph = {s: {"s": D[s]["s"], "p": D[s]["p"], "d": D[s]["d"]} for s in (l["slug"] for l in labs)}
    script = "\n<script>\nconst D=" + json.dumps(graph, separators=(",", ":"), ensure_ascii=False) + ";\n" + JS + "</script>\n"
    return head + bar + hero + bands + two + gaps + method + script


# ---------------------------------------------------------------- validation

def validate(page, M):
    labs, stages, D = M["labs"], M["stages"], M["D"]
    problems = []
    slugs = [l["slug"] for l in labs]

    cards = re.findall(r'<article class="card st-s\d+" id="c-([a-z0-9-]+)"', page)
    if cards != slugs:
        problems.append(f"cards: {len(cards)} found, expected {len(slugs)} in curriculum order")
    idx = re.findall(r'<span class="c-idx">([^<]+)</span>', page)
    if idx != [l["idx"] for l in labs]:
        problems.append("card indices are not the curriculum numbers in order")
    bands = re.findall(r'<section class="band st-s\d+" id="(s\d+)">', page)
    if bands != [st["key"] for st in stages]:
        problems.append(f"stage sections: {bands}")

    if "<title>Systems Atlas</title>" not in page:
        problems.append("title is not 'Systems Atlas'")
    for cls in re.findall(r'class="(?:card|band) (st-[a-z0-9]+)"', page):
        if not re.fullmatch(r"st-s\d+", cls):
            problems.append(f"stage class {cls} is not st-sN")
    hue_lines = re.findall(r"\.st-s\d+\{--hue:#[0-9A-Fa-f]{6}\}", page)
    if len(hue_lines) != 3 * len(stages):
        problems.append(f"{len(hue_lines)} --hue rules, expected {3 * len(stages)} (light, dark media, dark attr)")

    # every relative href is <numbered folder from LEARNING-PATH.md>/<file>, and
    # the file exists in the folder that currently holds the lab
    by_folder = {l["folder"]: l for l in labs}
    hrefs = re.findall(r'href="([^"]+)"', page)
    rel = [h for h in hrefs if not re.match(r"[a-z]+:|#", h)]
    bad = []
    for h in rel:
        folder, _, fname = h.partition("/")
        lab = by_folder.get(folder)
        ok = lab is not None and "/" not in fname and (
            h == lab["target"] or (lab["disk"] and os.path.isfile(os.path.join(ROOT, lab["disk"], fname))))
        if not ok:
            bad.append(h)
    if bad:
        problems.append(f"{len(bad)} relative hrefs resolve nowhere: {bad[:8]}")
    mismatched = [(h, p) for h, p in re.findall(r'<a class="lk" href="([^"]+)" data-path="([^"]+)"', page) if h != p]
    if mismatched:
        problems.append(f"{len(mismatched)} links where href != data-path: {mismatched[:4]}")
    n_open = len(re.findall(r'href="https://claude\.ai/code/artifact/[^"]+"', page))

    m = re.search(r"\nconst D=(\{.*?\});\n", page)
    try:
        G = json.loads(m.group(1))
    except Exception as e:  # noqa
        G, problems = {}, problems + [f"const D is not valid JSON: {e}"]
    if set(G) != set(slugs):
        problems.append("D keys differ from the lab list")
    for s, node in G.items():
        for t in node.get("p", []) + node.get("d", []):
            if t not in G:
                problems.append(f"D[{s}] refers to unknown lab {t}")
        for p in node.get("p", []):
            if s not in G.get(p, {}).get("d", []):
                problems.append(f"D[{p}].d lacks {s}")
    stage_keys = {st["key"] for st in stages}
    for s, node in G.items():
        if node.get("s") not in stage_keys:
            problems.append(f"D[{s}] has stage {node.get('s')}")
    for j in set(re.findall(r'data-jump="([^"]+)"', page)):
        if j not in G:
            problems.append(f"data-jump target {j} has no card")

    node_result = "skipped (node not installed)"
    js = re.search(r"<script>\n(.*)</script>\s*$", page, re.S)
    if shutil.which("node") and js:
        fd, tmp = tempfile.mkstemp(suffix=".js")
        with io.open(fd, "w", encoding="utf-8") as f:
            f.write(js.group(1))
        try:
            r = subprocess.run(["node", "--check", tmp], capture_output=True, text=True)
            node_result = "ok" if r.returncode == 0 else "FAILED: " + (r.stderr or r.stdout).strip()
            if r.returncode != 0:
                problems.append("node --check failed on the inline script")
        finally:
            os.unlink(tmp)
    summary = (f"{len(cards)} cards, {len(bands)} stages, {len(rel)} relative hrefs "
               f"({len(rel) - len(bad)} resolve), {n_open} artifact links, "
               f"D: {len(G)} keys / {sum(len(v.get('p', [])) for v in G.values())} edges, "
               f"node --check: {node_result}")
    return problems, summary


# ---------------------------------------------------------------- main

def main(argv):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:  # noqa
        pass
    if "-h" in argv or "--help" in argv:
        print(__doc__)
        return 0
    check_only = "--check" in argv
    out = OUT_HTML
    if "-o" in argv:
        out = os.path.abspath(argv[argv.index("-o") + 1])
    for a in argv:
        if a.startswith("-") and a not in ("--check", "-o"):
            sys.exit(f"ERROR: unknown option {a} (see --help)")
    M = build_model()
    page = render(M)
    problems, summary = validate(page, M)
    for w in M["warnings"]:
        print("WARN  " + w)
    for p in problems:
        print("FAIL  " + p)
    print(summary)
    if problems:
        print("not written" if not check_only else "check failed")
        return 1
    if check_only:
        print("check ok, nothing written")
        return 0
    io.open(out, "w", encoding="utf-8", newline="\n").write(page)
    print(f"wrote {os.path.relpath(out, ROOT)} ({len(page.encode('utf-8')):,} bytes)")
    return 0


# ---------------------------------------------------------------- page chrome
# The hand-designed CSS. Only the three @@HUES_…@@ lines are generated.

CSS_TEMPLATE = r''':root{
  --ground:#EFF2F0; --surface:#FAFCFA; --surface-2:#E5EAE7; --sunken:#E9EEEB;
  --ink:#16211E; --ink-2:#55645F; --ink-3:#8A9994; --line:#D3DBD7; --line-2:#C0CAC5;
  --accent:#0F6E63; --warn:#A2621A;
  --shadow:0 1px 2px rgba(20,35,30,.07), 0 8px 24px -12px rgba(20,35,30,.18);
  --radius:5px;
}
@@HUES_LIGHT@@
@media (prefers-color-scheme: dark){ :root:not([data-theme="light"]){
  --ground:#0E1415; --surface:#161E1F; --surface-2:#1F2A2B; --sunken:#121A1B;
  --ink:#E2EAE7; --ink-2:#9AAAA5; --ink-3:#6A7B76; --line:#2A3536; --line-2:#384748;
  --accent:#45C1B0; --warn:#DFA05C;
  --shadow:0 1px 2px rgba(0,0,0,.4), 0 8px 24px -12px rgba(0,0,0,.6);
}
@@HUES_DARK_MEDIA@@
}
:root[data-theme="dark"]{
  --ground:#0E1415; --surface:#161E1F; --surface-2:#1F2A2B; --sunken:#121A1B;
  --ink:#E2EAE7; --ink-2:#9AAAA5; --ink-3:#6A7B76; --line:#2A3536; --line-2:#384748;
  --accent:#45C1B0; --warn:#DFA05C;
  --shadow:0 1px 2px rgba(0,0,0,.4), 0 8px 24px -12px rgba(0,0,0,.6);
}
@@HUES_DARK_ATTR@@
*{box-sizing:border-box}
body{margin:0; background:var(--ground); color:var(--ink);
  font-family:"Source Sans 3",system-ui,-apple-system,"Segoe UI",sans-serif;
  font-size:15px; line-height:1.55; -webkit-font-smoothing:antialiased;}
h1,h2,h3{font-family:Archivo,system-ui,sans-serif; text-wrap:balance; margin:0}
code,.mono,.chip,.c-idx,.c-date,.bar-val,.g-n,.lt-d,.spine-count,.spine-num,.b-num{
  font-family:"IBM Plex Mono",ui-monospace,Menlo,monospace}
a{color:inherit}
:focus-visible{outline:2px solid var(--accent); outline-offset:2px; border-radius:3px}
.wrap{max-width:1240px; margin:0 auto; padding:0 22px}

/* ---------- top bar ---------- */
.bar{position:sticky; top:0; z-index:40; background:var(--ground); background:color-mix(in srgb, var(--ground) 88%, transparent);
  backdrop-filter:blur(9px); border-bottom:1px solid var(--line)}
.bar-in{max-width:1240px; margin:0 auto; padding:9px 22px; display:flex; gap:14px; align-items:center}
.mark{font-family:Archivo,sans-serif; font-weight:700; font-size:14px; letter-spacing:-.01em; white-space:nowrap}
.mark span{color:var(--ink-3); font-weight:500}
.search{flex:1; min-width:90px; max-width:340px; margin-left:auto}
.search input{width:100%; padding:6px 10px; font:inherit; font-size:13.5px; color:var(--ink);
  background:var(--surface); border:1px solid var(--line-2); border-radius:var(--radius)}
.search input::placeholder{color:var(--ink-3)}
.btn{padding:6px 11px; font-family:Archivo,sans-serif; font-size:12.5px; font-weight:600; color:var(--ink-2);
  background:var(--surface); border:1px solid var(--line-2); border-radius:var(--radius); cursor:pointer; white-space:nowrap}
.btn:hover{color:var(--ink); border-color:var(--ink-3)}
#clear{display:none; border-color:var(--accent); color:var(--accent)}
body.tracing #clear{display:block}

/* ---------- hero ---------- */
header.top{padding:52px 0 30px; border-bottom:1px solid var(--line)}
.eyebrow{font-family:Archivo,sans-serif; font-size:11px; font-weight:600; letter-spacing:.14em;
  text-transform:uppercase; color:var(--ink-3)}
h1{font-size:clamp(30px,5vw,52px); font-weight:700; letter-spacing:-.025em; line-height:1.02; margin:12px 0 0}
.lede{max-width:66ch; color:var(--ink-2); font-size:17px; margin:16px 0 0}
.lede strong{color:var(--ink); font-weight:600}
.stats{display:flex; flex-wrap:wrap; gap:10px; margin-top:26px}
.stat{flex:1 1 150px; background:var(--surface); border:1px solid var(--line); border-radius:var(--radius); padding:12px 14px}
.stat b{display:block; font-family:Archivo,sans-serif; font-size:27px; font-weight:700; letter-spacing:-.02em;
  line-height:1; font-variant-numeric:tabular-nums}
.stat span{display:block; margin-top:5px; font-size:12.5px; color:var(--ink-2)}

/* ---------- spine ---------- */
.spine{margin:34px 0 0}
.spine svg{width:100%; height:auto; display:block; overflow:visible}
.spine-rule{stroke:var(--line-2); stroke-width:2}
.spine-join{stroke:var(--line-2); stroke-width:1.5; stroke-dasharray:3 4; fill:none}
.spine-joindot{fill:var(--line-2)}
.spine-rail{fill:var(--sunken); stroke:var(--line-2); stroke-width:1.5; stroke-dasharray:4 4}
.spine-railtext{fill:var(--ink-2); font-family:Archivo,sans-serif; font-size:12px; font-weight:600}
.spine-count{fill:var(--ink-3); font-size:10px; text-anchor:middle; letter-spacing:.04em}
.spine-hit{fill:transparent; cursor:pointer}
.spine-dot{fill:var(--hue); cursor:pointer; transition:r .15s}
.spine-num{fill:var(--surface); font-size:14px; font-weight:600; text-anchor:middle; pointer-events:none}
.spine-lbl{fill:var(--ink); font-family:Archivo,sans-serif; font-size:11.5px; font-weight:600;
  text-anchor:middle; pointer-events:none}
.spine-node:hover .spine-dot, .spine-node:focus-visible .spine-dot{r:21}
.spine-cap{color:var(--ink-3); font-size:12.5px; margin:10px 0 0; text-align:center}
@media (max-width:720px){ .spine{display:none} }

/* ---------- ways in ---------- */
.ways{display:grid; grid-template-columns:repeat(auto-fit,minmax(250px,1fr)); gap:12px; margin:36px 0 8px}
.way{background:var(--surface); border:1px solid var(--line); border-left:3px solid var(--accent);
  border-radius:var(--radius); padding:15px 17px; text-decoration:none; display:block; box-shadow:var(--shadow)}
.way:hover{border-color:var(--ink-3); border-left-color:var(--accent)}
.way h3{font-size:15px; margin-bottom:5px}
.way p{margin:0; color:var(--ink-2); font-size:13.8px}

/* ---------- bands ---------- */
.band{padding:44px 0 8px; scroll-margin-top:60px}
.b-head{display:flex; gap:16px; align-items:flex-start; padding-bottom:16px; margin-bottom:20px;
  border-bottom:2px solid var(--hue)}
.b-num{flex:none; width:40px; height:40px; display:grid; place-items:center; border-radius:50%;
  background:var(--hue); color:var(--surface); font-size:17px; font-weight:600}
.b-par{background:transparent; border:1.5px dashed var(--hue); color:var(--hue)}
.b-txt{flex:1; min-width:0}
.b-txt h2{font-size:22px; font-weight:700; letter-spacing:-.015em}
.b-tag{margin-left:10px; font-family:"IBM Plex Mono",monospace; font-size:10.5px; font-weight:500;
  letter-spacing:.08em; text-transform:uppercase; color:var(--hue); vertical-align:2px}
.b-txt p{margin:6px 0 0; color:var(--ink-2); max-width:72ch; font-size:14.5px}
.b-count{flex:none; text-align:right}
.b-count b{display:block; font-family:Archivo,sans-serif; font-size:22px; font-weight:700; color:var(--hue);
  font-variant-numeric:tabular-nums; line-height:1}
.b-count span{font-size:11px; color:var(--ink-3)}

.grid{display:grid; grid-template-columns:repeat(auto-fill,minmax(292px,1fr)); gap:12px}
.card{background:var(--surface); border:1px solid var(--line); border-top:3px solid var(--hue);
  border-radius:var(--radius); padding:14px 15px 12px; display:flex; flex-direction:column; gap:9px;
  cursor:pointer; box-shadow:var(--shadow); transition:border-color .12s, transform .12s}
.card:hover{border-color:var(--ink-3); border-top-color:var(--hue)}
.c-top{display:flex; gap:9px; align-items:baseline}
.c-idx{font-size:11px; color:var(--ink-3); font-variant-numeric:tabular-nums}
.c-name{font-family:"IBM Plex Mono",monospace; font-size:14px; font-weight:600; color:var(--hue);
  letter-spacing:-.01em; word-break:break-word}
.c-hook{margin:0; font-size:14px; color:var(--ink); line-height:1.5}
.c-meta{display:flex; gap:7px; align-items:center; font-size:11.5px; color:var(--ink-3); margin-top:auto}
.c-date{font-variant-numeric:tabular-nums}
.c-rate{margin-left:6px; letter-spacing:.02em; white-space:nowrap; color:var(--hue,var(--ink-2))}
.flag{font-size:11.5px; color:var(--warn); border-left:2px solid var(--warn); padding-left:8px}
.c-pre{display:flex; flex-wrap:wrap; gap:5px; align-items:center}
.c-prelbl{font-family:Archivo,sans-serif; font-size:10px; font-weight:600; letter-spacing:.1em;
  text-transform:uppercase; color:var(--ink-3); margin-right:2px}
.chip{font-size:10.5px; padding:2px 6px; background:var(--sunken); color:var(--ink-2);
  border:1px solid var(--line); border-radius:3px; cursor:pointer}
.chip:hover{color:var(--ink); border-color:var(--ink-3)}
.chip-more{cursor:default; color:var(--ink-3)}
.c-links{display:flex; flex-wrap:wrap; gap:4px; padding-top:9px; border-top:1px solid var(--line)}
.lk{font-family:Archivo,sans-serif; font-size:11.5px; font-weight:600; color:var(--ink-2);
  text-decoration:none; padding:3px 8px; background:var(--sunken); border:1px solid var(--line); border-radius:3px}
.lk:hover{color:var(--surface); background:var(--hue); border-color:var(--hue)}
.lk.copied{color:var(--surface); background:var(--accent); border-color:var(--accent)}

/* ---------- trace ---------- */
body.tracing .card{opacity:.26; filter:saturate(.3)}
body.tracing .card.is-pre,body.tracing .card.is-post,body.tracing .card.is-focus{opacity:1; filter:none}
.card.is-focus{border-color:var(--hue); box-shadow:0 0 0 2px var(--hue), var(--shadow)}
.card.is-pre{border-color:var(--hue)}
.card.is-post{border-style:dashed; border-color:var(--ink-3)}
.tracebar{display:none; position:sticky; top:44px; z-index:30; margin:0 0 -8px}
body.tracing .tracebar{display:block}
.tracebar div{background:var(--surface); border:1px solid var(--accent); border-radius:var(--radius);
  padding:9px 14px; font-size:13.5px; color:var(--ink-2); box-shadow:var(--shadow)}
.tracebar b{font-family:"IBM Plex Mono",monospace; color:var(--ink)}
.tracebar em{font-style:normal; color:var(--hue-focus,var(--accent))}
.legend{display:flex; flex-wrap:wrap; gap:16px; margin-top:7px; font-size:12px; color:var(--ink-3)}
.legend i{display:inline-block; width:22px; height:0; border-top:2px solid var(--ink-3); vertical-align:3px; margin-right:5px}
.legend i.solid{border-color:var(--accent)}
.legend i.dash{border-top-style:dashed}
.card.hidden{display:none}
.band.hidden{display:none}

/* ---------- chart ---------- */
.two{display:grid; grid-template-columns:minmax(0,1.35fr) minmax(0,1fr); gap:34px; align-items:start;
  padding:52px 0 8px; border-top:1px solid var(--line); margin-top:44px}
@media (max-width:860px){ .two{grid-template-columns:1fr; gap:34px} }
.sec-h{font-family:Archivo,sans-serif; font-size:19px; font-weight:700; letter-spacing:-.015em}
.sec-p{color:var(--ink-2); font-size:14.5px; margin:7px 0 20px; max-width:62ch}
.bars{display:flex; flex-direction:column; gap:3px}
.bar-row{display:grid; grid-template-columns:200px minmax(0,1fr) 34px; gap:11px; align-items:center;
  background:none; border:0; padding:4px 5px; border-radius:4px; cursor:pointer; text-align:left; width:100%}
.bar-row:hover{background:var(--surface-2)}
.bar-name{font-family:"IBM Plex Mono",monospace; font-size:12.5px; color:var(--ink);
  overflow:hidden; text-overflow:ellipsis; white-space:nowrap}
.bar-track{height:11px; background:var(--sunken); border-radius:0 4px 4px 0}
.bar-fill{display:block; height:11px; background:var(--accent); border-radius:0 4px 4px 0}
.bar-val{font-size:12.5px; color:var(--ink-2); text-align:right; font-variant-numeric:tabular-nums}
@media (max-width:520px){ .bar-row{grid-template-columns:130px minmax(0,1fr) 30px} }

.late{list-style:none; margin:0; padding:0; display:flex; flex-direction:column; gap:4px}
.late button{width:100%; display:flex; justify-content:space-between; align-items:center; gap:10px;
  background:var(--surface); border:1px solid var(--line); border-left:3px solid var(--hue);
  border-radius:var(--radius); padding:8px 11px; cursor:pointer; text-align:left}
.late button:hover{border-color:var(--ink-3); border-left-color:var(--hue)}
.lt-name{font-family:"IBM Plex Mono",monospace; font-size:12.5px; color:var(--ink)}
.lt-d{font-size:12px; color:var(--warn); font-variant-numeric:tabular-nums}

/* ---------- gaps ---------- */
.gaps-sec{padding:52px 0 8px; border-top:1px solid var(--line); margin-top:44px}
.gaps{display:grid; grid-template-columns:repeat(auto-fill,minmax(320px,1fr)); gap:12px; margin-top:20px}
.gap{background:var(--surface); border:1px solid var(--line); border-radius:var(--radius); padding:15px 17px;
  display:flex; flex-direction:column; gap:10px; box-shadow:var(--shadow)}
.g-head{display:flex; gap:13px; align-items:center}
.g-n{font-size:26px; font-weight:600; color:var(--warn); font-variant-numeric:tabular-nums; line-height:1}
.g-head h3{font-family:"IBM Plex Mono",monospace; font-size:14.5px; font-weight:600}
.g-sub{font-size:11.5px; color:var(--ink-3)}
.gap p{margin:0; font-size:14px; color:var(--ink-2)}
.g-src{display:flex; flex-wrap:wrap; gap:5px; align-items:center; padding-top:9px; border-top:1px solid var(--line)}

/* ---------- method ---------- */
.method{margin:48px 0 0; padding:22px 0 60px; border-top:1px solid var(--line); color:var(--ink-2); font-size:14px}
.method h2{font-family:Archivo,sans-serif; font-size:15px; font-weight:700; color:var(--ink); margin-bottom:10px}
.method p{margin:0 0 10px; max-width:76ch}
.method b{color:var(--ink)}
.empty{display:none; padding:40px 0; color:var(--ink-3); text-align:center}
body.noresults .empty{display:block}
@media (prefers-reduced-motion:reduce){ *{transition:none !important; animation:none !important} }
'''

# The hand-designed script, unchanged. `const D=…;` is prepended at build time.

JS = r'''const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
const body=document.body;

/* theme */
try{const t=localStorage.getItem('atlas-theme'); if(t)document.documentElement.setAttribute('data-theme',t);}catch(e){}
$('#theme').addEventListener('click',()=>{
  const r=document.documentElement;
  const cur=r.getAttribute('data-theme')||(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');
  const next=cur==='dark'?'light':'dark';
  r.setAttribute('data-theme',next);
  try{localStorage.setItem('atlas-theme',next);}catch(e){}
});

/* links: real anchors on file://, copyable paths when hosted */
const local=location.protocol==='file:';
if(!local){
  $$('.lk').forEach(a=>{
    a.setAttribute('role','button'); a.removeAttribute('href'); a.tabIndex=0;
    a.title='Copy path: '+a.dataset.path;
    const go=()=>{
      const t=a.textContent;
      navigator.clipboard?.writeText(a.dataset.path).then(()=>{
        a.textContent='copied'; a.classList.add('copied');
        setTimeout(()=>{a.textContent=t; a.classList.remove('copied');},1100);
      });
    };
    a.addEventListener('click',e=>{e.stopPropagation();go();});
    a.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();e.stopPropagation();go();}});
  });
}

/* trace */
let focused=null;
function clearTrace(){
  focused=null; body.classList.remove('tracing');
  $$('.card').forEach(c=>c.classList.remove('is-pre','is-post','is-focus'));
}
function trace(name){
  const node=D[name]; if(!node) return;
  clearTrace(); focused=name; body.classList.add('tracing');
  const el=document.getElementById('c-'+name); if(!el) return;
  el.classList.add('is-focus');
  node.p.forEach(p=>document.getElementById('c-'+p)?.classList.add('is-pre'));
  node.d.forEach(d=>document.getElementById('c-'+d)?.classList.add('is-post'));
  $('#tracetext').innerHTML='Tracing <b>'+name+'</b> &#8212; '+node.p.length+
    ' prerequisite'+(node.p.length===1?'':'s')+' (solid) and '+node.d.length+
    ' lab'+(node.d.length===1?'':'s')+' that build on it (dashed). Press Esc to clear.'+
    '<div class="legend"><span><i class="solid"></i>needs this first</span>'+
    '<span><i class="dash"></i>depends on it</span></div>';
  el.scrollIntoView({block:'center',behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});
}
$$('.card').forEach(c=>{
  c.addEventListener('click',e=>{
    if(e.target.closest('.lk'))return;
    const chip=e.target.closest('.chip[data-jump]');
    if(chip){e.stopPropagation(); trace(chip.dataset.jump); return;}
    focused===c.dataset.name?clearTrace():trace(c.dataset.name);
  });
  c.addEventListener('keydown',e=>{
    if(e.key==='Enter'||e.key===' '){e.preventDefault(); focused===c.dataset.name?clearTrace():trace(c.dataset.name);}
  });
});
$$('[data-jump]').forEach(b=>{ if(!b.closest('.card')) b.addEventListener('click',()=>trace(b.dataset.jump)); });
$('#clear').addEventListener('click',clearTrace);
document.addEventListener('keydown',e=>{if(e.key==='Escape')clearTrace();});

/* spine navigation */
$$('.spine-node').forEach(g=>{
  const go=()=>document.getElementById(g.dataset.goto)?.scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});
  g.addEventListener('click',go);
  g.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();go();}});
});

/* filter */
$('#q').addEventListener('input',e=>{
  const q=e.target.value.trim().toLowerCase();
  if(q)clearTrace();
  let total=0;
  $$('.band').forEach(band=>{
    let n=0;
    band.querySelectorAll('.card').forEach(c=>{
      const hit=!q||c.dataset.text.toLowerCase().includes(q);
      c.classList.toggle('hidden',!hit); if(hit)n++;
    });
    band.classList.toggle('hidden',n===0); total+=n;
  });
  body.classList.toggle('noresults',total===0);
});
'''

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
