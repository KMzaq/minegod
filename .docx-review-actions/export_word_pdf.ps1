$ErrorActionPreference = 'Stop'

$sourcePath = 'C:\Users\ADMIN\Downloads\Mythic_TRPG_기획서_v0.1.docx'
$outputPath = Join-Path (Resolve-Path '.\.docx-review-actions').Path 'Mythic_TRPG_기획서_v0.1.pdf'

$word = $null
$document = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $document = $word.Documents.Open($sourcePath, $false, $true)
    $pageCount = $document.ComputeStatistics(2)
    $document.ExportAsFixedFormat($outputPath, 17)
    Write-Output "PDF=$outputPath"
    Write-Output "PAGES=$pageCount"
}
finally {
    if ($null -ne $document) {
        $document.Close($false)
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($document)
    }
    if ($null -ne $word) {
        $word.Quit()
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word)
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
