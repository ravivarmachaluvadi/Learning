#!/usr/bin/env python3
"""build-hub.py — build the "Learning Hub", the private claude.ai artifact that carries every lab.

One link opens, from any computer signed in to claude.ai, the learning tools (each is its own
artifact) and all the labs. Builds hub/ (git-ignored); the lab folders are only read, never changed:

    index.html                   the start page: the learning tools, then every lab by stage
    atlas.html                   the Systems Atlas (gen-atlas.py's page), its links kept inside the hub
    NN-slug/notes.html           the lab's README, EXERCISES and SOLUTIONS as one page
                                 (the solutions stay folded until opened)
    NN-slug/visualization.html   the lab's visualization, unchanged, inside a complete HTML document
                                 with a small bar: back, the notes, full screen

A page published beside the artifact's main page is served as it is (only the main page gets the
artifact's <!doctype>/<head> wrapper), so every other page here is written as a complete document.
Markdown is rendered here with mistune; ```mermaid blocks become <pre class="mermaid">, which
claude.ai draws itself. Links to code files (.java) say they are not in the hub.

Usage (from the workspace root):

    python -I tools/build-hub.py          # build hub/, print the files to publish as JSON

Claude then publishes hub/index.html with those files (root hub/) to the URL in README.md.
"""
import html, json, os, re, subprocess, sys

import mistune

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "hub")

# The learning tools, each published as its own private artifact (their repos sit next to this one).
TOOLS = [
    ("Claude Learning Tool", "https://claude.ai/artifact/F2dXmCHdbmVsXytTenxzXL",
     "Claude Code for everyday feature work: commands, prompts, playbooks, checkers and ready-made skills.",
     "Everything works here; Download asks claude.ai to save the file."),
    ("Lead Engineer Learning Tool", "https://claude.ai/artifact/FNCmKsUowHCJDJcVdYHJUf",
     "The parts of a lead's job that code does not cover: deadlines, estimates, status, priorities and people.",
     "Everything works here; Download asks claude.ai to save the file."),
    ("UI Learning Tool", "https://claude.ai/artifact/Sf2hHwAPS7M3YNuzpPJDAs",
     "HTML, CSS and JavaScript with a live preview of every element, attribute, property and value.",
     "Everything works here, previews included."),
    ("React Learning Tool", "https://claude.ai/artifact/LT8qhSzYynVbUNKmzk9gkt",
     "React 19 by running it, with interview flashcards and timed machine-coding problems.",
     "Your components run in the page, on React 19.3."),
    ("Python Learning Tool", "https://claude.ai/artifact/1inYnSfBz8fXwdpcDRZKPD",
     "Python from the first line to advanced topics, written for a Java developer.",
     "For reading here: lessons, katas, quizzes, the Reference. Running Python needs the tool on your own computer."),
    ("Kotlin Learning Tool", "https://claude.ai/artifact/Xy64TnYBZP6RY58dfPCjcQ",
     "Kotlin and coroutines as a Java developer, with checked challenges and the interview questions asked most.",
     "For reading here: lessons, quizzes, flashcards, the Reference. Running Kotlin needs ktlearn on your laptop."),
]

FONTS = ('<link rel="preconnect" href="https://fonts.googleapis.com">\n'
         '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n'
         '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@500;600;700'
         '&family=IBM+Plex+Mono:wght@400;500;600&family=Source+Sans+3:ital,wght@0,400;0,600;1,400&display=swap">\n')

# the artifact's own wrapper, repeated for the pages served beside the main page
RESET = (':root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}'
         'body{margin:0;font:14px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}'
         'img{max-width:100%}[hidden]{display:none!important}')

# the Systems Atlas palette (LEARNING-PATH.html), so the hub reads as part of it
TOKENS = """:root{
  --ground:#EFF2F0; --surface:#FAFCFA; --surface-2:#E5EAE7; --sunken:#E9EEEB;
  --ink:#16211E; --ink-2:#55645F; --ink-3:#8A9994; --line:#D3DBD7; --line-2:#C0CAC5;
  --accent:#0F6E63; --warn:#A2621A; --code:#E4EAE6;
  --display:"Archivo","Segoe UI",system-ui,sans-serif;
  --body:"Source Sans 3","Segoe UI",system-ui,sans-serif;
  --mono:"IBM Plex Mono",ui-monospace,Consolas,monospace;
}
@media (prefers-color-scheme: dark){ :root:not([data-theme="light"]){
  --ground:#0E1415; --surface:#161E1F; --surface-2:#1F2A2B; --sunken:#121A1B;
  --ink:#E2EAE7; --ink-2:#9AAAA5; --ink-3:#6A7B76; --line:#2A3536; --line-2:#384748;
  --accent:#45C1B0; --warn:#DFA05C; --code:#1B2425; color-scheme:dark;
}}
:root[data-theme="dark"]{
  --ground:#0E1415; --surface:#161E1F; --surface-2:#1F2A2B; --sunken:#121A1B;
  --ink:#E2EAE7; --ink-2:#9AAAA5; --ink-3:#6A7B76; --line:#2A3536; --line-2:#384748;
  --accent:#45C1B0; --warn:#DFA05C; --code:#1B2425; color-scheme:dark;
}
"""

