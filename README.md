# PalmVellum

> *Some things deserve to be written down. Not all of them deserve to be online.*
> *有些事，還是想親手寫下來。* / *有些东西，值得写下来。有些事情，也不宜上线。*
>
> *A slow tool for a fast world.*

[繁體中文](#palmvellum-繁體中文) · [简体中文](#palmvellum-简体中文) · [日本語](#palmvellum-日本語) · [English](#english)

<table>
  <tr>
    <td width="50%"><img src="docs/photos/palm-iiie.jpg" alt="A 1999 Palm IIIe running its classic launcher — Date Book, Address, To Do, Memo Pad"></td>
    <td width="50%"><img src="docs/photos/sony-clie.jpg" alt="A Sony Clié PEG-SL10 with a Memory Stick inserted, showing its launcher"></td>
  </tr>
</table>

<sub>Reference hardware in hand: a 1999 Palm IIIe and a Sony Clié PEG-SL10 — the AAA-battery devices PalmVellum is built around. 繁中／简中：PalmVellum 圍繞嘅 AAA 電池機 — 1999 年 Palm IIIe 同 Sony Clié PEG-SL10。</sub>

<sub>Web: <a href="https://tatliving.dev/palmvellum/">tatliving.dev/palmvellum</a> · Organizers: <a href="https://tatliving.dev/palmvellum/app">tatliving.dev/palmvellum/app</a></sub>

---

## English

**PalmVellum is an open-source platform that brings cloud sync and AI assistance to native Palm Pilot applications from the years 1996 to 2003, while keeping the Palm experience fundamentally intact.**

You do not use a Palm because it can do more than a modern smartphone. You use it because it does just enough. Two AAA batteries. A monochrome screen. No constantly connected network. No camera. No infinite feed. No red badges waiting for you to respond. You pick it up, write something down, and put it back — and the thought stays there. It does not instantly become a notification. It does not need to be uploaded everywhere. It does not need to become urgent just because it exists. PalmVellum works in the background only when you ask it to. It can help organise natural language, recognise handwritten notes, summarise research, or create calendar events and tasks. Your Palm remains quiet. The platform begins working only when you invite it to.

| 2× AAA | 160×160 | 0 | 1996–2003 |
|---|---|---|---|
| power for months of ordinary life | enough space for what matters | no always-on wireless | the era that shaped our reference hardware |

### What does retro computing mean here?

It does not mean low-spec hardware. It does not mean treating the past as an aesthetic filter. For us, retro computing is a deliberate choice — for fewer pixels, fewer alerts, fewer reasons to reach for the device again. It is not about rejecting technology; it is about deciding which technology deserves a place in your life. PalmVellum keeps AI and cloud services where they are genuinely useful: turning natural language into calendar events and tasks, converting handwritten notes and sketches into readable records, organising websites and research materials, and helping structure ideas, plans, and summaries. Beyond that, it should remain quiet. We do not build user profiles. We do not run ads. We do not turn your life into material for recommendation engines. What you write should remain yours.

### The PalmVellum philosophy

1. **Palm Is Where Trust Begins.** Palm devices do not maintain an always-on wireless connection. Important information can remain on the device, and whether you sync anything to the cloud is always your decision. Not everything needs to be uploaded immediately. Not every thought needs to leave your hands.
2. **Retro Is a Deliberate Rhythm.** A Palm IIIe made in 1999 can still work today on two AAA batteries. It reminds us that not every useful tool needs to disappear just because it is old. Some things are worth keeping not because they are nostalgic, but because they still do their job with quiet honesty. We respect the devices that are still sitting in drawers, still waiting, still capable of being useful.
3. **Infrastructure Should Belong to the Community.** PalmVellum's toolchain, sync utilities, data structures, HotSync engine, and conduit are released on GitHub under the Apache 2.0 License. This is not an attempt to bring old hardware into another closed platform — it is an attempt to make the system understandable, inspectable, modifiable, and sustainable for anyone who wants to continue it. A tool worth keeping should not belong to one company alone.
4. **Bring Your Own Key, or Use Platform Credits.** You can use your own OpenAI, Anthropic, or Google Gemini API key. You can also purchase PalmVellum platform credits through Airwallex. Both methods support the same core functions. Neither is required.
5. **No Feed, No Tracking, No Marketing Noise.** You write the record. You own the record. PalmVellum does not aggregate, sell, recommend, or profile your personal life. Not everyone needs to become content. Not every moment needs to be seen.
6. **Palm Should Remain Palm.** PalmVellum does not install custom firmware or change the core nature of Palm devices. The platform works through existing HotSync workflows and native Palm OS applications. Current interface and testing priorities focus on Palm devices powered by AAA batteries; other Palm OS devices may also work, but are not guaranteed to be fully tested.

### Platform features

Each native Palm OS application has a corresponding space inside PalmVellum Organizers. Your records can exist across your Palm, your computer, and optional cloud copies. Multiple Palm devices in the same household can also read and write to the same shared records.

| Palm app | What it does | AI assist |
|---|---|---|
| **Date Book** · Calendar | Calendar grid; create and edit events manually | Enter natural language directly — "Friday at 3pm, coffee with May" — and AI turns it into a structured calendar event |
| **To Do List** · Tasks | Tasks with priorities and due dates | Prefix an item with `(AI)` to send an instruction to the AI system; the result can be written back into Memo |
| **Memo Pad** · Notes | Two-way note syncing; upload PDF / DOCX / image | A Memo beginning with `(AI)` can ask the system to create events, tasks, or summaries. Uploaded files are organised into Palm-ready Memos |
| **Address Book** · Contacts | Contact info, categories, and extended fields | — |
| **Note Pad** · Handwriting & sketches | Handwritten notes and sketches synced from your Palm | Vision AI extracts handwriting and generates image descriptions |
| **Mail** · Reading & research | Daily digest inbox | AI-generated summaries from selected websites; Topic Mode searches the web and produces a ~10–20 minute research article with citations and a selectable reading language |
| **Expense** · Personal expenses | Multi-currency log with category totals | — |

### What we hope you will keep

PalmVellum is not only meant to help you organise data — it is meant to help you keep a personal archive that grows slowly with you: calendar entries, contacts, memos, handwritten notes, research summaries, expense records. Not all of them need to be important. Some may simply be a thought from an ordinary afternoon, a place you want to visit again, or a small task you finally completed after putting it off for weeks. But when these things are kept quietly, life becomes more than a stream of screens you have already scrolled past. Your Palm keeps your information in a place that does not rush you, and PalmVellum can keep optional cloud copies and provide AI assistance when you decide to ask for it. **Write a little slower. Keep things a little longer. Let your life exist somewhere beyond notifications.**

### Download and sync your Palm

Let your Palm become part of your life again. PalmVellum lets you sync your physical Palm device with your digital records. Your Palm remains unchanged. You can keep your data on the device, create backups on your computer, and sync when you choose. PalmVellum is free and open-source software. Please back up important data before use.

#### PalmVellum for Mac (macOS)

<img src="docs/screenshots/sync-app.png" alt="PalmVellum desktop sync app — login status, settings, and a live sync log" width="300" align="right">

Connect your Sony Clié through USB, select **Sync via USB** in the app, then press the **HotSync** button on your Palm. The app can sync Memo Pad, To Do, Date Book, Address, and Mail. You can also drag and drop `.prc` or `.pdb` files into the installation area to install applications onto your Palm. Memory Stick support is also available.

- **Passwordless login** — sign in with your platform account via an emailed code; the session is kept in the macOS Keychain. Every sync is scoped to you by Postgres RLS.
- **Insert-and-go** — the app detects the card, syncs **Memo Pad**, **To Do**, **Date Book**, **Address** and **Mail**, and ejects it for you. It can wait for any `(AI)` memo answers so they come back in the same sync.
- **Restore on the Palm** — put the card back and use MS Backup's *restore from card*. The CLIE may do a brief **soft reset** on restore — this is expected and harmless.

> ⚠️ USB syncing has currently been tested mainly with Sony Clié devices. Other USB Palm models and SD-card workflows may work, but have not yet been fully verified. A restore point is created before any data is written back to the device. This app is not currently signed — for the first launch, **right-click the app icon and choose "Open"**.

**Download:** [Releases → latest `.dmg`](https://github.com/palmvellum/palmvellum/releases/latest). Or build it yourself from [`packages/mac-daemon/`](packages/mac-daemon/) with `bash packaging/build-app.sh` → `dist/PalmVellum-<version>.dmg`. See [`docs/USAGE.md`](docs/USAGE.md) for the full guide.

<br clear="all">

#### Palm Organizers — Android (native)

**Palm Organizers** is a native Android personal organiser designed with a local-first approach, with optional cloud sync and AI features — Kotlin + Jetpack Compose, Room on-device. Source: [`packages/android-native/`](packages/android-native/). *(The earlier Capacitor wrapper under `packages/android/` is superseded.)* Two versions are currently available and can be installed side by side. The app is currently distributed as an APK and is not yet available on Google Play.

- **Standard Edition** — for standard Android phones with a portrait interface.
- **Cosmo Edition** — designed for the **Planet Cosmo Communicator**, with a landscape layout and physical QWERTY keyboard support: landscape-locked for the 2160×1080 main display, a left icon rail, two-pane master/detail layouts, and inline title-bar filters/search. UI spec: [`docs/cosmo-ui-spec.md`](docs/cosmo-ui-spec.md). On a Cosmo stuck on firmware V19, see the companion [**Cosmo V19→V23 upgrade & standby-battery-fix guide**](https://github.com/tathome2025/cosmo-standby-battery-fix).

**USB HotSync (Cosmo Edition).** Dock a vintage Palm/CLIE to the Cosmo's USB-C port (via a USB-OTG adapter), press HotSync on the Palm, and the app syncs Memo Pad, To Do, Date Book, Address and Mail straight to your PalmVellum cloud — no desktop needed. It can also **install `.prc`/`.pdb` files** onto the Palm, like the classic Palm Install Tool. A from-scratch Kotlin HotSync stack (NetSync + DLP, driven over the cable) powers it; verified on a Sony CLIE.

> ⚠️ After downloading, allow installation from unknown sources, then open the APK to install. These are sideload (debug-signed) APKs, not Play-reviewed. **No warranty of any kind — use at your own risk.**

**Download:** [Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8) — or build from `packages/android-native/` with `./gradlew :app:assembleStandardDebug` / `:app:assembleCosmoDebug`.

##### Screenshots — Cosmo Edition

| | |
|---|---|
| ![Launcher](docs/screenshots/cosmo-launcher.png) | ![Date Book](docs/screenshots/cosmo-datebook.png) |
| ![Address](docs/screenshots/cosmo-address.png) | ![Memo editor](docs/screenshots/cosmo-editor.png) |

_Captured on a real Cosmo Communicator (2160×1080): the Applications launcher, Date Book month view, an Address contact, and the Memo Pad AI editor._

### Project architecture

PalmVellum is released under the Apache 2.0 License on GitHub. The project uses a pnpm + Go monorepo structure with several focused packages.

```
packages/
├── pwa/             SvelteKit 2 + Svelte 5 web app, backed by Supabase
│                    (Organizers dashboard at tatliving.dev/palmvellum/app).
│                    Modules: Date Book, To Do, Address, Memo, Note Pad,
│                    Mail, Expense.  — v0.5 live
├── palm-engine/     Shared Go module: .pdb codec, UTF-8⇄Big5 charset,
│                    AppleDouble-safe card I/O, Supabase client, and the
│                    card↔cloud sync engine (Memo + To Do)
├── sync-cli/        Go CLI — vellum-sync: bridges PalmOS Memo Pad / To Do
│                    DBs with Supabase. Commands: push, pull, sync.  — v0.5 live
├── mac-daemon/      PalmVellum.app — macOS window app: passwordless login,
│                    card auto-detect + sync + eject (Go + modernc.org/sqlite,
│                    no CGO)
└── android-native/  Native Android Palm Organizers (Kotlin + Compose);
                     Standard + Cosmo editions
supabase/
├── migrations/      SQL schema (records, events, mail_sources, devices,
│                    Vault BYOK, sketch storage bucket, agent triggers)
└── functions/       Deno Edge Functions (process-ai-queue, ai-agent,
                     process-event-draft, process-sketch, fetch-mail-source,
                     summarize-upload)
docs/                Project docs: hardware-compatibility (reference
                     target list + buying tips)
```

#### Quick start (development)

```sh
pnpm install
pnpm --filter @palmvellum/pwa dev    # http://localhost:5173
pnpm --filter @palmvellum/pwa build  # static build into packages/pwa/build/
```

See `packages/sync-cli/.env.example` for the env vars the CLI expects.

### Reference hardware

PalmVellum currently focuses on Palm devices powered by AAA batteries. Interface design and sync testing are based primarily on this family of devices. The platform works through HotSync, so Palm OS devices outside this list may also be compatible, though they are not guaranteed to have been fully tested. Reference hardware in hand: a 1999 Palm IIIe ★ and a Sony Clié PEG-SL10 ★ (the only Sony Clié in the reference range that uses AAA batteries and includes a 320×320 high-resolution monochrome display). See [`docs/`](docs/) for the full compatibility list and buying tips.

### Join the project

PalmVellum is still before version 1.0. There is still much to test, translate, refine, and rebuild. We are looking for hardware testers; Traditional Chinese, Simplified Chinese, Japanese, Korean, Russian, and Spanish translators; logo and visual-design collaborators; and documentation writers and technical contributors. This is not a project trying to recreate the past exactly as it was. It is a project asking whether the tools of the future can be quieter, easier to understand, and more willing to return control to the people using them.

PalmVellum is built by one person in Hong Kong, during evenings and weekends — no company, no sponsor, no venture funding. If it means something to you, [supporting the research](https://tatliving.dev/palmvellum/) helps fund another evening of work.

---

## PalmVellum 繁體中文

**PalmVellum 是一個開源平台，為 1996 至 2003 年間的 Palm Pilot 原生應用程式提供 AI 協助與雲端同步，同時維持 Palm 裝置原有的使用方式。**

你使用 Palm，不是因為它能做得比今天的手機更多。恰恰相反，是因為它做得剛剛好。兩顆 AAA 電池。一塊單色螢幕。沒有永遠亮著的網路、沒有相機、沒有無限滑動，也沒有一個個等著你回應的紅點。你拿起它，記下一件事，然後放回口袋、書桌或床邊。事情會留在那裡——不會立刻變成提醒、不會被推送到更多地方，也不必馬上被處理。PalmVellum 所做的，是在你主動需要時，協助處理雲端上的副本：整理自然語言、辨識手寫內容、彙整研究資料，或建立行程與待辦事項。Palm 保持安靜；平台則在你呼叫它的時候，才開始工作。

| 2× AAA | 160×160 | 0 | 1996–2003 |
|---|---|---|---|
| 日常可取得的電力來源 | 只留下足夠清楚的資訊 | 沒有持續連線或背景推播 | 參考硬體所處的年代 |

### 在這裡，Retro Computing 是甚麼意思？

不是低規格，也不是把過去當成濾鏡。對我們來說，**復古運算是一種有意識的選擇**：選擇少一點像素、少一點通知、少一點讓人忍不住再次拿起裝置的理由。不是拒絕科技，而是重新決定哪些科技值得進入生活，哪些不必。PalmVellum 將 AI 與雲端保留在真正有用的地方——將自然語言整理成行程與待辦、將手寫筆記與塗鴉轉為可閱讀內容、彙整網站與研究資料、協助建立事件與摘要——除此之外，它應該保持安靜。我們不建立使用者側寫、不放置廣告、不把你的生活變成可被販售或推薦的資料。你寫下的紀錄，應該屬於你。

### 理念

1. **Palm 是信任的起點。** Palm 裝置本身沒有常駐無線連線。重要資料可以保留在裝置內；是否同步至雲端，始終由你決定。不是所有事情都要立刻上傳，也不是所有記錄都必須離開你的手中。
2. **復古，是一種有意識的節奏。** 一台仍可正常使用的 Palm IIIe，在 1999 年被製造、被購買，如今仍能依靠兩顆 AAA 電池繼續工作。它提醒我們：不是每一件工具，都必須很快被淘汰。有些東西之所以值得留下，不只是因為舊，而是因為它仍然忠實地完成它原本的工作。我們尊重那些還在抽屜裡、仍然可以使用的物件。
3. **基礎設施應屬於社群。** PalmVellum 的工具鏈、同步程式、資料結構、HotSync 引擎與同步 conduit，皆以 Apache 2.0 授權發布於 GitHub。這不是把舊設備帶進另一個封閉服務，而是讓任何有興趣的人，都能理解、檢視、修改與延續它。一個工具若真的值得被留下，它不應只屬於某一間公司。
4. **使用自己的金鑰，或使用平台點數。** 你可以使用自己的 OpenAI、Anthropic 或 Google Gemini API 金鑰；也可以透過 Airwallex 購買平台點數。兩種方式皆可完整使用對應功能，不強制綁定任何一種使用方式。
5. **沒有社群牆，沒有追蹤，沒有行銷轟炸。** 你寫下紀錄，你擁有紀錄。PalmVellum 不彙整、不販售、不推薦，也不會把你的生活變成一張等待被分析的報表。不是每一個人，都需要成為內容；不是每一段生活，都需要被看見。
6. **Palm 仍然是 Palm。** PalmVellum 不會推送自訂韌體，也不會改寫 Palm 的使用本質。平台透過既有的 HotSync 機制，與既有的 Palm OS 應用程式進行同步。目前的參考目標與介面設計，主要針對使用 AAA 電池的 Palm 系列裝置；其他 Palm OS 裝置同樣可以使用相關同步功能，但不保證已完成相容性測試。

### 平台功能

每一個原生 Palm OS 應用程式，都會在 PalmVellum 的 Organizers 後台中有對應介面。資料可同時存在於 Palm、電腦與雲端副本中；同一個家庭中的多台 Palm，也可以讀取與寫入相同紀錄。

| Palm 原生 app | 說明 | AI 協助 |
|---|---|---|
| **Date Book｜行事曆** | 可手動新增與編輯事件 | 可直接輸入自然語言，例如「這週五下午三點，和 May 在咖啡店見面。」AI 可將內容解析為結構化行程 |
| **To Do List｜待辦事項** | 建立附有優先順序與截止日期的待辦事項 | 在內容前加上 `(AI)`，系統可執行指令，並將結果寫回 Memo |
| **Memo Pad｜備忘錄** | 支援雙向同步筆記；可上傳 PDF、DOCX 或圖片 | 以 `(AI)` 開頭的 Memo，可觸發 AI 協助建立行程、待辦事項並加入摘要；上傳檔案由 AI 整理為 Memo |
| **Address Book｜聯絡人** | 管理聯絡人資料、分類與延伸欄位 | — |
| **Note Pad｜手寫筆記與塗鴉** | 從 Palm 傳來的手寫筆記與塗鴉 | 透過 Vision AI 轉錄手寫文字，並產生圖像描述 |
| **Mail｜閱讀與研究摘要** | 每日 digest 收件箱 | 可依指定網站來源建立 AI 摘要；主題模式由 AI 搜尋網路並撰寫約 10 至 20 分鐘可讀完的研究文章，附引用來源並支援選擇閱讀語言 |
| **Expense｜支出紀錄** | 支援多幣別記帳、分類與統計 | — |

### 想慢慢累積的，不只是資料

PalmVellum 希望幫你留下的，是一份可以慢慢長大的個人紀錄：行事曆、聯絡人、備忘錄、手寫筆記、研究摘要、支出紀錄。它們不必都很重要——有些只是某天下午想到的一句話，一間想再去的店，一件拖了很久卻終於完成的小事。但當它們被安靜地留存下來，日子就不再只是一連串被滑過的畫面。Palm 用兩顆 AAA 電池，把資料留在一個不會催促你的地方；平台則保存雲端副本，並在你需要時提供 AI 協助。**慢一點記錄。久一點保存。讓生活不只存在於通知裡。**

### 下載並同步你的 Palm

讓你的 Palm，重新成為生活的一部分。PalmVellum 可將實體 Palm 裝置與雲端資料同步。Palm 本身不需修改；你可以在裝置上保留備份、在電腦端同步資料，並於需要時還原。本專案為免費開源軟體，不提供保固，使用前請自行備份重要資料。

**PalmVellum for Mac。** 使用 USB 連接 Sony Clié 後，於應用程式中點選「透過 USB 同步」，再按下 Palm 上的 HotSync 按鈕。系統可同步 Memo Pad、To Do、Date Book、Address、Mail。也可直接拖放 `.prc` 或 `.pdb` 檔案至安裝區，將應用程式安裝至裝置。另提供 Memory Stick 卡片路徑支援。

> ⚠️ 目前僅針對 Sony Clié 的 USB 同步進行測試。其他 USB Palm 與 SD 卡裝置尚未完成驗證。每次寫回裝置前，系統會建立還原點。此 App 尚未簽署，首次開啟時請在 App 圖示上按右鍵，選擇「打開」。

**下載：**[Releases → 最新 `.dmg`](https://github.com/palmvellum/palmvellum/releases/latest)。使用說明見 [`docs/USAGE.md`](docs/USAGE.md)。

**Palm Organizers（Android，原生）。** 可原生運行於 Android 的個人整理工具，採本機優先設計，並提供選用的雲端同步與 AI 功能。源碼見 [`packages/android-native/`](packages/android-native/)。目前提供兩個可並存安裝的版本，以 APK 形式發布，尚未上架 Google Play：

- **標準版** — 適用一般 Android 手機，直向操作介面。
- **Cosmo 版** — 適用 **Planet Cosmo Communicator**，支援橫向介面（2160×1080）與實體 QWERTY 鍵盤；左側圖示 rail、兩欄 master/detail、標題列 inline 篩選/搜尋。UI 規格見 [`docs/cosmo-ui-spec.md`](docs/cosmo-ui-spec.md)。部 Cosmo 仍在 firmware V19？另見 [**Cosmo V19→V23 升級＋待機省電指南**](https://github.com/tathome2025/cosmo-standby-battery-fix)。

**USB HotSync（Cosmo 版）。** 用 USB-OTG 線將舊 Palm/CLIE 駁上 Cosmo 嘅 USB-C 口，喺 Palm 撳 HotSync，app 就會將 Memo Pad、To Do、Date Book、聯絡人同 Mail 直接同 PalmVellum 雲端同步 — 唔使電腦。仲可以將 `.prc`/`.pdb` 檔安裝落 Palm。背後係由零用 Kotlin 寫嘅 HotSync 協議堆疊（NetSync + DLP），喺 Sony CLIE 真機驗證過。

> ⚠️ 下載後請允許「安裝未知來源 App」，再開啟 APK 完成安裝。呢啲係 sideload（debug 簽署）APK，未經 Play 審查。**冇任何保養，使用風險自負。**

**下載：**[Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8)。

開發說明、專案架構（pnpm + Go monorepo）與參考硬體清單，見上方 [English](#english) 章節與 [`docs/`](docs/)。

### 一起參與

PalmVellum 仍在 1.0 之前。它還有很多地方需要被測試、被翻譯、被修正。目前正在尋找：硬體測試者；繁體中文、簡體中文、日文、韓文、俄文、西班牙文翻譯協作者；Logo 與視覺設計協作者；文件撰寫者與技術貢獻者。這不是一個只想把過去復刻一次的專案，而是一個想重新討論：未來的工具，能不能更少打擾、更容易理解，也更願意把主導權交還給使用者。

PalmVellum 由一位在香港生活的人，利用晚上與週末的時間，一點一點研究、設計與製作——沒有公司、沒有贊助商、沒有創投資金。如果它對你有意義，[支持這份研究](https://tatliving.dev/palmvellum/)能讓它多走一個晚上。

---

## PalmVellum 简体中文

**PalmVellum 是一个开源平台，为 1996–2003 年的 Palm Pilot 原生应用程序加上 AI 助理及云端同步 — 而 Palm 本身完全不用改动。**

你在 Palm 上写东西，是因为它很安静。两节 AAA 电池。160×160 单色屏幕。没有常开的无线电。没有相机。没有无限滚动。你拿起它、写下东西、放回口袋。平台默默看着云端那份副本，需要时才出手相助。

| 2× AAA | 160×160 | 0 | 1996–2003 |
|---|---|---|---|
| 便利店买到的电池 | 单色屏幕 | 无线电模组 | 参考硬件所处的年代 |

### 「retro computing（复古计算）」在这里的意思

不是质量差。不是为怀旧而怀旧。**retro computing（复古计算）＝刻意用旧机、刻意有限制。** 更少像素、更少通知、更少分心拿起手机的借口。平台只在真正有用的位置引入 AI 和云端 — 自然语言输入解析、手写涂鸦转录、每日研究摘要、agentic 事项/任务创建 — 然后止步于此。我们不会建立你的 profile。我们不展示广告。根本没有所谓 profile。

### 宣言

1. **Palm 硬件是信任根。** 没有无线电。它不会泄露。所有重要的东西都可以留在设备上；你 sync 到云端的内容由你决定。
2. **复古计算是刻意设计。** 2026 年仍在运作的 Palm IIIe 是 1999 年造的，买一次就用一辈子，每年只需要两节 AAA 电池。我们尊重你抽屉里已有的东西。
3. **社区拥有基础。** Toolchain、daemon、schema、HotSync engine、sync conduits — 全部 Apache 2.0 放在 GitHub。
4. **BYOK 或平台点数 — 由你选择。** 自备 OpenAI、Anthropic 或 Google Gemini 密钥，平台不会收费。或者通过 Airwallex 购买平台点数。两个方案同样是一等公民，没有任何一个是必选的。
5. **无社交、无分析、无 email 营销。** 你写的记录属于你。我们不会聚合、出售、或推荐。
6. **Palm 仍然是 Palm。** 我们永远不会推送自定义固件。平台只跟现有的 HotSync 协议及 Palm OS 应用程序沟通。我们的参考目标及设计重点是 AAA 电池家族（1996–2003）— 那是我们相信的、会亲自测试的设备。但平台本身只说 HotSync，所以其他不在我们目标内的 Palm OS 设备同样可以使用，只是我们不会替它们做测试。

### 平台做什么

每个 Palm OS 原生应用程序，在平台「Organizers」仪表板里都有对应界面。两边共享同一份数据。同一个家庭内多台 Palm 读写同一组记录。

| Palm 原生 app | 说明 | AI 助理 |
|---|---|---|
| **日历 Date Book** | 可手动新增/编辑事件 | 贴入任何 rough text — 例如「星期五下午 3 点和 May 喝咖啡」— AI 会解析成结构化事件 |
| **待办 To Do List** | 支持优先级及到期日 | 在前面加上 `(AI)`，agent 会执行该 prompt 并把结果写回 Memo |
| **备忘 Memo Pad** | 备忘双向同步；可上传 PDF、DOCX 或图片 | 以 `(AI)` 开头的备忘会触发 agent，创建事件/任务并附加摘要；上传文件 AI 替你写成摘要备忘 |
| **通讯录 Address Book** | 丰富字段及分类的联系人 | — |
| **涂鸦 Note Pad** | 来自 Palm 的涂鸦 | 视觉 AI 替你转录手写文字并描述图画 |
| **邮件 Mail** | 逐个来源的 AI 网页摘要 | 或主题模式 — AI 通过网络搜索，以你指定的语言撰写一篇引用来源的 10–20 分钟研究文章 |
| **开支 Expense** | 多币种记录，含分类总计 | — |

### 目标

帮你慢慢累积一份刻意、深思熟虑的个人记录 — 日历、联系人、备忘、涂鸦、研究摘要、开支 — 同时不会被自己的工具打断。Palm 用 AAA 电池作冷存。平台保存云端副本，需要时跑 AI 助理。

### 下载并同步你的 Palm

用这个云端同步真正的 Palm —— 通过 USB HotSync，或使用记忆棒存储卡。Palm 本身不会改动。免费、开源，**不提供任何保修 —— 风险自负**。使用前请备份重要数据。

**PalmVellum for Mac。** 用 USB 连接 Sony Clié，点一下 **Sync over USB**，再按 Palm 上的 HotSync 键 —— 即把 Memo Pad、To Do、Date Book、Address 及 Mail 与你的云端同步。拖放 `.prc`/`.pdb` 文件即可把程序安装到设备。也保留记忆棒存储卡同步方式。

> ⚠️ 仅在 Sony Clié 通过 USB 测试。其他 USB Palm 及 SD 卡设备尚未验证。每次写回前都会先保存还原点。未签名 —— 首次启动请右键 → 打开。

**下载：**[Releases → 最新 `.dmg`](https://github.com/palmvellum/palmvellum/releases/latest)。

**Palm Organizers（Android，原生）。** 在 Android 原生运行的 organizer（本地优先 + 可选云端同步及 AI）。源码见 [`packages/android-native/`](packages/android-native/)。两个版本可并存安装，以 sideload APK 发布 —— 不在 Play Store：

- **Standard** — 任何手机、竖向。
- **Cosmo 版** — Planet Cosmo Communicator（横向 2160×1080 + 实体键盘）；左侧图标 rail、两栏 master/detail、标题栏 inline 筛选/搜索。UI 规格见 [`docs/cosmo-ui-spec.md`](docs/cosmo-ui-spec.md)。设备仍在 firmware V19？另见 [**Cosmo V19→V23 升级＋待机省电指南**](https://github.com/tathome2025/cosmo-standby-battery-fix)。

**USB HotSync（Cosmo 版）。** 用 USB-OTG 线将旧 Palm/CLIE 接上 Cosmo 的 USB-C 口，在 Palm 按 HotSync，app 就会将 Memo Pad、To Do、Date Book、联系人和 Mail 直接与 PalmVellum 云端同步 — 不用电脑。还可以将 `.prc`/`.pdb` 文件安装到 Palm。背后是从零用 Kotlin 写的 HotSync 协议栈（NetSync + DLP），在 Sony CLIE 真机验证过。

> ⚠️ 请允许「安装未知来源应用」，再打开 APK 安装。这些是 sideload（debug 签名）APK，未经 Play 审查。**不提供任何保修，使用风险自负。**

**下载：**[Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8)。

开发说明、项目架构（pnpm + Go monorepo）与参考硬件清单，见上方 [English](#english) 章节与 [`docs/`](docs/)。

### 参与贡献

PalmVellum 处于 pre-1.0 阶段。我们正在寻找：硬件测试者、译者（繁中 / 简中 / 日本語 / 한국어 / Русский / Español）、logo 设计师、文档贡献者。

PalmVellum 由香港一个人在晚上和周末研究、设计、实作 —— 无公司、无 sponsor、无 VC。如果这份工作对你有意义，[支持研究](https://tatliving.dev/palmvellum/)就足以撑起多一个晚上。

---

## PalmVellum 日本語

**PalmVellum は、1996〜2003 年の Palm Pilot のネイティブアプリに AI 補助とクラウド同期を加えるオープンソースのプラットフォームです — Palm 本体には一切手を加えません。**

Palm を選ぶのは、それが静かだから。単 4 電池 2 本。160×160 のモノクロ画面。常時通信なし。カメラなし。無限スクロールなし。手に取って書き留め、書いたら置く。プラットフォームは背後でクラウドの控えを見守り、AI が必要なときだけ手を貸します。

### 「retro computing（レトロコンピューティング）」とは

品質が低いという意味ではありません。懐古のための懐古でもありません。**レトロコンピューティング ＝ あえて古い機械、あえて制限。** ピクセルは少なく、通知は少なく、端末を手に取る口実も少なく。プラットフォームは AI とクラウドが本当に役立つところ — 自然言語入力の解析、手書きの認識、毎日のニュース要約、タスク／予定の自動実行 — だけに介入し、そこで止まります。閲覧傾向を追跡せず、広告を出さず、プロフィールを見られたからと通知も送りません。そもそもプロフィールがありません。

### プラットフォームが実際にすること

| Palm ネイティブアプリ | プラットフォーム側 | AI 補助 |
|---|---|---|
| Date Book | カレンダー + 自由文の AI 解析 | 書いた文 → 構造化された予定 |
| To Do List | タスク一覧（優先度・期日） | `(AI) ...` で始まるタスクをエージェントが実行し、結果を Memo に書き出し |
| Memo Pad | メモ閲覧 + ファイルアップロード（PDF / DOCX / 画像） | `(AI) ...` でエージェントが予定／タスクを自動作成し要約をメモに追記。アップロードしたファイルを AI が要約してメモ化 |
| Address | 連絡先 | — |
| Note Pad | 手書きギャラリー（Web では閲覧のみ） | Vision モデルが手書きを認識し図を説明 |
| Mail | 毎日のダイジェスト受信箱 | 指定 URL を要約、または「トピック」モードで AI が Web 検索し 10〜20 分の記事 + 出典を作成 |
| Expense | 多通貨の支出表 | — |

Palm とプラットフォームの両方で、**ユーザーごとに 1 つのデータセットを共有**します。同じ家庭の複数の Palm が同じレコードを読み書きします。

### 目標

予定・連絡先・メモ・手書き・調査ダイジェスト・支出といった、意図的で穏やかな個人の記録を少しずつ積み上げる — しかも自分の道具に絶えず邪魔されることなく。Palm は単 4 電池で支えるコールドストレージ、プラットフォームはクラウドの控えを保ち AI 補助を担います。

### ダウンロード

- **PalmVellum.app（macOS）** — メモリースティック + Palm 内蔵の MS Backup を使って Memo Pad・To Do・Date Book・Address・Mail をクラウドと同期。**[Releases → 最新の .dmg](https://github.com/palmvellum/palmvellum/releases/latest)**（未署名 — 初回は右クリック →「開く」）。詳しくは [`docs/USAGE.md`](docs/USAGE.md)。
- **Palm Organizers（Android）** — ネイティブでローカルファースト。Standard / Cosmo 版が共存可能。**[Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8)**。サイドロード APK、**保証なし — 自己責任で**。

> ⚠️ 動作確認は Sony Clié + メモリースティック + MS Backup のみ。**SD カードの Palm は未検証**です。復元前に必ずカードのバックアップを別途お取りください。

---

## License

[Apache License 2.0](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for how to help, and [SECURITY.md](SECURITY.md) for security disclosures.
