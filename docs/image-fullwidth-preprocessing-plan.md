# Plan: Full-width Image Preprocessing for Traditional / Modern Themes

## 1. Problem

When switching to "Traditional" or "Modern" layout themes, block-level images and paragraphs consisting solely of an image (`<p><img/></p>`) should fill the full reader width — **but only if the original book did not specify a `width` or `max-width`** for that image. This respects authors who explicitly sized images while automatically stretching unsized illustrations.

## 2. Design Goals

| Goal | Detail |
|------|--------|
| User-layer only | The render engine (common box model) stays unchanged. Behavior lives in a preprocessing pass + one CSS rule. |
| Precise default | Only images that are (a) the sole non-whitespace content of a block container **and** (b) not sized by the book get the class. Inline images mixed with text are untouched. |
| Theme-scoped, cache-safe | The class is added at parse time (once per chapter, cached with the tree). The CSS rule lives in the theme stylesheet (tier 42), so it only activates in Traditional/Modern; "Original" mode is unaffected. No invalidation needed on theme switch. |
| Author-CSS aware | Width detection runs the cascade with only the chapter's author stylesheets + inline styles + HTML attributes. A book that sets `width` or `max-width` via a CSS class (`.illustration{width:80%}`) is correctly detected and skipped. |
| Cross-platform | The preprocessor lives in `common` and is called from both Android and Desktop parse paths. |

## 3. Algorithm

### 3.1 Class name

```
orilumn-fullwidth-image
```

Namespaced to avoid collisions with book classes.

### 3.2 Preprocessor entry point

```kotlin
// common/src/commonMain/kotlin/com/orilumn/engine/html/ChapterPreprocessor.kt
object ChapterPreprocessor {
    fun preprocess(
        root: MarkupElement,
        authorSheets: List<StyleSheet>, // chapter stylesheets only (no UA/theme/settings/UI)
    ): MarkupElement // returns a new root with qualifying images annotated
}
```

Returns a **copy-on-write rebuilt tree** (`MarkupElement` fields are `val`; only `parent` is `var`).

### 3.3 Per-image checks (both must pass)

#### A. Structural classifier — "block / sole-content image"

For an `img` node `el` with parent `p`:

```kotlin
val contentChildren = p.children.filter { child ->
    if (child.isText) child.text.isNotBlank() else true
}
val soleContent = contentChildren.size == 1 && contentChildren[0] === el
```

| Input | Result |
|-------|--------|
| `<p><img/></p>` | 1 content child (img) → qualifies |
| `<figure><img/></figure>` | 1 content child → qualifies |
| `<div><img/></div>` | 1 content child → qualifies |
| `<body><img/></body>` | 1 content child → qualifies |
| `<p>text <img/></p>` | 2 content children (text + img) → rejected |
| `<figure><img/><figcaption>…</figcaption>` | 2 element children → rejected |
| `<div><img/>  <img/></div>` | 2 element children → rejected |

#### B. Book did not set width or max-width

Build a `Cascade` with **only the chapter's author stylesheets** (UA, theme, settings, UI all null):

```kotlin
val cascade = Cascade(
    ua = null,
    authorSheets = authorSheets,
    theme = null,
    settings = null,
    ui = null,
)
```

For each qualifying image:

```kotlin
val inlineDecls = cascade.parseInline(el.attrs["style"])
val ancestors = el.ancestorsOrRoot.toList()
val winners = cascade.winningDeclarations(el, ancestors, inlineDecls)

val bookDeclaredWidth   = winners["width"] != null
val bookDeclaredMaxWidth = winners["max-width"] != null
    && !CssValues.isNoneOrAuto(winners["max-width"]!!)
```

If either is true → **skip** (do not add class).

What `winningDeclarations` already incorporates (no extra work needed):

| Source | Origin tier |
|--------|------------|
| Author CSS rules (`.illustration{width:80%}`) | 20 |
| HTML `width="200"` attribute | 15 (`htmlAttrOrigin`) |
| Inline `style="width:…"` | 30 (`parseInline`) |

What is **excluded** (correctly):

| Source | Why excluded |
|--------|-------------|
| UA `img{max-width:100%}` | UA sheet not in this cascade → not treated as "book set" |
| Theme/UI/settings sheets | Not in this cascade |

`CssValues.isNoneOrAuto` treats `"none"`, `"auto"`, `"initial"` as equivalent to no constraint (avoids false skip on `style="max-width:none"`).

### 3.4 Copy-on-write tree rebuild

Recursive walk; return a new node only when a child changed:

```kotlin
private fun rebuild(el: MarkupElement, cascade: Cascade): MarkupElement {
    if (el.isText) return el  // text leaves never change

    var changed = false
    val newChildren = el.children.map { child ->
        if (child.tag == "img" && qualifies(child, el, cascade)) {
            changed = true
            val newClass = buildString {
                append(child.attrs["class"]?.trim() ?: "")
                if (isNotEmpty()) append(' ')
                append("orilumn-fullwidth-image")
            }
            MarkupElement("img", child.attrs + ("class" to newClass))
        } else {
            val rebuilt = rebuild(child, cascade) // recurse
            if (rebuilt !== child) changed = true
            rebuilt
        }
    }
    return if (changed)
        MarkupElement(el.tag, el.attrs, newChildren, el.text)
    else el
}
```

After building the new root, walk it and assign `parent` pointers (same post-processing `HtmlTreeConverter.convertWithStyles` does after parse). Neglecting this would break the lazy cascade ancestor walk in layout.

### 3.5 Why class is safe at parse time (not layout time)