ICON_FULL = ('<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" '
             'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>')

# full screen and "where you were", shared by every page of the hub
PAGE_JS = """<script>
(function () {
  var b = document.getElementById('hub-full');
  if (b) {
    if (!document.fullscreenEnabled) b.hidden = true;
    var paint = function () {
      var on = !!document.fullscreenElement;
      b.setAttribute('aria-pressed', String(on));
      b.title = on ? 'Leave full screen (Esc)' : 'Full screen';
      var l = b.querySelector('.hub-full-label'); if (l) l.textContent = on ? 'Exit full screen' : 'Full screen';
    };
    b.addEventListener('click', function () {
      if (document.fullscreenElement) document.exitFullscreen().catch(function () {});
      else document.documentElement.requestFullscreen().catch(function () {});
    });
    document.addEventListener('fullscreenchange', paint); paint();
  }
  var here = document.documentElement.getAttribute('data-hub-lab');
  if (here) try { localStorage.setItem('hub.last', JSON.stringify({ lab: here, page: location.pathname.split('/').pop() || 'notes.html', at: Date.now() })); } catch (e) {}
})();
</script>"""

# the small bar on a visualization (and on the Atlas): it has to sit on someone else's design, so
# it brings its own colours and stays out of the way until the pointer comes near
BAR_CSS = """<style>
.hub-bar{position:fixed;right:calc(12px + env(safe-area-inset-right,0px));bottom:calc(12px + env(safe-area-inset-bottom,0px));z-index:2147483000;
  display:flex;gap:2px;padding:3px;border-radius:999px;background:rgba(14,20,21,.94);border:1px solid rgba(255,255,255,.22);
  box-shadow:0 4px 18px rgba(0,0,0,.3);font:600 12.5px/1 "Segoe UI",system-ui,sans-serif;opacity:.82;transition:opacity .15s}
.hub-bar:hover,.hub-bar:focus-within{opacity:1}
.hub-bar a,.hub-bar button{all:unset;display:inline-flex;align-items:center;gap:6px;padding:7px 11px;border-radius:999px;color:#EEF4F1;cursor:pointer;white-space:nowrap}
.hub-bar a:hover,.hub-bar button:hover{background:rgba(255,255,255,.14)}
.hub-bar a:focus-visible,.hub-bar button:focus-visible{outline:2px solid #45C1B0;outline-offset:1px}
.hub-bar [hidden]{display:none!important}
@media (prefers-reduced-motion: reduce){.hub-bar{transition:none}}
@media print{.hub-bar{display:none}}
</style>"""


def esc(s):
    return html.escape(s, quote=True)


def inline_md(s):
    """A one-line summary from LEARNING-PATH.md: its **bold** and `code`, as HTML."""
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", esc(s))
    return re.sub(r"`([^`]+)`", r"<code>\1</code>", s)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(rel, text):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def document(title, head, body, lab=None):
    """A complete page, for anything published beside the main page."""
    attr = f' data-hub-lab="{esc(lab)}"' if lab else ""
    return (f'<!doctype html>\n<html lang="en"{attr}>\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
            f'<style>{RESET}</style>\n<title>{esc(title)}</title>\n{head}</head>\n<body>\n{body}\n</body>\n</html>\n')


# ---------------------------------------------------------------- the order: LEARNING-PATH.md

def load_path():
    stages, labs = [], []
    stage = None
    for line in read(os.path.join(ROOT, "LEARNING-PATH.md")).splitlines():
        m = re.match(r"### Stage (\d+) — (.+)", line)
        if m:
            stage = {"n": int(m.group(1)), "title": m.group(2).strip(), "labs": []}
            stages.append(stage)
            continue
        m = re.match(r"([\d.]+)\. \[`([^`]+)`\]\(([^/]+)/README\.md\)(.*?)— (.+)$", line)
        if m and stage:
            lab = {"n": m.group(1), "slug": m.group(2), "folder": m.group(3), "hook": m.group(5).strip(),
                   "stage": stage["n"], "stage_title": stage["title"]}
            readme = read(os.path.join(ROOT, lab["folder"], "README.md"))
            h1 = re.search(r"^# (.+)$", readme, re.M)
            lab["title"] = h1.group(1).strip() if h1 else lab["slug"]
            viz = os.path.join(ROOT, lab["folder"], "visualization.html")
            t = re.search(r"<title>(.*?)</title>", read(viz), re.S) if os.path.exists(viz) else None
            lab["viz_title"] = html.unescape(t.group(1).strip()) if t else ""
            lab["has_viz"] = os.path.exists(viz)
            labs.append(lab)
            stage["labs"].append(lab)
    return stages, labs


# ---------------------------------------------------------------- Markdown -> HTML

DOCS = (("README.md", "readme", "Read"), ("EXERCISES.md", "exercises", "Exercises"), ("SOLUTIONS.md", "solutions", "Solutions"))
DOC_PART = {name: part for name, part, _ in DOCS}


