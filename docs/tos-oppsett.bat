<# : batch-del - resten av fila er PowerShell
@echo off
setlocal
chcp 65001 >nul
title T'OS oppsett
set "TOS_SELF=%~f0"
set "TOS_MODE=%~1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "iex ([IO.File]::ReadAllText($env:TOS_SELF, [Text.Encoding]::UTF8))"
if errorlevel 1 pause
exit /b
#>

# ---------------------------------------------------------------------------------------------
# T'OS oppsett med PC – dobbeltklikk tos-oppsett.bat og følg teksten.
#   * Laster ned ADB fra Google selv (til %LOCALAPPDATA%\TOS), ingen installasjon, ingen administrator.
#   * Finner stereoen på nettet selv (port 5555), eller spør etter adressen T'OS viser.
#   * Gir T'OS tillatelsene, skjuler stereoens linje øverst, gjør T'OS til «enhetseier» (Full kontroll)
#     og slår av feilsøking igjen til slutt.
#   * «tos-oppsett.bat angre» (eller valg 2) setter stereolinja, Google-tjenester og batterisparing tilbake.
# Samme kommandoer som docs/ADB-OPPSETT.md og T'OS («Ta over stereoen»). PcSetupTest passer på at de stemmer.
# Skrevet for Windows PowerShell 5.1 (Windows 10/11).
# ---------------------------------------------------------------------------------------------

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$Pkg = 'no.bil.launcher'
$Gms = 'com.google.android.gms'
$KeyService = 'no.bil.launcher/no.bil.launcher.KeyService'
$Self = $env:TOS_SELF
$HereDir = Split-Path -Parent $Self
$ToolDir = Join-Path $env:LOCALAPPDATA 'TOS'
if (-not (Test-Path -LiteralPath $ToolDir)) { New-Item -ItemType Directory -Path $ToolDir -Force | Out-Null }
$LogFile = Join-Path $HereDir 'tos-oppsett-logg.txt'
try { [IO.File]::WriteAllText($LogFile, '') } catch { $LogFile = Join-Path $ToolDir 'tos-oppsett-logg.txt' }
$NavFile = Join-Path $ToolDir 'show_navigationbar-foer.txt'
$GmsFile = Join-Path $ToolDir 'google-tjenester-av.txt'
$script:Adb = $null
$script:Serial = $null
$script:Results = New-Object System.Collections.ArrayList
$script:RemovedAccount = $false

function Log([string]$text) {
    try { Add-Content -LiteralPath $LogFile -Value ((Get-Date).ToString('HH:mm:ss') + '  ' + $text) -Encoding UTF8 } catch { }
}
function Say([string]$text, [string]$color = 'Gray') {
    Write-Host $text -ForegroundColor $color
    Log $text
}
function Title([string]$text) {
    Write-Host ''
    Say ('== ' + $text + ' ==') 'White'
}
function Wait-Enter([string]$text = 'Trykk Enter for å fortsette') {
    Read-Host ('  ' + $text) | Out-Null
}
function Ask-YesNo([string]$question) {
    $a = Read-Host ('  ' + $question + ' [J/n]')
    Log ($question + ' -> ' + $a)
    return -not ($a -match '^\s*n')
}
function Add-Result([string]$name, [string]$status, [string]$info = '') {
    [void]$script:Results.Add([pscustomobject]@{ Navn = $name; Status = $status; Info = $info })
    $color = 'Green'
    if ($status -eq 'HOPPET') { $color = 'DarkGray' }
    if ($status -eq 'FEIL') { $color = 'Red' }
    $line = '  ' + $status.PadRight(7) + $name
    if ($info -ne '') { $line = $line + '  (' + $info + ')' }
    Say $line $color
}

# --- ADB -------------------------------------------------------------------------------------

