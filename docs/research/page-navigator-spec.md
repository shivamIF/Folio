# Page Navigator: Evidence and Behaviour Spec (2026-10-20)

This spec drives Folio's page sidebar, per-page menu and single-page export. It reuses the artifacts in `docs/ARTIFACT_INVENTORY.md`. New analysis was done only to recover the per-page menu, which the earlier decompile had missed.

Labels:
- **Observed**: the artifact shows it directly (resource, string, or bytecode).
- **Inferred**: my interpretation of observed facts.
- **Unverified**: an assumption with no evidence, to be checked on a device.

No proprietary code or assets were copied. The findings below describe behaviour only.

## 1. Evidence

| # | Artifact | App / version | Used for |
|---|---|---|---|
| S1 | `~/Desktop/DropShare/…notes_4.4.48.15…apk_Decompiler.com.zip`: `res/layout/comp_page_list_item_layout.xml`, `comp_page_list_item_more_menu_item.xml`, `res/values/dimens.xml`, `strings.xml`, `res/menu/page_list_more_menu.xml`, `composer_page_list_edit_bottom_menu.xml`, `PageListContract`, `PageListConstants`, `PageListModeContract`, `share/ExportPagesMenu`, `share/SharePagesMenu`, `widget/share/ShareMenuContract` | Samsung Notes **4.4.48.15** (`versionName` in the APK file name and manifest) | Item layout and sizes, sidebar modes, share/export formats |
| S2 | The same APK's `classes*.dex`, disassembled with the Android SDK's `dexdump` (build-tools 36.0.0): `PageListHolderPresenter.moreBtnClicked`, `PageListMoreMenuPresenter.{getMenuList, addClearMenu, onAdd, insertTemplatePage, onDelete, doDeletePostAction, onErasePage, onPaste, onShare, onExport}`, `MoreMenuListPopup.{initMenuNames, show}`, `MoreMenuListPopup$4.onItemClick`, `AbsShareMenu.onItemClick` | Samsung Notes 4.4.48.15 | **The per-page ⋮ menu**: items, order, conditions and what each does. The decompiled ZIP lacks these classes (Decompiler.com dropped them), which is why the earlier pass called the menu "unverified" |
| G1 | `/Applications/Goodnotes.app/Contents/Resources/en.lproj/*.strings` | Goodnotes for macOS **7.0.36** (build 3612620.9252428, `Info.plist`) | Page-action vocabulary, delete rule, sidebar wording, export options |
| G2 | `docs/research/goodnotes-android-teardown.md` | Goodnotes Android 2.0.9 | Shows the Android app is a web shell with **no native page navigator** to study |

Not available: there are no screenshots or screen recordings of either app's page navigator. The screenshots and recordings on the Desktop are unrelated (confirmed by the user, 2026-10-20). That's why no timing, animation or pixel-level look is claimed below.

## 2. Reconstructed flows

### 2.1 Opening and collapsing the page sidebar
- **Observed (S1)**: Samsung's page list ("Page sorter", `composer_sort_pages`) is a panel with its own container width (`comp_page_list_container_width` 212dp). `IContainerPresenter` has `show(animate)` / `hide(animate)`. The app bar has a page-sorter toggle icon (`comp_appbar_ic_page_sorter_selected` / `_unselected`).
- **Observed (G1)**: Goodnotes has "Show Sidebar" and "Hide Sidebar", described as "Show thumbnails, bookmarks, or outlines".
- **Inferred**: showing or hiding the panel doesn't change the open page. Both apps treat it as a view of the document.

### 2.2 Scrolling the thumbnails
- **Observed (S1)**: a vertical `RecyclerView`. Item width is 99dp (portrait) or 140dp (landscape), with a 10dp gap below each item and a 24dp page-number row. The layout managers differ for phone and tablet.
- **Observed (S1/S2)**:
  - The view has `notifyCurrentPageIndexChanged(index, …)`, `updateScrollbar`, `clearThumbnail` and `clearRecyclerViewPool`.
  - There is a `PageListThumbnailHelper`, plus `PageThumbnailHoverListener` with an async task (S Pen hover preview).
