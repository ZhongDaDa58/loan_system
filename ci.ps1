# =============================================================================
# 本地 CI 脚本（阶段三 3.4）
# =============================================================================
# 一条命令跑完「CI 会做的事」：
#   1) 环境检查（Java / Docker）
#   2) mvnw verify —— 编译 + 全部测试 + JaCoCo 覆盖率门槛
#   3) docker build —— 构建应用镜像
#
# 用法：
#   .\ci.ps1                 完整流程（测试 + 镜像）
#   .\ci.ps1 -SkipDocker     只跑测试（Docker 不可用时的降级路径）
#   .\ci.ps1 -SkipTests      只编译打包（快速验证能否构建）
#
# ⚠️ 本文件必须保存为 UTF-8 with BOM。
#    PowerShell 5.1 读取无 BOM 的 .ps1 时会按系统 ANSI(GBK) 解析，
#    中文注释会变成乱码进而引发语法错误。
# =============================================================================
param(
    [switch]$SkipDocker,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-Location $PSScriptRoot

$script:failed = $false
$script:startTime = Get-Date

function Step($name) {
    Write-Host ""
    Write-Host ("=" * 68) -ForegroundColor Cyan
    Write-Host "  $name" -ForegroundColor Cyan
    Write-Host ("=" * 68) -ForegroundColor Cyan
}

function Ok($msg)   { Write-Host "  [OK]   $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:failed = $true }
function Info($msg) { Write-Host "  [INFO] $msg" -ForegroundColor Gray }

# ==================== 1. 环境检查 ====================

Step "1/3 环境检查"

$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if ($javaCmd) {
    # java -version 输出到 stderr，用 cmd /c 包一层避免 PowerShell 当成错误对象
    $v = (cmd /c "java -version 2>&1" | Select-Object -First 1)
    Ok "Java: $v"
    if ($env:JAVA_HOME) { Info "JAVA_HOME: $env:JAVA_HOME （mvnw 用它）" }
} else {
    Fail "未找到 java，请确认 JDK 17 已安装并在 PATH 中"
}

if (-not (Test-Path '.\mvnw.cmd')) {
    Fail "未找到 mvnw.cmd，请确认在项目根目录运行"
}

$dockerOk = $false
if (-not $SkipDocker) {
    docker ps *>$null
    if ($LASTEXITCODE -eq 0) {
        $dockerOk = $true
        Ok "Docker 引擎可用"
    } else {
        Fail "Docker 引擎不可用（Docker Desktop 未启动？）—— 可用 -SkipDocker 跳过镜像构建"
    }
} else {
    Info "已跳过 Docker 检查"
}

if ($script:failed) {
    Write-Host ""
    Write-Host "环境检查未通过，终止。" -ForegroundColor Red
    exit 1
}

# ==================== 2. 编译 + 测试 + 覆盖率门槛 ====================

Step "2/3 mvnw verify（编译 + 测试 + JaCoCo 覆盖率门槛）"

if ($SkipTests) {
    Info "已跳过测试，只做编译打包"
    & .\mvnw.cmd -B -q clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { Fail "打包失败"; exit 1 }
    Ok "打包完成"
} else {
    # verify 会依次执行：compile → test → jacoco:report → jacoco:check → package
    # jacoco:check 绑在 verify 阶段，覆盖率低于 25% 时构建会失败
    & .\mvnw.cmd -B verify
    if ($LASTEXITCODE -ne 0) {
        Fail "mvnw verify 失败（可能是测试未通过，或覆盖率低于门槛 25%）"
        Write-Host ""
        Write-Host "  排查建议：" -ForegroundColor Yellow
        Write-Host "    - 测试失败：看 target\surefire-reports\*.txt" -ForegroundColor Yellow
        Write-Host "    - 覆盖率不足：看 target\site\jacoco\index.html" -ForegroundColor Yellow
        Write-Host "    - 集成测试需要 Docker：确认 Docker Desktop 正在运行" -ForegroundColor Yellow
        exit 1
    }
    Ok "编译 + 测试 + 覆盖率门槛 全部通过"
}

# ==================== 3. 镜像构建 ====================

Step "3/3 docker build（应用镜像）"

if ($SkipDocker) {
    Info "已跳过镜像构建"
} elseif ($dockerOk) {
    $tag = "loan-app:ci-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Info "镜像标签: $tag"
    & docker build -t $tag .
    if ($LASTEXITCODE -ne 0) {
        Fail "镜像构建失败"
        exit 1
    }
    $size = (& docker images $tag --format '{{.Size}}')
    Ok "镜像构建成功: $tag  ($size)"
} else {
    Info "Docker 不可用，跳过镜像构建"
}

# ==================== 汇总 ====================

$elapsed = [math]::Round(((Get-Date) - $script:startTime).TotalSeconds, 1)

Write-Host ""
Write-Host ("=" * 68) -ForegroundColor $(if ($script:failed) { 'Red' } else { 'Green' })
if ($script:failed) {
    Write-Host "  CI 未通过（耗时 ${elapsed}s）" -ForegroundColor Red
} else {
    Write-Host "  CI 通过（耗时 ${elapsed}s）" -ForegroundColor Green
    Write-Host ""
    Write-Host "  覆盖率报告：target\site\jacoco\index.html" -ForegroundColor Green
    Write-Host "  测试报告  ：target\surefire-reports\" -ForegroundColor Green
}
Write-Host ("=" * 68) -ForegroundColor $(if ($script:failed) { 'Red' } else { 'Green' })

exit $(if ($script:failed) { 1 } else { 0 })