function Invoke-AdbRaw([string]$argLine, [int]$timeoutSec = 30, [bool]$quiet = $false) {
    Log ('adb ' + $argLine)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:Adb
    $psi.Arguments = $argLine
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    # ADB skriver UTF-8 (æøå i svar fra stereoen); uten dette leses det som Windows-tegnsett
    try { $psi.StandardOutputEncoding = [Text.Encoding]::UTF8; $psi.StandardErrorEncoding = [Text.Encoding]::UTF8 } catch { }
    try {
        $p = [System.Diagnostics.Process]::Start($psi)
    } catch {
        Log ('  kunne ikke starte adb: ' + $_)
        return [pscustomobject]@{ Code = -1; Text = 'adb kunne ikke startes' }
    }
    $out = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit($timeoutSec * 1000)) {
        try { $p.Kill() } catch { }
        Log '  (tidsavbrudd)'
        return [pscustomobject]@{ Code = -1; Text = 'tidsavbrudd' }
    }
    $p.WaitForExit()
    $text = ($out.Result + "`n" + $err.Result).Trim()
    if ($quiet) { Log ('  -> ' + $p.ExitCode + ' (svaret skrives ikke i loggen)') } else { Log ('  -> ' + $p.ExitCode + ': ' + $text) }
    return [pscustomobject]@{ Code = $p.ExitCode; Text = $text }
}

# Kjører en linje fra lista («adb shell …») mot stereoen.
function Invoke-Stereo([string]$cmd, [int]$timeoutSec = 30, [bool]$quiet = $false) {
    $rest = $cmd
    if ($rest.StartsWith('adb ')) { $rest = $rest.Substring(4) }
    return Invoke-AdbRaw ('-s ' + $script:Serial + ' ' + $rest) $timeoutSec $quiet
}

function Find-Adb {
    $here = Join-Path $HereDir 'adb.exe'
    if (Test-Path -LiteralPath $here) { return $here }
    $local = Join-Path $ToolDir 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $local) { return $local }
    $inPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($inPath) { return $inPath.Source }
    return $null
}

function Get-Adb {
    Title 'Steg 1 av 4: Verktøyet ADB'
    $found = Find-Adb
    if ($found) {
        Say ('  Fant ADB: ' + $found)
    } else {
        Say '  Laster ned ADB fra Google (ca. 7 MB). Det tar et lite øyeblikk ...'
        $zip = Join-Path $ToolDir 'platform-tools.zip'
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
            Expand-Archive -LiteralPath $zip -DestinationPath $ToolDir -Force
            Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
        } catch {
            Say ('  Nedlastingen feilet: ' + $_.Exception.Message) 'Red'
            Say '  Sjekk at PC-en er på nettet, og prøv igjen.' 'Yellow'
            return $false
        }
        $found = Find-Adb
        if (-not $found) {
            Say '  Fant ikke adb.exe etter nedlastingen.' 'Red'
            return $false
        }
        Say '  ADB er lastet ned.' 'Green'
    }
    $script:Adb = $found
    # Start ADB-tjenesten uten å fange utskriften (ellers kan den henge).
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:Adb
    $psi.Arguments = 'start-server'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    try { $sp = [System.Diagnostics.Process]::Start($psi); [void]$sp.WaitForExit(20000) } catch { Log ('start-server: ' + $_) }
    return $true
}

# --- Finne stereoen --------------------------------------------------------------------------

function Get-LocalIPv4 {
    $list = New-Object System.Collections.ArrayList
    try {
        foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($nic.OperationalStatus -ne 'Up') { continue }
            if ($nic.NetworkInterfaceType -eq 'Loopback') { continue }
            foreach ($u in $nic.GetIPProperties().UnicastAddresses) {
                if ($u.Address.AddressFamily -ne 'InterNetwork') { continue }
                $ip = $u.Address.ToString()
                if ($ip.StartsWith('169.254.') -or $ip.StartsWith('127.')) { continue }
                [void]$list.Add($ip)
            }
        }
    } catch { Log ('nettkort: ' + $_) }
    return $list
}

