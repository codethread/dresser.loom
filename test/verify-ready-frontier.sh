#!/usr/bin/env bash
set -euo pipefail

validator="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)/bin/validate-ready-frontier"

rejects_malformed_item() {
  local payload=$1 expected=$2 output
  if output=$("$validator" "$payload" 2>&1); then
    echo "validator accepted malformed payload: $payload" >&2
    exit 1
  fi
  [[ "$output" == *"observed item: $expected"* ]]
  [[ "$output" == *"expected item schema:"* ]]
  [[ "$output" == *"allowed gate values: empty or shell"* ]]
  [[ "$output" != *"Traceback"* ]]
}

rejects_malformed_item '[null]' 'null'
rejects_malformed_item '["unexpected"]' '"unexpected"'
rejects_malformed_item '[{"id":"step"}]' '{"id":"step"}'
rejects_malformed_item '[{"id":"step","role":"step","title":"Title","gate":"python"}]' '{"gate":"python","id":"step","role":"step","title":"Title"}'

if output=$("$validator" 'not-json' 2>&1); then
  echo "validator accepted malformed JSON" >&2
  exit 1
fi
[[ "$output" == *"observed payload 'not-json' is not valid JSON"* ]]
[[ "$output" == *"expected item schema:"* ]]
[[ "$output" == *"allowed gate values: empty or shell"* ]]
[[ "$output" != *"Traceback"* ]]

echo "ready frontier boundary checks: clean"
