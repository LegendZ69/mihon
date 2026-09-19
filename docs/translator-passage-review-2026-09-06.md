# AI-assisted passage review — 2026-09-06

This is an **AI-assisted review, not a human passage review or a certification of meaning**. The initial assessment compares all 29 authored fixture regions against the twelve-page AI captures and all twelve regions in the first five pages across Vertex, Max, Halving and Custom. The additional sections below compare Small PaddleOCR, Small PaddleOCR + AI, and three privately selected real pages. Reviewing the recorded runs made no new service calls. Human passage review remains pending.

There are 27 meaningful regions and two deliberately excluded regions. The captured set has 30 region observations because the final Korean promise appears in two overlapping image tiles; the blank page has none. No reversed warning negation, left/right direction, Yuna name inconsistency, three-minute quantity, medicine recipient, or first-person/second-person waiting swap was identified in these short passages. That finding is bounded to these synthetic examples. Two passages have specific semantic follow-ups, and role/geometry/order findings remain separate in the [mode review](translator-mode-comparison-2026-09-06.md).

## Findings requiring contextual review

**Page 04, `hant-2`: younger-sister nuance is missing in Vertex, Custom and twelve-page AI.** Source “如果我沒回來，就帶妹妹離開這裡。” retains a younger-female-sibling relation in 妹妹. Those runs output “If I don't come back, take your sister and leave this place.” The conditional and negative return are preserved, but ordinary “sister” does not encode younger. “Your” adds an explicit possessor that the source leaves unstated. This is a contextual ambiguity, not evidence that “your” is necessarily false: the authored reference's “my” is also not a literal source requirement.

Max says “take my little sister and get out of here”; Halving says “take little sister and leave this place.” Both retain the younger relation, although “little” is an idiomatic age/affection choice. Max makes an explicit possessor choice; Halving avoids it but sounds less natural in ordinary English. A context-sensitive correction should preserve “younger sister” and choose a possessor only when established by the chapter. A neutral review draft can use “take the younger sister away from here,” with a contextual note rather than silently deciding family relationships. This one sample does not establish a reliable quality ranking between modes.

**Page 11, `strip-4`: overlapping tiles disagree on who promises.** The authored exchange is A: “유나, 먼저 올라가!” → “Yuna, go up first!”; Yuna: “하지만 널 두고 갈 수는 없어.” → “But I can't leave you behind”; A: “걱정하지 마. 곧 따라갈게.” → “Don't worry. I'll catch up soon”; then Yuna: “약속이야.” The fixture intends Yuna to seek reassurance from A (“Promise me”), while allowing the less explicit “It's a promise” when the relationship remains clear.

The middle tile returns **“I promise.”** and the bottom overlap tile returns **“It's a promise.”** An explicit first-person promise can shift the commitment to Yuna relative to that authored exchange. The short Korean utterance has no expressed first-person subject, so this is a contextual speaker-role concern rather than an unconditional mistranslation of the isolated words. Neighboring speech and the fixture's speaker annotations matter. The API captures do not establish which duplicate the app finally retained, whether its order remained correct, or what the reader painted. Check the persisted region and full-page exchange before choosing a correction; “Promise me” or “You promise?” are possible context-specific review drafts, not automatic replacements for every occurrence.

## Complete twelve-page passage record

The following source text is original controlled-fixture content. Slashes below indicate source/translation line breaks, not extra words. “No specific issue” means no particular semantic problem was identified during this AI-assisted pass; it is not human approval. Role labels and geometry are assessed separately.

