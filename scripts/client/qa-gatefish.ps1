# QA scenario 4: right-click spam (rod in hand) right after pressing Proceed on the pack prompt, i.e. while the gate is still locked.
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
$gp = Get-Gp; Pin-Mc $gp; Focus-Mc $gp
Click-McAt $gp 405 515 'left'
1..14 | ForEach-Object { Focus-Mc $gp; Click-Mc $gp 'right' 50; Start-Sleep -Milliseconds 250 }
Shot z4_after_spam | Out-Null
C "ec debug fish status QaG1"; Start-Sleep 1