# Prøver port 5555 på alle adressene i nettet PC-en er på (samtidig, ca. 3 sekunder).
function Find-OpenAdbPorts {
    $own = Get-LocalIPv4
    $prefixes = New-Object System.Collections.ArrayList
    foreach ($ip in $own) {
        $parts = $ip.Split('.')
        $prefix = $parts[0] + '.' + $parts[1] + '.' + $parts[2]
        if (-not $prefixes.Contains($prefix)) { [void]$prefixes.Add($prefix) }
    }
    Log ('PC-ens adresser: ' + ($own -join ', '))
    $probes = New-Object System.Collections.ArrayList
    foreach ($prefix in ($prefixes | Select-Object -First 4)) {
        for ($i = 1; $i -le 254; $i++) {
            $ip = $prefix + '.' + $i
            if ($own -contains $ip) { continue }
            $client = New-Object System.Net.Sockets.TcpClient
            try {
                $task = $client.ConnectAsync([System.Net.IPAddress]::Parse($ip), 5555)
                [void]$probes.Add([pscustomobject]@{ Ip = $ip; Client = $client; Task = $task })
            } catch { $client.Close() }
        }
    }
    $deadline = (Get-Date).AddSeconds(3)
    while ((Get-Date) -lt $deadline) {
        $open = @($probes | Where-Object { -not $_.Task.IsCompleted }).Count
        if ($open -eq 0) { break }
        Start-Sleep -Milliseconds 200
    }
    $hits = New-Object System.Collections.ArrayList
    foreach ($p in $probes) {
        if ($p.Task.Status -eq 'RanToCompletion' -and $p.Client.Connected) { [void]$hits.Add($p.Ip) }
        try { $p.Client.Close() } catch { }
    }
    Log ('Åpen port 5555: ' + ($hits -join ', '))
    return $hits
}

function Get-ConnectedSerials {
    $r = Invoke-AdbRaw 'devices'
    $list = New-Object System.Collections.ArrayList
    foreach ($line in ($r.Text -split "`n")) {
        $line = $line.Trim()
        if ($line -match '^(\S+)\s+(device|unauthorized|offline)$') {
            [void]$list.Add([pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] })
        }
    }
    return $list
}

function Get-State([string]$serial) {
    $r = Invoke-AdbRaw ('-s ' + $serial + ' get-state') 10
    if ($r.Text -match 'unauthorized') { return 'unauthorized' }
    if ($r.Text -match 'offline') { return 'offline' }
    if ($r.Code -eq 0 -and $r.Text -match 'device') { return 'device' }
    return 'none'
}

# Kobler til og venter på «Tillat» på stereoen. Svarer med true når stereoen er klar.
function Connect-Serial([string]$serial) {
    if ($serial -match '^\d+\.\d+\.\d+\.\d+:\d+$') {
        $c = Invoke-AdbRaw ('connect ' + $serial) 15
        if ($c.Text -match 'cannot|failed|unable|refused|No route' -and $c.Text -notmatch 'authenticate') {
            Say ('  Fikk ikke kontakt med ' + $serial + '.') 'Yellow'
            return $false
        }
    }
    $askedAllow = $false
    $offlineTries = 0
    $started = Get-Date
    while ($true) {
        $state = Get-State $serial
        if ($state -eq 'device') { return $true }
        if ($state -eq 'unauthorized') {
            if (-not $askedAllow) {
                Say ''
                Say '  >>> SE PÅ STEREOEN NÅ <<<' 'Yellow'
                Say '  Der står det «Vil du tillate USB-feilsøking?» (eller lignende).' 'Yellow'
                Say '  Kryss av «Tillat alltid fra denne datamaskinen» og trykk OK.' 'Yellow'
                $askedAllow = $true
            }
        } elseif ($state -eq 'offline') {
            $offlineTries++
            if ($offlineTries -le 3 -and $serial -match ':') {
                Invoke-AdbRaw ('disconnect ' + $serial) 10 | Out-Null
                Start-Sleep -Seconds 1
                Invoke-AdbRaw ('connect ' + $serial) 15 | Out-Null
            }
        } else {
            if ($serial -match ':') { Invoke-AdbRaw ('connect ' + $serial) 15 | Out-Null }
        }
        if (((Get-Date) - $started).TotalSeconds -gt 60) {
            Say '  Stereoen svarer ikke ennå.' 'Yellow'
            if ($state -eq 'offline') {
                Say '  Tips: slå «USB-feilsøking» av og på igjen på stereoen.' 'Yellow'
            }
            $a = Read-Host '  Trykk Enter for å prøve igjen, eller skriv a for å avbryte'
            if ($a -match '^\s*a') { return $false }
            $started = Get-Date
        }
        Start-Sleep -Seconds 2
    }
}