| Page / region | Authored source | Captured translation | AI-assisted observation |
|---|---|---|---|
| 1 / `ja-1` | ユナ / 扉を開けないで / まだ敵が外にいる | Yuna, don't open the door. The enemy is still outside. | Prohibition, Yuna as addressee, and enemy still outside preserved. No polarity reversal identified. |
| 1 / `ja-2` | わかった / ここで待つ | Got it. I'll wait here. | Acknowledgement and first-person waiting preserved; response follows the warning within this image. |
| 2 / `ko-1` | 유나야, 그 문을 열지 마! / 밖에 아직 적이 있어. | Yuna, don't open that door! / There are still enemies outside. | Prohibition and outside/still preserved. Enemy number is not fixed by the unmarked source noun; plural is a contextual choice, not an established error. |
| 2 / `ko-2` | 알았어. 나는 여기서 기다릴게. / 너는 먼저 가. | Got it. I'll wait here. / You go on ahead. | First person waits; second person goes ahead. No actor swap identified. |
| 3 / `hans-1` | 尤娜，别打开那扇门！ / 敌人还在外面。 | Yuna, don't open that door! / The enemy is still outside. | Name, prohibition and outside/still preserved. |
| 3 / `hans-2` | 我们只剩三分钟。 / 不要把药交给他。 | We only have three minutes left. / Don't give the medicine to him. | Only three minutes, medicine, recipient him and prohibition preserved. |
| 4 / `hant-1` | 尤娜，別打開那扇門！ / 敵人還在外面。 | Yuna, don't open that door! / The enemy is still outside. | Name, prohibition and outside/still preserved; narration-role classification differs from fixture dialogue. |
| 4 / `hant-2` | 如果我沒回來， / 就帶妹妹離開這裡。 | If I don't come back, / take your sister and leave this place. | Younger-sister age relation is omitted by sister alone. Your is an explicit possessor absent from source; contextual ambiguity, not proof that your is false. Non-return condition and departure preserved. |
| 5 / `mixed-ja` | ユナ、こっちだ！ | Yuna, over here! | Yuna and direction toward the speaker preserved; over here is idiomatic for this way. |
| 5 / `mixed-ko` | 잠깐! 같이 가자. | Wait! Let's go together. | Wait and invitation to go together preserved. |
| 5 / `mixed-zh` | 出口在左边 | The exit is on the left. | Exit and left preserved; no left/right reversal. Role differs from meaningful sign. |
| 5 / `mixed-en` | KEEP DOOR CLOSED | KEEP DOOR CLOSED | Meaningful English warning retained unchanged and included; role differs from sign. |
| 6 / `tiny-ja` | 声を出さないで | Don't make a sound. | Negative command preserved at 12-pixel source size in this clean fixture. |
| 6 / `tiny-ko` | 아직 끝나지 않았어. | It's not over yet. | Not and yet both preserved. |
| 6 / `tiny-zh` | 别忘了我们的约定。 | Don't forget our promise. | Negative reminder and shared our promise preserved. |
| 7 / `rotated-ko` | 멈춰! 위험해! | Stop! It's dangerous! | Stop then danger warning preserved in the same order. |
| 7 / `rotated-ja` | 逃げて！ | Run! | Run is a common urgent rendering here; explicit away is less specific in English but no opposite action is introduced. Separate missing rotation still requires visual review. |
| 8 / `sign-ja` | 立入禁止 | NO ENTRY | Prohibition on entry preserved and included as meaningful text. |
| 8 / `sfx-ko` | 쿵! | THUD! | English impact THUD preserves the sound-effect function; equivalent impact terms may also fit. |
| 8 / `sign-hant` | 緊急出口 | EMERGENCY EXIT | Emergency-exit meaning preserved and included. |
| 9 / `story-ko` | 그날 밤, 우리는 다시 만났다. | That night, we met again. | That night, first-person plural we, and meeting again preserved between excluded incidental regions. |
| 9 / `advertisement` | ADVERTISEMENT / Buy a subscription today | ADVERTISEMENT / Buy a subscription today | Correctly excluded; retained raw translation is not evidence it was painted into the reader. |
| 9 / `watermark` | SAMPLE WATERMARK / example.invalid | SAMPLE WATERMARK / example.invalid | Correctly excluded; retained raw translation is not evidence it was painted into the reader. |
| 11 / `strip-1` | 유나, 먼저 올라가! | Yuna, go up first! | Yuna is addressed and told to go up first; upward direction preserved. |
| 11 / `strip-2` | 하지만 널 두고 갈 수는 없어. | But I can't leave you behind. | First-person refusal to leave second-person addressee behind preserved. |
| 11 / `strip-3` | 걱정하지 마. 곧 따라갈게. | Don't worry. I'll catch up soon. | Reassurance and following/catching up soon preserved; speaker does not promise to lead. |
| 11 / `strip-4` | 약속이야. | It's a promise. **OR** I promise. | Two tile translations disagree on explicit first person. I promise can shift the commitment to Yuna relative to the authored request for A to promise; the short Korean source itself omits a subject. It is a promise is less explicit and permitted by the fixture if the exchange remains clear. Final merged retained text and speaker context remain unverified. |
| 12 / `spread-right` | ユナ、橋を渡れ！ / 振り返るな！ | Yuna, cross the bridge! / Don't look back! | Yuna, bridge crossing and negative command not to look back preserved. |
| 12 / `spread-left` | 必ず戻ってきてね。 | Make sure you come back. | Second-person return request preserved; not changed into first-person promise. Global order across separate tiles remains a rendering/persistence check. |

