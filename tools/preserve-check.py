"""preserve-check.py — prove a rewrite lost nothing.

Usage:
    python tools/preserve-check.py <old README.md> <new README.md> [--strict]

Compares the two documents and reports anything present in the old one that is
missing from the new one:

  * numbers (with units)           — every measured or quoted figure
  * code spans                     — identifiers, commands, notation
  * glossary terms                 — bold terms in the glossary table
  * links                          — every relative link target
  * mermaid / fenced blocks        — count must not drop
  * table rows                     — count must not drop
  * word count                     — must not drop below 95% of the old

Exit code 1 when anything load-bearing is missing. Read the report; a rewrite
is allowed to *move* material (for example, epistemic notes into an appendix),
never to drop it.
"""
import re, sys, io

def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()

def norm_num(s):
    s = s.replace(",", "").replace(" ", "").replace(" ", "")
    s = re.sub(r"\.0+$", "", s)
    return s

NUM_RE = re.compile(r"(?<![A-Za-z0-9/_-])(\d[\d,]*(?:\.\d+)?)(\s?(?:ms|µs|us|ns|s|sec|secs|min|h|%|×|x|MB|GB|KB|TB|hops|ticks|rps|qps|ops|req|bytes|B)?)(?![A-Za-z0-9/_-])")

def numbers(text):
    out = set()
    for m in NUM_RE.finditer(text):
        n = norm_num(m.group(1))
        # ignore tiny bare integers (section numbers, list numbers) unless they carry a unit
        if not m.group(2).strip() and re.fullmatch(r"\d{1,2}", n):
            continue
        out.add(n)
    return out

# Lab folders carry a curriculum number (`../39-paxos/`). Strip it before
# comparing, so a snapshot taken before a renumber still matches the file after
# one: it is the same link, at a new name.
NUMBERED = re.compile(r"(^|\.\./)\d+(?:\.\d+)?-")

def unnumber(t):
    return NUMBERED.sub(r"\1", t)

def code_spans(text):
    return set(unnumber(x.strip()) for x in re.findall(r"`([^`\n]+)`", text) if len(x.strip()) > 1)

def glossary_terms(text):
    m = re.search(r"^##\s*\d*\.?\s*GLOSSARY.*$", text, flags=re.M | re.I)
    if not m:
        return set()
    tail = text[m.end():]
    return set(t.strip().lower() for t in re.findall(r"^\|\s*\*\*([^*|]+)\*\*", tail, flags=re.M))

def links(text):
    return set(unnumber(t) for t in re.findall(r"\]\(([^)\s#]+)", text))

def fenced(text):
    return len(re.findall(r"^```", text, flags=re.M)) // 2

def mermaid(text):
    return len(re.findall(r"^```mermaid", text, flags=re.M))

def table_rows(text):
    return sum(1 for l in text.splitlines() if l.strip().startswith("|") and not re.match(r"^\|\s*-", l.strip()))

def words(text):
    t = re.sub(r"```.*?```", " ", text, flags=re.S)
    return len(t.split())

def h2_topics(text):
    out = []
    for h in re.findall(r"^##\s+(.*)$", text, flags=re.M):
        h = re.sub(r"^\d+[\.–\-\s]*", "", h)
        out.append(h.strip())
    return out

def main():
    strict = "--strict" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__); sys.exit(2)
    old, new = read(args[0]), read(args[1])
    fails = 0

    def report(title, missing, load_bearing=True):
        nonlocal fails
        if not missing:
            print(f"  OK   {title}")
            return
        print(f"  {'FAIL' if load_bearing else 'WARN'} {title}: {len(missing)} missing")
        for x in sorted(missing)[:60]:
            print(f"         - {x}")
        if len(missing) > 60:
            print(f"         … and {len(missing) - 60} more")
        if load_bearing:
            fails += 1

    print(f"preserve-check: {args[0]}  ->  {args[1]}")
    on, nn = numbers(old), numbers(new)
    report("numbers", on - nn)
    oc, nc = code_spans(old), code_spans(new)
    # a code span survives if it appears anywhere in the new text, even outside backticks
    new_un = unnumber(new)
    report("code spans", {c for c in oc if c not in nc and c not in new_un}, load_bearing=strict)
    og, ng = glossary_terms(old), glossary_terms(new)
    report("glossary terms", {g for g in og if g not in ng and g not in new.lower()})
    ol, nl = links(old), links(new)
    report("links", ol - nl)
    fo, fn = fenced(old), fenced(new)
    print(f"  {'OK  ' if fn >= fo else 'WARN'} fenced blocks: {fo} -> {fn}")
    mo, mn = mermaid(old), mermaid(new)
    print(f"  {'OK  ' if mn >= mo else 'FAIL'} mermaid diagrams: {mo} -> {mn}")
    if mn < mo: fails += 1
    to, tn = table_rows(old), table_rows(new)
    print(f"  {'OK  ' if tn >= to else 'WARN'} table rows: {to} -> {tn}")
    wo, wn = words(old), words(new)
    ratio = wn / max(1, wo)
    print(f"  {'OK  ' if ratio >= 0.95 else 'FAIL'} words: {wo} -> {wn}  ({ratio:.0%})")
    if ratio < 0.95: fails += 1
    ho = h2_topics(old)
    def survives(h):
        # Match on the heading's longest words. A heading like "KEY IDEA" has no
        # word of five letters, so fall back to shorter ones rather than report
        # it missing on every rewrite that retitles it as a question.
        keys = re.findall(r"[A-Za-z]{5,}", h)[:2] or re.findall(r"[A-Za-z]{3,}", h)[:2]
        return any(k.lower() in new.lower() for k in keys) if keys else True
    lost = [h for h in ho if not survives(h)]
    report("H2 topics (fuzzy)", set(lost), load_bearing=False)
    print("RESULT:", "PASS" if fails == 0 else f"FAIL ({fails} load-bearing checks)")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
