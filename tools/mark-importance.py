"""mark-importance.py — put the two importance ratings into LEARNING-PATH.md.

The ratings live in `tools/importance.tsv`, one line per lab:

    slug<TAB>interview<TAB>learning<TAB>note

They are a judgement call, deliberately **not** derived from the `Builds on`
graph. The `↳N` count on each line says how many labs cite this one — a fact
about how the notes are wired, not a claim about whether the topic is worth
your time. The two disagree in both directions, and this script's second job is
to surface exactly where.

Usage (from the workspace root):

    python tools/mark-importance.py           # rewrite the lab lines and the section
    python tools/mark-importance.py --check   # report what would change

Each lab line becomes

    39. [`paxos`](39-paxos/README.md) ↳13 · I★★ · L★★★ — one-line hook

and the file gains an "Importance" section holding the legend, the two
shortlists, and the divergence table.
"""
import io, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TSV = os.path.join(ROOT, "tools", "importance.tsv")
PATH_MD = os.path.join(ROOT, "LEARNING-PATH.md")
STAR = {1: "★", 2: "★★", 3: "★★★"}

# `N. [`slug`](target) ↳K[ · I★★ · L★★★] — hook`
LINE = re.compile(r"^(\d+(?:\.\d+)?)\.\s+\[`([a-z0-9-]+)`\]\(([^)]+)\)\s+↳(\d+)"
                  r"(?:\s+·\s+I★+\s+·\s+L★+)?\s+—\s+(.*)$", re.M)
MARKER = "<!-- IMPORTANCE:START -->"
END = "<!-- IMPORTANCE:END -->"

def read(p): return io.open(p, encoding="utf-8", errors="replace").read()
def write(p, s): io.open(p, "w", encoding="utf-8", newline="\n").write(s)

def ratings():
    out = {}
    for line in io.open(TSV, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            print(f"ERROR: malformed line in importance.tsv: {line[:60]}"); sys.exit(2)
        slug, iv, lr = parts[0].strip(), int(parts[1]), int(parts[2])
        note = parts[3].strip() if len(parts) > 3 else ""
        out[slug] = (iv, lr, note)
    return out

def section(r, order, indeg):
    """The legend, the two shortlists, and the divergences."""
    def by(key, want):
        return [s for s in order if s in r and r[s][key] == want]
    L = []
    L.append("## Which of these actually matter?\n")
    L.append("Two ratings per lab, on the lines above. They are a judgement call, and they "
             "are **not** the `↳N` count: that says how many labs cite this one, which is a "
             "fact about how these notes are wired together, not a claim about whether the "
             "topic is worth your time. The two disagree in both directions.\n")
    L.append("| Mark | Means |\n|---|---|")
    L.append("| `I★★★` | **Interview:** expect to be asked, and to be fluent |")
    L.append("| `I★★` | Comes up as a follow-up, or as supporting knowledge |")
    L.append("| `I★` | Rarely asked; read it when you are curious |")
    L.append("| `L★★★` | **Learning:** understanding it changes how you think, and unlocks other topics |")
    L.append("| `L★★` | A solid gain, narrower reach |")
    L.append("| `L★` | Narrow or tool-specific |")
    L.append("| `↳N` | How many labs name this one as a prerequisite — structure, not importance |")
    L.append("")
    must_i = by(0, 3)
    L.append(f"### If an interview is close, read these {len(must_i)}\n")
    L.append("In curriculum order. These are the ones you are most likely to be asked about "
             "directly.\n")
    L.append(", ".join(f"`{s}`" for s in must_i) + "\n")
    must_l = by(1, 3)
    L.append(f"### If you want to actually understand this field, read these {len(must_l)}\n")
    L.append("Overlapping but not identical. These are the ones that change how you read "
             "everything else.\n")
    L.append(", ".join(f"`{s}`" for s in must_l) + "\n")
    L.append("### Where the two disagree\n")
    L.append("This is the part worth acting on. The first group is what you would cram and "
             "then forget; the second is what quietly makes you better and will almost never "
             "be asked by name.\n")
    gap_i = [s for s in order if s in r and r[s][0] - r[s][1] >= 1]
    gap_l = [s for s in order if s in r and r[s][1] - r[s][0] >= 2]
    gap_l1 = [s for s in order if s in r and r[s][1] - r[s][0] == 1]
    L.append("| Lab | I | L | ↳ | Why the gap |\n|---|---|---|---|---|")
    for s in gap_i:
        iv, lr, note = r[s]
        L.append(f"| `{s}` | {STAR[iv]} | {STAR[lr]} | {indeg.get(s, 0)} | {note} |")
    L.append("")
    L.append("And the other direction — high learning value, low interview odds:\n")
    L.append("| Lab | I | L | ↳ | Why it is worth it anyway |\n|---|---|---|---|---|")
    for s in gap_l + [x for x in gap_l1 if r[x][1] == 3]:
        iv, lr, note = r[s]
        L.append(f"| `{s}` | {STAR[iv]} | {STAR[lr]} | {indeg.get(s, 0)} | {note} |")
    L.append("")
    L.append("Note the `↳` column in both tables. `flp-impossibility` is cited twice and is "
             "one of the most clarifying labs here; `redis` is cited eleven times and is "
             "mostly vocabulary. That is the whole reason these ratings are not computed from "
             "the graph.\n")
    L.append("Ratings live in `tools/importance.tsv` with a reason on every divergent line. "
             "Edit that file and run `python tools/mark-importance.py` to change them.\n")
    return "\n".join(L)

def main():
    check = "--check" in sys.argv
    r = ratings()
    text = read(PATH_MD)
    order, indeg, missing, changed = [], {}, [], 0

    def sub(m):
        nonlocal changed
        number, slug, target, k, hook = m.groups()
        order.append(slug); indeg[slug] = int(k)
        if slug not in r:
            missing.append(slug)
            return m.group(0)
        iv, lr, _ = r[slug]
        new = f"{number}. [`{slug}`]({target}) ↳{k} · I{STAR[iv]} · L{STAR[lr]} — {hook}"
        if new != m.group(0):
            changed += 1
        return new

    text2 = LINE.sub(sub, text)
    for slug in missing:
        print(f"WARN {slug} is in LEARNING-PATH.md but has no rating in importance.tsv")
    extra = [s for s in r if s not in order]
    for slug in extra:
        print(f"WARN {slug} is rated but is not a lab in LEARNING-PATH.md")

    body = section(r, order, indeg)
    block = f"{MARKER}\n{body}\n{END}"
    if MARKER in text2:
        text2 = re.sub(re.escape(MARKER) + r".*?" + re.escape(END), lambda _: block, text2, flags=re.S)
    else:
        # Put it directly after the numbered order, before the load-bearing section.
        anchor = "## Load-bearing twelve"
        if anchor not in text2:
            print("ERROR: cannot find where to insert the importance section"); sys.exit(2)
        text2 = text2.replace(anchor, block + "\n" + anchor, 1)

    if not check and text2 != text:
        write(PATH_MD, text2)
    verb = "would rewrite" if check else "rewrote"
    print(f"{verb} {changed} lab lines; rated {len(r)} labs, "
          f"{len(missing)} unrated, {len(extra)} stale")

if __name__ == "__main__":
    main()
