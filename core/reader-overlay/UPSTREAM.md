# WebGPU viewer overlay integration

The Kotlin sources in this module are vendored from `ca.mpreg:webgpuviewer:47`.

- Source: https://repo.maven.apache.org/maven2/ca/mpreg/webgpuviewer/47/webgpuviewer-47-sources.jar
- Source SHA-256: `6f515460c3440993059abf4b7cbbb1db78d0985465d4ee20b4ad41a6bfb869e0`
- Upstream author: w (`wwww-wwww`), https://github.com/mpreg-ca/webgpuviewer
- Upstream POM declares MIT licensing.

The build retrieves the exact v47 AAR and retains its bundled WebGPU API, native
Dawn and resize libraries, resources, assets and license notices. It excludes the
original viewer classes, which are rebuilt here with the source-space overlay
hook. No upstream native libraries are recompiled. The pinned v47 native artifacts replace
the earlier v40 artifacts together with their corresponding Kotlin sources.

Local changes add immutable revisioned overlays, draw them after the base image
and tile pass in paged/continuous viewers, and reseed transition caches before
compositing an overlay. A dedicated overlay shader premultiplies straight-RGBA
samples before interpolation and uses source-over blending without applying alpha
a second time. The upstream v47 image shaders and original page textures are preserved. Overlay
pipelines use the destination format, including the HDR float render target; spread
overlays use the same per-side height normalization as the corresponding originals.
Continuous overlays use image content height, excluding the configured page gap.

The frame and tile rendering paths also release caller-owned WebGPU references:
acquired surface textures, command encoders/buffers, temporary color views,
render passes, bind groups/layouts, per-draw uniforms and queue getter results.
Recorded commands retain their own resource references; cached image, stencil and
atlas views keep their existing owners. `destroy()` and ending a pass do not
replace `close()` for reference ownership. The native regression draws fixed
overlay tiles, waits for GPU completion, checks overlay/original pixels and
bounds warmed native allocation growth. Ownership semantics were originally verified on 2026-09-06 against the bundled
v40 API and [Android's close contract](https://developer.android.com/reference/androidx/webgpu/GPUCommandEncoder#close()).
The v47 update on 2026-09-20 used official v40 sources as the three-way merge
base, preserving these ownership scopes alongside upstream v47 release helpers,
HDR/gainmap support, surface lifecycle and variable-height spreads. v40 source
SHA-256: `1585c76f46477b3ee99e9f4aaa6520da13d6bbf27721c08a4f4d697257ddefab`.
Device validation of the updated native artifact is recorded separately; the prior
v40 measurements do not establish v47 performance or compatibility.

The subsampling viewer sources are pinned to `com.github.mihonapp:subsampling-scale-image-view:94915e6f73`.
Source: https://jitpack.io/com/github/mihonapp/subsampling-scale-image-view/94915e6f73/subsampling-scale-image-view-94915e6f73-sources.jar
SHA-256: `af99ab23a39fd74f2911ae860afd515320e614cf7f712a41116edbd45b04e3c1`.
Its original crop JNI/resource artifacts are retained. Local changes expose the
exact decoder crop rectangle to align overlays with asymmetric border cropping.

`ReaderImageDecoder.kt` adapts `ca.mpreg:imagedecoder:14` to the established raw
source-coordinate contract in both readers. The official decoder now applies
EXIF orientation unconditionally in `nativeDecode`/`apply_orientation`, including
the gainmap, and exposes no orientation opt-out. Its `getTag("Orientation")`
returns the original numeric EXIF value. The adapter undoes that pixel permutation
before cropping or GPU upload, preserving all RGBA8/RGBA16F and gainmap bytes;
normal orientation reuses the buffer without a pixel allocation. Checked buffer
dimensions bound any required copy to the existing decoded frame size. The
classic decoder closes its native encoded-image owner with `use` and converts
half-float extended-sRGB samples to its existing SDR ARGB_8888 tile format.
Decoder 14 output buffers are Java-owned direct buffers, independent of that
close operation. No dependency or native binary is added by this adaptation.

Verified on 2026-09-20 against the
[v14 Kotlin source artifact](https://repo.maven.apache.org/maven2/ca/mpreg/imagedecoder/14/imagedecoder-14-sources.jar)
and [native implementation](https://github.com/mpreg-ca/imagedecoder/blob/260e71ca07a330962d0b696bc6109861d8cbaaa1/library/src/main/cpp/imagedecoder/imagedecoder.cpp).
Regressions retain the raw-coordinate JPEG assertion and add all eight EXIF
orientations, asymmetric crop consistency, byte-exact half-float/gainmap
permutations, and native decode → GPU overlay readback. `getTag` describes the
main image; per-frame differing orientations in multipage TIFF are not covered
by these static-image checks.