function Test-HasTos([string]$serial) {
    $r = Invoke-AdbRaw ('-s ' + $serial + ' shell pm path ' + $Pkg) 15
    return ($r.Text -match 'package:')
}

function Find-Stereo {
    Title 'Steg 2 av 4: Finne stereoen'
    Say '  Både PC-en og stereoen må være på samme Wi-Fi (f.eks. delt nett fra iPhonen).'
    Say '  På stereoen må utviklermodus og feilsøking være på.'
    Say '  T''OS viser hvor: Innstillinger → Om stereoen → Ta over stereoen.'
    Say ''
    # 1) Allerede koblet til (også med kabel)?
    foreach ($d in (Get-ConnectedSerials)) {
        if ($d.State -eq 'device' -and (Test-HasTos $d.Serial)) {
            $script:Serial = $d.Serial
            Say ('  Stereoen er allerede koblet til (' + $d.Serial + ').') 'Green'
            return $true
        }
    }
    # 2) Let på nettet
    Say '  Leter etter stereoen på nettet ...'
    $hits = Find-OpenAdbPorts
    foreach ($ip in $hits) {
        $serial = $ip + ':5555'
        Say ('  Fant noe på ' + $ip + '. Kobler til ...')
        if (Connect-Serial $serial) {
            if (Test-HasTos $serial) {
                $script:Serial = $serial
                Say ('  Koblet til stereoen (' + $ip + ').') 'Green'
                return $true
            }
            Say ('  ' + $ip + ' har ikke T''OS – det er nok en annen enhet. Leter videre.') 'DarkGray'
        }
    }
    # 3) Spør etter adressen
    while ($true) {
        Say ''
        Say '  Fant ikke stereoen av meg selv.' 'Yellow'
        Say '  Sjekk at feilsøking er på, og at PC-en og stereoen er på samme Wi-Fi.' 'Yellow'
        Say '  Adressen til stereoen står i T''OS: Innstillinger → Om stereoen → Ta over stereoen.' 'Yellow'
        $ip = Read-Host '  Skriv adressen (f.eks. 192.168.1.50), eller trykk bare Enter for å avslutte'
        $ip = $ip.Trim()
        if ($ip -eq '') { return $false }
        if ($ip -notmatch ':') { $ip = $ip + ':5555' }
        if ($ip -notmatch '^\d+\.\d+\.\d+\.\d+:\d+$') {
            Say '  Det ser ikke ut som en adresse. Den skal se slik ut: 192.168.1.50' 'Yellow'
            continue
        }
        if (Connect-Serial $ip) {
            if (Test-HasTos $ip) {
                $script:Serial = $ip
                Say '  Koblet til stereoen.' 'Green'
                return $true
            }
            Say '  Fant en enhet, men T''OS er ikke installert der. Installer T''OS først.' 'Yellow'
        }
    }
}

# --- Kommandolista (samme rekkefølge som docs/ADB-OPPSETT.md og T'OS) ------------------------
# o = $true: valgfri / gjelder ikke alle Android-versjoner – feil her er helt greit.

