"""mark-rewritten.py — record which labs have been re-delivered in the STYLE.md voice.

The list of finished labs lives in `tools/rewritten.txt`, one slug per line
(blank lines and `#` comments ignored). This script reads it and updates the
two places a reader looks:

  KNOWLEDGE-MAP.md   stamps ` — rewritten for delivery <date>` on the lab's
                     `## slug — learned …` header line (idempotent; the date
                     comes from the slug's line in rewritten.txt if it carries
                     one, else today).
  README.md          rewrites the table between the BACKLOG markers: every lab
                     in curriculum order, its status, and — for a queued lab —
                     the audit signals that put it there.

Usage (from the workspace root):

    python tools/mark-rewritten.py            # update both files
    python tools/mark-rewritten.py --check    # report what would change

A lab is "queued" when `tools/audit.py` trips one of the delivery thresholds
(meta before the story, repeated label templates, measurement asides in the
prose, symbols before words). Everything else is left alone and says so.
"""
import io, os, re, sys, datetime, subprocess, json

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
LIST = os.path.join(ROOT, "tools", "rewritten.txt")
KM = os.path.join(ROOT, "KNOWLEDGE-MAP.md")
PATH_MD = os.path.join(ROOT, "LEARNING-PATH.md")
README = os.path.join(ROOT, "README.md")
# Labs the reader named as hard to read, or that were rebuilt as exemplars.
REPORTED = {"networking-for-developers", "paxos", "communication-protocols"}

def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()

def write(p, s):
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)

def done_list():
    if not os.path.isfile(LIST):
        return {}
    out = {}
    for line in io.open(LIST, encoding="utf-8"):
        line = line.split("#")[0].strip()
        if not line:
            continue
        parts = line.split()
        out[parts[0]] = parts[1] if len(parts) > 1 else datetime.date.today().isoformat()
    return out

def audit_rows():
    """Run tools/audit.py over the workspace and return {slug: row}."""
    tmp = os.path.join(ROOT, "tools", ".audit-tmp.json")
    subprocess.run([sys.executable, os.path.join("tools", "audit.py"), ".", "--json", tmp],
                   cwd=ROOT, capture_output=True, text=True)
    try:
        rows = json.load(io.open(tmp, encoding="utf-8"))
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    # Folders are named NN-slug; index by the bare slug, which is what
    # LEARNING-PATH.md and KNOWLEDGE-MAP.md use.
    return {r.get("slug", r["lab"]): r for r in rows}

def signals(r):
    out = []
    if r["front"] > 40: out.append(f"{r['front']} lines of meta before the story")
    if r["tmpl"] > 25: out.append(f"{r['tmpl_labels']} labels repeated as a template")
    if r["meas"] > 25: out.append(f"{r['meas']} measurement asides in the prose")
    if r["state"] + r["idn"] > 40: out.append("symbols before words")
    return out

def stamp_km(done, check):
    s = read(KM)
    changed = 0
    def sub(m):
        nonlocal changed
        slug, rest = m.group(1), m.group(2)
        if slug not in done:
            return m.group(0)
        if "rewritten for delivery" in rest:
            return m.group(0)
        changed += 1
        # insert before the " — lab: …" tail so the folder stays last
        if " — lab:" in rest:
            head, tail = rest.split(" — lab:", 1)
            return f"## {slug}{head} — rewritten for delivery {done[slug]} — lab:{tail}"
        return f"## {slug}{rest} — rewritten for delivery {done[slug]}"
    s2 = re.sub(r"^## ([a-z0-9-]+)(.*)$", sub, s, flags=re.M)
    if not check and changed:
        write(KM, s2)
    return changed

def in_new_voice(folder):
    """Does this README already carry the STYLE.md markers?

    A lab can be rewritten and committed but never added to rewritten.txt, in
    which case the backlog quietly files it as an untouched lab that happens to
    read well. These two markers appear in every rewrite and in none of the
    originals, so they catch that mistake.
    """
    p = os.path.join(ROOT, folder, "README.md")
    if not os.path.isfile(p):
        return False
    s = read(p)
    return "**Tell a friend:**" in s and re.search(r"^##.*About this lab", s, flags=re.M) is not None

def backlog(done, check):
    order = {m.group(2): m.group(1) for m in
             re.finditer(r"^(\d+(?:\.\d+)?)\.\s+\[`([a-z0-9-]+)`\]", read(PATH_MD), flags=re.M)}
    rows = audit_rows()
    unlisted = []
    lines = ["| # | Lab | Status | Note |", "|---|---|---|---|"]
    n_done = n_queued = n_clean = 0
    for slug in sorted(order, key=lambda s: float(order[s])):
        r = rows.get(slug)
        if r is None:
            # A lab with no README at all — java-memory-model is the only one.
            lines.append(f"| {order[slug]} | `{slug}` | no README yet | visualization and source only; writing it is the top item in the gaps list |")
            continue
        if slug in done:
            # The audit signals describe the file as it stands, so for a lab that
            # has been rewritten they say nothing about why it was queued.
            status, n_done = "**rewritten**", n_done + 1
            note = f"re-delivered {done[slug]}"
            if slug in REPORTED:
                note += ", after the reader reported it hard to read"
        else:
            why = signals(r)
            if slug in REPORTED:
                why.insert(0, "reader reported it hard to read")
            if why:
                status, n_queued = "queued", n_queued + 1
                note = "; ".join(why)
            else:
                status, n_clean = "reads well, left as-is", n_clean + 1
                note = ""
                if in_new_voice(r["lab"]):
                    unlisted.append(slug)
        lines.append(f"| {order[slug]} | `{slug}` | {status} | {note} |")
    head = (f"{n_done} rewritten, {n_queued} queued, {n_clean} left as-is. A lab is left as-is when "
            "`tools/audit.py` finds none of the delivery problems: no wall of meta before the story, "
            "no repeated label templates, few measurement asides, symbols introduced after words.\n\n"
            "Queued labs are re-delivered with `/learn rewrite <slug>`. A rewrite never removes "
            "content: `tools/preserve-check.py` compares old against new and fails if a number, link, "
            "diagram or glossary row went missing, and `tools/xref-check.py` confirms the exercises "
            "still point at the right sections.\n")
    table = head + "\n" + "\n".join(lines)
    s = read(README)
    s2 = re.sub(r"<!-- BACKLOG:START -->.*?<!-- BACKLOG:END -->",
                lambda _: "<!-- BACKLOG:START -->\n" + table + "\n<!-- BACKLOG:END -->", s, flags=re.S)
    if not check and s2 != s:
        write(README, s2)
    return n_done, n_queued, n_clean, s2 != s, unlisted

def main():
    check = "--check" in sys.argv
    done = done_list()
    n = stamp_km(done, check)
    d, q, c, changed, unlisted = backlog(done, check)
    verb = "would stamp" if check else "stamped"
    print(f"{verb} {n} KNOWLEDGE-MAP header(s); backlog: {d} rewritten, {q} queued, {c} left as-is"
          + ("" if changed or check else " (README unchanged)"))
    for slug in unlisted:
        print(f"  WARN {slug} reads as a rewrite (Tell a friend + About this lab) "
              f"but is not in tools/rewritten.txt — add it, or the backlog undercounts")

if __name__ == "__main__":
    main()
