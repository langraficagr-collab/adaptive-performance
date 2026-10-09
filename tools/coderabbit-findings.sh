#!/data/data/com.termux/files/usr/bin/bash
# Read-only review fetcher: GitHub token is managed by gh auth, never printed.
set -euo pipefail
repo="langraficagr-collab/adaptive-performance"
pr="${1:-}"
if [[ ! "$pr" =~ ^[1-9][0-9]*$ ]]; then
  echo "Uso: bash tools/coderabbit-findings.sh NUMERO_DO_PR" >&2
  exit 2
fi
echo "CodeRabbit | ${repo} | PR #${pr}"
echo "--- Revisões ---"
gh api --paginate "repos/${repo}/pulls/${pr}/reviews?per_page=100" --jq '.[] | select(.user.login | ascii_downcase | contains("coderabbit")) | "[\(.state)] \(.user.login): \(.body // "")"'
echo "--- Anotações em linhas de código ---"
gh api --paginate "repos/${repo}/pulls/${pr}/comments?per_page=100" --jq '.[] | select(.user.login | ascii_downcase | contains("coderabbit")) | "\(.path):\(.line // .original_line // 0) [\(.user.login)] \(.body // "")"'
echo "--- Comentários gerais ---"
gh api --paginate "repos/${repo}/issues/${pr}/comments?per_page=100" --jq '.[] | select(.user.login | ascii_downcase | contains("coderabbit")) | "[\(.user.login)] \(.body // "")"'
