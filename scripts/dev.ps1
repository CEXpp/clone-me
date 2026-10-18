param(
    [Parameter(Position = 0)]
    [ValidateSet('Test', 'Build', 'Run', 'InitAdmin', 'MasterKey', 'Format', 'FrontendDev')]
    [string]$Action = 'Test',
    [string]$EnvFile
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $EnvFile) { $EnvFile = Join-Path $taskRoot '.env' }
if (Test-Path -LiteralPath $EnvFile) {
    foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') { throw 'Invalid .env line; use KEY=value.' }
        $taskKey = $Matches[1]
        $taskValue = $Matches[2].Trim()
        if ($taskKey -notmatch '^(CLONE_|DEEPSEEK_|SILICONFLOW_|TYPESAFE_|DASHSCOPE_|BAILIAN_|TURNSTILE_|SPRING_MAIL_|DECISION_PROVIDER$|PORT$)') { throw "Unsupported environment key: $taskKey" }
        if ($taskValue.Length -ge 2 -and (($taskValue.StartsWith('"') -and $taskValue.EndsWith('"')) -or ($taskValue.StartsWith("'") -and $taskValue.EndsWith("'")))) { $taskValue = $taskValue.Substring(1, $taskValue.Length - 2) }
        [Environment]::SetEnvironmentVariable($taskKey, $taskValue, 'Process')
    }
}
$taskPrevious = Get-Location
try {
    if ($Action -in @('Test', 'Build', 'FrontendDev')) {
        Set-Location -LiteralPath (Join-Path $taskRoot 'frontend')
        if (-not (Test-Path -LiteralPath 'node_modules')) { & npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' } }
        switch ($Action) {
            'Test' { & npm test }
            'Build' { & npm run build }
            'FrontendDev' { & npm run dev }
        }
        if ($LASTEXITCODE -ne 0) { throw 'Frontend command failed.' }
        if ($Action -eq 'FrontendDev') { return }
    }
    Set-Location -LiteralPath (Join-Path $taskRoot 'backend')
    switch ($Action) {
        'Test' { & mvn -s .mvn/settings.xml '-Dmaven.repo.local=../.cache/maven' -B -ntp test }
        'Build' { & mvn -s .mvn/settings.xml '-Dmaven.repo.local=../.cache/maven' -B -ntp clean package }
        'Format' { & mvn -s .mvn/settings.xml '-Dmaven.repo.local=../.cache/maven' -B -ntp fmt:format }
        default {
            $taskJar = Join-Path (Get-Location) 'target/clone-me-0.1.0-SNAPSHOT.jar'
            if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Run scripts/dev.ps1 Build first.' }
            $taskJavaArgs = @('--enable-native-access=ALL-UNNAMED', '-Xms128m', '-Xmx512m', '-XX:MaxDirectMemorySize=128m', '-jar', $taskJar)
            if ($Action -eq 'InitAdmin') { $taskJavaArgs += '--initialize-admin' }
            if ($Action -eq 'MasterKey') { $taskJavaArgs += '--generate-master-key' }
            & java @taskJavaArgs
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE" }
} finally { Set-Location -LiteralPath $taskPrevious.Path }