Page 10 is deliberately blank and returns zero regions. The exclusions on page 09 were recorded as `included=false`; the included story passage remains between them. A raw translation string for an excluded advertisement or watermark does not prove it appeared in an overlay.

## First-five-page mode comparison

All four modes preserve Yuna's name, the door prohibition, the enemy's outside location, the waiting/going actors, only three minutes, the medicine prohibition, the instruction to go together, the exit's left direction, and the meaningful English warning. The main substantive variance is `hant-2`, discussed above. Vertex uses “enemies” for Korean `ko-1` and Traditional Chinese `hant-1`, while other responses often use “the enemy.” Those source nouns do not explicitly fix singular/plural here, so this is a contextual number choice, not a proven omission or invented plot event.

Other differences are register/punctuation or close equivalents: “Got it” / “Understood” / “All right,” “give him the medicine” / “hand the medicine over to him,” and “over here” for Japanese “こっちだ.” No altered actor or polarity was identified in those variants. Names are consistently romanized Yuna. The first four modes used HIGH configured thinking, whereas the twelve-page run used verified MEDIUM wire thinking; these are separate samples, not a controlled comparison of reasoning settings.

## Reproducibility and limits

The structured private review is `build/translator/validation/private/ai-assisted-passage-review-2026-09-06.json`; it retains all 29 source/reference rows, captured translations, mode variants, expected speaker/order and per-region findings. The [corpus manifest/specification](translator-fixture-corpus.md) preserves authored source separately. The [capture reviewer](translator-capture-review.md) reconciles source image and tile hashes, exact/whitespace text matches and completeness independently of this semantic assessment.

Public text above is limited to authored synthetic passages and associated captured translations. Raw credentials/account metadata are not reproduced. This initial capture-only assessment cannot verify final rendered geometry, mask readability, speaker attribution inside the app, global reading order across tiles, or the persisted winner of a duplicate region. The later two-page hybrid check below adds bounded persisted-result evidence. Human passage review remains pending for all examples, including those without a specific issue identified here.

## Small PaddleOCR + AI comparison

The twelve-page hybrid run used Small local OCR and `gemini-3.8-flash`, MEDIUM thinking, with 15 complete HTTP-200 generation captures. All 15 transmitted tile pixel identities match the fixed corpus. This is a separate sample from the earlier AI and pure Paddle runs, not a general quality ranking of pipelines. The hybrid request contained the same local OCR transcriptions as the pure Paddle run, transformed into tile coordinates where applicable.

All **27 meaningful authored passages** are present when returned OCR lines are associated with their full authored passages. The two incidental regions are also accounted for and excluded. There are 40 captured region observations: several authored passages are split into lines, and the final strip passage appears in two overlapping tiles. The automatic whole-region matcher matches only 20 of 29 groups; its remaining nine flags on pages 01, 03, 04, 09 and 12 are explained by exact individual lines, rather than established missing text. This manual association by an AI reviewer does not convert those matcher results into human approval.

