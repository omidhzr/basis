# One scenario, end to end, narrated: create, replay, amend, a stale approval,
# a real approval, a refused self-approval, then what the mortgage process
# reads. scripts/demo.sh is the same scenario for bash -- a change to one is a
# change to both.
#
#   scripts\demo.ps1 [-BaseUrl http://localhost:8080]
#
# Needs a running service and nothing else: Invoke-WebRequest is built in.
#
# Windows PowerShell 5.1 throws on a 4xx response instead of returning it, and
# -SkipHttpErrorCheck exists only on PowerShell 7. The 409 and the 403 are the
# two steps that matter most and both are 4xx, so Invoke-Call catches the
# exception and reads the status and body from it. That is what lets this run
# on the PowerShell that ships with Windows.
#
# Identity is the X-User-Id / X-User-Role pair, standing in for JWT claims.
# Nothing verifies it; that is why one script can play both people.
#
# A step that does not answer what it narrates stops the script. A demo that
# says "this is refused" and carries on after a 200 would be telling the
# reader the behaviour holds at the moment it has stopped holding.
#
# ASCII only: 5.1 reads a script with no byte-order mark as ANSI, so anything
# else would be garbled. demo.sh is held to the same rule so the two stay
# comparable.

param(
    [string]$BaseUrl = $(if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' })
)

$ErrorActionPreference = 'Stop'

$Total = 8

# Unique per run so a second run against the same instance shows the same
# thing instead of colliding with the first run's approved exception. The key
# is made once here and reused by the replay: a fresh key per attempt would
# defeat the mechanism from the client side.
$Run = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$Application = "APP-DEMO-$Run"
$Key = "demo-$Run-$PID"
$Rm = 'demo-rm'
$Reviewer = 'demo-reviewer'
$RmRole = 'RELATIONSHIP_MANAGER'
$ReviewerRole = 'REVIEWER'

$script:StepNumber = 0
$script:Status = ''
$script:Response = ''

# Invoke-Call sets $script:Status and $script:Response.
function Invoke-Call {
    param(
        [string]$Method,
        [string]$Path,
        [string]$User,
        [string]$Role,
        [string]$Body = '',
        [string]$IdempotencyKey = ''
    )

    $headers = @{ 'X-User-Id' = $User; 'X-User-Role' = $Role }
    if ($IdempotencyKey) { $headers['Idempotency-Key'] = $IdempotencyKey }

    $request = @{
        Method          = $Method
        Uri             = "$BaseUrl$Path"
        Headers         = $headers
        UseBasicParsing = $true
        TimeoutSec      = 30
    }
    if ($Body) {
        $request['Body'] = $Body
        $request['ContentType'] = 'application/json'
    }

    try {
        $reply = Invoke-WebRequest @request
        $script:Status = [string][int]$reply.StatusCode
        $script:Response = $reply.Content
    }
    catch {
        $failure = $_.Exception.Response
        if ($null -eq $failure) {
            [Console]::Error.WriteLine("Could not reach $BaseUrl. Is the service running? (./mvnw spring-boot:run)")
            exit 1
        }
        $script:Status = [string][int]$failure.StatusCode

        # The body of a 4xx is the problem detail, which is what step 4 and
        # step 6 exist to show. PowerShell puts it in ErrorDetails on both
        # editions; the response stream is the fallback for a host that does not.
        $text = $null
        if ($_.ErrorDetails) { $text = $_.ErrorDetails.Message }
        if (-not $text -and $failure.GetResponseStream) {
            $reader = New-Object System.IO.StreamReader($failure.GetResponseStream())
            $text = $reader.ReadToEnd()
        }
        $script:Response = $text
    }
}

function Step {
    param([string]$Title, [string]$Detail)
    $script:StepNumber++
    Write-Host ''
    Write-Host ("Step {0} of {1}: {2}" -f $script:StepNumber, $Total, $Title)
    Write-Host "  $Detail"
}

function Fail {
    param([string]$Reason)
    Write-Host "  MISMATCH: $Reason"
    Write-Host "  $($script:Response)"
    exit 1
}

# Expect STATUS [TEXT-THE-BODY-MUST-CONTAIN] [-Quiet]
function Expect {
    param([string]$Expected, [string]$Contains = '', [switch]$Quiet)

    Write-Host "  expected $Expected, got $($script:Status)"
    if ($script:Status -ne $Expected) { Fail 'status' }
    if ($Contains -and -not $script:Response.Contains($Contains)) {
        Fail "body does not contain $Contains"
    }
    # Quiet is for the one step whose body is reduced to a few lines below.
    if ($Quiet) { Write-Host '  OK' } else { Write-Host "  OK: $($script:Response)" }
}

Write-Host "Service: $BaseUrl"
Write-Host "Application: $Application"
Write-Host "Idempotency-Key: $Key"

# 1 -------------------------------------------------------------------------
Step 'A relationship manager raises a request' `
     "POST /requests as $Rm -- PENDING at version 1"
$createBody = '{"applicationId":"' + $Application + '","discountBps":25,"reason":"Retention case for a long-standing client"}'
Invoke-Call POST '/requests' $Rm $RmRole $createBody $Key
Expect '201' '"status":"PENDING"'
$firstResponse = $script:Response
$idMatch = [regex]::Match($script:Response, '^\{"id":"([^"]*)"')
if (-not $idMatch.Success) { Fail 'no request id in the response' }
$Id = $idMatch.Groups[1].Value
Write-Host '  25 bp is 0.25 percentage points'

# 2 -------------------------------------------------------------------------
Step 'The same create is retried with the same key' `
     'POST /requests again -- the original response, nothing new created'
Invoke-Call POST '/requests' $Rm $RmRole $createBody $Key
Expect '201'
if ($script:Response -cne $firstResponse) { Fail 'the replay is not the response the first call received' }
Write-Host '  the replay is byte-for-byte the first response, so no second request exists'

# 3 -------------------------------------------------------------------------
Step 'The requester amends it while it is still pending' `
     "PATCH /requests/$Id as $Rm -- 40 bp, version 1 becomes version 2"
Invoke-Call PATCH "/requests/$Id" $Rm $RmRole '{"discountBps":40,"version":1}'
Expect '200' '"version":2'

# 4 -------------------------------------------------------------------------
Step 'A reviewer approves the version they were shown, which has moved on' `
     "POST /requests/$Id/decision as $Reviewer for version 1 -- refused, the request is at version 2"
Invoke-Call POST "/requests/$Id/decision" $Reviewer $ReviewerRole '{"decision":"APPROVE","version":1}'
Expect '409' 'version 2'

# 5 -------------------------------------------------------------------------
Step 'The reviewer approves the current version' `
     "POST /requests/$Id/decision as $Reviewer for version 2 -- recorded against version 2"
Invoke-Call POST "/requests/$Id/decision" $Reviewer $ReviewerRole `
     '{"decision":"APPROVE","version":2,"note":"Within delegated authority"}'
Expect '200' '"status":"APPROVED"'

# 6 -------------------------------------------------------------------------
Step 'The requester tries to approve their own request' `
     "POST /requests/$Id/decision as $Rm holding the REVIEWER role -- refused: the role is not the point"
Invoke-Call POST "/requests/$Id/decision" $Rm $ReviewerRole '{"decision":"APPROVE","version":2}'
Expect '403' 'cannot be decided by the person who raised it'

# 7 -------------------------------------------------------------------------
Step 'The mortgage process asks what discount applies' `
     "GET /applications/$Application/approved-exception"
Invoke-Call GET "/applications/$Application/approved-exception" 'pricing-service' $RmRole
Expect '200' '"discountBps":40'
Write-Host "  40 bp is 0.40 percentage points, approved by $Reviewer"

# 8 -------------------------------------------------------------------------
Step 'The history answers what was approved, by whom' `
     "GET /requests/$Id -- newest first, each entry naming the version it concerned"
Invoke-Call GET "/requests/$Id" $Rm $RmRole
Expect '200' '"entryType":"APPROVED"' -Quiet
foreach ($entry in ($script:Response | ConvertFrom-Json).history) {
    Write-Host ("    {0} at version {1}, by {2}" -f $entry.entryType, $entry.version, $entry.actor)
}

Write-Host ''
Write-Host "All $Total steps answered as narrated."
