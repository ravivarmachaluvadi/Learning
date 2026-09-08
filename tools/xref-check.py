"""xref-check.py — do EXERCISES.md / SOLUTIONS.md still point at real README sections?

Usage:  python tools/xref-check.py <lab-folder> [<lab-folder> ...]

Collects every "§N", "stage N", "section N", "Stage N" reference in the lab's
EXERCISES.md and SOLUTIONS.md and confirms the README has a "## N." heading for
it. Also lists the README's highest section number, so a reference past the end
is easy to spot. Experiment / failure / scenario numbers are not checked here;
they are kept by convention.
"""
import io, os, re, sys

def headings(readme):
    nums = set()
    for m in re.finditer(r"^##\s+(\d+)(?:[–-](\d+))?\.", readme, flags=re.M):
        a = int(m.group(1)); b = int(m.group(2) or a)
        nums.update(range(a, b + 1))
    return nums

def refs(text):
    out = set()
    for m in re.finditer(r"(?:§|\b[Ss]tage\s+|\b[Ss]ection\s+)(\d+)(?:\.\d+)?", text):
        out.add(int(m.group(1)))
    return out

bad = 0
for lab in sys.argv[1:]:
    rp = os.path.join(lab, "README.md")
    if not os.path.isfile(rp):
        print(f"{lab}: no README"); continue
    have = headings(io.open(rp, encoding="utf-8", errors="replace").read())
    for f in ("EXERCISES.md", "SOLUTIONS.md"):
        p = os.path.join(lab, f)
        if not os.path.isfile(p):
            continue
        want = refs(io.open(p, encoding="utf-8", errors="replace").read())
        missing = sorted(n for n in want if n not in have and n <= max(have, default=0) + 1)
        if missing:
            bad += 1
            print(f"{lab}/{f}: references sections {missing} — README has {sorted(have)}")
        else:
            print(f"{lab}/{f}: OK ({len(want)} section refs, README sections 1..{max(have, default=0)})")
sys.exit(1 if bad else 0)