| Case | Pure Small OCR / text translation evidence | Hybrid capture evidence and AI-assisted assessment |
| --- | --- | --- |
| Vertical Japanese | All five columns recognized; the original OCR order is left-to-right within each bubble. The text provider returned corrected order. | All five columns retained in the correct provider order: name, prohibition, enemy, acknowledgement, waiting. The subsequent app merge lost this corrected order; see the implementation finding below. |
| Korean dialogue and mixed-language Korean | Two page-02 passages and the page-05 Korean passage absent from local OCR. The initially invented page-02 response was rejected; its text-only retry correctly returned no regions but still supplied no Korean translation. | Both door-warning/response passages and the invitation to go together restored from the images. No reversal of prohibition or waiting/going actors identified. |
| Tiny lettering | Japanese and Chinese retained; Korean recognized only as punctuation, which the pure text translation kept as an ellipsis. | All three passages restored, including Korean negation and “yet.” No specific meaning issue identified in these clean 12/18/24-pixel fixtures. |
| Rotated lettering | Japanese retained; Korean warning absent. | Korean stop/danger warning restored; Japanese explicitly rendered as “Run away!” The missing Japanese rotation remains a separate geometry failure. |
| Signs and sound effect | Signs retained. Korean impact recognized as Latin-like noise; the provider changed its source to a Chinese character and returned an impact sound, which does not establish recovery of the authored Korean. | Both signs retained; Korean impact correctly transcribed and translated as “THUD!” Sign type labels still differ from the fixture. |
| Incidental text and blank page | Ads/watermarks excluded, but adjacent Korean narration absent. Initially invented blank-page regions were rejected; the retry returned no regions. | Korean narration restored; all four ad/watermark lines have `included=false`, explicit reasons and empty translations. Blank page has no regions. |
| Long Korean strip | Four meaningful passages missing or reduced to punctuation/numeric noise. The retry excluded those fragments and did not invent replacements. | All four passages restored. The two overlapping final-passage observations agree on “It's a promise.” This removes the earlier AI run's inconsistent first-person wording; the authored speaker context still requires human review. |
| Japanese spread | Both passages retained as three lines. | All lines retained; the return request is rendered as “Promise you'll come back.” This is a contextual recasting of an emphatic request, rather than an explicit promise verb in the source. No actor reversal identified; chapter context should guide the final wording. |

Across these fixed inputs, local Small OCR missed or corrupted **all 11 Korean passages**; hybrid recovered all 11 from images. This is evidence about the selected model/configuration and fixtures, not a claim about every Paddle model or the separate Korean recognizer. The AI-only run already retained these passages. Hybrid also preserves Yuna's name, the medicine recipient and prohibition, three minutes, and the exit's left direction. The Traditional Chinese younger-sister omission persists: hybrid again chooses “your sister,” leaving the same age nuance and contextual possessor follow-up as the AI run.

**A deterministic app merge defect was reproduced and fixed.** The hybrid provider corrected the Japanese order, but `PreparedTranslationRequest.merge` restored old OCR `readingOrder` and polygons for recognized IDs. In the strip, mixing new AI regions with restored OCR orders could also produce duplicate orders and place the final promise before the preceding reassurance. The focused correction keeps hybrid provider order as contiguous page indices and keeps its geometry after mapping to original coordinates. Raw OCR/source text, measured confidence, IDs, user styles, corrected transcription and rotation remain preserved separately. Paddle-only identity/geometry behavior is unchanged. The host regression was red on old order and geometry, then all ten image-preparation tests passed; the complete app suite subsequently passed 94 tests. A subsequent two-original minified handset check verifies newly assembled results as described below. Previously saved results are not silently rewritten.

The private review `build/translator/validation/private/ai-assisted-hybrid-passage-review-2026-09-06.json` records all 29 group associations, 40 observations, source identities and rectangle-union measurements. Its SHA-256 is `51acd9df21d4fbbdff94e6fd7cf4e07794f74a992aabcfcc62b5d09561d37575`; the hybrid capture archive is `9a7b48ca82580964e7c213dd5ff64813550a558bc990e5d4892b6dc55b3e7435`.

## Physical two-page hybrid follow-up

The corrected merger has a **bounded physical PASS** on new copies of fixture page 01 and page 11, using minified release `a899cd4ea4d3c0fa8070dc4801d981289ddfa242639f041930c98a127d60e928`, Small PaddleOCR + AI and MEDIUM thinking. Four first-attempt generation responses were accepted. Original source SHA-256 values are `080d1c771e309e331f84699254d4ae5e7c52c0d6830e55253f932bbcde685246` for Japanese and `6493ca6dfb50a0032a66d74baaa04056af250e9c966e9e7dbec802c591e0a36a` for the strip; all transmitted crop pixels match those originals.

Actual persisted data exported through the app's Full OCR details inspector now retains the Japanese sequence **Yuna → prohibition → enemy → acknowledgement → waiting**, with stable IDs and contiguous reading orders. The long strip retains four passages in sequence: going up first, refusal to leave the addressee behind, reassurance about following soon, and the promise. AI-added passages survive, corrected Korean remains separate from noisy raw OCR, and measured scores are preserved. Geometry/duplicate checks and their 0.0002-pixel mapping tolerance are recorded in the [physical quality review](translator-quality-review-2026-09-06.md#physical-two-page-hybrid-merge-verification).

