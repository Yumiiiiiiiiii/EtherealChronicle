# Task 25: gathering channel (move-cancel then complete) at ashwood_dew_moss_1 (68,65,-24).
. "$PSScriptRoot\mcwin.ps1"; . "$PSScriptRoot\qa-helpers.ps1"
$gp = Get-Gp; Pin-Mc $gp
C 'ec give QaT25 ec:item_tool_sickle_silver 1'; Start-Sleep 2
Tp QaT25 68.5 65 -21.5 180 24; Start-Sleep 4
Focus-Mc $gp; Press-McKey $gp 'D4'; Start-Sleep 1
Shot t25_v_gather_aim | Out-Null
# 1回目: チャネル開始直後に前進してキャンセルされることを確認
Focus-Mc $gp; RClick; Start-Sleep -Milliseconds 500
Shot t25_w_gather_channel | Out-Null
Focus-Mc $gp; Press-McKey $gp 'W' 600; Start-Sleep 2
Shot t25_x_gather_cancel | Out-Null
# 2回目: 元の位置へ戻って完了まで待つ
Tp QaT25 68.5 65 -21.5 180 24; Start-Sleep 3
Focus-Mc $gp; RClick; Start-Sleep 4
Shot t25_y_gather_done | Out-Null

