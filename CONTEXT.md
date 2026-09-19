# Mihon reading and translation

Mihon organizes series into chapters and presents their original images in a reader. Translation adds reusable text and region geometry while preserving those images.

## Language

**Series**:
A manga, manhwa, or manhua containing chapters. The existing manga model represents a series.
_Avoid_: Book, translation account

**Original image**:
A chapter image before reader cropping, rotation, splitting, or translation preparation. Its pixel coordinates define the common reference for text regions.
_Avoid_: Screen, rendered page

**Text region**:
A polygon around a meaningful passage or separately classified incidental text, together with its transcription, translation, and reading order.
_Avoid_: Bubble (narration and signs can also be regions)

**Raw OCR**:
The uncorrected recognition result and available measured scores from the OCR stage, retained independently of later corrections.
_Avoid_: Corrected transcription

**Translation job**:
One queued chapter with its chosen settings and image results. Completed images remain available when other images fail.
_Avoid_: Download (source acquisition has separate controls)

**Translation batch**:
An ordered group of images processed together within a chapter. A failed batch can have child batches that retain its split ancestry.

**Max**:
The mode that groups as many ordered images as fit the provider's documented request and output limits. “Full” is an alias for Max.
_Avoid_: Maximum reasoning

**Halving**:
The mode that first attempts a whole chapter, then recursively processes the first half before the second after a content or limit failure.
_Avoid_: Repeated authentication retry

**Overlay**:
The translated text and optional lettering mask placed over an original image. Changing its appearance does not require translating the image again.

**Sanitized API payload capture**:
An optional retained API request and response structure with credentials, image bodies, model binaries and thought signatures omitted before storage. Omission metadata preserves useful identities and byte counts. Interrupted captures remain distinguishable from complete captures; captures are not exact replay archives.
