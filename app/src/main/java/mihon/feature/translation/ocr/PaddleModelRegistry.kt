package mihon.feature.translation.ocr

/** Immutable official PaddlePaddle artifacts, verified 2026-09-05 against Hugging Face blob metadata.
 * Model SHA-256 values are upstream LFS digests; YAML SHA-256 values were calculated from pinned bytes.
 * Models and dictionaries use Apache-2.0. Never substitute a dictionary from another revision.
 */
internal object PaddleModelRegistry {
    val models = listOf(
        PaddleModelSpec(
            id = "PP-OCRv6_tiny_det_onnx",
            revision = "2ba1506c0380b8f0b03dd142459aac66d4421f6c",
            artifacts = listOf(
                PaddleModelArtifact(
                    "inference.onnx",
                    1780590L,
                    "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
                ),
            ),
        ),
        PaddleModelSpec(
            id = "PP-OCRv6_tiny_rec_onnx",
            revision = "2612ab37152ae0a677521bae4e1e3d4fb4cf7c30",
            artifacts = listOf(
                PaddleModelArtifact(
                    "inference.onnx",
                    4462639L,
                    "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
                ),
                PaddleModelArtifact(
                    "inference.yml",
                    55571L,
                    "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
                ),
            ),
        ),
        PaddleModelSpec(
            id = "PP-OCRv6_small_det_onnx",
            revision = "28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
            artifacts = listOf(
                PaddleModelArtifact(
                    "inference.onnx",
                    9880512L,
                    "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
                ),
            ),
        ),
        PaddleModelSpec(
            id = "PP-OCRv6_small_rec_onnx",
            revision = "b8f84f0b80c529de40b4fbb3544b84fa7233a513",
            artifacts = listOf(
                PaddleModelArtifact(
                    "inference.onnx",
                    21159378L,
                    "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
                ),
                PaddleModelArtifact(
                    "inference.yml",
                    150579L,
                    "ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1",
                ),
            ),
        ),
        PaddleModelSpec(
            id = "PP-OCRv6_medium_det_onnx",
            revision = "61323801669c338b7891481ec7bac61ce31b576a",
            artifacts = listOf(
                PaddleModelArtifact(
                    "inference.onnx",
                    62032837L,
                    "eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1",
                ),
            ),
        ),
        PaddleModelSpec(
            id = "PP-OCRv6_medium_rec_onnx",
            revision = "50c7eacafc52fa7bcf4194e8cd08e46f8558504b",
            artifacts = listOf(
                PaddleModelArtifact(
                    "inference.onnx",
                    76554979L,
                    "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
                ),
                PaddleModelArtifact(
                    "inference.yml",
                    150580L,
                    "991b700facf5b50a7de193468207d5f4255b538dde0d312ae3b7c7a9b6873129",
                ),
            ),
        ),
        PaddleModelSpec(
            id = "korean_PP-OCRv5_mobile_rec_onnx",
            revision = "5c6f574b8e2230adf4287b33e736d71b9fabd28e",
            artifacts = listOf(
                PaddleModelArtifact(
                    "inference.onnx",
                    13418787L,
                    "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08",
                ),
                PaddleModelArtifact(
                    "inference.yml",
                    96039L,
                    "f757fa1c40e99edcf27e9cce879b93eb2a51fa46f5ef39095689b8c37dd75998",
                ),
            ),
        ),
    ).associateBy { it.id }
}

internal data class PaddleModelSpec(val id: String, val revision: String, val artifacts: List<PaddleModelArtifact>)
internal data class PaddleModelArtifact(val name: String, val bytes: Long, val sha256: String)
