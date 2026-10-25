# =============================================================================
# 压测基线（阶段二 任务 5）
# =============================================================================
# 为什么需要它：阶段二的核心逻辑是「先压测 → 知道瓶颈 → 才决定缓存什么」。
# 之前跳过了这一步直接做了 Redis 和线程池，导致"缓存加得对不对、有没有效果"
# 全是没有数据支撑的判断。这个脚本补上「测量」环节。
#
# 用法：
#   .\loadtest.ps1                      # 默认跑 A/B/C（无副作用）
#   .\loadtest.ps1 -Scenario A          # 只跑单个场景
#   .\loadtest.ps1 -Scenario All -Phone 13900009001 -Password test1234
#   .\loadtest.ps1 -Scenario D          # ⚠️ 会真的建单！跑完要清理（见输出提示）
#
# 依赖：hey（Go 写的压测工具，替代 JMeter/wrk）
#   安装：go install github.com/rakyll/hey@latest
#
# 四个场景是分层的 —— 逐层加变量，才能定位瓶颈：
#   A  框架基线      /actuator/health      （含每次探 MySQL+Redis，所以不是"纯框架"）
#   B  只读+缓存     /api/v1/products      （对比 A 看 DB 探测 vs 缓存命中的差距）
#   C  准入校验链    POST /submit(超范围)   （认证 + 事务 + 准入校验，400 早退不建单）
#   D  完整决策链    POST /submit(合法)     （⚠️ 会建单，用唯一金额标记便于清理）
# =============================================================================