def slugify(text):
    """GitHub's heading ids: lower case, punctuation dropped, spaces to hyphens."""
    text = re.sub(r"<[^>]+>", "", html.unescape(text)).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


class LabRenderer(mistune.HTMLRenderer):
    def __init__(self, part, base, site, missing):
        super().__init__(escape=False)
        # base: the folder the document sits in (its links are relative to it); site: what the hub holds
        self.part, self.base, self.site, self.missing = part, base, site, missing
        self.seen, self.toc = {}, []

    def heading(self, text, level, **attrs):
        base = slugify(text) or "section"
        n = self.seen.get(base, 0)
        self.seen[base] = n + 1
        hid = f"{self.part}-{base}" + (f"-{n}" if n else "")
        if level == 2:
            self.toc.append((hid, html.unescape(re.sub(r"<[^>]+>", "", text))))
        return f'<h{level} id="{hid}">{text}</h{level}>\n'

    def block_code(self, code, info=None):
        lang = (info or "").split()[0] if info else ""
        if lang == "mermaid":
            return f'<pre class="mermaid">{esc(code)}</pre>\n'
        cls = f' class="language-{esc(lang)}"' if lang else ""
        return f'<div class="code"><pre><code{cls}>{esc(code)}</code></pre></div>\n'

    def table(self, text):
        return f'<div class="table"><table>{text}</table></div>\n'

    def link(self, text, url, title=None):
        href = self.rewrite(url)
        t = f' title="{esc(title)}"' if title else ""
        if href is None:
            self.missing.append(url)
            return f'<span class="missing" title="Not in the hub: {esc(url)} is in the lab folder on your laptop">{text}</span>'
        ext = ' target="_blank" rel="noopener"' if re.match(r"https?:", href) else ""
        return f'<a href="{esc(href)}"{t}{ext}>{text}</a>'

    def rewrite(self, url):
        """Where a link in a lab document points inside the hub, or None when its target is not here."""
        if re.match(r"(https?|mailto):", url):
            return url
        path, _, anchor = url.partition("#")
        if not path:
            return f"#{self.part}-{anchor}" if anchor else "#"
        page = self.site.page_for(os.path.normpath(os.path.join(self.base, path)).replace("\\", "/"), anchor)
        if page is None:
            return None
        target, frag = page
        return os.path.relpath(target, self.base or ".").replace("\\", "/") + (f"#{frag}" if frag else "")


class Site:
    """What the hub holds, by source path: the labs' folders, their documents, their visualizations."""

    def __init__(self, labs):
        self.folders = {lab["folder"] for lab in labs}
        self.extra = {}   # "21-java-version-features/java-17.md" -> its page, "…/java-17.html"
        for lab in labs:
            for dirpath, dirs, files in os.walk(os.path.join(ROOT, lab["folder"])):
                dirs[:] = [d for d in dirs if not d.startswith("_")]   # _java-original/, _python-original/
                for f in files:
                    rel = os.path.relpath(os.path.join(dirpath, f), ROOT).replace("\\", "/")
                    main_doc = rel == f'{lab["folder"]}/{f}' and f in DOC_PART   # on the notes page
                    if f.endswith(".md") and not main_doc:
                        self.extra[rel] = rel[:-3] + ".html"

    def page_for(self, target, anchor):
        """(page, fragment) for a source path, or None."""
        if target in ("LEARNING-PATH.md", "LEARNING-PATH.html"):
            return "atlas.html", ""
        parts = target.split("/")
        if parts[0] in self.folders and len(parts) <= 2:
            name = parts[1] if len(parts) == 2 else ""
            if name in ("", "README.md", "EXERCISES.md", "SOLUTIONS.md"):
                part = DOC_PART.get(name, "readme")
                return f"{parts[0]}/notes.html", (f"{part}-{anchor}" if anchor else part)
            if name == "visualization.html" and os.path.exists(os.path.join(ROOT, target)):
                return target, ""
        if target in self.extra:
            return self.extra[target], (f"doc-{anchor}" if anchor else "")
        return None


def render(md, part, base, site, missing):
    r = LabRenderer(part, base, site, missing)
    to_html = mistune.create_markdown(renderer=r, plugins=["table", "strikethrough", "footnotes", "task_lists", "url", "def_list"])
    out = to_html(md)

    def raw(m):   # links written as HTML inside the Markdown still point at a folder or a .md file
        new = r.rewrite(html.unescape(m.group(1)))
        return f'href="{esc(new)}"' if new else m.group(0)
    out = re.sub(r'href="([^"#:]*(?:/|\.md)(?:#[^"]*)?)"', raw, out)
    return out, r.toc


# ---------------------------------------------------------------- the pages