$Commands = @(
    @{ c = 'adb shell settings put secure accessibility_enabled 1'; w = 'Slår på knappestyringen'; o = $false },
    @{ c = 'adb shell cmd notification allow_listener no.bil.launcher/no.bil.launcher.MediaListener'; w = 'Varseltilgang (sangtittel, sving, hvem som ringer)'; o = $false },
    @{ c = 'adb shell appops set no.bil.launcher SYSTEM_ALERT_WINDOW allow'; w = 'Vise over andre apper'; o = $false },
    @{ c = 'adb shell appops set no.bil.launcher WRITE_SETTINGS allow'; w = 'Lysstyrke og klokke'; o = $false },
    @{ c = 'adb shell appops set no.bil.launcher REQUEST_INSTALL_PACKAGES allow'; w = 'Installere oppdateringer'; o = $false },
    @{ c = 'adb shell appops set no.bil.launcher ACCESS_RESTRICTED_SETTINGS allow'; w = 'Begrensede innstillinger (Android 13)'; o = $true },
    @{ c = 'adb shell appops set no.bil.launcher GET_USAGE_STATS allow'; w = 'Siste apper'; o = $false },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.ACCESS_FINE_LOCATION'; w = 'Posisjon (nøyaktig)'; o = $false },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.ACCESS_COARSE_LOCATION'; w = 'Posisjon'; o = $false },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.BLUETOOTH_CONNECT'; w = 'Bluetooth (Android 12+)'; o = $true },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.BLUETOOTH_SCAN'; w = 'Søke etter Bluetooth (Android 12+)'; o = $true },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.POST_NOTIFICATIONS'; w = 'Varsler (Android 13+)'; o = $true },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.READ_CONTACTS'; w = 'Kontakter'; o = $false },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.READ_CALL_LOG'; w = 'Siste anrop'; o = $false },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.CALL_PHONE'; w = 'Ringe'; o = $false },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.READ_PHONE_STATE'; w = 'Se når telefonen ringer'; o = $false },
    @{ c = 'adb shell cmd wifi network-suggestions-set-user-approved no.bil.launcher yes'; w = 'Wi-Fi uten spørsmål (Android 11+)'; o = $true },
    @{ c = 'adb shell dumpsys deviceidle whitelist +no.bil.launcher'; w = 'Ikke stoppet av batterisparing'; o = $false },
    @{ c = 'adb shell cmd package set-home-activity no.bil.launcher/.MainActivity'; w = 'T''OS som startskjerm'; o = $false },
    @{ c = 'adb shell cmd role add-role-holder android.app.role.DIALER no.bil.launcher'; w = 'T''OS som telefon-app'; o = $true },
    @{ c = 'adb shell settings put system show_navigationbar 0'; w = 'Skjul stereoens linje øverst (FYT)'; o = $true },
    @{ c = 'adb shell pm grant no.bil.launcher android.permission.WRITE_SECURE_SETTINGS'; w = 'T''OS holder knappestyringen på selv'; o = $false },
    @{ c = 'adb shell settings put global auto_time 1'; w = 'Klokka stilles automatisk'; o = $true },
    @{ c = 'adb shell settings put global auto_time_zone 1'; w = 'Tidssonen stilles automatisk'; o = $true }
)

$HarmlessPattern = 'Unknown permission|Unknown operation|not a changeable permission|Unknown command|No shell command implementation|has not requested permission|Unknown option'

function Get-Status($r, [bool]$optional) {
    $bad = ($r.Code -ne 0) -or ($r.Text -match '(?m)^(Error|Exception|Failure)') -or ($r.Text -match 'SecurityException|java\.lang\.')
    if (-not $bad) { return 'OK' }
    if ($optional -or ($r.Text -match $HarmlessPattern)) { return 'HOPPET' }
    return 'FEIL'
}

function Short([string]$text) {
    $t = ($text -split "`n")[0].Trim()
    if ($t.Length -gt 70) { $t = $t.Substring(0, 70) + ' …' }
    return $t
}

# Legger T'OS til i en kolon-liste uten å fjerne stereoens egne tjenester.
function Merge-ColonList([string]$current, [string]$item) {
    $parts = New-Object System.Collections.ArrayList
    if ($current -and $current -ne 'null') {
        foreach ($p in $current.Split(':')) {
            $p = $p.Trim()
            if ($p -ne '' -and -not $parts.Contains($p)) { [void]$parts.Add($p) }
        }
    }
    if (-not $parts.Contains($item)) { [void]$parts.Add($item) }
    return ($parts -join ':')
}

function Enable-GmsIfDisabled([bool]$quiet) {
    $r = Invoke-Stereo ('adb shell pm list packages -d ' + $Gms) 20
    if ($r.Text -match [regex]::Escape('package:' + $Gms)) {
        if (-not $quiet) { Say '  Google-tjenestene var slått av (forrige gang ble avbrutt). Slår dem på igjen ...' 'Yellow' }
        $e = Invoke-Stereo 'adb shell pm enable com.google.android.gms' 30
        if ($e.Text -match 'new state: enabled') { Remove-Item -LiteralPath $GmsFile -Force -ErrorAction SilentlyContinue }
        return $e
    }
    Remove-Item -LiteralPath $GmsFile -Force -ErrorAction SilentlyContinue
    return $null
}

# --- Full kontroll (enhetseier) --------------------------------------------------------------

