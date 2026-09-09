"""renumber.py — keep lab folders in step with the numbered order in LEARNING-PATH.md.

LEARNING-PATH.md is the single source of the curriculum order. Each lab line
looks like:

    39. [`paxos`](39-paxos/README.md) ↳13 — one-line hook

Folders are named  <number>-<slug>  where <number> is the integer part padded
to two digits plus any decimal part:  01-networking-for-developers,
03.5-multipaxos, 39-paxos.

Usage (run from the workspace root):

    python tools/renumber.py --check      # report mismatches and broken links; change nothing
    python tools/renumber.py --apply      # rename folders to match LEARNING-PATH.md, rewrite links
    python tools/renumber.py --renumber   # collapse decimals (3, 3.5, 4 -> 3, 4, 5) in
                                          # LEARNING-PATH.md, then --apply

Inserting a lab between 3 and 4 is done by giving it the number 3.5 in
LEARNING-PATH.md and naming its folder 03.5-<slug>. Nothing else moves. Only
run --renumber when the user asks for a clean integer sequence again.

The link rewriter only touches path-like contexts:  ../<folder>/  ](<folder>/
lab: <folder>/  href="<folder>/  cd <folder>  and the .gitignore lines. Prose
mentions of a slug are left alone.
"""
import os, re, sys, io, subprocess

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PATH_MD = os.path.join(ROOT, "LEARNING-PATH.md")
LINE_RE = re.compile(r"^(\d+(?:\.\d+)?)\.\s+\[`([a-z0-9-]+)`\]\(([^)]+)\)", re.M)
FOLDER_RE = re.compile(r"^(\d{2}(?:\.\d+)?)-([a-z0-9-]+)$")
NOT_LABS = {"lib", "tools", "dino-rescue-adventure", ".git", ".claude"}
TEXT_EXT = {".md", ".html", ".txt", ".sh", ".gitignore"}

def folder_for(number, slug):
    if "." in number:
        i, d = number.split(".", 1)
        return f"{int(i):02d}.{d}-{slug}"
    return f"{int(number):02d}-{slug}"

def read(p):
    return io.open(p, encoding="utf-8", errors="replace").read()

def write(p, s):
    io.open(p, "w", encoding="utf-8", newline="\n").write(s)

def desired():
    text = read(PATH_MD)
    out = []
    seen = set()
    for m in LINE_RE.finditer(text):
        number, slug, target = m.group(1), m.group(2), m.group(3)
        if slug in seen:
            print(f"ERROR: {slug} listed twice in LEARNING-PATH.md"); sys.exit(2)
        seen.add(slug)
        out.append((number, slug, folder_for(number, slug), target))
    return out

def current_folders():
    out = {}
    for d in os.listdir(ROOT):
        if d in NOT_LABS or not os.path.isdir(os.path.join(ROOT, d)) or d.startswith("."):
            continue
        m = FOLDER_RE.match(d)
        slug = m.group(2) if m else d
        out[slug] = d
    return out

def text_files():
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in {".git", "out"}]
        for f in fn:
            if os.path.splitext(f)[1] in TEXT_EXT or f == ".gitignore":
                yield os.path.join(dp, f)

def rewrite_links(renames):
    """renames: {old_folder: new_folder}"""
    if not renames:
        return 0
    changed = 0
    pats = []
    for old, new in renames.items():
        o = re.escape(old)
        pats += [
            (re.compile(r"(\.\./)" + o + r"(/|\)|`|$)", re.M), r"\g<1>" + new + r"\g<2>"),
            (re.compile(r"(\]\()" + o + r"(/)"), r"\g<1>" + new + r"\g<2>"),
            (re.compile(r"(lab: )" + o + r"(/)"), r"\g<1>" + new + r"\g<2>"),
            (re.compile(r'(href=")' + o + r"(/)"), r"\g<1>" + new + r"\g<2>"),
            (re.compile(r"(cd (?:~/Documents/(?:github/)?Learning/)?)" + o + r"(\s|$)", re.M), r"\g<1>" + new + r"\g<2>"),
            (re.compile(r"(Documents/(?:github/)?Learning/)" + o + r"(/|`|\s|$)", re.M), r"\g<1>" + new + r"\g<2>"),
            (re.compile(r"^" + o + r"(/)", re.M), new + r"\g<1>"),  # .gitignore-style lines
        ]
    for f in text_files():
        s = read(f); t = s
        for rx, rep in pats:
            t = rx.sub(rep, t)
        if t != s:
            write(f, t); changed += 1
    return changed

