#!/bin/bash
# Pushes the UI test report and screenshots to the ci-screenshots branch (one commit, replaced
# every run) so they can be looked at without downloading the artifact.
set -e
OUT="${UITEST_OUT:-$PWD/uitest-out}"
[ -d "$OUT" ] || exit 0
rm -f "$OUT/peer.session"
cd "$OUT"
git init -q
git checkout -q -b ci-screenshots
git add -A
git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit -qm "UI test of ${GITHUB_SHA:0:8} (run ${GITHUB_RUN_ID})"
git push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" HEAD:ci-screenshots
echo "Published to ci-screenshots"
