[CmdletBinding()]
param(
  [ValidateRange(1, 9)][int]$Chapter,
  [string]$Section,
  [switch]$Full
)

# 只读权威 DOCX，默认先列目录，不另维护一份产品正文。
$ErrorActionPreference = 'Stop'
if ($Section -and !$Chapter) { throw 'Specify -Chapter before -Section.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$planPath = Join-Path $PSScriptRoot '../docs/product/产品方案.docx'
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $planPath).Path)
try {
  $reader = [System.IO.StreamReader]::new($archive.GetEntry('word/document.xml').Open())
  try { [xml]$document = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $archive.Dispose() }
$ns = [System.Xml.XmlNamespaceManager]::new($document.NameTable)
$ns.AddNamespace('w', 'http://schemas.openxmlformats.org/wordprocessingml/2006/main')
function Get-PlanText($node) { ($node.SelectNodes('.//w:t', $ns) | ForEach-Object { $_.InnerText }) -join '' }
$current = 0
$blocks = @(
  foreach ($node in $document.SelectSingleNode('//w:body', $ns).ChildNodes) {
    if ($node.LocalName -notin @('p', 'tbl')) { continue }
    $text = Get-PlanText $node
    $style = $node.SelectSingleNode('./w:pPr/w:pStyle', $ns)
    $level = if ($style -and $style.GetAttribute('val', $ns.LookupNamespace('w')) -match '^Heading([1-4])$') { [int]$Matches[1] } else { 0 }
    if ($level -eq 1 -and $text -match '^([1-9])\s+') { $current = [int]$Matches[1] }
    if ($current) { [pscustomobject]@{ Chapter = $current; Level = $level; Text = $text; Node = $node } }
  }
)
if (!$Chapter) { $blocks | Where-Object Level -EQ 1 | ForEach-Object { $_.Text }; return }
$selected = @($blocks | Where-Object Chapter -EQ $Chapter)
if (!$Full -and !$Section) {
  $selected | Where-Object { $_.Level -in @(1, 2) } | ForEach-Object { ('#' * $_.Level) + ' ' + $_.Text }
  Write-Output '`-Section <title>` reads one section; `-Full` reads the whole chapter.'
  return
}
if ($Section) {
  $matchesByTitle = @($selected | Where-Object { $_.Level -ge 2 -and $_.Text.Equals($Section, [StringComparison]::OrdinalIgnoreCase) })
  if (!$matchesByTitle.Count) { $matchesByTitle = @($selected | Where-Object { $_.Level -ge 2 -and $_.Text.IndexOf($Section, [StringComparison]::OrdinalIgnoreCase) -ge 0 }) }
  if ($matchesByTitle.Count -ne 1) { throw 'Section not found or ambiguous. Read the chapter section list first.' }
  $start = [array]::IndexOf($selected, $matchesByTitle[0])
  $end = $selected.Count
  for ($i = $start + 1; $i -lt $selected.Count; $i++) {
    if ($selected[$i].Level -gt 0 -and $selected[$i].Level -le $selected[$start].Level) { $end = $i; break }
  }
  $selected = @($selected[$start..($end - 1)])
}
foreach ($block in $selected) {
  if ($block.Node.LocalName -eq 'tbl') {
    $first = $true
    foreach ($row in $block.Node.SelectNodes('./w:tr', $ns)) {
      $cells = @($row.SelectNodes('./w:tc', $ns) | ForEach-Object { (Get-PlanText $_).Replace('|', '\|') })
      Write-Output ('| ' + ($cells -join ' | ') + ' |')
      if ($first) { Write-Output ('| ' + (($cells | ForEach-Object { '---' }) -join ' | ') + ' |'); $first = $false }
    }
  } elseif ($block.Level) { Write-Output (('#' * $block.Level) + ' ' + $block.Text) }
  elseif ($block.Text) { Write-Output $block.Text }
  elseif ($block.Node.SelectSingleNode('.//w:drawing', $ns)) { Write-Output '[图示内容请查看产品方案原件]' }
  Write-Output ''
}