NOTES_CSS = """<style>
""" + TOKENS + """
body{background:var(--ground);color:var(--ink);font-family:var(--body);font-size:17px;line-height:1.6}
a{color:var(--accent)}
.top{position:sticky;top:env(safe-area-inset-top,0px);z-index:20;background:var(--surface);border-bottom:1px solid var(--line);
  padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
body::before{content:"";position:fixed;inset:0 0 auto;height:env(safe-area-inset-top,0px);z-index:30;background:var(--surface)}
.top-in{max-width:1180px;margin:0 auto;display:flex;flex-wrap:wrap;align-items:center;gap:6px 14px;padding-block:8px;font-size:14px}
.crumb{font-family:var(--mono);font-size:12.5px;color:var(--ink-2);text-decoration:none;white-space:nowrap}
.crumb:hover{color:var(--accent)}
.where{font-family:var(--mono);font-size:12.5px;color:var(--ink-3);white-space:nowrap}
.tabs{display:flex;gap:2px;flex-wrap:wrap;margin-left:auto}
.tabs a,.tbtn{display:inline-flex;align-items:center;gap:6px;padding:5px 10px;border-radius:5px;color:var(--ink-2);text-decoration:none;font-weight:600;
  border:0;background:none;font:inherit;font-weight:600;cursor:pointer}
.tabs a:hover,.tbtn:hover{background:var(--surface-2);color:var(--ink)}
.tabs a:focus-visible,.tbtn:focus-visible,.crumb:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.wrap{max-width:1180px;margin:0 auto;display:grid;grid-template-columns:minmax(0,1fr) 250px;gap:40px;
  padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px));padding-block:28px 80px}
main{min-width:0;max-width:74ch}
aside{position:sticky;top:64px;align-self:start;max-height:calc(100vh - 90px);overflow:auto;font-size:13.5px;line-height:1.45}
aside h2{font-family:var(--mono);font-size:11.5px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);margin:18px 0 6px}
aside a{display:block;padding:3px 0;color:var(--ink-2);text-decoration:none}
aside a:hover{color:var(--accent)}
.kicker{font-family:var(--mono);font-size:12.5px;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-3);margin:0 0 6px}
h1,h2,h3,h4{font-family:var(--display);line-height:1.2;text-wrap:balance;color:var(--ink)}
h1{font-size:2rem;margin:0 0 18px}
h2{font-size:1.45rem;margin:2.2em 0 .6em;padding-top:.4em;border-top:1px solid var(--line)}
h3{font-size:1.15rem;margin:1.8em 0 .5em}
h1,h2,h3,h4{scroll-margin-top:72px}
.part{font-size:1.9rem;border-top:3px solid var(--ink);padding-top:.6em;margin-top:2.6em}
blockquote{margin:1.2em 0;padding:.6em 1em;border-left:3px solid var(--accent);background:var(--surface);color:var(--ink-2)}
blockquote p{margin:.4em 0}
code{font-family:var(--mono);font-size:.86em;background:var(--code);padding:.1em .35em;border-radius:4px}
.code{overflow-x:auto;background:var(--sunken);border:1px solid var(--line);border-radius:6px;margin:1em 0}
.code pre{margin:0;padding:12px 14px;line-height:1.5}
.code code{background:none;padding:0;font-size:13.5px;white-space:pre}
pre.mermaid{background:var(--surface);border:1px solid var(--line);border-radius:6px;padding:12px;overflow-x:auto;font-family:var(--mono);font-size:12px}
.table{overflow-x:auto;margin:1em 0}
table{border-collapse:collapse;font-size:15px;line-height:1.45}
th,td{border:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top}
th{background:var(--surface-2);font-family:var(--display);font-weight:600}
td{background:var(--surface)}
hr{border:0;border-top:1px solid var(--line);margin:2em 0}
.missing{text-decoration:underline dotted var(--ink-3);color:var(--ink-2);cursor:help}
details.sol{margin-top:1em;border:1px solid var(--line);border-radius:6px;background:var(--surface)}
details.sol>summary{cursor:pointer;padding:12px 16px;font-family:var(--display);font-weight:600;color:var(--accent)}
details.sol>summary:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
details.sol>div{padding:0 16px 16px}
.pager{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:3em;padding-top:1em;border-top:1px solid var(--line);font-size:15px}
.pager a{text-decoration:none;max-width:48%}
.pager small{display:block;font-family:var(--mono);font-size:12px;color:var(--ink-3)}
.hljs-keyword,.hljs-built_in,.hljs-type{color:var(--accent);font-weight:600}
.hljs-string,.hljs-number,.hljs-literal{color:var(--warn)}
.hljs-comment{color:var(--ink-3);font-style:italic}
.hljs-title,.hljs-function{color:var(--ink)}
@media (max-width:1000px){.wrap{grid-template-columns:minmax(0,1fr)}aside{display:none}}
@media (max-width:600px){body{font-size:16px}h1{font-size:1.6rem}.tabs{margin-left:0}}
@media (prefers-reduced-motion: reduce){html{scroll-behavior:auto}}
</style>
"""

