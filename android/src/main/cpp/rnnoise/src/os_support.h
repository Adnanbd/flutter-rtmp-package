/* Shim, not part of the RNNoise 0.2 release: vec.h / vec_neon.h include "os_support.h" (an Opus header) on non-x86
 * builds, but the release tarball and upstream tree don't ship it. It maps the Opus helpers they use
 * (OPUS_INLINE, OPUS_CLEAR, OPUS_COPY, OPUS_MOVE) onto RNNoise's own equivalents in common.h. Added by flutter_rtmp_broadcaster (docs/decisions/0025-audio-cleanup.md). */
#ifndef RNNOISE_OS_SUPPORT_SHIM_H
#define RNNOISE_OS_SUPPORT_SHIM_H
#include "common.h"
#ifndef OPUS_INLINE
#define OPUS_INLINE inline
#endif
#ifndef OPUS_CLEAR
#define OPUS_CLEAR(dst, n) RNN_CLEAR(dst, n)
#endif
#ifndef OPUS_COPY
#define OPUS_COPY(dst, src, n) RNN_COPY(dst, src, n)
#endif
#ifndef OPUS_MOVE
#define OPUS_MOVE(dst, src, n) RNN_MOVE(dst, src, n)
#endif
#endif
