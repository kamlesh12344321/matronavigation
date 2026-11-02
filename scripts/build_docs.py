#!/usr/bin/env python3
"""
Builds DOCX and PDF versions of the Metro Navigator documents (docs/metro_navigator/*.md).

Markdown -> styled HTML (python "markdown" package) -> PDF (headless Google Chrome) and
DOCX (macOS textutil). Also writes a zip with all formats.

Usage:  pip install markdown && python3 scripts/build_docs.py
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

import markdown

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
DOCS = os.path.join(ROOT, "docs", "metro_navigator")
CHROME_CANDIDATES = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
    "google-chrome", "chromium",
]

CSS = """
body { font-family: -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif; font-size: 10.5pt;
       line-height: 1.45; color: #1a1a1a; max-width: 900px; margin: 0 auto; padding: 24px; }
h1.title { font-size: 22pt; margin-bottom: 4px; color: #0b3d91; }
p.subtitle { font-size: 12pt; color: #444; margin-top: 0; }
p.date { color: #666; }
h1 { font-size: 16pt; color: #0b3d91; border-bottom: 2px solid #0b3d91; padding-bottom: 3px; margin-top: 26px; page-break-after: avoid; }
h2 { font-size: 13pt; color: #0b3d91; margin-top: 20px; page-break-after: avoid; }
h3 { font-size: 11.5pt; margin-top: 16px; page-break-after: avoid; }
table { border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 9.5pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th, td { border: 1px solid #b8c4d6; padding: 4px 6px; vertical-align: top; text-align: left; }
th { background: #e8eef8; }
code { font-family: Menlo, Consolas, monospace; font-size: 9pt; background: #f3f5f8; padding: 0 2px; }
pre { background: #f3f5f8; border: 1px solid #d6dde8; padding: 8px; font-size: 8.3pt; line-height: 1.25;
      white-space: pre; overflow-x: auto; page-break-inside: avoid; }
pre code { background: none; padding: 0; }
"""


def front_matter(text):
    meta = {}
    m = re.match(r"^---\n(.*?)\n---\n", text, re.S)
    if m:
        for line in m.group(1).splitlines():
            k, _, v = line.partition(":")
            meta[k.strip()] = v.strip().strip('"')
        text = text[m.end():]
    return meta, text


def to_html(md_path):
    meta, body = front_matter(open(md_path, encoding="utf-8").read())
    html_body = markdown.markdown(body, extensions=["tables", "fenced_code", "sane_lists"])
    head = f"<h1 class='title'>{meta.get('title', '')}</h1>"
    if meta.get("subtitle"):
        head += f"<p class='subtitle'>{meta['subtitle']}</p>"
    if meta.get("date"):
        head += f"<p class='date'>{meta['date']}</p>"
    return (f"<!DOCTYPE html><html><head><meta charset='utf-8'><title>{meta.get('title', '')}</title>"
            f"<style>{CSS}</style></head><body>{head}{html_body}</body></html>")


def chrome():
    for c in CHROME_CANDIDATES:
        if os.path.exists(c) or subprocess.call(["which", c], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0:
            return c
    return None


def main():
    browser = chrome()
    if browser is None:
        sys.exit("Chrome/Brave not found (needed for PDF)")
    outputs = []
    with tempfile.TemporaryDirectory() as tmp:
        for name in sorted(os.listdir(DOCS)):
            if not name.endswith(".md"):
                continue
            base = name[:-3]
            html_path = os.path.join(tmp, base + ".html")
            with open(html_path, "w", encoding="utf-8") as f:
                f.write(to_html(os.path.join(DOCS, name)))
            pdf = os.path.join(DOCS, base + ".pdf")
            docx = os.path.join(DOCS, base + ".docx")
            subprocess.run([browser, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                            f"--print-to-pdf={pdf}", "file://" + html_path],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["textutil", "-convert", "docx", "-output", docx, html_path], check=True)
            outputs += [os.path.join(DOCS, name), pdf, docx]
            print(f"{base}: md, pdf ({os.path.getsize(pdf) // 1024} KB), docx ({os.path.getsize(docx) // 1024} KB)")
    zip_path = os.path.join(DOCS, "Metro_Navigator_Documentation.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for p in outputs:
            z.write(p, os.path.basename(p))
    print("zip:", os.path.relpath(zip_path, ROOT))


if __name__ == "__main__":
    main()