NOTES_JS = """<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
<script>
(function () {
  // the solutions stay folded until asked for: a link into them (or the Solutions tab) opens them
  var sol = document.getElementById('solutions-fold');
  function openFor(hash) {
    if (!sol || !hash) return;
    if (hash === '#solutions' || hash.indexOf('#solutions-') === 0) {
      sol.open = true;
      var el = document.getElementById(hash.slice(1));
      if (el) el.scrollIntoView();
    }
  }
  openFor(location.hash);
  window.addEventListener('hashchange', function () { openFor(location.hash); });
  if (window.hljs) document.querySelectorAll('.code code[class*="language-"]').forEach(function (el) {
    try { hljs.highlightElement(el); } catch (e) {}
  });
  // claude.ai draws Mermaid only on an artifact's main page, so these pages draw their own diagrams
  var diagrams = document.querySelectorAll('pre.mermaid');
  if (diagrams.length) {
    var s = document.createElement('script');
    s.src = 'https://cdnjs.cloudflare.com/ajax/libs/mermaid/11.12.0/mermaid.min.js';
    s.onload = function () {
      var forced = document.documentElement.getAttribute('data-theme');
      var dark = forced ? forced === 'dark' : !!(window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true, theme: dark ? 'dark' : 'neutral',
                           fontFamily: '"Source Sans 3", "Segoe UI", system-ui, sans-serif' });
      // one at a time, and the text is replaced only by a drawing that worked: a diagram with a syntax error in
      // the lab's source stays readable as text
      [].reduce.call(diagrams, function (done, pre, i) {
        return done.then(function () {
          return mermaid.render('hub-diagram-' + i, pre.textContent).then(function (r) { pre.innerHTML = r.svg; }, function () {
            pre.title = 'This diagram has a syntax error in the lab, so it is shown as its text';
            var left = document.getElementById('dhub-diagram-' + i);
            if (left) left.remove();
          });
        });
      }, Promise.resolve());
    };
    document.head.appendChild(s);
  }
})();
</script>
"""


def full_button(label=True):
    text = '<span class="hub-full-label">Full screen</span>' if label else ""
    return f'<button class="tbtn" id="hub-full" type="button" aria-pressed="false">{ICON_FULL}{text}</button>'


def top_bar(up, where, tabs):
    return ('<header class="top"><div class="top-in">'
            f'<a class="crumb" href="{up}index.html">Learning Hub</a><span class="where">/</span>'
            f'<a class="crumb" href="{up}atlas.html">Atlas</a>'
            f'<span class="where">{where}</span>'
            f'<nav class="tabs" aria-label="This lab">{"".join(tabs)}{full_button()}</nav>'
            '</div></header>')


def extras_of(lab, site):
    return sorted(p for src, p in site.extra.items() if src.startswith(lab["folder"] + "/"))


def notes_page(lab, prev, nxt, site, missing):
    sections, toc = [], []
    for name, part, label in DOCS:
        path = os.path.join(ROOT, lab["folder"], name)
        if not os.path.exists(path):
            continue
        body, heads = render(read(path), part, lab["folder"], site, missing)
        if part == "readme":
            sections.append(f'<article id="readme">{body}</article>')
        elif part == "exercises":
            sections.append(f'<section id="exercises"><h2 class="part">Exercises</h2>{body}</section>')
        else:
            sections.append('<section id="solutions"><h2 class="part">Solutions</h2>'
                            '<details class="sol" id="solutions-fold"><summary>Show the solutions (try the exercises first)</summary>'
                            f'<div>{body}</div></details></section>')
        toc.append((part, label, heads))
    tabs = ['<a href="#readme">Read</a>', '<a href="#exercises">Exercises</a>', '<a href="#solutions">Solutions</a>']
    if lab["has_viz"]:
        tabs.append('<a href="visualization.html">Visual</a>')
    side = "".join(f'<h2>{esc(label)}</h2><a href="#{part}">Top of {esc(label.lower())}</a>' if part != "readme" else
                   "<h2>Read</h2>" + "".join(f'<a href="#{hid}">{esc(t)}</a>' for hid, t in heads)
                   for part, label, heads in toc)
    more = extras_of(lab, site)
    if more:
        side += "<h2>More in this lab</h2>" + "".join(
            f'<a href="{esc(os.path.relpath(p, lab["folder"]).replace(chr(92), "/"))}">{esc(os.path.splitext(os.path.relpath(p, lab["folder"]))[0])}</a>'
            for p in more)
    pager = '<nav class="pager" aria-label="Other labs">'
    pager += (f'<a href="../{prev["folder"]}/notes.html"><small>← Lab {prev["n"]}</small>{esc(prev["slug"])}</a>' if prev else "<span></span>")
    pager += (f'<a href="../{nxt["folder"]}/notes.html" style="text-align:right"><small>Lab {nxt["n"]} →</small>{esc(nxt["slug"])}</a>' if nxt else "<span></span>")
    pager += "</nav>"
    top = top_bar("../", f'Lab {lab["n"]} of {TOTAL} · Stage {lab["stage"]}', tabs)
    body = (top + f'<div class="wrap"><main><p class="kicker">Lab {lab["n"]} · {esc(lab["stage_title"])}</p>'
            + "".join(sections) + pager + f'</main><aside aria-label="On this page">{side}</aside></div>'
            + NOTES_JS + PAGE_JS)
    return document(f'{lab["n"]}. {lab["slug"]}', FONTS + NOTES_CSS, body, lab=lab["folder"])


