# 本地开发统一入口：测试、构建、运行和管理员初始化都从项目根目录调用。
# 脚本只修改当前进程环境，不写机器全局配置；退出后恢复原工作目录。
param(
    [Parameter(Position = 0)]
    [ValidateSet('Test', 'Build', 'Run', 'InitAdmin', 'MasterKey', 'Format', 'FrontendDev')]
    [string]$Action = 'Test',
    [string]$EnvFile
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $EnvFile) { $EnvFile = Join-Path $taskRoot '.env' }
# 环境文件按纯文本处理，只允许项目认可的配置前缀，绝不点执行或调用表达式。
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
    # 前端先构建到 dist，随后 Maven 把静态文件一起打入可执行 JAR。
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
    # 使用项目缓存和明确的 HTTPS 仓库，不覆盖开发机原有 Maven 全局设置。
    switch ($Action) {
        'Test' { & mvn -s .mvn/settings.xml '-Dmaven.repo.local=../.cache/maven' -B -ntp test }
        'Build' { & mvn -s .mvn/settings.xml '-Dmaven.repo.local=../.cache/maven' -B -ntp clean package }
        'Format' { & mvn -s .mvn/settings.xml '-Dmaven.repo.local=../.cache/maven' -B -ntp fmt:format }
        default {
            $taskJar = Join-Path (Get-Location) 'target/clone-me-0.1.0-SNAPSHOT.jar'
            if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Run scripts/dev.ps1 Build first.' }
            # 为小内存服务器提供初始内存边界；实际资源占用仍需部署后测量。
            # SQLite 需要本地调用权限，显式启用以免新版 JDK 输出本地访问警告。
            $taskJavaArgs = @('--enable-native-access=ALL-UNNAMED', '-Xms128m', '-Xmx512m', '-XX:MaxDirectMemorySize=128m', '-jar', $taskJar)
            if ($Action -eq 'InitAdmin') { $taskJavaArgs += '--initialize-admin' }
            if ($Action -eq 'MasterKey') { $taskJavaArgs += '--generate-master-key' }
            & java @taskJavaArgs
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE" }
} finally { Set-Location -LiteralPath $taskPrevious.Path }