- **Inferred**:
  - The list follows the current page.
  - Thumbnails are cached bitmaps that can be dropped.
  - Hovering the pen over a thumbnail shows a larger preview. Folio doesn't do this; see §4.

### 2.3 Selecting a page and going to it
- **Observed (S1)**: each thumbnail container is a `CheckableRelativeLayout` with an outline foreground and 4dp elevation, so the current page is shown with an outline.
- **Observed (G1)**: "Go to Page".
- **Inferred**: tapping a thumbnail opens that page in the editor.

### 2.4 The per-page ⋮ menu
- **Observed (S1)**: `pages_item_more_btn` ("More options") sits at the **bottom-end corner of the thumbnail**. The page number is centred **below** the thumbnail (12dp text). A bookmark toggle sits at the top-end. Menu rows have one line of text at 17sp with 24dp side padding.
- **Observed (S2)**: tapping ⋮ calls `moreBtnClicked`, which:
  - dismisses any open menu;
  - cancels a drag in progress;
  - shows a popup list anchored to the button.

  The list is `getMenuList(pageId)`, using item ids 0–7 mapped to strings in `initMenuNames`. In the normal (non-search, non-bookmark) mode the order is:

  | # | Item (en string) | Shown when (observed) | Action (observed) |
  |---|---|---|---|
  | 0 | Add page | Always | Opens the template picker. The new page is inserted at **tapped index + 1** (after the page), sized to the note's default width, with the template taken from the tapped page |
  | 1 | Copy | Not on a page with a maths object; not in a co-edited note | Copies the page to the clipboard as page data |
  | 2 | Cut | Same as Copy | Copy, then remove |
  | 3 | Paste | Only when the clipboard holds page data | Pastes the copied page(s) after this page |
  | 4 | Delete | Always | Deletes inside one **history group** (undoable). **No confirmation dialog in this path.** When the note is left with a single page, a fresh blank page is inserted and the "all pages deleted" flag is set (the exact condition is **inferred** from bytecode) |
  | 5 | Erase page | Only when the page has content (`isObjectEmpty` false) | Clears the page's objects; undoable |
  | 6 | Share | When sharing is allowed (locked notes need FileProvider support) | Format popup, then the share sheet |
  | 7 | Save as | Same as Share | Format popup, then a **folder picker** (`executeSaveDirectoryPicker`) |

  In search or bookmark mode only Copy and (with enough pages) Delete appear, then Share and Save as.
- **Observed (S2)**: Share and Save as offer the share types Samsung Notes file, PDF, Microsoft Word, PowerPoint, Image and Text only. PDF has an extra options menu.
- **Observed (G1)**: Goodnotes' page actions include "Add Page Before", "Add Page After" (also "Before/After Current Page"), "Copy Page", "Paste Page(s)", "Duplicate", "Rotate Page", "Bookmark", "Clear Page", "Move Page to Trash" (with the dialog "Move Page to Trash?") and "Export This Page". The rule "A Document Needs to Have at Least 1 Page." and the tooltip "More page options" also appear.
- **Unverified**: the **order** of Goodnotes' page menu. Strings don't establish order.

### 2.5 Reordering
- **Observed (S1/S2)**:
  - Drag-and-drop in the sorter (`PageDragListenerImpl`, `PageReorderController`, `PageReorderAnimator`, with a drag shadow);
  - a long press starts multi-select (`startLongPressMultiSelection`);
  - the strings "Release to add page." and "Release to add a new page." are present.
- **Inferred**: on a tablet, a long press then drag moves a page.
- **Unverified**: the exact gesture split between long-press-select and drag on a tablet.

### 2.6 Exporting one page vs. the whole document
- **Observed (S2)**: the page menu's Share and Save as act on the page's data list (`getSelectedPageDataList(pageId)`). The note's own option menu (not the page list) exports the whole note.
- **Observed (G1)**: "Export This Page", "Export Selected Page(s)" and "Export All Pages" are separate. The options are "Include Page Background" and "Export Images as Zip File", and PDF can be "Editable" or "Flattened".