def extra_page(lab, src, site, missing):
    """Another document of a lab (a playbook, a catalogue, one page per Java release), on its own page."""
    base = os.path.dirname(src)
    up = "../" * (base.count("/") + 1)
    md = read(os.path.join(ROOT, src))
    body, heads = render(md, "doc", base, site, missing)
    h1 = re.search(r"^# (.+)$", md, re.M)
    rel = lambda p: os.path.relpath(p, base).replace("\\", "/")
    tabs = [f'<a href="{rel(lab["folder"] + "/notes.html")}">Lab notes</a>']
    if lab["has_viz"]:
        tabs.append(f'<a href="{rel(lab["folder"] + "/visualization.html")}">Visual</a>')
    side = "<h2>On this page</h2>" + "".join(f'<a href="#{hid}">{esc(t)}</a>' for hid, t in heads)
    side += "<h2>More in this lab</h2>" + "".join(
        f'<a href="{esc(rel(p))}">{esc(os.path.splitext(os.path.relpath(p, lab["folder"]))[0])}</a>' for p in extras_of(lab, site))
    top = top_bar(up, f'Lab {lab["n"]} · {esc(os.path.relpath(src, lab["folder"]))}', tabs)
    page = (top + f'<div class="wrap"><main><p class="kicker">Lab {lab["n"]} · {esc(lab["slug"])}</p>{body}</main>'
            f'<aside aria-label="On this page">{side}</aside></div>' + NOTES_JS + PAGE_JS)
    return document(h1.group(1).strip() if h1 else os.path.basename(src), FONTS + NOTES_CSS, page, lab=lab["folder"])


def bar(links):
    items = "".join(f'<a href="{esc(h)}">{esc(t)}</a>' for h, t in links)
    return (BAR_CSS + f'<nav class="hub-bar" aria-label="Learning Hub">{items}'
            f'<button id="hub-full" type="button" aria-pressed="false" title="Full screen">{ICON_FULL}<span class="hub-full-label">Full screen</span></button></nav>')


def viz_page(lab):
    src = read(os.path.join(ROOT, lab["folder"], "visualization.html"))
    links = [("../atlas.html", "← Atlas"), ("notes.html", "Notes")]
    if re.search(r"<!doctype", src[:200], re.I):   # already a whole document: add the bar before </body>
        i = src.lower().rfind("</body>")
        return src[:i] + bar(links) + PAGE_JS + src[i:]
    return document(lab["viz_title"] or lab["slug"], "", src + "\n" + bar(links) + PAGE_JS, lab=lab["folder"])


def atlas_page():
    tmp = os.path.join(OUT, "_atlas.src.html")
    subprocess.run([sys.executable, "-I", os.path.join(ROOT, "tools", "gen-atlas.py"), "-o", tmp], check=True, cwd=ROOT,
                   stdout=subprocess.DEVNULL)
    src = read(tmp)
    os.remove(tmp)

    def lk(m):
        path = m.group(1)
        folder, name = path.split("/", 1) if "/" in path else (None, path)
        if folder and name in DOC_PART:
            new = f"{folder}/notes.html" + ("" if name == "README.md" else f"#{DOC_PART[name]}")
        elif folder and name == "visualization.html":
            new = path
        else:
            return ""   # the old per-lab artifact links: the hub has the visualization itself
        return f'<a class="lk" href="{new}" data-path="{new}">'
    src, n = re.subn(r'<a class="lk" href="([^"]+)" data-path="[^"]+"(?: target="_blank" rel="noopener")?>', lk, src)
    src = re.sub(r'(<a class="lk" [^>]*>)?Open</a>', lambda m: "" if m.group(1) is None else m.group(0), src)
    src = re.sub(r"<a class=\"lk\"\s*>Open</a>", "", src)
    old = "const local=location.protocol==='file:';"
    assert old in src, "gen-atlas.py changed: the hosted-links switch is not where it was"
    src = src.replace(old, "const local=true;   /* the Learning Hub: every link opens a page inside the hub */")
    src = src.replace("<meta charset=\"utf-8\">\n", "", 1)
    return document("Systems Atlas", "", src + "\n" + bar([("index.html", "← Hub")]) + PAGE_JS)


