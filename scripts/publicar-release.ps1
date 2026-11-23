<#
.SYNOPSIS
    Publica (ou atualiza) a GitHub Release do Charrua OBD Pro com o APK de release assinado.

.DESCRIPTION
    Fluxo padrão de release (o APK nunca entra no Git):
      1. lê versionName/versionCode de app/build.gradle.kts;
      2. roda `gradlew assembleRelease` (pule com -SemBuild), que gera
         dist/CharruaOBDPro-<versao>-release.apk e copia para G:\Meu Drive\CharruaOBD se a pasta existir;
      3. cria a release v<versao> no GitHub com o APK anexado e notas geradas dos commits
         (`gh release create ... --generate-notes`) ou, se a tag já tiver release, substitui o APK
         anexado (`gh release upload --clobber`).

    Requer o GitHub CLI instalado e autenticado:
      winget install --id GitHub.cli
      gh auth login

.PARAMETER SemBuild
    Não roda o Gradle: publica o APK que já estiver em dist/.

.PARAMETER Notas
    Arquivo .md com as notas da release (no lugar das notas geradas pelos commits).

.EXAMPLE
    .\scripts\publicar-release.ps1
.EXAMPLE
    .\scripts\publicar-release.ps1 -SemBuild -Notas .\notas-1.6.md
#>
[CmdletBinding()]
param(
    [switch]$SemBuild,
    [string]$Notas
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz

# ------------------------------------------------------------------ versão
$gradle = Get-Content (Join-Path $raiz 'app\build.gradle.kts') -Raw -Encoding UTF8
$versao = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $versao) { throw 'versionName não encontrado em app/build.gradle.kts' }
$tag = "v$versao"
$titulo = "Charrua OBD Pro v$versao"
$apk = Join-Path $raiz "dist\CharruaOBDPro-$versao-release.apk"
Write-Host "Release $tag (versionCode $versionCode)" -ForegroundColor Cyan

# ------------------------------------------------------------------ GitHub CLI
$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    $padrao = 'C:\Program Files\GitHub CLI\gh.exe'
    if (Test-Path $padrao) { $gh = Get-Item $padrao } else {
        throw "GitHub CLI (gh) não encontrado. Instale com 'winget install --id GitHub.cli' e autentique com 'gh auth login'."
    }
}
$ghExe = if ($gh.Source) { $gh.Source } else { $gh.FullName }
& $ghExe auth status *> $null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI sem login. Rode 'gh auth login' e tente de novo." }

# ------------------------------------------------------------------ build
if (-not $SemBuild) {
    Write-Host 'Gerando o APK de release (gradlew assembleRelease)...' -ForegroundColor Cyan
    & (Join-Path $raiz 'gradlew.bat') assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'assembleRelease falhou' }
}
if (-not (Test-Path $apk)) { throw "APK não encontrado: $apk (o release precisa do keystore.properties para ser assinado)" }

# Cópia para o Google Drive (o Gradle já copia; aqui garante com -SemBuild).
foreach ($drive in @('G:\Meu Drive\CharruaOBD', 'G:\My Drive\CharruaOBD')) {
    if (Test-Path $drive) {
        Copy-Item $apk $drive -Force
        Write-Host "Copiado para $drive"
    }
}

# ------------------------------------------------------------------ release
& $ghExe release view $tag *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Host "A release $tag já existe: substituindo o APK anexado..." -ForegroundColor Yellow
    & $ghExe release upload $tag $apk --clobber
    if ($LASTEXITCODE -ne 0) { throw 'gh release upload falhou' }
} else {
    $argumentos = @('release', 'create', $tag, $apk, '--title', $titulo, '--target', 'main')
    if ($Notas) { $argumentos += @('--notes-file', $Notas) } else { $argumentos += '--generate-notes' }
    & $ghExe @argumentos
    if ($LASTEXITCODE -ne 0) { throw 'gh release create falhou' }
}

& $ghExe release view $tag --json url --jq .url
Write-Host "Release $tag publicada." -ForegroundColor Green
