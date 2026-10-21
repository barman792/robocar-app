#!/usr/bin/env python3
"""Copy the control page out of the ESP32 firmware (web_ui.h) into the app's assets (used by demo mode).
   usage: python3 tools/sync_ui.py path/to/RoboCar/web_ui.h"""
import re, sys, pathlib
src = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
html = re.search(r'R"HTML\((.*)\)HTML"', src, re.S).group(1)
out = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/index.html"
out.write_text(html, encoding="utf-8")
print("written", out, len(html), "bytes")