function Test-IsOwner {
    $r = Invoke-Stereo 'adb shell dumpsys device_policy' 30
    return ($r.Text -match 'Device Owner[\s\S]{0,300}no\.bil\.launcher')
}

function Set-Owner {
    $r = Invoke-Stereo 'adb shell dpm set-device-owner no.bil.launcher/.TosAdmin' 60
    if ($r.Text -match 'Success') { return 'OK' }
    if ($r.Text -match 'already some accounts') { return 'KONTO' }
    if ($r.Text -match 'several users|already several') { return 'BRUKERE' }
    if ($r.Text -match 'Unknown admin') { return 'GAMMEL' }
    if ($r.Text -match 'already set|already provisioned|is already') {
        if (Test-IsOwner) { return 'OK' }
    }
    return ('FEIL: ' + (Short $r.Text))
}

function Invoke-FullControl {
    Title 'Full kontroll'
    if (Test-IsOwner) {
        Add-Result 'Full kontroll (enhetseier)' 'OK' 'var allerede på'
        return
    }
    Say '  T''OS blir «sjef» over stereoen: nedtrekksmenyen stenges, de gamle startskjermene skjules,'
    Say '  og oppdateringer skjer uten spørsmål. Kan slås av igjen i T''OS:'
    Say '  Innstillinger → Om stereoen → Slå av full kontroll.'
    if (-not (Ask-YesNo 'Slå på full kontroll nå?')) {
        Add-Result 'Full kontroll (enhetseier)' 'HOPPET' 'du valgte nei'
        return
    }
    $users = Invoke-Stereo 'adb shell pm list users' 20
    $count = ([regex]::Matches($users.Text, 'UserInfo\{')).Count
    if ($count -gt 1) {
        Say '  Stereoen har flere brukere. Da tillater ikke Android full kontroll (uten å nullstille stereoen).' 'Yellow'
        Add-Result 'Full kontroll (enhetseier)' 'HOPPET' 'stereoen har flere brukere'
        return
    }
    # Først rett på. Sier Android nei fordi det finnes en (Google-)konto, slås Google-tjenestene av i noen sekunder,
    # så kontoen ikke teller – og ALLTID på igjen etterpå. Da står Google aldri av uten grunn.
    $result = Set-Owner
    $types = @([regex]::Matches((Invoke-Stereo 'adb shell dumpsys account' 30 $true).Text, 'Account \{name=[^,]*, type=([^}\s]+)\}') |
        ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    $onlyGoogle = @($types | Where-Object { $_ -notmatch '^com\.google' }).Count -eq 0
    Log ('Kontotyper: ' + ($types -join ', '))
    if ($result -eq 'KONTO' -and $onlyGoogle) {
        try {
            Say '  Slår av Google-tjenestene i noen sekunder ...'
            [IO.File]::WriteAllText($GmsFile, 'av')
            $d = Invoke-Stereo 'adb shell pm disable-user --user 0 com.google.android.gms' 30
            Log ('disable-user: ' + $d.Text)
            foreach ($wait in @(2, 4)) {
                Start-Sleep -Seconds $wait
                $result = Set-Owner
                if ($result -ne 'KONTO') { break }
            }
        } finally {
            $e = Invoke-Stereo 'adb shell pm enable com.google.android.gms' 30
            if ($e.Text -notmatch 'new state: enabled') {
                # Forbindelsen kan ha falt: koble til igjen og prøv en gang til
                if ($script:Serial -match ':') { Invoke-AdbRaw ('connect ' + $script:Serial) 15 | Out-Null; Start-Sleep -Seconds 2 }
                $e = Invoke-Stereo 'adb shell pm enable com.google.android.gms' 30
            }
            if ($e.Text -match 'new state: enabled') {
                Remove-Item -LiteralPath $GmsFile -Force -ErrorAction SilentlyContinue
                Say '  Google-tjenestene er på igjen.'
            } else {
                Say '  OBS: Fikk ikke slått Google-tjenestene på igjen. Kjør tos-oppsett.bat på nytt, så prøver den igjen.' 'Red'
                Say '  (T''OS prøver også selv: Innstillinger → Om stereoen → Ta over stereoen → «Slå på Play-tjenester».)' 'Red'
            }
        }
    }
    while ($result -eq 'KONTO') {
        Say ''
        Say '  Android vil at Google-kontoen fjernes fra stereoen et øyeblikk.' 'Yellow'
        Say '  Jeg åpner kontosiden på stereoen nå. Der: trykk på kontoen → «Fjern konto».' 'Yellow'
        Say '  (Du legger den inn igjen om et minutt. Ingenting forsvinner fra Google.)' 'Yellow'
        Invoke-Stereo 'adb shell am start -a android.settings.SYNC_SETTINGS' 20 | Out-Null
        $a = Read-Host '  Trykk Enter når kontoen er fjernet, eller skriv h for å hoppe over full kontroll'
        if ($a -match '^\s*h') { $result = 'HOPP'; break }
        $script:RemovedAccount = $true
        $result = Set-Owner
    }
    switch -Regex ($result) {
        '^OK$' { Add-Result 'Full kontroll (enhetseier)' 'OK' }
        '^HOPP$' { Add-Result 'Full kontroll (enhetseier)' 'HOPPET' 'du hoppet over' }
        '^BRUKERE$' { Add-Result 'Full kontroll (enhetseier)' 'HOPPET' 'stereoen har flere brukere' }
        '^GAMMEL$' { Add-Result 'Full kontroll (enhetseier)' 'FEIL' 'oppdater T''OS til 5.12 eller nyere først' }
        default { Add-Result 'Full kontroll (enhetseier)' 'FEIL' $result }
    }
    if ($script:RemovedAccount) {
        Say ''
        Say '  Legg Google-kontoen inn igjen nå. Jeg åpner «Legg til konto» på stereoen.' 'Yellow'
        Invoke-Stereo 'adb shell am start -a android.settings.ADD_ACCOUNT_SETTINGS' 20 | Out-Null
        Wait-Enter 'Trykk Enter når kontoen er lagt inn igjen'
    }
}

# --- Ta over ---------------------------------------------------------------------------------

function Invoke-Takeover {
    Title 'Steg 3 av 4: Gjør stereoen klar for T''OS'
    $g = Enable-GmsIfDisabled $false
    # Knappestyring: legg T'OS til tjenestene stereoen alt har på (ikke slå av dem)
    $cur = (Invoke-Stereo 'adb shell settings get secure enabled_accessibility_services' 20).Text.Trim()
    $merged = Merge-ColonList $cur $KeyService
    $r = Invoke-Stereo ("adb shell settings put secure enabled_accessibility_services '" + $merged + "'") 20
    $ks = Get-Status $r $false
    $ksInfo = ''
    if ($ks -ne 'OK') { $ksInfo = Short $r.Text }
    Add-Result 'Knappestyring («T''OS knapper»)' $ks $ksInfo
    foreach ($cmd in $Commands) {
        if ($cmd.c -like '*show_navigationbar 0') {
            # Husk hvordan linja var, så «angre» kan sette den tilbake
            if (-not (Test-Path -LiteralPath $NavFile)) {
                $before = (Invoke-Stereo 'adb shell settings get system show_navigationbar' 20).Text.Trim()
                if ($before -eq '' -or $before -eq 'null') { $before = '1' }
                [IO.File]::WriteAllText($NavFile, $before)
            }
        }
        $r = Invoke-Stereo $cmd.c 30
        $s = Get-Status $r ([bool]$cmd.o)
        $info = ''
        if ($s -ne 'OK') { $info = Short $r.Text }
        Add-Result $cmd.w $s $info
    }
    Invoke-FullControl
    Title 'Steg 4 av 4: Rydde opp'
    Say '  Feilsøking (ADB) bør slås av igjen nå – da kan ingen andre koble seg til stereoen.'
    Say '  T''OS beholder alt du har gitt den.'
    if (Ask-YesNo 'Slå av feilsøking på stereoen nå?') {
        Invoke-Stereo 'adb shell settings put global adb_enabled 0' 15 | Out-Null
        Add-Result 'Feilsøking slått av igjen' 'OK'
    } else {
        Add-Result 'Feilsøking slått av igjen' 'HOPPET' 'du valgte nei'
    }
}

# --- Angre -----------------------------------------------------------------------------------

function Invoke-Undo {
    Title 'Angre'
    $nav = '1'
    if (Test-Path -LiteralPath $NavFile) { $nav = ([IO.File]::ReadAllText($NavFile)).Trim() }
    if ($nav -eq '1') {
        $r = Invoke-Stereo 'adb shell settings put system show_navigationbar 1' 20
    } else {
        $r = Invoke-Stereo ('adb shell settings put system show_navigationbar ' + $nav) 20
    }
    Add-Result 'Stereoens linje øverst vises igjen' (Get-Status $r $false)
    if ((Get-Status $r $false) -eq 'OK') { Remove-Item -LiteralPath $NavFile -Force -ErrorAction SilentlyContinue }
    $g = Enable-GmsIfDisabled $true
    if ($g) { Add-Result 'Google-tjenestene på igjen' (Get-Status $g $false) } else { Add-Result 'Google-tjenestene er på' 'OK' }
    $r = Invoke-Stereo 'adb shell dumpsys deviceidle whitelist -no.bil.launcher' 20
    Add-Result 'Vanlig batterisparing for T''OS' (Get-Status $r $true)
    Say ''
    Say '  Tillatelsene T''OS har fått, blir stående – de gjør ingen skade.'
    Say '  FULL KONTROLL slås av i T''OS: Innstillinger → Om stereoen → Slå av full kontroll (trykk to ganger).' 'Yellow'
}

# --- Oppsummering ----------------------------------------------------------------------------

function Show-Summary {
    Title 'Ferdig'
    $ok = @($script:Results | Where-Object { $_.Status -eq 'OK' }).Count
    $skip = @($script:Results | Where-Object { $_.Status -eq 'HOPPET' }).Count
    $bad = @($script:Results | Where-Object { $_.Status -eq 'FEIL' })
    Say ('  ' + $ok + ' OK, ' + $skip + ' hoppet over (helt greit), ' + $bad.Count + ' feil.') 'White'
    if ($bad.Count -gt 0) {
        Say ''
        Say '  Dette virket ikke:' 'Red'
        foreach ($b in $bad) { Say ('   - ' + $b.Navn + '  (' + $b.Info + ')') 'Red' }
        Say ''
        Say '  Send denne fila til utvikleren, så kan utvikleren se hva som skjedde:' 'Yellow'
        Say ('  ' + $LogFile) 'Yellow'
    } else {
        Say ''
        Say '  Alt gikk bra. Sjekk T''OS: Innstillinger → Om stereoen.' 'Green'
        Say ('  (Logg: ' + $LogFile + ')') 'DarkGray'
    }
}

# --- Start -----------------------------------------------------------------------------------

Write-Host ''
Write-Host '  T''OS oppsett' -ForegroundColor White
Write-Host '  ============' -ForegroundColor White
Write-Host '  Gjør stereoen klar for T''OS – uten Android-menyene. Tar et par minutter.'
Log ('T''OS oppsett startet ' + (Get-Date).ToString('yyyy-MM-dd HH:mm') + ' fra ' + $Self)

$mode = ('' + $env:TOS_MODE).Trim().ToLower()
if ($mode -ne 'angre') {
    Write-Host ''
    Write-Host '  1 = Ta over stereoen (anbefalt)'
    Write-Host '  2 = Angre (vis stereoens linje igjen m.m.)'
    Write-Host '  3 = Avslutt'
    $choice = Read-Host '  Skriv 1, 2 eller 3 (Enter = 1)'
    Log ('Valg: ' + $choice)
    if ($choice -match '^\s*3') { return }
    if ($choice -match '^\s*2') { $mode = 'angre' }
}

if (-not (Get-Adb)) { Wait-Enter 'Trykk Enter for å lukke'; return }
if (-not (Find-Stereo)) {
    Say ''
    Say '  Avsluttet uten å endre noe på stereoen.' 'Yellow'
    Say ('  Logg: ' + $LogFile) 'DarkGray'
    Wait-Enter 'Trykk Enter for å lukke'
    return
}
if ($mode -eq 'angre') { Invoke-Undo } else { Invoke-Takeover }
Show-Summary
Write-Host ''
Wait-Enter 'Trykk Enter for å lukke'