def check_links():
    bad = []
    for f in text_files():
        if not f.endswith(".md"):
            continue
        base = os.path.dirname(f)
        for m in re.finditer(r"\]\(([^)\s#]+)", read(f)):
            t = m.group(1)
            if re.match(r"[a-z]+:", t):
                continue
            p = os.path.normpath(os.path.join(base, t))
            if not os.path.exists(p):
                bad.append((os.path.relpath(f, ROOT), t))
    return bad

def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "--check"
    if mode == "--renumber":
        text = read(PATH_MD)
        n = 0
        def sub(m):
            nonlocal n
            n += 1
            return f"{n}. [`{m.group(2)}`]({folder_for(str(n), m.group(2))}/README.md)"
        write(PATH_MD, LINE_RE.sub(sub, text))
        print(f"LEARNING-PATH.md renumbered 1..{n}")
        mode = "--apply"
    want = desired()
    have = current_folders()
    renames = {}
    problems = 0
    for number, slug, folder, target in want:
        if slug not in have:
            print(f"MISSING folder for {slug} (expected {folder})"); problems += 1; continue
        if have[slug] != folder:
            renames[have[slug]] = folder
        if not target.startswith(folder + "/"):
            print(f"LINK   {slug}: LEARNING-PATH.md points at {target}, expected {folder}/README.md"); problems += 1
    listed = {s for _, s, _, _ in want}
    for slug, d in sorted(have.items()):
        if slug not in listed:
            print(f"UNLISTED folder {d} — add it to LEARNING-PATH.md"); problems += 1
    for old, new in sorted(renames.items()):
        print(f"RENAME {old}  ->  {new}")
    if mode == "--check":
        bad = check_links()
        for f, t in bad[:50]:
            print(f"BROKEN {f}: {t}")
        if len(bad) > 50:
            print(f"… and {len(bad) - 50} more broken links")
        print("OK — folders match LEARNING-PATH.md and all relative links resolve" if not (problems or renames or bad) else f"{problems} problems, {len(renames)} renames pending, {len(bad)} broken links")
        sys.exit(1 if (problems or renames or bad) else 0)
    if mode == "--apply":
        for old, new in renames.items():
            src, dst = os.path.join(ROOT, old), os.path.join(ROOT, new)
            r = subprocess.run(["git", "mv", old, new], cwd=ROOT, capture_output=True, text=True)
            if r.returncode != 0:
                os.rename(src, dst)
        n = rewrite_links(renames)
        # LEARNING-PATH.md link targets
        # Rewrite only the folder part of each link, so a lab that points at
        # something other than README.md (java-memory-model has no README, only
        # a visualization) keeps pointing at the file it actually has.
        def fix(m):
            number, slug, target = m.group(1), m.group(2), m.group(3)
            tail = target.split("/", 1)[1] if "/" in target else "README.md"
            return f"{number}. [`{slug}`]({folder_for(number, slug)}/{tail})"
        write(PATH_MD, LINE_RE.sub(fix, read(PATH_MD)))
        bad = check_links()
        print(f"renamed {len(renames)} folders, rewrote links in {n} files, {len(bad)} broken links remain")
        for f, t in bad[:50]:
            print(f"BROKEN {f}: {t}")
        sys.exit(1 if bad else 0)
    print(__doc__)

if __name__ == "__main__":
    main()