INDEX_CSS = """<style>
/* layout: a working desk. Tools first as a two-column shelf, then the labs as a long ledger by stage */
""" + TOKENS + """
body{background:var(--ground);color:var(--ink);font-family:var(--body);font-size:16px;line-height:1.5}
a{color:var(--accent)}
.page{max-width:1080px;margin:0 auto;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px));padding-block:28px 72px}
.head{display:flex;flex-wrap:wrap;align-items:flex-end;justify-content:space-between;gap:12px 24px;padding-bottom:18px;border-bottom:3px solid var(--ink)}
.head h1{font-family:var(--display);font-size:clamp(1.9rem,4vw,2.6rem);line-height:1.05;margin:0;letter-spacing:-.01em}
.head p{margin:6px 0 0;color:var(--ink-2);max-width:60ch}
.tbtn{display:inline-flex;align-items:center;gap:6px;padding:7px 12px;border-radius:5px;border:1px solid var(--line-2);background:var(--surface);
  color:var(--ink-2);font:600 14px var(--body);cursor:pointer}
.tbtn:hover{color:var(--ink);border-color:var(--ink-3)}
.tbtn:focus-visible,a:focus-visible,input:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.resume{margin-top:18px;display:flex;flex-wrap:wrap;align-items:baseline;gap:6px 12px;padding:12px 16px;background:var(--surface);border:1px solid var(--line);border-left:4px solid var(--accent);border-radius:5px}
.resume b{font-family:var(--display)}
.label{font-family:var(--mono);font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3)}
section{margin-top:40px}
.sec-head{display:flex;flex-wrap:wrap;align-items:baseline;justify-content:space-between;gap:8px 16px;margin-bottom:14px}
.sec-head h2{font-family:var(--display);font-size:1.5rem;margin:0}
.shelf{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.tool{display:grid;grid-template-rows:auto 1fr auto;gap:6px;padding:16px 18px;background:var(--surface);border:1px solid var(--line);border-radius:5px;text-decoration:none;color:var(--ink)}
.tool:hover{border-color:var(--accent)}
.tool h3{font-family:var(--display);font-size:1.12rem;margin:0;display:flex;justify-content:space-between;gap:8px}
.tool h3 span{color:var(--accent);font-family:var(--mono);font-size:.8rem;font-weight:500;white-space:nowrap}
.tool p{margin:0;color:var(--ink-2);font-size:15px}
.tool small{font-family:var(--mono);font-size:12px;color:var(--ink-3)}
.tool.off{opacity:.6;pointer-events:none}
.atlas-link{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:8px 20px;padding:16px 18px;margin-bottom:18px;background:var(--ink);color:var(--ground);border-radius:5px;text-decoration:none}
.atlas-link b{font-family:var(--display);font-size:1.15rem}
.atlas-link span{color:var(--ground);opacity:.8;font-size:15px}
#lab-filter{width:100%;max-width:360px;padding:8px 12px;border:1px solid var(--line-2);border-radius:5px;background:var(--surface);color:var(--ink);font:15px var(--body)}
.stage{margin-top:22px}
.stage h3{font-family:var(--display);font-size:1.05rem;margin:0 0 6px;display:flex;gap:10px;align-items:baseline}
.stage h3 .label{font-size:11.5px}
.ledger{list-style:none;margin:0;padding:0;border-top:1px solid var(--line)}
.ledger li{display:grid;grid-template-columns:3.2em minmax(0,1fr) auto;gap:4px 14px;align-items:baseline;padding:9px 0;border-bottom:1px solid var(--line)}
.num{font-family:var(--mono);font-size:13px;color:var(--ink-3);font-variant-numeric:tabular-nums;text-align:right}
.what{min-width:0}
.what a{font-weight:600;text-decoration:none;color:var(--ink)}
.what a:hover{color:var(--accent)}
.what p{margin:2px 0 0;color:var(--ink-2);font-size:14.5px}
.what code{font-family:var(--mono);font-size:.86em;background:var(--surface-2);padding:0 .3em;border-radius:3px}
.go{display:flex;gap:10px;font-size:14px;white-space:nowrap}
.go a{text-decoration:none}
.go a:hover{text-decoration:underline}
.empty{color:var(--ink-2);padding:12px 0}
.foot{margin-top:48px;color:var(--ink-3);font-size:13.5px;max-width:70ch}
@media (max-width:720px){.shelf{grid-template-columns:minmax(0,1fr)}.ledger li{grid-template-columns:2.6em minmax(0,1fr)}.go{grid-column:2}}
</style>
"""

INDEX_JS = """<script>
(function () {
  var f = document.getElementById('lab-filter');
  var none = document.getElementById('no-match');
  f.addEventListener('input', function () {
    var q = f.value.trim().toLowerCase(), shown = 0;
    document.querySelectorAll('.ledger li').forEach(function (li) {
      var hit = !q || li.getAttribute('data-text').indexOf(q) >= 0;
      li.hidden = !hit; if (hit) shown++;
    });
    document.querySelectorAll('.stage').forEach(function (s) { s.hidden = !s.querySelector('li:not([hidden])'); });
    none.hidden = shown > 0;
  });
  try {
    var last = JSON.parse(localStorage.getItem('hub.last') || 'null');
    var li = last && document.querySelector('.ledger li[data-folder="' + last.lab + '"]');
    if (li) {
      var r = document.getElementById('resume');
      r.querySelector('b').textContent = li.querySelector('.what a').textContent;
      var a = r.querySelector('a');
      a.href = last.lab + '/' + (last.page === 'visualization.html' ? 'visualization.html' : 'notes.html');
      a.textContent = last.page === 'visualization.html' ? 'Open the visual' : 'Open the notes';
      r.hidden = false;
    }
  } catch (e) {}
})();
</script>
"""