| Concern | Resolution |
|---------|-----------|
| Theme switch needs re-render | Class is theme-independent; CSS rule only activates in Traditional/Modern (tier 42). No re-parse needed. |
| `structureKeyOf` fingerprint (BoxChapterLayouter) | Fingerprints tag structure, not attrs → adding a class attr does not invalidate the key. |
| Author-only cascade with nominal font size | Null-ness of `width`/`max-width` is font-independent. Using a nominal `rootFontPx` (e.g. 16) is safe. |
| Cached tree carries class across sessions | Desired: the classification is permanent metadata about the chapter's images. |

## 4. Implementation Steps

| Step | What | Files |
|------|------|-------|
| **1** | **Preserve `class` on `<img>` in the parser.** Currently `HtmlTreeConverter.kt:100` drops the `class` attribute from img elements. Add `"class"` to the `attribs()` call so book-authored classes (needed for correct cascade matching) are retained. Also fixes a pre-existing bug independent of this feature. | `common/.../html/HtmlTreeConverter.kt:100` |
| **2** | **Implement `ChapterPreprocessor`** in common. New file. Contains: structural classifier, author-only cascade width check, `CssValues.isNoneOrAuto` helper, copy-on-write tree rebuild, parent-pointer assignment. Pure logic, JVM unit-testable. | New: `common/src/commonMain/kotlin/com/orilumn/engine/html/ChapterPreprocessor.kt` |
| **3** | **Wire into Android parse path.** In `BookDocumentController.readChapter` (lines 1342–1347), after `buildCssBundle` (1345), parse the bundle into `List<StyleSheet>` (reuse the same `LightCssParser().parse()` pattern as `BoxChapterLayouter.parseAuthorSheets`), call `ChapterPreprocessor.preprocess(parsed.tree, authorSheets)`, and return the preprocessed tree. The caller (`ensureMarkup`) then caches it. | `app/.../engine/BookDocumentController.kt:1342-1347` |
| **4** | **Wire into Desktop parse path.** In `DesktopReaderHost.buildShell` (lines 236–249), `sheets` is already built at 237–242 from `parsed.styles`/`parsed.linkHrefs`. After building `sheets` and before setting `c.markup` (249), call `ChapterPreprocessor.preprocess(markup, sheets)` and use the result. | `desktopApp/.../desktop/DesktopReaderHost.kt:236-249` |
| **5** | **Add CSS rule to theme stylesheets.** Append `.orilumn-fullwidth-image { width: 100%; }` to `traditional.css` and `modern.css`. Also append the same rule string to the desktop inline fallback strings (`themeSheet()` around lines 376–380). | `app/src/main/assets/css/traditional.css`, `app/src/main/assets/css/modern.css`, `desktopApp/.../desktop/DesktopReaderHost.kt:376-380` |
| **6** | **Unit tests.** (a) `ChapterPreprocessorTest` in `common/src/jvmTest`: verify structural classifier, width detection, `isNoneOrAuto`, no mutation of original tree. (b) Integration test in `app/src/test` or `common`: mock chapter with mixed images → only qualifying ones get class. (c) CSS rule test: verify rule present in parsed theme sheets. (d) Run existing suites: `:engine-skia:jvmTest`, `:shared-ui:jvmTest`, `:common:jvmTest`, `:app:testDebugUnitTest`. | New test files in `common/src/jvmTest` and/or `app/src/test` |

## 5. Edge Cases & Limitations

| Case | Behavior | Why |
|------|----------|-----|
| `<p>text <img/></p>` | **Not** classified | Text node is non-blank →2 content children |
| `<figure><img/><figcaption>…</figcaption>` | **Not** classified | 2 element children |
| Image with `style="width:80%"` | **Skipped** | `winners["width"]` non-null (tier 30) |
| Image with `style="max-width:none"` | May classify | `isNoneOrAuto` treats `"none"` as no constraint |
| Image with HTML `width="200"` attribute | **Skipped** | `winners["width"]` non-null (tier 15) |
| Image with CSS class `.fig{width:80%}` | **Skipped** | Author cascade detects → `winners["width"]` non-null (tier 20) |
| UA `img{max-width:100%}` | **Ignored** | Not in author-only cascade |
| "Original" layout mode (no theme sheet) | Class exists but no CSS targets it → intrinsic size | Theme sheet is null in Original mode |
| Theme switch (Traditional ↔ Modern) | Instant, no re-parse | Class baked at parse is theme-independent |
| Multiple `<img/>` siblings in one container | **None** classified | Each has >1 content sibling |
| `<img style="display:none">` | Class may be added, but hidden → harmless | No visual effect |
| `style="width:auto"` | Treated as no width (`isNoneOrAuto`) → may classify | `auto` is equivalent to intrinsic |
| Book already uses class `orilumn-fullwidth-image` | Duplicate token in class attr → `hasClass` still matches | Cosmetic, functionally safe |

## 6. Risks

| Risk | Mitigation |
|------|-----------|
| **Parent pointers broken after copy-on-write** | Walk new tree and assign `parent` on all copied nodes, same as `HtmlTreeConverter.convertWithStyles` does after initial parse. |
| **`Cascade(ua=null, …)` crashes on null layers** | `addSheet` short-circuits on null: `if (sheet == null) return`. Verified in `Cascade.kt:11-17`. |
| **`structureKeyOf` invalidation** | Fingerprints tag + structure, not attrs. Adding a class attr does not change the key. Safe. |
| **Class name collision with book** | Namespaced (`orilumn-fullwidth-image`). If a book coincidentally uses this exact class, duplicate token in class attr is harmless (`hasClass` matches either). |

## 7. Open Questions

None. Ready for implementation pending approval.
