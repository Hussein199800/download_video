# سَيْل (Sayl) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Personal Arabic (RTL) Android app that downloads video/audio (single links and whole playlists) from any site yt-dlp supports, with in-app yt-dlp updates and signed self-updates from GitHub Releases.

**Architecture:** Capacitor 8 shell (vanilla JS + esbuild UI, same layout as the owner's `sakina` and `Car` repos). Pure JS modules decide *what* to download (yt-dlp argv, parsing, error text) and are unit-tested in Node; a Kotlin layer owns the queue (`JobStore`), runs jobs in a WorkManager foreground worker through `youtubedl-android`, and emits progress events to the UI.

**Tech Stack:** Capacitor 8.6, esbuild 0.25, Node 22 `node:test`, Playwright (preinstalled Chromium) for e2e, Kotlin, WorkManager 2.10, `io.github.junkfood02.youtubedl-android:{library,ffmpeg,aria2c}:0.18.1`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-29-sayl-downloader-design.md`

## Global Constraints
- App name «سَيْل», appId/package `com.sayl.downloader`, repo `Hussein199800/download_video`.
- minSdk 24, compileSdk/targetSdk 36, ABI splits `arm64-v8a` + `armeabi-v7a` (no universal APK); `android:extractNativeLibs="true"`.
- Arabic UI only, `dir="rtl"`, font IBM Plex Sans Arabic bundled via `@fontsource/ibm-plex-sans-arabic`.
- No analytics, no third-party SDKs beyond youtubedl-android. Network: target sites, `api.github.com` / `github.com` (updates), yt-dlp release (component update).
- Save to `Download/سيل/` and playlists to `Download/سيل/<playlist title>/` via MediaStore (API 29+) or direct file (API 24–28 with WRITE_EXTERNAL_STORAGE maxSdk 28).
- Version lives in `package.json`, `src/version.js`, `android/app/build.gradle` (`versionName`, `versionCode`); a test asserts they match. Start at `1.0.0` / code `1`.
- Signing secrets `SAYL_KEYSTORE_BASE64`, `SAYL_KEYSTORE_PASSWORD`, alias `sayl`; keystore never committed.
- Release assets named `sayl-<ver>-arm64-v8a.apk` and `sayl-<ver>-armeabi-v7a.apk`.
- About text verbatim: «تطوير: **حسين محمد علي الطويل**. اللهم اغفر له ولوالديه، واجعل هذا العمل صدقة جارية في ميزان حسناتهم.»
- Colour classes: video `c-sky`, audio `c-violet`, playlist `c-amber`, history `c-green`, errors `c-pink`, settings `c-slate`; primary `--pri` indigo.
- App update check every 12 h automatically; yt-dlp update check every 7 days automatically.
- Parallel downloads 1–3 (default 1); network retries 3.

## Review Focus
1. Shared text with words around the link ("شاهد هذا https://vt.tiktok.com/x/ 🔥") → the URL alone is extracted. Test in Task 2.
2. Playlist containing deleted/private entries (null title or `[Private video]`) → those entries are shown disabled and never enqueued. Test in Task 2.
3. Titles with `/ \ : * ? " < > |` or > 150 bytes → valid file name; output template uses `%(title).150B` and `--windows-filenames`. Test in Task 2.
4. App process killed mid-download → on next start, jobs left `running` become `queued` and resume; nothing stays stuck "running". Test in Task 4.
5. Same URL+format enqueued twice while still queued/running → second is ignored with a toast «موجود في الطابور». Test in Task 4.

---

### Task 1: Project scaffold, build, version check, CI

**Files:**
- Create: `package.json`, `capacitor.config.json`, `scripts/build.mjs`, `www/index.html`, `src/main.js` (placeholder render), `src/version.js`, `src/styles.css` (empty for now), `.gitignore`, `release-notes.md`, `tests/unit/version.test.mjs`, `.github/workflows/android.yml`
- Create via `npx cap add android`, then modify: `android/app/build.gradle`, `android/variables.gradle`, `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `src/version.js` → `export const VERSION = '1.0.0'; export const VERSION_CODE = 1;`; npm scripts `build`, `sync`, `serve`, `apk`, `release`, `test`, `test:e2e`, `test:android` (same shapes as `Car/package.json`).

- [ ] **Step 1: Write failing test** `tests/unit/version.test.mjs` — reads the three files and asserts `package.json.version === VERSION`, gradle `versionName "VERSION"` and `versionCode VERSION_CODE`.
- [ ] **Step 2:** `npm test` → FAIL (files missing).
- [ ] **Step 3:** Create files. `build.mjs` bundles `src/main.js` → `www/app.js` and `src/styles.css` → `www/app.css` with esbuild `target: ['chrome70']`, loader `woff2: 'file'`. `npx cap add android`. Gradle: deps above, `splits.abi { enable true; reset(); include 'arm64-v8a','armeabi-v7a'; universalApk false }`, `versionCode` per-ABI = `VERSION_CODE*10 + (arm64?2:1)`, signing config from env `SAYL_KEYSTORE`, `SAYL_KEYSTORE_PASSWORD`, `SAYL_KEY_ALIAS` (as in `Car`). Manifest: `extractNativeLibs=true`, INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, REQUEST_INSTALL_PACKAGES, WRITE_EXTERNAL_STORAGE maxSdkVersion 28, FileProvider `${applicationId}.fileprovider`, SEND text/plain intent filter on MainActivity.
- [ ] **Step 4:** `npm test` → PASS; install Android SDK locally (cmdline-tools, platform 36, build-tools 36) and run `cd android && ./gradlew assembleDebug` → BUILD SUCCESSFUL, two APKs.
- [ ] **Step 5:** Workflow = `Car/.github/workflows/android.yml` with taqati→sayl renames; release step uploads both ABI APKs renamed per Global Constraints. Commit `chore: scaffold Sayl Capacitor app`.

### Task 2: Pure JS core — links, formats, playlist, errors

**Files:**
- Create: `src/links.js`, `src/formats.js`, `src/errors.js`, `tests/unit/links.test.mjs`, `tests/unit/formats.test.mjs`, `tests/unit/errors.test.mjs`

**Interfaces:**
- Produces:
  - `extractUrl(text: string): string|null` — first `https?://` URL, trailing punctuation/emoji stripped.
  - `siteOf(url): {id: 'youtube'|'facebook'|'tiktok'|'instagram'|'x'|'other', name: string}`.
  - `parseInfo(json): {kind:'single', id, title, uploader, duration, thumbnail, url, heights:number[]} | {kind:'playlist', id, title, count, entries:[{id,title,url,duration,available:boolean}]}`.
  - `buildArgs(choice, settings): string[]` where `choice = {type:'video', height:number|'best'} | {type:'audio', format:'mp3'|'m4a', kbps?:128|192|320}`, `settings = {aria2:boolean, retries:number}`; never contains `-o`, `--exec`, `-P` (Kotlin adds output).
  - `OUTPUT_TEMPLATE = '%(title).150B [%(id)s].%(ext)s'`.
  - `classifyError(stderr: string): {code:'login'|'extract'|'network'|'unsupported'|'space'|'unknown', title:string, fix:string, action:'cookies'|'update'|'retry'|null}`.

- [ ] **Step 1: Failing tests** (exact cases):
  - `extractUrl('شاهد هذا https://vt.tiktok.com/ZS123/ 🔥')` → `'https://vt.tiktok.com/ZS123/'`; `extractUrl('لا رابط')` → `null`; `extractUrl('(https://youtu.be/abc).')` → `'https://youtu.be/abc'`.
  - `siteOf('https://m.youtube.com/watch?v=x').id === 'youtube'`, `youtu.be`, `fb.watch`→facebook, `vm.tiktok.com`→tiktok, `instagr.am`→instagram.
  - `buildArgs({type:'video',height:720},{aria2:false,retries:3})` contains `-f`, `'bv*[height<=720]+ba/b[height<=720]/b'`, `--merge-output-format`, `'mp4'`, `--retries`, `'3'`, `--windows-filenames`, `--no-playlist`; best → `'bv*+ba/b'`.
  - audio mp3 192 → `-x --audio-format mp3 --audio-quality 192K`; m4a → `-f 'ba[ext=m4a]/ba' -x --audio-format m4a` (no `--audio-quality`).
  - `aria2:true` adds `--downloader libaria2c.so`.
  - none of the outputs contain `-o`, `--exec`, `-P`.
  - `parseInfo` with fixture `tests/fixtures/playlist.json` (3 entries, one `title:'[Private video]'`, one `title:null`) → `count 3`, availability `[true,false,false]`; single fixture → `heights` sorted desc unique.
  - `classifyError('ERROR: ... Sign in to confirm you’re not a bot')` → login/cookies; `'Unable to extract'` → extract/update; `'Unsupported URL'` → unsupported; `'Errno 28'`/`'No space left'` → space; `'timed out'`/`'Network is unreachable'` → network/retry; else unknown.
- [ ] **Step 2:** `npm test` → FAIL.
- [ ] **Step 3:** Implement. Arabic titles/fixes copied from spec §6 table.
- [ ] **Step 4:** `npm test` → PASS.
- [ ] **Step 5:** Commit `feat: link, format and error core`.

### Task 3: Kotlin engine and argument guard

**Files:**
- Create: `android/app/src/main/java/com/sayl/downloader/Engine.kt`, `ArgGuard.kt`, `Progress.kt`, `android/app/src/test/java/com/sayl/downloader/ArgGuardTest.kt`, `ProgressTest.kt`

**Interfaces:**
- Produces:
  - `object ArgGuard { fun check(args: List<String>): String? }` — null if allowed; rejects `-o`, `--output`, `-P`, `--paths`, `--exec`, `--exec-before-download`, `--config-location`, `--batch-file`, `-a`.
  - `object Progress { fun parse(line: String): Pair<Long?, Long?> }` — (downloadedBytes?, speedBytesPerSec?) from yt-dlp `[download]  42.0% of ~ 10.50MiB at 1.20MiB/s` and aria2 `[#..  1.2MiB/10MiB(12%) ... DL:800KiB]` lines.
  - `object Engine { fun init(ctx); fun info(url: String, cookies: File?): String /*raw JSON*/; fun download(id: String, url: String, args: List<String>, outDir: File, cookies: File?, onProgress:(pct: Float, eta: Long, line: String)->Unit): File; fun cancel(id: String); fun update(ctx): String /*"DONE"|"ALREADY_UP_TO_DATE"*/; fun version(ctx): String? }`.
  - `info` runs `-J --flat-playlist --no-warnings`; `download` adds `-o <outDir>/OUTPUT_TEMPLATE`, `--print after_move:filepath`, and returns the printed path.
- [ ] **Step 1:** JUnit tests: ArgGuard rejects each listed flag and `--exec=foo` form, accepts Task 2's mp3 args; Progress parses the two sample lines to (≈11010048·0.42, 1258291) and (1258291, 819200).
- [ ] **Step 2:** `./gradlew testDebugUnitTest` → FAIL.
- [ ] **Step 3:** Implement on `YoutubeDL`, `FFmpeg`, `Aria2c` (`init` once, guarded by `@Volatile` flag).
- [ ] **Step 4:** tests PASS. **Step 5:** Commit `feat: native engine wrapper`.

### Task 4: Queue, worker, plugin bridge, share intent

**Files:**
- Create: `JobStore.kt`, `DownloadWorker.kt`, `SaylPlugin.kt`, `Saver.kt`, test `JobStoreTest.kt`
- Modify: `MainActivity.kt` (register plugin, forward SEND intent), `src/native.js` (create)

**Interfaces:**
- Consumes: `Engine`, `ArgGuard`, `Progress` (Task 3); args from `buildArgs` (Task 2).
- Produces:
  - `data class Job(id, url, title, thumb, site, kind /*video|audio*/, args: List<String>, folder: String? /*playlist subdir*/, state /*queued|running|done|failed|canceled*/, pct, speed, eta, error, path, createdAt, finishedAt)`; `JobStore(file: File)` with `add(jobs): List<Job> /*skips dup url+args among queued/running*/`, `next(): Job?`, `update(id){}`, `recoverInterrupted()`, `all()`, `remove(id)`, `clearFinished()`. JSON file `filesDir/jobs.json`, synchronized.
  - `DownloadWorker` — unique work `sayl-queue`, foreground notification channel `downloads` («التنزيلات»), runs up to `parallel` jobs, 3 attempts per job on `network` errors, `Saver.publish(ctx, file, folder, kind)` moves the result into `Download/سيل/[folder]/` and returns a content URI.
  - JS `native.js` exports `Sayl = { info(url), enqueue(jobs), list(), cancel(id), retry(id), remove(id, deleteFile), clearFinished(), open(id), share(id), engineVersion(), engineUpdate(), setConfig({parallel, aria2}), importCookies(), clearCookies(), takeSharedText(), onProgress(cb), onShared(cb) }`; when `Capacitor.isNativePlatform()` is false it uses an in-memory mock engine (used by e2e and `npm run serve`).
- [ ] **Step 1:** JUnit `JobStoreTest`: dup is skipped; `recoverInterrupted` turns `running`→`queued`; persists across new `JobStore(sameFile)`; `next()` returns oldest queued.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. Plugin emits `progress` `{id,pct,speed,eta,state}` at most 2×/s per job and `shared` `{text}` for SEND intents.
- [ ] **Step 4:** unit tests PASS; `assembleDebug` succeeds. **Step 5:** Commit `feat: persistent download queue and worker`.

### Task 5: Updates — app self-update and yt-dlp schedule

**Files:**
- Create: `src/update.js`, `Updater.kt` (port of `Car` Updater with `PREFIX = "https://github.com/Hussein199800/download_video/releases/download/"`, message «الملف المحمّل ليس تطبيق سيل», file `sayl-update.apk`), `EngineUpdateWorker.kt`, tests `tests/unit/update.test.mjs`, `UpdateRulesTest.kt`

**Interfaces:**
- Produces: `fetchLatest(fetchFn, abi): {version, notes, url, size}|null` choosing asset `sayl-<ver>-<abi>.apk`; `isNewer(a,b)`; `shouldCheck(lastAt:number, now:number, hours:number): boolean`. Native `Sayl.abi()` returns `Build.SUPPORTED_ABIS[0]`. `EngineUpdateWorker` periodic 7 days, network-connected constraint.
- [ ] **Step 1:** Tests: asset picked by abi; debug assets ignored; foreign URL rejected; `isNewer('1.2.0','1.10.0') === false`; `shouldCheck` true after 12 h. Kotlin: `UpdateRules.reject` cases (wrong pkg, older code, sig mismatch, ok).
- [ ] **Step 2–4:** FAIL → implement → PASS. **Step 5:** Commit `feat: app and engine updates`.

### Task 6: Design system and app shell

**Files:**
- Create: `src/styles.css`, `src/icons.js`, `src/ui.js` (`h(tag, attrs, ...children)`, `toast(msg)`, `sheet(content)`, `card({color, icon, title, sub, onClick})`), `src/store.js`
- Modify: `src/main.js`, `www/index.html`

**Interfaces:**
- Produces: `store.js` → `getSettings(): Settings`, `setSettings(patch)`, defaults `{theme:'auto', fontScale:1, video:'best', audio:'mp3', kbps:192, parallel:1, aria2:true, clipboard:true}`; `main.js` → `go(tab: 'home'|'jobs'|'history'|'guide'|'more', sub?: string)`; bottom tab bar with 5 tabs labelled «الرئيسية · التنزيلات · السجل · الشرح · المزيد».
- [ ] **Step 1:** `tests/unit/store.test.mjs`: defaults returned on empty/corrupt storage; `parallel` clamped to 1–3; `fontScale` clamped 0.85–1.3.
- [ ] **Step 2–4:** FAIL → implement (tokens from `Car/src/styles.css` dark/light + `c-*` gradients, card style, safe-area insets, `data-theme` with auto via `prefers-color-scheme`) → PASS.
- [ ] **Step 5:** Commit `feat: design system and tab shell`.

### Task 7: Home, preview and playlist screens

**Files:** Create `src/views/home.js`, `src/views/playlist.js`

**Interfaces:** Consumes `extractUrl`, `siteOf`, `parseInfo`, `buildArgs`, `classifyError`, `Sayl.*`, `getSettings`.
- Home: URL field + «لصق» button; clipboard suggestion card when `clipboard` setting on and clipboard holds a URL not already queued; three type cards (video/audio/playlist) with a one-line explanation each; «آخر التنزيلات» card (last 3).
- Preview sheet: thumbnail, title, uploader, duration, segmented choices (video heights from `heights` ∩ [2160,1440,1080,720,480,360] + «أفضل»; audio MP3 128/192/320 or M4A), «نزّل» button → `enqueue` → toast «أُضيف إلى الطابور».
- Playlist page: title + count, «تحديد الكل/إلغاء الكل», checkbox rows (unavailable rows disabled with «غير متاح»), one choice for all, button «أضف N إلى الطابور» with `folder = playlist title`.
- Errors from `info` shown as a `c-pink` card with `title`, `fix`, and action button (update engine / import cookies / retry).
- [ ] **Step 1:** e2e (Task 9 harness) cases written now in `tests/e2e/run.cjs`: paste single URL → preview → enqueue appears in jobs; paste playlist → 2 of 3 selectable → «أضف 2 إلى الطابور».
- [ ] **Step 2–4:** FAIL → implement → PASS. **Step 5:** Commit `feat: home, preview and playlist`.

### Task 8: Downloads, history, guide and more screens

**Files:** Create `src/views/jobs.js`, `src/views/history.js`, `src/views/guide.js`, `src/views/more.js`, `src/changelog.js`

- Jobs: card per active job (progress bar coloured by kind, pct, speed «1.2 م.ب/ث», ETA «متبقٍ 2:10», state), cancel/retry, «مسح المكتمل».
- History: search box, filter chips by site and kind, open/share/delete (confirm sheet: «من السجل فقط» / «مع الملف»).
- Guide: cards — كيف أنزّل؟، المشاركة من التطبيقات، قوائم التشغيل، الصيغ والدقات، عند ظهور خطأ (one item per spec §6 row), cookies شرح.
- More: settings groups (المظهر، التنزيل، الحافظة) each option with explanation line; «مكوّن التنزيل» row (version + update button + last check time); cookies import/clear; diagnostics (last 50 errors, redacted `cookie|token|sig|signature|key` query params, copy button); ما الجديد (from `changelog.js`); التحقق من التحديثات (download progress → install); مشاركة التطبيق (releases URL); حول التطبيق (icon, name, version, About text verbatim).
- [ ] **Step 1:** unit test `tests/unit/redact.test.mjs` for `redact(url)` in `src/errors.js`: `?token=abc&v=1` → `?token=[مخفي]&v=1`. e2e cases: settings change persists after reload; About contains the exact developer sentence.
- [ ] **Step 2–4:** FAIL → implement → PASS. **Step 5:** Commit `feat: jobs, history, guide and more screens`.

### Task 9: E2E harness, docs, release

**Files:** Create `tests/e2e/run.cjs` (Playwright, serves `www/` on a free port, mock engine), `README.md` (Arabic, sections like `sakina`/`Car`: المزايا، التحديثات، البنية التقنية، الأوامر، بناء APK على GitHub، إضافة مفتاح التوقيع step-by-step), `CLAUDE.md` (how to release, version files, never commit keystore), `LICENSE` note (personal; youtubedl-android GPL-3.0 → app GPL-3.0).

- [ ] **Step 1:** `npm test && npm run test:e2e` → all PASS; `cd android && ./gradlew testReleaseUnitTest assembleDebug` → PASS.
- [ ] **Step 2:** Push; CI green on branch.
- [ ] **Step 3:** Commit `docs: README and contributor notes`.