## 3. What Folio implements (decisions)

| Reference behaviour | Folio | Why |
|---|---|---|
| Sidebar panel, toggled from the app bar (S1, G1) | Left sidebar, toggled by a "Pages" button in the top bar. Its open/closed state persists | Matches both. Left is Goodnotes' default |
| Current page outlined (S1) | Accent outline plus a tinted page-number chip, kept in view automatically | Matches |
| ⋮ at the bottom-end of the thumbnail, number below (S1) | Same placement. The ⋮ has a 40dp touch target | Matches, sized for a finger as well as the S Pen |
| "Add page" after the page, same template (S2). Goodnotes also has *Before* (G1) | **Add page before** / **Add page after**. The new page copies the reference page's paper and size, without its PDF background | Both observed. Copying the template follows Samsung |
| Copy / Cut / Paste (S2), Duplicate (G1) | **Duplicate page**: an independent copy inserted right after the page | Copy/paste between notebooks would have to copy PDF attachments across notebooks. Deferred (KNOWN_LIMITATIONS) |
| Cut + Paste, and drag in the sorter (S2) | **Move page…** (pick a position) **and** long-press drag to reorder in the sidebar | Both observed |
| Delete: undoable, no dialog (S2). Goodnotes: confirmation dialog (G1) | **Delete page**: confirmation dialog **and** an Undo snackbar (the user's choice, 2026-10-20). The last page can't be deleted (Goodnotes' rule) | User decision. Reversible either way |
| Erase page only when the page has content (S2), "Clear Page" (G1) | **Erase page**: shown only when the page has content. It removes ink, text and pictures as one undoable edit; the paper and PDF background stay | Matches. Uses `PageEdit`, so undo works |
| Share / Save as with formats (S2), "Export This Page" (G1) | **Export page…**: choose PDF, PNG or JPEG (raster at 72, 150 or 300 dpi), then **Save to device** (system file picker) or **Share** (share sheet) | Word, PowerPoint, Text and a native-file export need a text layer or recognition that Folio doesn't have, so they aren't offered |
| Whole-note export lives elsewhere (S2, G1) | The existing top-bar "Export as PDF" (all pages, Downloads/Folio) is unchanged | Kept separate as required |
| Bookmark, Rotate, Hide page, Outline, Change template (S1, G1) | **Not implemented** | Each needs a persisted field and a schema migration. Not faked |
| Search mode, multi-select edit mode (S1) | **Not implemented** | Out of scope; see KNOWN_LIMITATIONS |
| Hover preview (S1) | Hover highlight only (the item lifts under the pen) | A preview popup is a follow-up |

## 4. Folio facts the design respects
- **One page at a time.** The editor shows one page and switches with Previous/Next. There's no continuous vertical scrolling in any branch. The user chose (2026-10-20) to keep this model; continuous scrolling is a separate task. The "visible page" is `EditorUiState.pageIndex`.
- **Sources of truth:**
  - Page order: `NotebookMeta.pageIds` in `library.json`.
  - Page content and size: `Page` files, edited through `PageEditor`.
  - Current page: `EditorUiState.pageIndex`.
  - Undo: per-page `PageEditor` history.
  - Export: `NotebookPdfExporter` for whole-notebook PDF.

  The sidebar adds no parallel model. Page operations are new `DocumentRepository` calls and update `pageIds` atomically.
- **Sync** (ADR-7) sees page add, delete and move as `meta.json` and page-file changes, as with the existing "Add page".

## 5. Gaps to check on hardware
1. Thumbnail legibility and ⋮ hit rate with the S Pen on the Galaxy Tab S6 Lite.
2. Long-press drag with the S Pen (Android sends stylus long-press like a touch long-press).
3. One UI's file picker behaviour for duplicate names (AOSP DocumentsUI appends " (1)").
