#!/bin/bash
# The offline package as <10 MB parts (some agents can't download bigger files), + SHA256SUMS + join.sh,
# bundled into two zips for uploading by hand. Run after tools/make_packages.sh.
set -euo pipefail
cd "$(dirname "$0")/../dist"
rm -rf parts && mkdir parts && cd parts
gzip -dc ../naked-eye-offline-linux-x64.tar.gz | xz -9e -T2 > naked-eye-offline.tar.xz
split -b 9961472 -d -a 2 naked-eye-offline.tar.xz naked-eye-offline.tar.xz.part-
sha256sum naked-eye-offline.tar.xz naked-eye-offline.tar.xz.part-* > SHA256SUMS
rm naked-eye-offline.tar.xz
cat > join.sh <<'EOF'
#!/bin/sh
# Put all naked-eye-offline.tar.xz.part-NN files, SHA256SUMS and this script in one folder, then:  sh join.sh
# Checks every part, joins them, checks the whole, unpacks to ./naked-eye (needs only python3).
cd "$(dirname "$0")" && exec python3 - <<'PY'
import hashlib, os, sys, tarfile
sums = dict(reversed(l.split()) for l in open("SHA256SUMS") if l.strip())
whole = "naked-eye-offline.tar.xz"
parts = sorted(n for n in sums if n != whole)
h = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
missing = [p for p in parts if not os.path.exists(p)]
bad = [p for p in parts if p not in missing and h(p) != sums[p]]
if missing or bad:
    if missing: print(f"missing {len(missing)} of {len(parts)} parts:", " ".join(missing))
    if bad: print("damaged (download again):", " ".join(bad))
    sys.exit(1)
with open(whole, "wb") as out:
    for p in parts: out.write(open(p, "rb").read())
if h(whole) != sums[whole]: sys.exit("joined file is damaged")
print(f"all {len(parts)} parts OK; unpacking ...")
with tarfile.open(whole, "r:xz") as t:
    t.extractall(".", **({"filter": "tar"} if hasattr(tarfile, "tar_filter") else {}))
os.remove(whole)
print("done: cd naked-eye && ./setup.sh && ./start.sh   (the parts can be deleted now)")
PY
EOF
n=$(ls naked-eye-offline.tar.xz.part-* | wc -l); half=$(( (n + 1) / 2 ))
ls naked-eye-offline.tar.xz.part-* | head -$half | xargs zip -q -0 ../naked-eye-offline-parts-1of2.zip join.sh SHA256SUMS
ls naked-eye-offline.tar.xz.part-* | tail -n +$((half + 1)) | xargs zip -q -0 ../naked-eye-offline-parts-2of2.zip join.sh SHA256SUMS
echo "$n parts"; ls -la ../naked-eye-offline-parts-*.zip
