# Run from the workspace root after committing:  .\publish.ps1
# 1) backs up everything to the main repo, 2) sends only room-relay/ to the deploy repo (beamkey-web).
Set-Location $PSScriptRoot

if (git status --porcelain) {
  Write-Host 'You have uncommitted changes. Run: git add .  then  git commit -m "message"' -ForegroundColor Yellow
  exit 1
}
if (-not (git remote | Select-String -SimpleMatch "deploy")) {
  git remote add deploy https://github.com/CodeDocRJ/beamkey-web.git
}

git push origin main
if ($LASTEXITCODE -ne 0) { Write-Host "Push to the main repo failed." -ForegroundColor Red; exit 1 }

git subtree push --prefix=room-relay deploy main
if ($LASTEXITCODE -ne 0) { Write-Host "Push to beamkey-web failed. See DEPLOY-GUIDE.md, Troubleshooting." -ForegroundColor Red; exit 1 }

Write-Host "Done. Cloudflare redeploys getbeamkey.com from beamkey-web." -ForegroundColor Green
