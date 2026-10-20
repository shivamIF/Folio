# Goodnotes for Mac: library (landing page) teardown

Target: `/Applications/Goodnotes.app` on this Mac (Mach-O universal, arm64 slice; sha256 `29598ae8…76d5`, 485 MB).
The Android APK is only a web shell (see `goodnotes-android-teardown.md`), so the native Mac build was the useful target.

Tool: REA 6.5.0 (`open_binary`, `decode_interface_builder`, `inspect_plist`) plus `assetutil` and `strings` on the thinned arm64 slice.
No decompiler provider was available (Ghidra and Hopper were not configured), so there is **no pseudocode**: everything below comes from shipped resources, type and string names, and interface archives.
**Clean-room**: we used names, layouts and behaviour as a feature list only. No Goodnotes code, assets, colours or strings were copied into Folio (CLAUDE.md rule). Folio's folder art, palette and icon set are original or come from Material Icons.

## Evidence

| What | How | Evidence ID |
|---|---|---|
| 3,906 interface-builder objects; folder/document cell outlets | `decode_interface_builder` (summary + edges for `FoldersCollectionThumbnailItemCell.nib`) | `ev_f419d953…` (parent), `ev_0841ac46…` (edges view) |
| Folder customisation strings: "Color", "Icon", "Includes: %lu Documents" | `inspect_plist` on `en.lproj/FolderCustomization.strings` | `ev_6c3ab296…` |
| Library, sidebar, favourites, trash, sort and filter strings | `plutil` on `en.lproj/Localizable.strings`, `GN6EmptyView.strings` | (shell; file is shipped data) |
| Folder palette, folder icons, folder art layers | `assetutil --info` on `GNLibraryUI_GNLibraryUIAssets.bundle/Assets.car` | (shell) |
| Type names: split view, sidebar, folder customisation model | `strings` on the arm64 slice | (shell) |

## Observed (shipped artefacts)

**Navigation shell.** Module `GNRootSplitView` has `RootSplitViewController`, `RootSidebarViewController`, `RootSidebarInnerViewController`, `RootSidebarCell`, `SidebarNavigationBar` and, for compact widths, `RootTabBarController` and `RootTabBarAddButton`. The sidebar sections are Documents, Favorites, Shared and Marketplace (screenshot), with a Trash Bin page (`trash_bin_page_title`).

**Library header.** Strings: `documents_page_title` "Documents", `folder_root_folder_name` "Documents", `folder_separator_v2` ">" (breadcrumb), `library_header_new_button_title` "New", `filter_option_all/documents/folders/pages`, `library_option_grid/list`, and the grouped options button ("Sorting, View Mode, Select"). Sort keys: Date, Name, Type, Date Created, Last Modified, with Ascending/Descending or Newest/Oldest first.

**New menu.** `new_document_popover_title` "Create New" with Notebook, Folder, Whiteboard, Workbook, Shared Notebook, plus import.

**Item cell** (`FoldersCollectionThumbnailItemCell.nib` outlets): `imageView` (thumbnail), `titleLabel` and `titleTapArea` (tap the title for options), `dateLabel`, `bookmarkButton` (the star), `optionButton`, `selectedMarkerView` (multi-select), `syncStatusView`, `sharingIndicatorView`, `dueView` (classroom).

**Item actions.** Open, Rename, Duplicate, Move ("Move to a Different Folder", picker "Select a Location"), Favorite, Customize (folders), Share/Export, Move to Trash; in Trash: Restore and Delete Permanently. Select mode has Select All / Deselect all.

**Folder customisation.** `FolderCustomizationViewModel`, `FolderCustomizationSegmentedControl` (two segments: Color | Icon), `FolderSymbolPickerViewController`, `FolderColor`, `FolderSymbol`, `folderColorPalette`, `folderColorRgb`, `customFolderColor`. Assets: `folder-palette/color_{black,blue,gray,green,orange,pink,purple,red,yellow}` and `folder-palette/rainbow` (custom colour), and 20 `folder-symbols/folder_icon_*` glyphs (apple, atom, banknote, barchart, beaker, book, briefcase, building, calculator, codesnippet, compass, file, globe, graduation, moonstar, musicnote, palette, scales, settings, smile). Folder art is layered: `custom-folder/back_cover`, `back_shadow`, `front_cover`, `front_highlight`, `front_shadow`.

**Folder record fields** (from Swift field names near the folder snapshot type): `parentID`, `rootFolderID`, `deleted`, `favourited`, `symbol`, `position`, `timestamp`, `version`; documents also carry `folderColor`.

**Empty states.** Library, folder ("This Folder is Empty"), favourites (with Any / Documents only / Folders only filters), shared, filtered ("No Results", "Reset Filters"), search.

## Inferred (not proven by code)

- The sidebar collapses by changing the split view's display mode; the exact animation and widths are unknown.
- Folder colour is either a palette entry or a custom RGB (`folderColorRgb`), chosen through the "rainbow" swatch.
- Folders are soft-deleted (`deleted`) and live in the Trash Bin with their contents.
- A folder's date in the grid tracks recent changes inside it (seen in the user's screenshot; mechanism unknown).

## Unknowns

- Any business logic (no decompiler): sorting tie-breaks, the trash retention period, how `position` is used.
- Drag-and-drop rules for moving items onto folders.

## What Folio implemented (independently)

Sidebar: full width ↔ icon rail, animated, remembered. Breadcrumb header. Filter (All / Notebooks / Folders). "New" menu (Notebook, Folder, Import). Grid ↔ list. Sort (Last modified, Name, Date created, Type; either direction). Select mode with bulk Favorite, Move and Trash. Star on every tile. Title chevron menu. Folder customisation (10 original colours plus a custom hue/shade picker; 26 Material icons). Move-to picker as a folder tree. Duplicate. Folder trash, restore and delete-forever, alongside the existing notebook trash. Favorites page. See `docs/FEATURE_STATUS.md`.
