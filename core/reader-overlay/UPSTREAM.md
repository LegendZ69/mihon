# WebGPU viewer overlay integration

The Kotlin sources in this module are vendored from `ca.mpreg:webgpuviewer:54`, the
version upstream Mihon pins in `gradle/libs.versions.toml`.

- Source: https://repo.maven.apache.org/maven2/ca/mpreg/webgpuviewer/54/webgpuviewer-54-sources.jar
- Source SHA-256: `af64189bc20bf5979856d63394211f2a7ea95f734a9bfda6dfcb8055cd1b4b03`
- Upstream author: w (`wwww-wwww`), https://github.com/mpreg-ca/webgpuviewer
- Upstream POM declares MIT licensing.

The build retrieves the AAR of the pinned version and retains its bundled WebGPU API, native
Dawn and resize libraries, resources, assets and license notices. It excludes the
original viewer classes, which are rebuilt here with the source-space overlay
hook. No upstream native libraries are recompiled. Native artifacts and Kotlin sources
always come from the same version.

Local changes add immutable revisioned overlays, draw them after the base image
and tile pass in paged/continuous viewers, and reseed transition caches before
compositing an overlay. A dedicated overlay shader premultiplies straight-RGBA
samples before interpolation and uses source-over blending without applying alpha
a second time. The upstream image shaders and original page textures are preserved. Overlay
pipelines use the destination format, including the HDR float render target; spread
overlays use the same per-side height normalization as the corresponding originals.
Continuous overlays use image content height, excluding the configured page gap, and the
same crop scale and shift as the page image; a scissor keeps each page's regions inside its band.

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

The v54 update on 2026-10-29 used the official v47 sources as the three-way merge base.
v54 adopted its own release helpers for surface textures, command encoders, render passes and
transient bind groups, so its frame, tile and draw paths are taken as published; the local
change set is now the overlay hook (`ImageOverlay`, `RenderPage.renderOverlay`, the
`ImagePage.ImageSingle` overlay members and the continuous overlay pass) plus the
`GpuRenderPass` helpers the overlay pass uses. No device validation of v54 has been recorded.

The subsampling viewer sources are pinned to `com.github.mihonapp:subsampling-scale-image-view:72a0537151`,
the version upstream Mihon pins.
Source: https://jitpack.io/com/github/mihonapp/subsampling-scale-image-view/72a0537151/subsampling-scale-image-view-72a0537151-sources.jar
SHA-256: `6172922c7f29d8a47120a722f26834a977603bd80c28d6ce4abf9ee2673f9445`.
Its original crop JNI/resource artifacts are retained. Local changes expose the
exact decoder crop rectangle to align overlays with asymmetric border cropping. The
upstream decoder keeps the native decoder open and decodes each tile's region; an
image with an Exif orientation instead decodes once and serves tiles from a
raw-coordinate copy, as below.

`ReaderImageDecoder.kt` adapts `ca.mpreg:imagedecoder:18` to the established raw
source-coordinate contract in both readers. The official decoder applies
EXIF orientation to every frame, region and gainmap, and exposes no orientation opt-out. Its `getTag("Orientation")`
returns the original numeric EXIF value until a frame is decoded; decoding drops the tags (`null`
afterwards, checked on an Android 15 emulator), so each reader reads it once before its first decode and
passes it to the restore helpers. The adapter undoes that pixel permutation
before cropping or GPU upload, preserving all RGBA8/RGBA16F and gainmap bytes;
normal orientation reuses the buffer without a pixel allocation. Checked buffer
dimensions bound any required copy to the existing decoded frame size. The
classic decoder converts half-float extended-sRGB samples to its existing SDR
ARGB_8888 tile format. Decoder 18 frames own native pixels until closed, so a
restored copy is taken before the frame closes, and the WebGPU reader shows no
streaming preview of an oriented image. The webtoon strip view keeps upstream's
oriented strips and maps overlays through the same Exif transform instead. No
dependency or native binary is added by this adaptation.

The v14 adaptation was verified on 2026-09-20 against the
[v14 Kotlin source artifact](https://repo.maven.apache.org/maven2/ca/mpreg/imagedecoder/14/imagedecoder-14-sources.jar)
and [native implementation](https://github.com/mpreg-ca/imagedecoder/blob/260e71ca07a330962d0b696bc6109861d8cbaaa1/library/src/main/cpp/imagedecoder/imagedecoder.cpp).
Regressions retain the raw-coordinate JPEG assertion and add all eight EXIF
orientations, asymmetric crop consistency, byte-exact half-float/gainmap
permutations, and native decode → GPU overlay readback. `getTag` describes the
main image; per-frame differing orientations in multipage TIFF are not covered
by these static-image checks. The decoder 18 adaptation was checked against the
[v18 Kotlin source artifact](https://repo.maven.apache.org/maven2/ca/mpreg/imagedecoder/18/imagedecoder-18-sources.jar)
on 2026-10-29. Its regressions in `TranslationOverlayRenderingTest`, including the real-GPU Exif
decode, were rerun the same day on an Android 15 x86_64 emulator with SwiftShader rendering, not on
a handset.
