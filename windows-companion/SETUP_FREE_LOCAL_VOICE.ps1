$ErrorActionPreference = 'Stop'

$VoiceRoot = Join-Path $env:APPDATA 'SarahMorgan-R2-Candidate\local_voice'
$Venv = Join-Path $VoiceRoot '.venv'
$Reference = Join-Path $VoiceRoot 'sarah_original_reference.wav'
$ExpectedSha = '6F92CDF4EE7D2409C08550D6B75553C944CA9180C3F056A462C437177E04A672'
$ExpectedBytes = 526124
$ReferenceUrl = 'https://media.githubusercontent.com/media/rmcmurrer81/KiraWorld/main/Voice/qwen3_original_voice_forge/auditions/calm_female_pilot_20260825_01/candidate.wav'

New-Item -ItemType Directory -Force -Path $VoiceRoot | Out-Null

$Py = Get-Command py -ErrorAction SilentlyContinue
if (-not $Py) {
    throw 'Python launcher was not found. Install Python 3.11, then run this setup again.'
}

& py -3.11 -c "import sys; print(sys.version)"
if ($LASTEXITCODE -ne 0) {
    throw 'Python 3.11 is required for the validated local Chatterbox voice setup.'
}

if (-not (Test-Path $Venv)) {
    & py -3.11 -m venv $Venv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create Sarah local voice environment.' }
}
$Python = Join-Path $Venv 'Scripts\python.exe'

& $Python -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) { throw 'Could not update pip.' }
& $Python -m pip install 'chatterbox-tts==0.1.7' soundfile numpy
if ($LASTEXITCODE -ne 0) { throw 'Could not install the free local voice runtime.' }

if (-not (Test-Path $Reference)) {
    Invoke-WebRequest -UseBasicParsing -Uri $ReferenceUrl -OutFile $Reference
}
$File = Get-Item $Reference
if ($File.Length -ne $ExpectedBytes) {
    Remove-Item $Reference -Force -ErrorAction SilentlyContinue
    throw "Sarah voice reference size check failed: $($File.Length)"
}
$Hash = (Get-FileHash $Reference -Algorithm SHA256).Hash
if ($Hash -ne $ExpectedSha) {
    Remove-Item $Reference -Force -ErrorAction SilentlyContinue
    throw 'Sarah voice reference hash check failed.'
}

# Download/cache the open local model once. Sarah runs it in offline mode afterwards.
& $Python -c "from chatterbox.tts import ChatterboxTTS; ChatterboxTTS.from_pretrained(device='cpu'); print('Sarah local voice model cached')"
if ($LASTEXITCODE -ne 0) {
    throw 'The local Chatterbox model could not be cached.'
}

Write-Host ''
Write-Host 'Sarah free local voice is ready.'
Write-Host 'Restart Sarah. No ElevenLabs key, subscription, or per-sentence payment is required.'