def index_page(stages, labs):
    tools = []
    for name, url, desc, note in TOOLS:
        if url:
            tools.append(f'<a class="tool" href="{esc(url)}" target="_blank" rel="noopener"><h3>{esc(name)}<span>new tab ↗</span></h3>'
                         f'<p>{esc(desc)}</p><small>{esc(note)}</small></a>')
        else:
            tools.append(f'<div class="tool off"><h3>{esc(name)}<span>not published yet</span></h3><p>{esc(desc)}</p><small>{esc(note)}</small></div>')
    ledger = []
    for st in stages:
        rows = []
        for lab in st["labs"]:
            text = re.sub(r"[*`]", "", " ".join([lab["n"], lab["slug"], lab["title"], lab["hook"], lab["viz_title"]])).lower()
            go = f'<a href="{lab["folder"]}/notes.html">Notes</a>'
            if lab["has_viz"]:
                go += f'<a href="{lab["folder"]}/visualization.html" title="{esc(lab["viz_title"])}">Visual</a>'
            rows.append(f'<li data-folder="{esc(lab["folder"])}" data-text="{esc(text)}"><span class="num">{lab["n"]}</span>'
                        f'<div class="what"><a href="{lab["folder"]}/notes.html">{esc(lab["slug"].replace("-", " "))}</a>'
                        f'<p>{inline_md(lab["hook"])}</p></div><div class="go">{go}</div></li>')
        ledger.append(f'<div class="stage"><h3><span class="label">Stage {st["n"]}</span>{esc(st["title"])}</h3>'
                      f'<ol class="ledger">{"".join(rows)}</ol></div>')
    body = f"""<div class="page">
<header class="head">
  <div><h1>Learning Hub</h1>
  <p>Your learning tools and all {len(labs)} labs, on any computer signed in to claude.ai. Only you can open it.</p></div>
  {full_button()}
</header>
<div class="resume" id="resume" hidden><span class="label">Where you were</span><b></b><a href="#"></a></div>
<section aria-labelledby="tools-h">
  <div class="sec-head"><h2 id="tools-h">Tools</h2><span class="label">{sum(1 for t in TOOLS if t[1])} of {len(TOOLS)} · each keeps its own progress</span></div>
  <div class="shelf">{"".join(tools)}</div>
</section>
<section aria-labelledby="labs-h">
  <div class="sec-head"><h2 id="labs-h">Labs</h2><span class="label">{len(labs)} labs · {len(stages)} stages · in reading order</span></div>
  <a class="atlas-link" href="atlas.html"><b>Systems Atlas</b><span>The map: stages, what builds on what, the twelve load-bearing labs, what to learn next.</span></a>
  <label class="label" for="lab-filter">Find a lab</label><br>
  <input id="lab-filter" type="search" autocomplete="off" placeholder="raft, kafka, isolation, gc…">
  {"".join(ledger)}
  <p class="empty" id="no-match" hidden>No lab matches. Try a shorter word.</p>
</section>
<p class="foot">Each lab has its notes (the README, then the exercises, then the solutions, folded) and its visual.
Code files from the older labs are not in the hub: links to them are marked with a dotted underline.
Built {BUILT} from the Learning folder by tools/build-hub.py.</p>
</div>
""" + INDEX_JS + PAGE_JS
    return "<title>Learning Hub</title>\n<meta name=\"claude:viewport-fit-sides\" content=\"cover\">\n" + FONTS + INDEX_CSS + body


# ---------------------------------------------------------------- build

def main():
    global TOTAL, BUILT
    import datetime
    BUILT = datetime.date.today().isoformat()
    stages, labs = load_path()
    TOTAL = len(labs)
    site = Site(labs)
    if os.path.isdir(OUT):
        for dirpath, _, files in os.walk(OUT):
            for f in files:
                if f.endswith(".html"):
                    os.remove(os.path.join(dirpath, f))
    missing, files = [], ["atlas.html"]
    for i, lab in enumerate(labs):
        prev = labs[i - 1] if i else None
        nxt = labs[i + 1] if i + 1 < len(labs) else None
        write(f'{lab["folder"]}/notes.html', notes_page(lab, prev, nxt, site, missing))
        files.append(f'{lab["folder"]}/notes.html')
        for src in sorted(s for s in site.extra if s.startswith(lab["folder"] + "/")):
            write(site.extra[src], extra_page(lab, src, site, missing))
            files.append(site.extra[src])
        if lab["has_viz"]:
            write(f'{lab["folder"]}/visualization.html', viz_page(lab))
            files.append(f'{lab["folder"]}/visualization.html')
    write("atlas.html", atlas_page())
    write("index.html", index_page(stages, labs))
    size = sum(os.path.getsize(os.path.join(OUT, f)) for f in files + ["index.html"])
    kinds = {}
    for m in missing:
        k = os.path.splitext(m.partition("#")[0])[1] or m
        kinds[k] = kinds.get(k, 0) + 1
    print(json.dumps(files))
    print(f"{len(labs)} labs, {len(files) + 1} pages, {size // 1024} KB; links to files not in the hub: "
          + (", ".join(f"{v} {k}" for k, v in sorted(kinds.items(), key=lambda x: -x[1])) or "none"), file=sys.stderr)


TOTAL = BUILT = None
if __name__ == "__main__":
    main()
