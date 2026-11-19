# 自适应层为 108dp，启动器通常显示中央 72dp。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot
$src = [Drawing.Bitmap]::new((Join-Path $root 'docs/assets/app-icon-source.png'))
$mark = $src.Clone([Drawing.Rectangle]::new(26,34,144,134),$src.PixelFormat)
try {
    # 裁框的右下角与原稿白色外框相交。只清除外框灰白像素，保留绿色徽标的抗锯齿边缘。
    $base = $src.GetPixel(24,24)
    for ($y=124; $y -lt $mark.Height; $y++) {
        for ($x=131; $x -lt $mark.Width; $x++) {
            $color = $mark.GetPixel($x,$y)
            if ($color.R -eq $color.G -and $color.G -eq $color.B) { $mark.SetPixel($x,$y,$base) }
        }
    }
    # 徽标内部保留原色；其余区域将白色 Z 在深底上的抗锯齿还原为透明度。
    for ($y=0; $y -lt $mark.Height; $y++) {
        $badgeLeft = $mark.Width
        $badgeRight = -1
        for ($x=0; $x -lt $mark.Width; $x++) {
            $pixel = $mark.GetPixel($x,$y)
            if ($pixel.G -gt $pixel.R) {
                $badgeLeft = [Math]::Min($badgeLeft,$x)
                $badgeRight = $x
            }
        }
        for ($x=0; $x -lt $mark.Width; $x++) {
            if ($x -ge $badgeLeft -and $x -le $badgeRight) { continue }
            $value = $mark.GetPixel($x,$y).R
            $alpha = [int](255 * [Math]::Max(0, $value - $base.R) / (255 - $base.R))
            $mark.SetPixel($x,$y,[Drawing.Color]::FromArgb($alpha,255,255,255))
        }
    }
    foreach ($entry in @{'mdpi'=1; 'hdpi'=1.5; 'xhdpi'=2; 'xxhdpi'=3; 'xxxhdpi'=4}.GetEnumerator()) {
        $dir = Join-Path $root "app/src/main/res/mipmap-$($entry.Key)"
        foreach ($adaptive in @($true, $false)) {
            $size = [int]($(if ($adaptive) {108} else {48}) * $entry.Value)
            $bmp = [Drawing.Bitmap]::new($size, $size)
            $g = [Drawing.Graphics]::FromImage($bmp)
            try {
                $g.InterpolationMode = 'HighQualityBicubic'
                $g.PixelOffsetMode = 'HighQuality'
                if ($adaptive) {
                    # 原稿中白色 Z 的像素范围为 x=31..146、y=39..138；
                    # 按像素边界计算，Z 的中心是 (89,89)，在裁框内为 (63,55)。
                    $scale = $entry.Value / 3
                    $dest = [Drawing.RectangleF]::new(
                        $size / 2 - 63 * $scale, $size / 2 - 55 * $scale,
                        $mark.Width * $scale, $mark.Height * $scale)
                    $g.DrawImage($mark,$dest)
                    $name = 'ic_launcher_mark.png'
                } else {
                    $g.DrawImage($src,[Drawing.Rectangle]::new(0,0,$size,$size))
                    $name = 'ic_launcher.png'
                }
                $bmp.Save((Join-Path $dir $name),[Drawing.Imaging.ImageFormat]::Png)
            } finally { $g.Dispose(); $bmp.Dispose() }
        }
    }
} finally { $mark.Dispose(); $src.Dispose() }
