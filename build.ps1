<#
  MosaicGem 构建脚本（免 Gradle：javac + @argfile + jar）

  用法：
    .\build.ps1 -ServerRoot "D:\mc\server"
    或先设置环境变量 MOSAICGEM_SERVER_ROOT，然后直接  .\build.ps1

  说明：
    - classpath 取服务端 libraries 下全部 jar；PlaceholderAPI / MythicMobs 若装在服务端
      plugins 目录则一并加入。缺任意一个时，对应集成类自动跳过编译：
        PlaceholderAPI -> hook\MosaicGemExpansion.java
        MythicMobs     -> hook\mm\MythicMechanicBridge.java
      插件仍可正常构建、正常启动，只是少那一项集成。
    - 版本号默认从 build.gradle.kts 读取，可用 -Version 覆盖
    - 产物：target\MosaicGem-<版本>.jar

  注意：本文件含中文，请以 UTF-8(BOM) 保存，否则 Windows PowerShell 5.1 会按 GBK 读取导致乱码。
#>
param(
    [string]$ServerRoot = $env:MOSAICGEM_SERVER_ROOT,
    [string]$Version
)

$ErrorActionPreference = 'Continue'

if (-not $ServerRoot) {
    throw '请指定服务端根目录： .\build.ps1 -ServerRoot "X:\path\to\server"（或设置环境变量 MOSAICGEM_SERVER_ROOT）'
}
if (-not (Test-Path -LiteralPath $ServerRoot)) { throw "服务端根目录不存在: $ServerRoot" }
$libsRoot = Join-Path $ServerRoot 'libraries'
if (-not (Test-Path -LiteralPath $libsRoot)) { throw "在 $ServerRoot 下找不到 libraries 目录" }
$pluginsRoot = Join-Path $ServerRoot 'plugins'

$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcDir = Join-Path $root 'src\main\java'
$resDir = Join-Path $root 'src\main\resources'
$outDir = Join-Path $root 'target\classes'

if (-not $Version) {
    $gradleFile = Join-Path $root 'build.gradle.kts'
    if (Test-Path -LiteralPath $gradleFile) {
        $m = [regex]::Match((Get-Content -LiteralPath $gradleFile -Raw -Encoding UTF8), 'version\s*=\s*"([^"]+)"')
        if ($m.Success) { $Version = $m.Groups[1].Value }
    }
}
if (-not $Version) { $Version = '1.1.3' }
$jarPath = Join-Path $root ("target\MosaicGem-$Version.jar")

# ---- classpath 与「可选集成」 ----
$cpParts = @()
$cpParts += Get-ChildItem -LiteralPath $libsRoot -Recurse -File -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$optional = @(
    @{ Pattern = 'PlaceholderAPI*.jar'; Skip = 'hook[\\/]MosaicGemExpansion\.java$'; Name = 'PlaceholderAPI' },
    @{ Pattern = 'MythicMobs*.jar';     Skip = 'hook[\\/]mm[\\/]MythicMechanicBridge\.java$'; Name = 'MythicMobs' }
)
$skipPatterns = @()
foreach ($opt in $optional) {
    $jar = Get-ChildItem -LiteralPath $pluginsRoot -Filter $opt.Pattern -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jar) { $cpParts += $jar.FullName }
    else {
        $skipPatterns += $opt.Skip
        Write-Host ("未找到 " + $opt.Name + " jar：跳过对应集成类（插件仍可构建）") -ForegroundColor Yellow
    }
}
$cp = (($cpParts | Select-Object -Unique) -join ';') -replace '\\', '/'

Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$sources = Get-ChildItem -Recurse -File $srcDir -Filter *.java
foreach ($p in $skipPatterns) { $sources = $sources | Where-Object { $_.FullName -notmatch $p } }
$sources = $sources | ForEach-Object { $_.FullName -replace '\\', '/' }
if ($sources.Count -eq 0) { throw '没有找到 .java 源文件' }

$argFile = Join-Path $env:TEMP 'mosaicgem_javac.args'
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('-encoding'); [void]$sb.AppendLine('UTF-8')
[void]$sb.AppendLine('-cp'); [void]$sb.AppendLine($cp)
[void]$sb.AppendLine('-d'); [void]$sb.AppendLine(($outDir -replace '\\', '/'))
foreach ($s in $sources) { [void]$sb.AppendLine($s) }
[IO.File]::WriteAllText($argFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "编译 $($sources.Count) 个源文件 ..."
$javacLog = Join-Path $env:TEMP 'mosaicgem_javac.log'
& javac "@$argFile" *> $javacLog
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath $javacLog -Encoding UTF8 | Out-String -Width 250 | Write-Host
    throw 'javac 编译失败'
}

# ---- 资源 ----
Copy-Item -LiteralPath (Join-Path $resDir 'config.yml') -Destination $outDir -Force
Copy-Item -LiteralPath (Join-Path $resDir 'permissions.yml') -Destination $outDir -Force
Copy-Item -LiteralPath (Join-Path $resDir 'external-aggregate.yml') -Destination $outDir -Force
foreach ($dir in @('messages', 'items')) {
    $target = Join-Path $outDir $dir
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Copy-Item -Path (Join-Path $resDir "$dir\*") -Destination $target -Recurse -Force
}
# plugin.yml 的 ${projectVersion} 占位符替换
$pluginYml = Get-Content -LiteralPath (Join-Path $resDir 'plugin.yml') -Raw -Encoding UTF8
$pluginYml = $pluginYml.Replace('${projectVersion}', $Version)
[IO.File]::WriteAllText((Join-Path $outDir 'plugin.yml'), $pluginYml, (New-Object System.Text.UTF8Encoding($false)))

Push-Location $outDir
try {
    if (Test-Path -LiteralPath $jarPath) { Remove-Item -LiteralPath $jarPath -Force }
    & jar cf $jarPath . | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '打包失败' }
} finally { Pop-Location }

Write-Host "构建完成: $jarPath" -ForegroundColor Green
