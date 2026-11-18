from pathlib import Path

p = Path("patch_multivm.py")
s = p.read_text(encoding="utf-8-sig")

start = s.index("# 8. Record worker count in JSON configuration.")
end = s.index("\nif source == original:", start)

s = s[:start] + "# JSON worker-count metadata will be added separately.\n" + s[end:]

p.write_text(s, encoding="utf-8")
print("PATCH SCRIPT UPDATED: skipped JSON metadata section only.")
