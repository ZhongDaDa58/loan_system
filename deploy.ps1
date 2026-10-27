# =============================================================================
# deploy.ps1 —— 统一部署入口（本地 / Docker 二选一，禁止混跑）
# =============================================================================
# 解决什么问题：
#   以前 8080 被 Docker 占用时，就临时 mvnw spring-boot:run -Dserver.port=8090，
#   但 Vue / Android / AI 客服全都还指向 8080 —— 于是开了两个后端：
#   8080 是旧镜像、8090 是新代码，前端连的是旧的，查半天才发现。
#
# 本脚本的做法：**不让改端口**。端口被占就报错，告诉你该停谁。
#
# 用法：
#   .\deploy.ps1 status              # 谁在跑、什么版本
#   .\deploy.ps1 start local         # 本地全套（本机 MySQL/Redis + Java:8080）
#   .\deploy.ps1 start docker        # Docker 全套（会先停本地 Java）
#   .\deploy.ps1 stop                # 全停（本地 Java + Docker）
#   .\deploy.ps1 check               # 五方连通性自检
# =============================================================================

param(
    [Parameter(Position = 0)]
    [ValidateSet('status', 'start', 'stop', 'check')]
    [string]$Action = 'status',

    [Parameter(Position = 1)]
    [ValidateSet('local', 'docker')]
    [string]$Mode = 'local'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 端口契约 —— 全项目唯一定义处，不要在别处写死端口
# ---------------------------------------------------------------------------
$PORTS = [ordered]@{
    JavaBackend = 8080    # Vue(proxy) / Android(10.0.2.2) / AI客服 都指它
    AiService   = 8000    # 只被 Java 调用，不对外
    VueDev      = 3000    # 浏览器访问
    MySQL       = 3306    # 本机服务（Docker 模式是 3307 映射）
    Redis       = 6379    # 本机服务（Docker 模式是 6380 映射）
}

$LOAN_DIR = $PSScriptRoot

function Write-Head($t) {
    Write-Host ''
    Write-Host "===== $t =====" -ForegroundColor Cyan
}

function Get-PortOwner([int]$port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
    if (-not $conn) { return $null }
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($conn.OwningProcess)" -ErrorAction SilentlyContinue
    return [pscustomobject]@{
        Port    = $port
        Pid     = $conn.OwningProcess
        Name    = if ($p) { $p.Name } else { '?' }
        Started = if ($p) { $p.CreationDate } else { $null }
        CmdLine = if ($p) { $p.CommandLine } else { '' }
    }
}

# 判断占用者是「本项目的 Java（本地跑）」还是「Docker 容器」
function Test-IsLocalJava($owner) {
    return $owner -and $owner.Name -match '^java' -and $owner.CmdLine -match 'loan|spring-boot|LoanApplication'
}

function Test-IsDockerPort($owner) {
    if (-not $owner) { return $false }
    return $owner.Name -match 'com.docker|vpnkit|Docker'
}

# ---------------------------------------------------------------------------
# status
# ---------------------------------------------------------------------------
function Show-Status {
    Write-Head '端口占用'
    foreach ($k in $PORTS.Keys) {
        $port = $PORTS[$k]
        $o = Get-PortOwner $port
        if ($o) {
            $kind = if (Test-IsLocalJava $o) { '本地Java' }
                    elseif (Test-IsDockerPort $o) { 'Docker' }
                    else { $o.Name }
            Write-Host ("  {0,-12} {1,-6} 占用  [{2}] PID={3}  启动于 {4}" -f $k, $port, $kind, $o.Pid, $o.Started) -ForegroundColor Yellow
        } else {
            Write-Host ("  {0,-12} {1,-6} 空闲" -f $k, $port) -ForegroundColor DarkGray
        }
    }

    Write-Head 'Docker 容器'
    $ps = docker ps -a --format '{{.Names}}|{{.Status}}|{{.Ports}}' 2>$null
    if ($ps) { $ps | ForEach-Object { Write-Host "  $_" } } else { Write-Host '  （无）' -ForegroundColor DarkGray }

    Write-Head 'Docker 镜像（loan-app）'
    $img = docker images --format '{{.Repository}}:{{.Tag}}  {{.CreatedSince}}' 2>$null | Where-Object { $_ -match 'loan-app' }
    if ($img) { $img | ForEach-Object { Write-Host "  $_" } } else { Write-Host '  （无）' -ForegroundColor DarkGray }

    Write-Head '当前模式判断'
    $java = Get-PortOwner $PORTS.JavaBackend
    if (-not $java) {
        Write-Host '  后端未运行' -ForegroundColor DarkGray
    } elseif (Test-IsLocalJava $java) {
        Write-Host '  ★ local 模式（本地 Java 占 8080）' -ForegroundColor Green
    } elseif (Test-IsDockerPort $java) {
        Write-Host '  ★ docker 模式（容器占 8080）' -ForegroundColor Green
    } else {
        Write-Host "  ⚠ 8080 被未知进程占用：$($java.Name)" -ForegroundColor Red
    }
    Write-Host ''
}

# ---------------------------------------------------------------------------
# start local
# ---------------------------------------------------------------------------
function Start-Local {
    Write-Head '启动 local 模式'

    # 闸门 1：8080 必须是空的（Docker 或别的 Java 都不能占）
    $owner = Get-PortOwner $PORTS.JavaBackend
    if ($owner) {
        $isDocker = Test-IsDockerPort $owner
        Write-Host "  ✗ 端口 $($PORTS.JavaBackend) 已被占用：$($owner.Name) (PID=$($owner.Pid))" -ForegroundColor Red
        if ($isDocker) {
            Write-Host '    看起来是 Docker 在跑。local 模式下请先执行：' -ForegroundColor Yellow
            Write-Host '      docker compose down' -ForegroundColor Yellow
        } else {
            Write-Host '    先停掉占用者，不要改端口：' -ForegroundColor Yellow
            Write-Host "      Stop-Process -Id $($owner.Pid)" -ForegroundColor Yellow
        }
        Write-Host ''
        Write-Host '  ⚠ 不要用 -Dserver.port=8090 绕过 —— 前端/AI 全都指向 8080，' -ForegroundColor Red
        Write-Host '    改了端口它们连的是另一个后端（这就是「多端口 + 旧版本」的根源）。' -ForegroundColor Red
        exit 1
    }

    # 闸门 2：本机 MySQL / Redis 必须在（local 模式不用 Docker 的）
    foreach ($dep in @(@{ N = 'MySQL'; P = $PORTS.MySQL }, @{ N = 'Redis'; P = $PORTS.Redis })) {
        if (-not (Get-PortOwner $dep.P)) {
            Write-Host "  ✗ $($dep.N) 未运行（端口 $($dep.P)）" -ForegroundColor Red
            Write-Host "    local 模式依赖本机 $($dep.N) 服务，请先启动" -ForegroundColor Yellow
            exit 1
        }
        Write-Host "  ✓ $($dep.N) :$($dep.P) 已在运行" -ForegroundColor Green
    }

    # 闸门 3：AI 客服（可选，缺失只警告）
    if (Get-PortOwner $PORTS.AiService) {
        Write-Host "  ✓ AI 客服 :$($PORTS.AiService) 已在运行" -ForegroundColor Green
    } else {
        Write-Host "  ⚠ AI 客服 :$($PORTS.AiService) 未运行（智能客服会不可用，其它功能正常）" -ForegroundColor Yellow
        Write-Host "    启动：cd D:\code\ai_service; .\.venv\Scripts\python -m uvicorn app.main:app --port 8000" -ForegroundColor Yellow
    }

    Write-Host ''
    Write-Host '  正在启动 Java 后端（端口 8080）...' -ForegroundColor Cyan
    Write-Host '  （前台运行，Ctrl+C 停止）' -ForegroundColor DarkGray
    Push-Location $LOAN_DIR
    try { & .\mvnw.cmd spring-boot:run } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# start docker
# ---------------------------------------------------------------------------
function Start-Docker {
    Write-Head '启动 docker 模式'

    # 闸门：本地 Java 不能占 8080
    $owner = Get-PortOwner $PORTS.JavaBackend
    if ($owner -and (Test-IsLocalJava $owner)) {
        Write-Host "  ✗ 本地 Java 正在占用 8080 (PID=$($owner.Pid))" -ForegroundColor Red
        Write-Host '    docker 模式下请先停掉它（否则容器起不来）：' -ForegroundColor Yellow
        Write-Host "      Stop-Process -Id $($owner.Pid)" -ForegroundColor Yellow
        exit 1
    }

    Push-Location $LOAN_DIR
    try {
        Write-Host '  docker compose up -d --build ...' -ForegroundColor Cyan
        & docker compose up -d --build
        if ($LASTEXITCODE -ne 0) { throw 'docker compose 失败' }
        Write-Host ''
        Write-Host '  ✓ 已启动。注意：' -ForegroundColor Green
        Write-Host '    - MySQL 从宿主机访问是 3307（容器内 3306）' -ForegroundColor DarkGray
        Write-Host '    - Redis 从宿主机访问是 6380（容器内 6379）' -ForegroundColor DarkGray
        Write-Host '    - AI 客服不在 compose 里，需要单独起 :8000' -ForegroundColor DarkGray
    } finally { Pop-Location }
}

# ---------------------------------------------------------------------------
# stop
# ---------------------------------------------------------------------------
function Stop-All {
    Write-Head '停止所有'

    $owner = Get-PortOwner $PORTS.JavaBackend
    if ($owner -and (Test-IsLocalJava $owner)) {
        Write-Host "  停止本地 Java (PID=$($owner.Pid))" -ForegroundColor Yellow
        Stop-Process -Id $owner.Pid -Force -ErrorAction SilentlyContinue
    }

    Push-Location $LOAN_DIR
    try {
        Write-Host '  docker compose down（保留数据卷）' -ForegroundColor Yellow
        & docker compose down 2>$null
    } finally { Pop-Location }

    Write-Host '  ✓ 已停止（AI 客服和 Vue 需手动停）' -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# check —— 五方连通性自检
# ---------------------------------------------------------------------------
function Invoke-Check {
    Write-Head '连通性自检'

    # 1. Java 后端
    try {
        $h = Invoke-RestMethod "http://127.0.0.1:$($PORTS.JavaBackend)/actuator/health" -TimeoutSec 5
        Write-Host "  ✓ Java 后端 :$($PORTS.JavaBackend)  status=$($h.status)" -ForegroundColor Green
        $backendUp = $true
    } catch {
        Write-Host "  ✗ Java 后端 :$($PORTS.JavaBackend)  不可达" -ForegroundColor Red
        $backendUp = $false
    }

    # 2. AI 客服
    try {
        $a = Invoke-RestMethod "http://127.0.0.1:$($PORTS.AiService)/health" -TimeoutSec 5 -ErrorAction Stop
        Write-Host "  ✓ AI 客服 :$($PORTS.AiService)  可达" -ForegroundColor Green
    } catch {
        # 试 /docs（FastAPI 默认）
        try {
            Invoke-WebRequest "http://127.0.0.1:$($PORTS.AiService)/docs" -TimeoutSec 5 -UseBasicParsing | Out-Null
            Write-Host "  ✓ AI 客服 :$($PORTS.AiService)  可达" -ForegroundColor Green
        } catch {
            Write-Host "  ✗ AI 客服 :$($PORTS.AiService)  不可达" -ForegroundColor Red
        }
    }

    # 3. Java → AI 客服 的配置值是否正确
    if ($backendUp) {
        try {
            $login = Invoke-RestMethod -Method Post "http://127.0.0.1:$($PORTS.JavaBackend)/api/v1/admin/login" `
                -ContentType 'application/json; charset=utf-8' `
                -Body ([Text.Encoding]::UTF8.GetBytes('{"phone":"18759323116","password":"123456abc"}')) -TimeoutSec 10
            if ($login.data.token) {
                Write-Host "  ✓ 管理员登录可用（token 已获取）" -ForegroundColor Green
            }
        } catch {
            Write-Host "  ⚠ 管理员登录失败（可能触发限流，5~10 分钟后再试）" -ForegroundColor Yellow
        }
    }

    # 4. MySQL / Redis
    foreach ($dep in @(@{ N = 'MySQL'; P = $PORTS.MySQL }, @{ N = 'Redis'; P = $PORTS.Redis })) {
        $o = Get-PortOwner $dep.P
        if ($o) { Write-Host "  ✓ $($dep.N) :$($dep.P) 在监听" -ForegroundColor Green }
        else { Write-Host "  ✗ $($dep.N) :$($dep.P) 未监听" -ForegroundColor Red }
    }

    # 5. 配置一致性检查（最容易出错的地方）
    Write-Head '配置一致性'
    $vueCfg = Join-Path 'D:\code\vue-loan-project\vue-loan-project' 'vite.config.ts'
    if (Test-Path $vueCfg) {
        $m = Select-String -Path $vueCfg -Pattern 'localhost:(\d+)' | Select-Object -First 1
        if ($m) {
            $p = $m.Matches[0].Groups[1].Value
            $okFlag = if ([int]$p -eq $PORTS.JavaBackend) { '✓' } else { '✗' }
            Write-Host "  $okFlag Vue proxy → :$p （应为 $($PORTS.JavaBackend)）" -ForegroundColor $(if ($okFlag -eq '✓') { 'Green' } else { 'Red' })
        }
    }
    $aiEnv = Join-Path 'D:\code\ai_service' '.env'
    if (Test-Path $aiEnv) {
        $m = Select-String -Path $aiEnv -Pattern 'JAVA_BASE_URL=.*:(\d+)' | Select-Object -First 1
        if ($m) {
            $p = $m.Matches[0].Groups[1].Value
            $okFlag = if ([int]$p -eq $PORTS.JavaBackend) { '✓' } else { '✗' }
            Write-Host "  $okFlag AI客服 JAVA_BASE_URL → :$p （应为 $($PORTS.JavaBackend)）" -ForegroundColor $(if ($okFlag -eq '✓') { 'Green' } else { 'Red' })
        }
    }
    $androidCfg = 'D:\code\LoanBorrowApp\LoanBorrowApp\app\src\main\java\com\example\loanborrowapp\utils\Constants.java'
    if (Test-Path $androidCfg) {
        $m = Select-String -Path $androidCfg -Pattern 'BASE_URL\s*=\s*"http://[^:]+:(\d+)' | Select-Object -First 1
        if ($m) {
            $p = $m.Matches[0].Groups[1].Value
            $okFlag = if ([int]$p -eq $PORTS.JavaBackend) { '✓' } else { '✗' }
            Write-Host "  $okFlag Android BASE_URL → :$p （应为 $($PORTS.JavaBackend)；10.0.2.2 = 模拟器访问宿主机）" -ForegroundColor $(if ($okFlag -eq '✓') { 'Green' } else { 'Red' })
        }
        # CONTRACT_BASE_URL 历史遗留硬编码局域网 IP，换网络/换机器就失效
        $c = Select-String -Path $androidCfg -Pattern 'CONTRACT_BASE_URL\s*=\s*"http://(\d+\.\d+\.\d+\.\d+):(\d+)' | Select-Object -First 1
        if ($c) {
            $ip = $c.Matches[0].Groups[1].Value
            if ($ip -ne '10.0.2.2') {
                Write-Host "  ⚠ Android CONTRACT_BASE_URL 硬编码 $ip —— 换网络会失效，建议复用 BASE_URL" -ForegroundColor Yellow
            }
        }
    }
    Write-Host ''
}

# ---------------------------------------------------------------------------
switch ($Action) {
    'status' { Show-Status }
    'start' {
        if ($Mode -eq 'local') { Start-Local } else { Start-Docker }
    }
    'stop' { Stop-All }
    'check' { Invoke-Check }
}
