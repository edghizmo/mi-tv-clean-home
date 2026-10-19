#!/bin/bash
# Disable every package listed in debloat-list.txt (one per line, # for comments).
# Safe to run again: already-disabled packages are just reported.
cd "$(dirname "$0")"
grep -v '^\s*#' debloat-list.txt | grep -v '^\s*$' | xargs tools/disable.sh
