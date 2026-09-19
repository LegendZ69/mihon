# Keep translation geometry in original-image coordinates

Translation results use original-image pixel coordinates and content identity, independently of provider tiles, reader splits, crops, zoom, and appearance. Both reader backends map this shared geometry into their existing page placements; this costs explicit transform tracking but preserves cached translations and manual corrections when reader settings or overlay styles change.

The WebGPU v40 and subsampling-viewer source patches retain their pinned native artifacts and licenses. Maintaining these narrow hooks is preferable to baking translated pixels into the original image, which would prevent instant comparison and require rebuilding original image tiles for every style change.
