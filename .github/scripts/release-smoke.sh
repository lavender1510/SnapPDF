#!/usr/bin/env bash
# Verify a pre-signed APK, exercise it, and optionally attach it to a draft.
# No private signing key is needed by or transferred to this workflow.
set -euo pipefail

evidence_dir="${GITHUB_WORKSPACE:-$PWD}/app/build/outputs/release-smoke"
runtime_dir="${RUNNER_TEMP:?RUNNER_TEMP is required}"
apk_path="$runtime_dir/snappdf-release-smoke.apk"
build_tools="${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/35.0.0"
mkdir -p "$evidence_dir"

fail() {
  echo "::error::$*" >&2
  exit 1
}

validate_inputs() {
  [[ ${SNAPPDF_RELEASE_TAG:-} =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || fail 'Invalid release tag.'
  [[ ${SNAPPDF_EXPECTED_SHA256:-} =~ ^[[:xdigit:]]{64}$ ]] || fail 'Expected SHA-256 must contain exactly 64 hexadecimal digits.'
  if [[ -n ${SNAPPDF_CANDIDATE_REF:-} ]]; then
    [[ $SNAPPDF_CANDIDATE_REF =~ ^refs/heads/release-candidate/v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail 'Invalid temporary candidate branch.'
  fi
}

verify_hash() {
  printf '%s  %s\n' "${SNAPPDF_EXPECTED_SHA256,,}" "$apk_path" | sha256sum --check --strict
}

prepare() {
  validate_inputs
  local releases_json="$runtime_dir/snappdf-release-list.json"
  local asset_id_file="$runtime_dir/snappdf-release-asset-id"
  if ! gh api --paginate --slurp "repos/${GITHUB_REPOSITORY:?}/releases" > "$releases_json"; then
    fail 'The workflow token could not list releases. Check contents-read access to this repository and its draft release.'
  fi
  python3 - "$releases_json" "$evidence_dir/asset.json" "$asset_id_file" <<'PY'
import json
import os
import sys
from pathlib import Path

pages = json.loads(Path(sys.argv[1]).read_text())
releases = [release for page in pages for release in page]
tag = os.environ['SNAPPDF_RELEASE_TAG']
matching = [release for release in releases if release['tag_name'] == tag]
if len(matching) != 1:
    raise SystemExit('Requested release is not visible. Ensure it exists and the workflow token can read this draft; no asset has been published.')
release = matching[0]
name = f'SnapPDF-{tag}.apk'
assets = [asset for asset in release['assets'] if asset['name'] == name and asset['state'] == 'uploaded']
candidate = os.environ.get('SNAPPDF_CANDIDATE_REF', '')
if candidate and not release['draft']:
    raise SystemExit('Candidate uploads are allowed only while the release remains a draft.')
if not candidate and (len(assets) != 1 or assets[0]['size'] <= 0):
    raise SystemExit(f'Expected exactly one completely uploaded asset named {name}.')
asset = assets[0] if not candidate else None
metadata = {
    'release_id': release['id'], 'tag': tag, 'draft_at_download': release['draft'],
    'asset_id': asset['id'] if asset else None, 'asset_name': name,
    'asset_size': asset['size'] if asset else None, 'candidate_ref': candidate,
    'expected_sha256': os.environ['SNAPPDF_EXPECTED_SHA256'].lower(),
    'workflow_commit': os.environ.get('GITHUB_SHA', '')
}
Path(sys.argv[2]).write_text(json.dumps(metadata, indent=2) + '\n')
Path(sys.argv[3]).write_text(str(int(asset['id'])) + '\n' if asset else '\n')
PY
  if [[ -n ${SNAPPDF_CANDIDATE_REF:-} ]]; then
    git fetch --no-tags --depth=1 origin "$SNAPPDF_CANDIDATE_REF"
    git show FETCH_HEAD:manifest.json > "$runtime_dir/snappdf-candidate-manifest.json"
    python3 - "$runtime_dir/snappdf-candidate-manifest.json" "$apk_path" "$evidence_dir/asset.json" <<'PY'
import json, os, re, subprocess, sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text())
expected = os.environ['SNAPPDF_EXPECTED_SHA256'].lower()
if manifest['filename'] != f"SnapPDF-{os.environ['SNAPPDF_RELEASE_TAG']}.apk" or manifest['sha256'] != expected:
    raise SystemExit('Candidate manifest does not match the requested APK and independent checksum.')
parts = manifest['parts']
if not 1 <= len(parts) <= 32 or parts != [f'part-{i:03d}' for i in range(len(parts))]:
    raise SystemExit('Candidate parts must be a bounded, ordered list.')
if not re.fullmatch(r'[0-9a-f]{40}', manifest['sourceCommit']):
    raise SystemExit('Candidate source commit is invalid.')
with Path(sys.argv[2]).open('wb') as output:
    for part in parts:
        size = int(subprocess.check_output(['git', 'cat-file', '-s', f'FETCH_HEAD:{part}']))
        if not 0 < size <= 8 * 1024 * 1024:
            raise SystemExit('Candidate part has an invalid size.')
        subprocess.run(['git', 'show', f'FETCH_HEAD:{part}'], stdout=output, check=True)
metadata_path = Path(sys.argv[3])
metadata = json.loads(metadata_path.read_text())
metadata.update(asset_size=Path(sys.argv[2]).stat().st_size, source_commit=manifest['sourceCommit'])
metadata_path.write_text(json.dumps(metadata, indent=2) + '\n')
PY
  else
    local asset_id
    read -r asset_id < "$asset_id_file"
    if ! gh api -H 'Accept: application/octet-stream' "repos/$GITHUB_REPOSITORY/releases/assets/$asset_id" > "$apk_path"; then
      fail 'The workflow token could not download the draft release asset. The release remains unpublished.'
    fi
  fi
  verify_hash
  sha256sum "$apk_path" > "$evidence_dir/SHA256SUMS.txt"
  "$build_tools/apksigner" verify --verbose --print-certs "$apk_path" > "$evidence_dir/signature.txt"
  "$build_tools/aapt" dump badging "$apk_path" > "$evidence_dir/apk-badging.txt"
  "$build_tools/aapt" dump permissions "$apk_path" > "$evidence_dir/apk-permissions.txt"
  python3 - "$evidence_dir" <<'PY'
import os
import re
import sys
from pathlib import Path

directory = Path(sys.argv[1])
badging = (directory / 'apk-badging.txt').read_text()
permissions = (directory / 'apk-permissions.txt').read_text()
package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
if not package or package.group(1) != 'de.snappdf':
    raise SystemExit('The asset does not contain the production de.snappdf application.')
if package.group(3) != os.environ['SNAPPDF_RELEASE_TAG'][1:]:
    raise SystemExit('APK versionName does not match the selected release tag.')
if re.search(r'^application-debuggable\b', badging, re.M):
    raise SystemExit('A debuggable APK cannot pass the production release smoke test.')
if 'android.permission.INTERNET' in permissions:
    raise SystemExit('The production APK unexpectedly declares INTERNET permission.')
(directory / 'static-checks.txt').write_text(
    'SHA-256 matched the supplied independent checksum.\n'
    'APK signature verified with apksigner.\n'
    'Production package and release version matched.\n'
    'APK is not debuggable and declares no INTERNET permission.\n'
)
PY
  touch "$runtime_dir/snappdf-release-smoke-prepared"
}

upload_candidate() {
  validate_inputs
  [[ -n ${SNAPPDF_CANDIDATE_REF:-} && -s "$evidence_dir/runtime-checks.txt" ]] || fail 'Candidate installation and launch must succeed before attaching assets.'
  verify_hash
  gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases" > "$runtime_dir/snappdf-upload-release-list.json"
  python3 - "$runtime_dir/snappdf-upload-release-list.json" <<'PY'
import json, os, sys
from pathlib import Path
releases = [release for page in json.loads(Path(sys.argv[1]).read_text()) for release in page]
matches = [release for release in releases if release['tag_name'] == os.environ['SNAPPDF_RELEASE_TAG']]
if len(matches) != 1 or not matches[0]['draft']:
    raise SystemExit('The release is no longer a unique draft. No assets were changed.')
PY
  local assets_dir="$runtime_dir/snappdf-tested-release"
  local filename="SnapPDF-$SNAPPDF_RELEASE_TAG.apk"
  mkdir -p "$assets_dir"
  cp "$apk_path" "$assets_dir/$filename"
  printf '%s  %s\n' "${SNAPPDF_EXPECTED_SHA256,,}" "$filename" > "$assets_dir/SHA256SUMS.txt"
  {
    printf 'SnapPDF signed APK verification\n\nWorkflow: https://github.com/%s/actions/runs/%s\n\n' "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID"
    cat "$evidence_dir/static-checks.txt" "$evidence_dir/runtime-checks.txt"
    printf '\nSHA-256: %s\n' "${SNAPPDF_EXPECTED_SHA256,,}"
  } > "$assets_dir/SMOKE_VERIFICATION.txt"
  gh release upload "$SNAPPDF_RELEASE_TAG" "$assets_dir/$filename" "$assets_dir/SHA256SUMS.txt" "$assets_dir/SMOKE_VERIFICATION.txt" --repo "$GITHUB_REPOSITORY" --clobber
}

collect_runtime_evidence() {
  local result=0
  timeout 20s adb exec-out screencap -p > "$evidence_dir/release-home.png" || result=1
  timeout 20s adb logcat -d -v threadtime > "$evidence_dir/logcat.txt" || result=1
  timeout 20s adb logcat -d -v brief -s AndroidRuntime:E > "$evidence_dir/android-runtime.txt" || result=1
  return "$result"
}

run_smoke() {
  validate_inputs
  [[ -f "$runtime_dir/snappdf-release-smoke-prepared" ]] || fail 'Static APK verification must succeed before installation.'
  verify_hash
  trap 'collect_runtime_evidence || true' EXIT
  timeout 120s adb install -r "$apk_path" | tee "$evidence_dir/install.txt"
  adb logcat -c
  timeout 30s adb shell am start -W -n de.snappdf/.MainActivity | tee "$evidence_dir/launch.txt"
  local deadline=$((SECONDS + 60))
  local home_ready=false
  while (( SECONDS < deadline )); do
    if timeout 10s adb shell uiautomator dump /sdcard/snappdf-release-ui.xml > "$evidence_dir/ui-dump-status.txt" 2>&1 &&
       adb pull /sdcard/snappdf-release-ui.xml "$evidence_dir/ui.xml" >/dev/null 2>&1 &&
       python3 - "$evidence_dir/ui.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

nodes = list(ET.parse(sys.argv[1]).getroot().iter('node'))
labels = [node.attrib.get('text', '') + '\n' + node.attrib.get('content-desc', '') for node in nodes]
if not any(node.attrib.get('package') == 'de.snappdf' for node in nodes):
    raise SystemExit(1)
if not any('SnapPDF' in label for label in labels) or not any('Scan document' in label for label in labels):
    raise SystemExit(1)
PY
    then
      home_ready=true
      break
    fi
    sleep 2
  done
  [[ "$home_ready" == true ]] || fail 'The installed release did not display its SnapPDF / Scan document home screen within 60 seconds.'
  local app_pid
  app_pid=$(adb shell pidof de.snappdf | tr -d '\r') || fail 'The installed release process is no longer running.'
  [[ -n "$app_pid" ]] || fail 'The installed release process is no longer running.'
  printf '%s\n' "$app_pid" > "$evidence_dir/process-id.txt"
  collect_runtime_evidence || fail 'Required screenshot or runtime logs could not be collected.'
  trap - EXIT
  [[ -s "$evidence_dir/release-home.png" ]] || fail 'Screenshot evidence is empty.'
  if grep -q 'FATAL' "$evidence_dir/android-runtime.txt"; then
    fail 'AndroidRuntime reported a fatal error during signed release startup; inspect the runtime evidence.'
  fi
  adb shell pidof de.snappdf > "$evidence_dir/process-id-after-capture.txt" || fail 'The release exited while runtime evidence was collected.'
  printf '%s\n' 'The exact signed production APK installed and displayed the expected home screen on Android API 35.' \
    'The application process remained alive and AndroidRuntime reported no fatal startup error.' \
    'This is an install/launch smoke test; it does not replace scan, OCR, PDF or physical-camera tests.' \
    > "$evidence_dir/runtime-checks.txt"
}

case ${1:-} in
  prepare) prepare ;;
  run) run_smoke ;;
  upload) upload_candidate ;;
  *) fail 'Usage: release-smoke.sh prepare|run|upload' ;;
esac
