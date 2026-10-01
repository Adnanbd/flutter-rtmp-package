# Vendored RNNoise

- Source: https://github.com/xiph/rnnoise/releases/download/v0.2/rnnoise-0.2.tar.gz
  (sha256 90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37), fetched 2026-10-01.
- Licence: BSD-3-Clause, see `COPYING`.
- Copied unchanged: the library sources from `src/` (the autotools `RNNOISE_SOURCES` list, incl. the bundled model
  `rnnoise_data.c`), headers they include, `include/rnnoise.h`.
- Not copied: training/tools (`dump_features.c`, `write_weights.c`), x86 runtime CPU dispatch (`x86/*.c`).
- Added: `src/os_support.h` shim (maps `OPUS_INLINE/CLEAR/COPY/MOVE` to `common.h`; missing upstream, needed by the NEON path).
- Used by `../rnnoise_jni.c` → `com.flutterrtmp.broadcaster.audio.RnnoiseNative` (docs/specs/audio-cleanup.md).