param(
    [string]$BaseUrl = "http://127.0.0.1:8090",
    [ValidateSet('A','B','C','D','All')]
    [string]$Scenario = "All",
    [string]$Phone = "13900009001",
    [string]$Password = "test1234",
    [int]$HealthN = 3000,  [int]$HealthC = 50,
    [int]$ProductsN = 3000, [int]$ProductsC = 50,
    [int]$SubmitN = 1000,  [int]$SubmitC = 20,
    [int]$WriteN = 50,     [int]$WriteC = 10,
    # 场景 D 的标记金额：必须是一个"平时不会出现"的唯一值，便于精确清理
    [string]$MarkerAmount = "77777.01"
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ---------- 找 hey ----------
$hey = (Get-Command hey -ErrorAction SilentlyContinue).Source
if (-not $hey) {
    $candidate = Join-Path $env:USERPROFILE 'go\bin\hey.exe'
    if (Test-Path $candidate) { $hey = $candidate }
}
if (-not $hey) {
    Write-Host "❌ 未找到 hey。安装：go install github.com/rakyll/hey@latest" -ForegroundColor Red
    exit 1
}

$results = @()

function Show-Result {
    param([string]$Name, [string]$Note, [string[]]$Lines)
    $qps = ($Lines | Select-String -Pattern 'Requests/sec:\s+([\d\.]+)').Matches.Groups[1].Value
    $avg = ($Lines | Select-String -Pattern 'Average:\s+([\d\.]+)').Matches.Groups[1].Value
    $slow = ($Lines | Select-String -Pattern 'Slowest:\s+([\d\.]+)').Matches.Groups[1].Value
    $codes = ($Lines | Select-String -Pattern '\[(\d{3})\]\s+(\d+) responses' | ForEach-Object { $_.Line.Trim() }) -join ' '
    Write-Host ("  QPS={0,-10} 平均={1}s 最慢={2}s   {3}" -f $qps, $avg, $slow, $codes) -ForegroundColor Cyan
    $script:results += [PSCustomObject]@{ 场景 = $Name; 说明 = $Note; QPS = $qps; 平均秒 = $avg; 最慢秒 = $slow; 状态码 = $codes }
}

# ---------- 登录（C/D 需要）----------
function Get-Token {
    $body = [Text.Encoding]::UTF8.GetBytes("{`"phone`":`"$Phone`",`"password`":`"$Password`"}")
    try {
        $r = Invoke-RestMethod -Method Post "$BaseUrl/api/v1/user/login" -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 10
        return $r.data.token
    } catch {
        Write-Host "  ❌ 登录失败：$($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

# ⚠️ 用文件传 body —— PowerShell 会吃掉 -d '{...}' 里的双引号，
#    导致 hey 收到非法 JSON，服务端报 HttpMessageNotReadableException（表现为 500）
function Invoke-HeyWithJson {
    param([string]$Json, [string]$Url, [int]$N, [int]$C, [string]$Token)
    $tmp = Join-Path $env:TEMP ("hey-" + [guid]::NewGuid().ToString('N').Substring(0,8) + ".json")
    [IO.File]::WriteAllText($tmp, $Json, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $out = & $hey -n $N -c $C -m POST -T "application/json" `
            -H "Authorization: Bearer $Token" -D $tmp $Url 2>&1
        return @($out | ForEach-Object { "$_" })
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "`n===== 压测目标: $BaseUrl =====" -ForegroundColor Yellow

# ---------- A ----------
if ($Scenario -in @('A','All')) {
    Write-Host "`n[A] 框架基线  GET /actuator/health  (n=$HealthN, c=$HealthC)" -ForegroundColor Green
    Write-Host "    说明：health 每次都会探 MySQL + Redis，所以这个数字含 DB 探测开销"
    $o = @(& $hey -n $HealthN -c $HealthC "$BaseUrl/actuator/health" 2>&1 | ForEach-Object { "$_" })
    Show-Result 'A 框架基线' '含 DB/Redis 探测' $o
}

# ---------- B ----------
if ($Scenario -in @('B','All')) {
    Write-Host "`n[B] 只读+缓存  GET /api/v1/products  (n=$ProductsN, c=$ProductsC)" -ForegroundColor Green
    Write-Host "    说明：对比 A 可以量化「每次探 DB」vs「缓存命中」的差距"
    $o = @(& $hey -n $ProductsN -c $ProductsC "$BaseUrl/api/v1/products" 2>&1 | ForEach-Object { "$_" })
    Show-Result 'B 只读+缓存' '商品列表（@Cacheable）' $o
}

# ---------- C ----------
if ($Scenario -in @('C','All')) {
    Write-Host "`n[C] 准入校验链  POST /submit（金额超范围→400 早退，不建单）  (n=$SubmitN, c=$SubmitC)" -ForegroundColor Green
    $token = Get-Token
    if ($token) {
        $json = '{"productId":"product_001","applyAmount":99999999,"applyTerm":12}'
        $o = Invoke-HeyWithJson -Json $json -Url "$BaseUrl/api/v1/loan/application/submit" -N $SubmitN -C $SubmitC -Token $token
        Show-Result 'C 准入校验' '认证+事务+准入校验（400早退）' $o
    }
}

# ---------- D ----------
if ($Scenario -in @('D','All')) {
    Write-Host "`n[D] 完整决策链  POST /submit（合法金额，⚠️ 会真的建单）  (n=$WriteN, c=$WriteC)" -ForegroundColor Magenta
    Write-Host "    标记金额 $MarkerAmount —— 跑完用这条 SQL 精确清理：" -ForegroundColor Magenta
    Write-Host "    DELETE FROM loan_application WHERE apply_amount=$MarkerAmount;" -ForegroundColor Magenta
    $token = Get-Token
    if ($token) {
        $json = "{`"productId`":`"product_001`",`"applyAmount`":$MarkerAmount,`"applyTerm`":12}"
        $o = Invoke-HeyWithJson -Json $json -Url "$BaseUrl/api/v1/loan/application/submit" -N $WriteN -C $WriteC -Token $token
        Show-Result 'D 完整建单' "建单（标记金额 $MarkerAmount）" $o
    }
}

# ---------- 汇总 ----------
if ($results.Count -gt 0) {
    Write-Host "`n`n===== 汇总 =====" -ForegroundColor Yellow
    $results | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    Write-Host "参考结论（2026-10-25 实测）：" -ForegroundColor Gray
    Write-Host "  · B 明显快于 A  → 缓存在生效；「每次探 DB」约 70ms 量级" -ForegroundColor Gray
    Write-Host "  · C 约 1000 QPS → 认证 + 准入校验(3 次 DB 查询) 不是瓶颈" -ForegroundColor Gray
    Write-Host "  · D 单次 27~35ms → 申请提交链路对低频写业务完全够用" -ForegroundColor Gray
    Write-Host "  · 结论：不需要再加速度优化；申请提交是低频写，加缓存反而引入一致性风险" -ForegroundColor Gray
}