**Meaning remains pending:** the new middle tile again says “I promise.” while the bottom overlap says “It's a promise.” The actual saved final region is now known: the larger middle-tile rectangle wins and retains **“I promise.”** The merge no longer moves this passage ahead of the reassurance, but correct ordering and deterministic duplicate selection cannot resolve the authored speaker-role concern discussed above. This does not supersede the earlier twelve-page hybrid capture's matching alternatives or establish human approval of either wording.

The private `hybrid-merge-two/reconciliation.json` has SHA-256 `a960bd73a924e2021107eb2a97cfd75359e7668b388dcffb8b916f9aebeb33bb` and preserves the two inspector JSON identities, matched provider fields, crop hashes and usage reconciliation. This is a new two-original sample; the rest of the twelve-page run, real chapters, translated glyph layout and human passage review remain outside this physical merge pass.

## Three selected real pages: tiled versus full input

This additional **AI-assisted visual and passage assessment** compares original JPEGs `00007`–`00009` from the privately selected volume with recorded AI translations. The images and full copyrighted passages remain in app-private/host-private evidence, outside the repository report. Both runs used the same three 1362 × 1920 originals and MEDIUM thinking: tiled input produced six generation responses, and disabling upload tiling produced three full-image responses. These runs precede the hybrid merge fix and use the AI pipeline, so that fix does not explain their differences.

| Page | AI-assisted meaning and order finding |
| --- | --- |
| `00007` | Names and the students' shared school recollection remain recognizable. The bottom speech bubble crosses the tiling boundary: the lower tile returns a spurious translated fragment and a separate punctuation region. Full input removes these extras and keeps one coherent passage, but its transcription duplicates a kana. Translating a broken crop successfully is not complete-passage fidelity. The full output's expanded recognition question is a contextual rendering, not evidence of a new named person. |
| `00008` | Both runs retain Jingo's denial that he kidnapped the students and his claim that he was brought there too. No reversal of that negation or shared-victim relationship was identified. Rough speech remains rough; English profanity is a register choice requiring contextual review. Full input merges a long reply into a rectangle spanning separated text areas across the character's face; semantic continuity does not establish safe overlay geometry. |
| `00009` | Satsuki Tsukihara and Shou Hinata agree with the visible name readings; the students' middle-school status and ID ownership remain present. Both runs place the ID-recognition passage after the return demand and reaction in the neighboring left panel. Visual panel order puts ID recognition earlier, inside the tall right panel. The later spacing-out accusation and victim-blaming statement remain attributed to the adult; preserving those statements is not endorsement of them. |

For page `00009`, the visually assessed sequence is the two introductions, age observation, ID recognition, return demand, reaction, spacing-out accusation and victim-blaming statement. The complete-image provider still orders the ID passage late; therefore horizontal crop boundaries alone do not explain this failure. The current merger's sequential tile policy adds a separate limitation in tiled input. No broad panel-order or segmentation change was made from this small sample. Human review of meaning, panel order and actual overlay readability remains pending for all three pages.

| Original JPEG | SHA-256 |
| --- | --- |
| `00007` | `5e2b2fec4fca5fa4c6fa3df6724b57dd30cf36f0395d2e6372e50f24a4a6257f` |
| `00008` | `5e244620b0ceba479949d7d43576980a065987b29c585ae502e632626f1eec09` |
| `00009` | `bd5b0af43d1df43a5e69c8509a2b9e4dc5f1a98f05998a01d8e137a63844ae53` |

Private capture archive identities are `4891da5853d71b0cd3a624923adeeb3dc29150ff24f00543ff6232605606da09` (tiled) and `2b31b8fc4528714fea026fa3ae82c3fb98d716b895726f8269a7e6b5fec80da5` (full input). Returned-page snapshots are respectively `e383c2f88e414774b38583e3b9c19d185e5fcdcefe4dd1c6ed073db728a30ccb` and `d05670aad997c30dd7badf2c23bddd7a80debe977dffdda005214e00cb2b9b32`. These hashes identify evidence; they do not certify translation quality or billed cost.
