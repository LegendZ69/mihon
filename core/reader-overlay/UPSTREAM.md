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
