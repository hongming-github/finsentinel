#!/usr/bin/env bash
# Downloads the MAS documents listed in knowledge/sources.json into knowledge/docs/.
# They are public but MAS-copyrighted, so they are fetched locally instead of committed.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p knowledge/docs
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'

python3 -c 'import json; [print(d["file"], d["url"]) for d in json.load(open("knowledge/sources.json"))["documents"]]' |
while read -r file url; do
  if [[ -s "knowledge/docs/$file" ]]; then
    echo "have  $file"
    continue
  fi
  curl -fsSL -A "$UA" -o "knowledge/docs/$file.part" "$url"
  if [[ "$(head -c 4 "knowledge/docs/$file.part")" != "%PDF" ]]; then
    echo "error $file: not a PDF (blocked or moved?): $url" >&2
    rm -f "knowledge/docs/$file.part"
    exit 1
  fi
  mv "knowledge/docs/$file.part" "knowledge/docs/$file"
  echo "got   $file ($(wc -c < "knowledge/docs/$file" | tr -d ' ') bytes)"
done
