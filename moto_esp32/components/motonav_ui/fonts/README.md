These generated LVGL fonts retain the simulator's Anton speed numerals and
Barlow Condensed distance typography. Noto Sans SC supplies common Simplified
Chinese labels and road-name glyphs (GB2312 repertoire, supported CJK basic
ideographs including Traditional Chinese, Latin letters through U+024F, ASCII,
copyright and degree symbols). Extra glyphs are flash-resident fallback chunks
under the LVGL compact bitmap-offset limit. Unsupported glyphs must remain visibly unknown rather than being replaced
with invented text. All three upstream fonts are licensed under SIL OFL 1.1;
their licenses accompany the generated sources.

Pinned source SHA256 values are in `tools/generate_ui_fonts.py`. Sources:

- https://github.com/google/fonts/blob/main/ofl/anton/Anton-Regular.ttf
- https://github.com/google/fonts/blob/main/ofl/barlowcondensed/BarlowCondensed-Bold.ttf
- https://github.com/google/fonts/blob/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf

Regenerate with `python tools/generate_ui_fonts.py <local-source-directory>`.
Firmware builds use the checked-in C data and do not download fonts.
