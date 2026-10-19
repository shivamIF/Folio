# Google Drive sync: one-time setup (free)

Folio syncs notebooks through a **private folder in your own Google Drive** (the Drive "app data" folder). It's free: the Drive API costs nothing, the data uses your normal Drive storage (15 GB free), and the Google Cloud project needs **no billing account**.

Google only lets an app use Drive if the Drive account's owner registers it. So you do this once, in **your** Google account (about 10 minutes). Folio has no passwords, API keys or secrets inside: Google recognises the app by its package name and signing fingerprint.

## What you need
| | Value |
|---|---|
| Package name | `app.folio.notes` |
| SHA-1 signing fingerprint (debug builds from this Mac) | `CE:08:4D:2D:A4:3E:C8:1C:D7:C0:BF:CD:E4:8E:F7:E2:5A:8F:B3:94` |
| Scope | `https://www.googleapis.com/auth/drive.appdata` |

## Steps
1. **Create a project.** Open https://console.cloud.google.com/ and sign in with the Google account whose Drive you want to use. Choose **Select a project → New project**, name it `Folio`, and create it. If billing is offered, skip it; none is needed.
2. **Turn on the Drive API.** Go to **APIs & Services → Library**, search for **Google Drive API**, and click **Enable**.
3. **Set up the consent screen** (**Google Auth Platform**, also shown as **OAuth consent screen**):
   - App name `Folio`, with your email as the support and developer contact.
   - Audience: **External**.
   - **Data access → Add or remove scopes:** add `.../auth/drive.appdata` ("See, create, and delete its own configuration data in your Google Drive").
   - **Audience → Test users:** add your own Gmail address, plus any other account you'll sign in with.
4. **Register the Android app.** Go to **Credentials → Create credentials → OAuth client ID** and choose application type **Android**:
   - Package name: `app.folio.notes`
   - SHA-1: `CE:08:4D:2D:A4:3E:C8:1C:D7:C0:BF:CD:E4:8E:F7:E2:5A:8F:B3:94`
   - Create it. There's nothing to copy back into Folio.
5. **Link Folio on each device:** in Folio, open **Settings** (gear on the library screen) and tap the words **Auto sync**, then **Link Google Drive**. Pick your account and press **Allow**. The toggle turns on and the first sync starts.

Changes can take a few minutes to take effect at Google. If linking says "isn't set up" right after step 4, wait 5 minutes and try again.

## When it syncs
- **Your edits go out right after they're saved**, even while the notebook is still open.
- **With a notebook open**, Folio checks Drive's change feed every second, so another device's edit typically shows up **5–10 seconds** after it was written. Google Drive itself takes most of that time (about 2 s to save even a small file, 1–1.5 s to fetch one; measured 2026-10-19). Very large pages (several MB, e.g. some imported notes) take longer to send.
- **In the library**, Folio checks every 30 seconds, and right away when you open the app.
- A notebook that's **open in the editor** updates live: a page edited elsewhere is merged with what you're drawing (strokes, text and pictures are matched one by one, so both sides' additions stay). Only deleted pages and a page edited on two devices before either received the other's version wait until you close the notebook (the second version then becomes an extra page). A merged page's undo history starts over.
- **In the background:** when you leave Folio, one more sync runs, so your last edits go out even if Android closes the app. While Folio is closed, it syncs about every 15 minutes when there's a network. Android may space that out to save battery.

## Notes
- **Testing mode:** while the app is in *Testing*, Google may ask you to approve again every 7 days ("Sign in again" in the Drive panel). To stop that, you can publish the app (**Audience → Publish app**). Google asks for review only for sensitive scopes, so check what the console says for `drive.appdata` before relying on it.
- **Phone:** install the same debug APK (built on this Mac, so it has the same fingerprint) and link the same account. A future release build has a different fingerprint and needs a second Android client ID (step 4 again with that SHA-1).
- **Mac:** there's no Mac app yet. The synced data is ready for one.
- **What sync never does:**
  - drop an edit because of a delete elsewhere (the edit wins);
  - overwrite a page edited on two devices (the second version becomes an extra page right after it);
  - delete your Drive copy when you unlink.
