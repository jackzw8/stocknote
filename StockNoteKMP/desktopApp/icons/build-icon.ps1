# 生成桌面版图标 StockNote.ico —— 老周 2026-09-30「桌面版图标用手机应用的图标」
#
# 用法：  powershell -ExecutionPolicy Bypass -File build-icon.ps1
# 产物：  desktopApp/icons/StockNote.ico（多尺寸，供 jpackage 的 windows.iconFile 使用）
#
# 来源：androidApp 的 mipmap-xxxhdpi/ic_launcher.png（192×192，系统合成的成品图标）
# —— 复用同一份资产，保证桌面与手机的图标是**同一个**（手机图标改了重跑本脚本即可）。
#
# ⚠️⚠️ 两条踩过的坑，改这个脚本前先看：
#  1. **必须用 DIB（BMP）帧，不能用 PNG 帧**。第一版每帧直接塞 PNG 字节（Vista+ 确实支持），
#     结果 jpackage 打出的启动器 **exe 膨胀到 1.2 GB**、图标也没换 —— jpackage 的 Windows
#     launcher 只按 BITMAPINFOHEADER 解析，遇到 PNG 帧就把内容当"未压缩位图"重复写入。
#     改成 DIB 帧（自下而上 32bpp BGRA + AND 掩码）后 exe 回到 ~0.5 MB。
#  2. **不要把这套字节拼装封装进 function**：实测（PowerShell 5.1）函数返回 byte[] 会被
#     展开成 Object[]，落回 hashtable 后长度丢失，最后写出的 ICO 只有 102 字节（只有目录头、
#     没有任何帧）。这里全部内联 + `[byte[]]` 显式转换 + 每帧打印字节数，一眼能看出对错。
#  3. 本文件必须存成 **UTF-8 with BOM**：无 BOM 时 PowerShell 5.1 按 GBK 解析，
#     中文注释会把脚本语法搞坏（报 "Unexpected token '}'"）。
#
# ⚠️ 只生成 ≤192 的尺寸：超过原图分辨率只会是插值放大，反而更糊。
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$src = Join-Path $PSScriptRoot "..\..\androidApp\src\main\res\mipmap-xxxhdpi\ic_launcher.png"
$out = Join-Path $PSScriptRoot "StockNote.ico"
$sizes = @(16, 32, 48, 64, 128, 192)

$source = [System.Drawing.Image]::FromFile((Resolve-Path $src).Path)
$frameSizes = New-Object System.Collections.Generic.List[int]
$frameBytes = New-Object System.Collections.Generic.List[byte[]]

foreach ($s in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap $s, $s, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.DrawImage($source, 0, 0, $s, $s)
    $g.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter $ms

    # BITMAPINFOHEADER（40 字节）：高度写 2×（ICO 约定 = XOR 位图 + AND 掩码）
    $bw.Write([UInt32]40)             # biSize
    $bw.Write([Int32]$s)              # biWidth
    $bw.Write([Int32]($s * 2))        # biHeight
    $bw.Write([UInt16]1)              # biPlanes
    $bw.Write([UInt16]32)             # biBitCount
    $bw.Write([UInt32]0)              # biCompression = BI_RGB
    $bw.Write([UInt32]($s * $s * 4))  # biSizeImage
    $bw.Write([Int32]0)               # biXPelsPerMeter
    $bw.Write([Int32]0)               # biYPelsPerMeter
    $bw.Write([UInt32]0)              # biClrUsed
    $bw.Write([UInt32]0)              # biClrImportant

    # 像素：32bpp BGRA、自下而上（DIB 行序与屏幕坐标相反）
    for ($y = $s - 1; $y -ge 0; $y--) {
        for ($x = 0; $x -lt $s; $x++) {
            $c = $bmp.GetPixel($x, $y)
            $bw.Write([Byte]$c.B)
            $bw.Write([Byte]$c.G)
            $bw.Write([Byte]$c.R)
            $bw.Write([Byte]$c.A)
        }
    }

    # AND 掩码（1bpp）：32bpp 下内容无意义，但必须存在，每行按 4 字节对齐
    $maskRow = [int]([Math]::Floor(($s + 31) / 32) * 4)
    $zeroRow = New-Object byte[] $maskRow
    for ($y = 0; $y -lt $s; $y++) { $bw.Write($zeroRow) }

    $bw.Flush()
    $bytes = $ms.ToArray()
    $bw.Dispose(); $ms.Dispose(); $bmp.Dispose()

    $frameSizes.Add($s)
    $frameBytes.Add([byte[]]$bytes)
    Write-Host ("  帧 {0,3}x{0,-3} -> {1,7} 字节" -f $s, $bytes.Length)
}
$source.Dispose()

# ---- 组装 ICO：ICONDIR + N×ICONDIRENTRY + N 份帧数据 ----
$final = New-Object System.IO.MemoryStream
$fw = New-Object System.IO.BinaryWriter $final
$fw.Write([UInt16]0)                       # reserved
$fw.Write([UInt16]1)                       # type = icon
$fw.Write([UInt16]$frameSizes.Count)       # 帧数
$offset = 6 + 16 * $frameSizes.Count
for ($i = 0; $i -lt $frameSizes.Count; $i++) {
    $dim = if ($frameSizes[$i] -ge 256) { 0 } else { $frameSizes[$i] }
    $fw.Write([Byte]$dim)                  # 宽
    $fw.Write([Byte]$dim)                  # 高
    $fw.Write([Byte]0)                     # 调色板色数（真彩填 0）
    $fw.Write([Byte]0)                     # 保留
    $fw.Write([UInt16]1)                   # 色彩平面
    $fw.Write([UInt16]32)                  # 位深
    $fw.Write([UInt32]$frameBytes[$i].Length)
    $fw.Write([UInt32]$offset)
    $offset += $frameBytes[$i].Length
}
for ($i = 0; $i -lt $frameSizes.Count; $i++) { $fw.Write([byte[]]$frameBytes[$i]) }
$fw.Flush()
[System.IO.File]::WriteAllBytes($out, $final.ToArray())
$fw.Dispose(); $final.Dispose()

$finalSize = (Get-Item $out).Length
"已生成: $out"
"尺寸: $($sizes -join ', ')    总大小: $([math]::Round($finalSize/1KB,1)) KB"
if ($finalSize -lt 10240) { throw "ICO 只有 $finalSize 字节，明显不含帧数据 —— 生成失败" }
