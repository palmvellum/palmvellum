# PalmVellum

> *Slow tools for fast lives.*  
> *老器具，新雲端。* / *老器具，新云端。*

[繁體中文](#palmvellum-繁體中文) · [简体中文](#palmvellum-简体中文) · [日本語](#palmvellum-日本語) · [English](#english)

<table>
  <tr>
    <td width="50%"><img src="docs/photos/palm-iiie.jpg" alt="A 1999 Palm IIIe running its classic launcher — Date Book, Address, To Do, Memo Pad"></td>
    <td width="50%"><img src="docs/photos/sony-clie.jpg" alt="A Sony Clié PEG-SL10 with a Memory Stick inserted, showing its launcher"></td>
  </tr>
</table>

<sub>Reference hardware: a 1999 Palm IIIe and a Sony Clié PEG-SL10 — the AAA-battery devices PalmVellum is built around. 繁中／简中：PalmVellum 圍繞嘅 AAA 電池機 — 1999 年 Palm IIIe 同 Sony Clié PEG-SL10。</sub>

---

## PalmVellum on Mac

<img src="docs/photos/sync-app.png" alt="PalmVellum on Mac — always-listening USB HotSync, drag-and-drop install zone, and a live sync log" width="300" align="right">

Connect a Sony Clié by USB, click **Sync over USB**, then press the **HotSync**
button on the Palm — it syncs Memo Pad, To Do, Date Book, Address & Mail with the
cloud in one go. Drag and drop `.prc`/`.pdb` files to install apps onto the
device. A Memory Stick card-sync path is included too.

⚠️ **Tested on Sony Clié over USB only** — other USB Palms and SD-card devices are
unverified. A restore point is saved before every write-back.

**[⬇ Download PalmVellum on Mac (.dmg)](https://github.com/palmvellum/palmvellum/releases/latest)** — unsigned (first launch: right-click → Open).

<br clear="all">

---

## English

**PalmVellum is an open-source platform that gives the native apps on a 1996–2003 Palm Pilot AI-assist and cloud sync — while leaving the Palm itself completely alone.**

You write on the Palm because it's quiet. Two AAA batteries. 160×160 monochrome screen. No always-on radio. No camera. No infinite scroll. You hold it, you note things down, you put it away. The platform watches the cloud copy and helps when called.

### What "retro computing" means here

Not low quality. Not nostalgia for nostalgia's sake. **Retro computing = deliberately old, deliberately limited.** Fewer pixels, fewer notifications, fewer pretexts to pick up the device. The platform brings AI and cloud in where they're genuinely useful — natural-language input parsing, transcription of handwritten sketches, daily research digests, agentic event / task creation — and stops there. We don't profile you. We don't show ads. We don't notify because someone you don't know looked at your profile. There is no profile.

### The platform — what it actually does

| Palm app    | Platform partner | AI assist |
|-------------|------------------|-----------|
| Date Book   | Calendar grid + paste-anything AI parser     | Free-form text → structured events |
| To Do List  | Task list with priority / due dates          | `(AI) ...` prefix triggers an agent that executes the prompt and writes the result back as a memo |
| Memo Pad    | Memo browser + file upload (PDF / DOCX / image) | `(AI) ...` triggers an agent that creates Date Book events / To Do tasks then appends a summary to the memo. Upload a file and AI summarises it into a memo. |
| Address     | Contacts with rich fields                    | — |
| Note Pad    | Sketch gallery (read-only on web)            | Vision model transcribes handwriting and describes drawings |
| Mail        | Daily digest inbox                           | Per-source AI digest of a website, or topic-mode where AI uses web search to write a 10-20 minute research article with cited sources |
| Expense     | Multi-currency log with category totals      | — |

Both sides — Palm and platform — share **one dataset per user**. Multiple Palms in the same household read and write the same set of records.

### Goal

Help you accumulate a slow, deliberate body of personal records — calendar, contacts, notes, sketches, research digests, expenses — without being interrupted by your own tools. The Palm holds them in cold storage on AAA batteries. The platform holds the duplicate cloud copy and runs the AI assist whenever called.

### What we promise

- **Apache 2.0.** Code on this GitHub.
- **BYOK** (Bring Your Own Key) for AI providers — OpenAI, Anthropic, or Google Gemini. Your usage is your own bill. A platform-credits option (via Airwallex) is opt-in.
- **No social, no analytics, no email marketing.** You write the records, you own them.
- **AAA-battery Palms (1996–2003) are our reference target and design focus.** That's the device we believe in and test on. The platform itself just speaks HotSync, so other Palm OS devices outside our target are welcome to use it too; we just won't be testing those.
- **The Palm stays Palm.** We will never push a custom firmware. The platform speaks the existing HotSync protocol to the existing PalmOS apps. If a conduit doesn't work on your model we fix the conduit — the model itself never changes.

### What's where in this repo

```
packages/
├── pwa/             SvelteKit + adapter-static web app
│                    (Organizers dashboard at tatliving.dev/palmvellum/app)
├── palm-engine/     Shared Go module: .pdb codec, UTF-8⇄Big5 charset,
│                    AppleDouble-safe card I/O, Supabase client, and the
│                    card↔cloud sync engine (Memo + To Do)
├── mac-daemon/      PalmVellum.app — macOS window app: passwordless login,
│                    card auto-detect + sync + eject (uses palm-engine)
├── sync-cli/        Go CLI prototype: vellum-sync — manual MemoDB /
│                    ToDoDB ↔ Supabase round-trip
└── android/         Capacitor scaffold for the Palm Organizers Android
                     companion app (in prep)
supabase/
├── migrations/      SQL schema (records, events, mail_sources, devices,
│                    Vault BYOK, sketch storage bucket, agent triggers)
└── functions/       Deno Edge Functions (process-ai-queue, ai-agent,
                     process-event-draft, process-sketch, fetch-mail-source,
                     summarize-upload)
docs/                Project docs: hardware-compatibility (reference
                     target list + buying tips)
```

### Desktop sync app — PalmVellum.app (macOS)

A small macOS app syncs a Palm backup card with your cloud copy. The on‑device
half uses the **Sony CLIE's built‑in MS Backup** — back up to the Memory Stick,
drop the card in a reader, and the app does the rest. Nothing new is installed
on the Palm.

<p align="center">
  <img src="docs/screenshots/sync-app.png" width="420" alt="PalmVellum desktop sync app — login status, settings, and a live sync log">
</p>

- **Passwordless login** — sign in with your platform account via an emailed
  code; the session is kept in the macOS Keychain, so you stay logged in until
  you log out. Every sync is scoped to you by Postgres RLS (no keys to copy).
- **Insert‑and‑go** — the app detects the card, syncs **Memo Pad**, **To Do**,
  **Date Book**, **Address** and **Mail**, and ejects it for you. It can wait
  for any `(AI)` memo answers so they come back to the card in the same sync.
- **Restore on the Palm** — put the card back and use MS Backup's *restore from
  card*. The CLIE may do a brief **soft reset** on restore — this is expected
  and harmless; your records load normally.

**Download:** [Releases → latest `.dmg`](https://github.com/palmvellum/palmvellum/releases/latest)
— unsigned, so on first launch **right‑click the app → Open**. Or build it
yourself from [`packages/mac-daemon/`](packages/mac-daemon/) with
`bash packaging/build-app.sh` → `dist/PalmVellum-<version>.dmg`.

It syncs Memo Pad, To Do, Date Book, Address (contacts) and Mail digests.
(Expense and Note Pad aren't present on the reference Sony CLIE, so they're out
of scope.) See [`docs/USAGE.md`](docs/USAGE.md) for the full guide.

> ⚠️ **No warranty — use at your own risk.** Tested only on a Sony Clié with a
> Memory Stick + the built-in MS Backup app. Palm devices using an **SD card
> are not yet tested**. Always keep a separate backup of your card before
> restoring.

### Android app — Palm Organizers (native)

**Palm Organizers** is a native, local‑first Android build of the organizer apps (Date Book, Address, To Do, Memo, Note Pad, Expense, Mail) — Kotlin + Jetpack Compose, Room on‑device, with optional cloud sync + AI (BYOK). Source: [`packages/android-native/`](packages/android-native/). *(The earlier Capacitor wrapper under `packages/android/` is superseded.)*

It ships in two flavors that install side by side:

- **Standard** — portrait, any phone.
- **Cosmo edition** — built specifically for the **Planet Cosmo Communicator** (landscape clamshell + physical QWERTY): landscape‑locked for the 2160×1080 main display, a left icon rail, two‑pane master/detail layouts, and inline title‑bar filters/search. UI spec: [`docs/cosmo-ui-spec.md`](docs/cosmo-ui-spec.md). On a Cosmo stuck on firmware V19, see the companion [**Cosmo V19→V23 upgrade & standby‑battery‑fix guide**](https://github.com/tathome2025/cosmo-standby-battery-fix).

**USB HotSync (Cosmo edition).** Dock a vintage Palm/CLIE to the Cosmo's USB‑C port (via a USB‑OTG adapter), press HotSync on the Palm, and the app syncs Memo Pad, To Do, Date Book, Address and Mail straight to your PalmVellum cloud — no desktop needed. It can also **install `.prc`/`.pdb` files** (apps and databases) onto the Palm, like the classic Palm Install Tool. A from‑scratch Kotlin HotSync stack (NetSync + DLP, driven over the cable) powers it; verified on a Sony CLIE.

**Download:** [Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8) — or build from `packages/android-native/` with `./gradlew :app:assembleStandardDebug` / `:app:assembleCosmoDebug`.

> ⚠️ **Not on the Play Store.** These are sideload (debug‑signed) APKs, not Play‑reviewed. You'll need to allow "Install unknown apps", then open the APK. **No warranty of any kind — use at your own risk.**

#### Screenshots — Cosmo edition

| | |
|---|---|
| ![Launcher](docs/screenshots/cosmo-launcher.png) | ![Date Book](docs/screenshots/cosmo-datebook.png) |
| ![Address](docs/screenshots/cosmo-address.png) | ![Memo editor](docs/screenshots/cosmo-editor.png) |

_Captured on a real Cosmo Communicator (2160×1080): the Applications launcher, Date Book month view, an Address contact, and the Memo Pad AI editor._

### Quick start (development)

```sh
pnpm install
pnpm --filter @palmvellum/pwa dev    # http://localhost:5173
pnpm --filter @palmvellum/pwa build  # static build into packages/pwa/build/
```

The Supabase project this repo targets is at `jrkwncplngmznfzzqwee.supabase.co`. See `packages/sync-cli/.env.example` for the env vars the CLI expects.

---

## PalmVellum 繁體中文

**PalmVellum 是一個開源平台，為 1996-2003 年那批 Palm Pilot 的原生 app 加入 AI 輔助同雲端同步 — Palm 機本身一點都唔需要改。**

你揀 Palm 是因為佢靜。兩粒 AAA 電池。160×160 黑白屏。冇恆常通訊。冇鏡頭。冇無限滾動。攞上手寫嘢，寫完放低。平台就喺後台守住雲端副本，要用 AI 嗰陣先攞出嚟。

### 「retro computing（復古運算）」對我哋嚟講是甚麼

唔係指品質低。唔係為咗懷舊而懷舊。**Retro computing（復古運算）＝刻意用舊機、刻意有限制。** 像素少啲、通知少啲、令你拎機嘅藉口少啲。平台只係喺 AI 同雲端真係有用嘅地方介入 — 自然語言輸入解析、手稿辨識、每日新聞摘要、自動執行任務／日程 — 然後止步。我哋唔追蹤你嘅閱讀習慣，唔賣廣告，唔會因為「有人睇過你嘅 profile」推 push notification 畀你。根本冇 profile。

### 平台真正做嘅事

| Palm 原生 app | 平台對應 | AI 輔助 |
|---|---|---|
| Date Book | 月曆 + 自由文字 AI 解析 | 隨手打嘅句子 → 結構化日程 |
| To Do List | 待辦清單 (優先級、到期日) | `(AI) ...` 開頭嘅 task 由智能代理執行，結果寫成 Memo |
| Memo Pad | 記事瀏覽 + 上傳檔案（PDF / DOCX / 圖片）| `(AI) ...` 觸發智能代理 — 自動建立日程／任務並 append summary 入 memo。上傳檔案 AI 自動讀完做摘要記事。 |
| Address | 通訊錄 | — |
| Note Pad | 手稿 gallery（網頁端唯讀） | Vision model 辨識手寫 + 描述圖畫 |
| Mail | 每日 digest 收件箱 | 對指定網址做摘要，或者「話題」模式 AI 上網搜尋寫一篇 10-20 分鐘嘅深度文章 + 參考來源 |
| Expense | 多幣別開支表 | — |

兩邊（Palm 同平台）**每個 user 共用一個資料集**。同一個家庭幾部 Palm，讀寫同一組 records。

### 目標

幫你慢慢累積一份有意識嘅個人紀錄 — 日程、聯絡人、記事、手稿、研究摘要、開支 — 又唔會俾自己嘅工具不斷打斷。Palm 機係 AAA 電池支撐嘅冷儲存，平台保存雲端副本同負責 AI 輔助。

### 我哋嘅承諾

- **Apache 2.0**，code 喺呢個 GitHub。
- **BYOK**（自帶 API key）AI 供應商 — OpenAI、Anthropic 或 Google Gemini。消費由你自己 control。平台 credits（Airwallex 付款）係 opt-in。
- **冇 social、冇 analytics、冇 email marketing**。Records 由你寫，亦由你擁有。
- **AAA 電池 Palm (1996-2003) 係我哋嘅參考目標同設計重點** — 嗰個係我哋相信、會親自測試嘅機型。但平台本身只講 HotSync，所以其他唔係我哋 target 嘅 Palm OS 機都一樣可以用 — 我哋只係唔會替佢哋做測試。
- **Palm 機保持 Palm 機。** 我哋不會推 custom firmware。平台講嘅係現有 HotSync 協議，對住現有 PalmOS app。如果某個 model conduit 唔通，我哋會修 conduit — 唔會改 Palm 嘅 app。

### Android app — Palm Organizers（原生）

**Palm Organizers** 係原生、local‑first 嘅 Android 版（Date Book / Address / To Do / Memo / Note Pad / Expense / Mail），Kotlin + Jetpack Compose、機上 Room，雲同步同 AI 都係 opt‑in（BYOK）。源碼喺 [`packages/android-native/`](packages/android-native/)。出兩個可並存嘅版本：

- **Standard** — 直屏，任何手機。
- **Cosmo 版** — 專為 **Planet Cosmo Communicator**（橫向翻蓋 + 實體 QWERTY）設計：橫屏鎖定（2160×1080）、左側圖示 rail、兩欄 master/detail、標題列 inline 篩選/搜尋。UI 規格見 [`docs/cosmo-ui-spec.md`](docs/cosmo-ui-spec.md)。部 Cosmo 仲喺 firmware V19？睇埋 [**Cosmo V19→V23 升級＋待機省電指南**](https://github.com/tathome2025/cosmo-standby-battery-fix)。

**USB HotSync（Cosmo 版）。** 用 USB‑OTG 線將舊 Palm/CLIE 駁上 Cosmo 嘅 USB‑C 口，喺 Palm 撳 HotSync，app 就會將 Memo Pad、To Do、Date Book、聯絡人同 Mail 直接同 PalmVellum 雲端同步 — 唔使電腦。仲可以將 `.prc`/`.pdb` 檔（app 同資料庫）**安裝**落 Palm，似經典 Palm Install Tool。背後係由零用 Kotlin 寫嘅 HotSync 協議堆疊（NetSync + DLP，經 cable 驅動），喺 Sony CLIE 真機驗證過。

**下載：**[Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8)。

> ⚠️ **冇喺 Play Store 上架。** 呢啲係 sideload（debug 簽署）APK，未經 Play 審查，要先允許「安裝未知來源 app」先裝到。**冇任何保養，使用風險自負。**

#### Screenshots — Cosmo 版

| | |
|---|---|
| ![啟動器](docs/screenshots/cosmo-launcher.png) | ![日程](docs/screenshots/cosmo-datebook.png) |
| ![通訊錄](docs/screenshots/cosmo-address.png) | ![備忘編輯](docs/screenshots/cosmo-editor.png) |

_喺真 Cosmo Communicator（2160×1080）影：應用程式啟動器、Date Book 月曆、通訊錄聯絡人、Memo Pad AI 編輯器。_

---

## PalmVellum 简体中文

**PalmVellum 是一个开源平台，为 1996-2003 年那批 Palm Pilot 的原生 app 加入 AI 辅助和云端同步 — Palm 机本身完全不需要改动。**

你选 Palm 是因为它安静。两节 AAA 电池。160×160 黑白屏。没有常驻通讯。没有摄像头。没有无限滚动。拿上手写东西，写完放下。平台在后台保存云端副本，要用 AI 时才取出来。

### 「retro computing（复古计算）」对我们来说意味着什么

不是说品质低。不是为怀旧而怀旧。**Retro computing（复古计算）＝刻意用旧机、刻意有限制。** 像素少一些、通知少一些、让你拿起设备的理由少一些。平台只在 AI 和云端真正有用的地方介入 — 自然语言输入解析、手稿识别、每日新闻摘要、自动执行任务／日程 — 然后止步。我们不追踪你的阅读习惯，不卖广告，不会因为「有人看过你的 profile」推送通知。根本没有 profile。

### 平台具体做什么

| Palm 原生 app | 平台对应 | AI 辅助 |
|---|---|---|
| Date Book | 月历 + 自由文字 AI 解析 | 随手打的句子 → 结构化日程 |
| To Do List | 待办清单（优先级、到期日） | `(AI) ...` 开头的 task 由智能代理执行，结果写成 Memo |
| Memo Pad | 记事浏览 + 上传文件（PDF / DOCX / 图片） | `(AI) ...` 触发智能代理 — 自动建立日程／任务并 append summary 到 memo。上传文件 AI 自动阅读做摘要记事。 |
| Address | 通讯录 | — |
| Note Pad | 手稿 gallery（网页端只读） | Vision model 识别手写 + 描述图画 |
| Mail | 每日 digest 收件箱 | 对指定网址做摘要，或者「话题」模式 AI 联网搜索写一篇 10-20 分钟的深度文章 + 参考来源 |
| Expense | 多币别开支表 | — |

两边（Palm 和平台）**每个用户共享一个数据集**。同一个家庭几部 Palm，读写同一组 records。

### 目标

帮你慢慢累积一份有意识的个人记录 — 日程、联系人、记事、手稿、研究摘要、开支 — 又不会被自己的工具不断打断。Palm 机是 AAA 电池支撑的冷存储，平台保存云端副本和负责 AI 辅助。

### 我们的承诺

- **Apache 2.0**，代码在此 GitHub。
- **BYOK**（自带 API key）AI 供应商 — OpenAI、Anthropic 或 Google Gemini。消费由你自己控制。平台 credits（Airwallex 付款）是 opt-in。
- **没有 social、没有 analytics、没有 email marketing**。Records 由你写，也由你拥有。
- **AAA 电池 Palm (1996-2003) 是我们的参考目标和设计重点** — 那是我们相信、会亲自测试的机型。但平台本身只讲 HotSync，所以其他不是我们 target 的 Palm OS 机都一样可以用 — 我们只是不会替它们做测试。
- **Palm 机保持 Palm 机。** 我们不会推 custom firmware。平台讲的是现有 HotSync 协议，对着现有 PalmOS app。如果某个 model conduit 不通，我们会修 conduit — 不会改 Palm 上的 app。

### Android app — Palm Organizers（原生）

**Palm Organizers** 是原生、local‑first 的 Android 版（Date Book / Address / To Do / Memo / Note Pad / Expense / Mail），Kotlin + Jetpack Compose、设备端 Room，云同步与 AI 均为 opt‑in（BYOK）。源码在 [`packages/android-native/`](packages/android-native/)。提供两个可并存的版本：

- **Standard** — 竖屏，任何手机。
- **Cosmo 版** — 专为 **Planet Cosmo Communicator**（横向翻盖 + 实体 QWERTY）设计：横屏锁定（2160×1080）、左侧图标 rail、两栏 master/detail、标题栏 inline 筛选/搜索。UI 规格见 [`docs/cosmo-ui-spec.md`](docs/cosmo-ui-spec.md)。设备仍在 firmware V19？另见 [**Cosmo V19→V23 升级＋待机省电指南**](https://github.com/tathome2025/cosmo-standby-battery-fix)。

**USB HotSync（Cosmo 版）。** 用 USB‑OTG 线将旧 Palm/CLIE 接上 Cosmo 的 USB‑C 口，在 Palm 按 HotSync，app 就会将 Memo Pad、To Do、Date Book、联系人和 Mail 直接与 PalmVellum 云端同步 — 不用电脑。还可以将 `.prc`/`.pdb` 文件（app 和数据库）**安装**到 Palm，类似经典 Palm Install Tool。背后是从零用 Kotlin 写的 HotSync 协议栈（NetSync + DLP，经 cable 驱动），在 Sony CLIE 真机验证过。

**下载：**[Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8)。

> ⚠️ **未在 Play Store 上架。** 这些是 sideload（debug 签名）APK，未经 Play 审查，需先允许「安装未知来源应用」才能安装。**不提供任何保修，使用风险自负。**

#### Screenshots — Cosmo 版

| | |
|---|---|
| ![启动器](docs/screenshots/cosmo-launcher.png) | ![日程](docs/screenshots/cosmo-datebook.png) |
| ![通讯录](docs/screenshots/cosmo-address.png) | ![备忘编辑](docs/screenshots/cosmo-editor.png) |

_在真 Cosmo Communicator（2160×1080）拍摄：应用程序启动器、Date Book 月历、通讯录联系人、Memo Pad AI 编辑器。_

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

### 私たちの約束

- **Apache 2.0**。コードはこの GitHub に。
- **BYOK**（自分の API キー）対応の AI プロバイダ — OpenAI、Anthropic、Google Gemini。利用料は自分の管理下に。プラットフォームのクレジット（Airwallex 決済）はオプトイン。
- **ソーシャルなし、アナリティクスなし、メールマーケティングなし。** レコードはあなたが書き、あなたが所有します。
- **単 4 電池の Palm（1996〜2003）が私たちの基準であり設計の中心です** — 私たちが信じ、自ら検証する機種です。プラットフォーム自体は HotSync を話すだけなので、対象外の他の Palm OS 機でも利用できます — ただし検証はしません。
- **Palm は Palm のまま。** カスタムファームウェアは押し付けません。プラットフォームは既存の HotSync で既存の PalmOS アプリと話します。ある機種で conduit が動かなければ conduit を直します — Palm のアプリは変えません。

### デスクトップ同期アプリ — PalmVellum.app（macOS）

小さな Mac アプリが、メモリースティック + Palm 内蔵の MS Backup を使って、Sony Clié の Memo Pad・To Do・Date Book・Address・Mail をクラウドと同期します。Palm 本体は変わりません。**[Releases → 最新の .dmg](https://github.com/palmvellum/palmvellum/releases/latest)**（未署名 — 初回は右クリック →「開く」）。詳しくは [`docs/USAGE.md`](docs/USAGE.md)。

> ⚠️ **無保証 — 自己責任でご利用ください。** 動作確認は Sony Clié + メモリースティック + MS Backup のみ。**SD カードの Palm は未検証**です。復元前に必ずカードのバックアップを別途お取りください。

### Android アプリ — Palm Organizers（ネイティブ）

**Palm Organizers** は、ネイティブでローカルファーストな Android 版（Date Book / Address / To Do / Memo / Note Pad / Expense / Mail）。Kotlin + Jetpack Compose、端末上の Room、クラウド同期と AI はオプトイン（BYOK）。ソースは [`packages/android-native/`](packages/android-native/)。共存できる 2 つのビルド：

- **Standard** — 縦向き、どの端末でも。
- **Cosmo 版** — **Planet Cosmo Communicator**（横向きクラムシェル + 物理 QWERTY）向け：横向き固定（2160×1080）、左のアイコンレール、2 ペインのマスター/詳細、タイトルバーのインライン絞り込み/検索。UI 仕様は [`docs/cosmo-ui-spec.md`](docs/cosmo-ui-spec.md)。Cosmo がファームウェア V19 のまま？ [**Cosmo V19→V23 アップグレード＋待機電力対策ガイド**](https://github.com/tathome2025/cosmo-standby-battery-fix) も参照。

**USB HotSync（Cosmo エディション）。** USB‑OTG アダプタで往年の Palm/CLIE を Cosmo の USB‑C ポートに接続し、Palm で HotSync を押すと、Memo Pad・To Do・Date Book・アドレス・Mail を PalmVellum クラウドへ直接同期します — パソコン不要。さらに `.prc`/`.pdb` ファイル（アプリやデータベース）を Palm へ**インストール**できます（往年の Palm Install Tool と同様）。ゼロから Kotlin で書いた HotSync スタック（NetSync + DLP、ケーブル経由）が駆動し、Sony CLIE 実機で検証済み。

**ダウンロード：**[Releases → `android-v0.1.8`](https://github.com/palmvellum/palmvellum/releases/tag/android-v0.1.8)。

> ⚠️ **Play ストアにはありません。** これらはサイドロード（debug 署名）の APK で、Play の審査を受けていません。「提供元不明のアプリ」を許可してからインストールしてください。**いかなる保証もなく、自己責任でご利用ください。**

#### スクリーンショット — Cosmo 版

| | |
|---|---|
| ![ランチャー](docs/screenshots/cosmo-launcher.png) | ![Date Book](docs/screenshots/cosmo-datebook.png) |
| ![連絡先](docs/screenshots/cosmo-address.png) | ![メモ編集](docs/screenshots/cosmo-editor.png) |

_実機の Cosmo Communicator（2160×1080）で撮影：アプリランチャー、Date Book の月表示、連絡先、Memo Pad の AI エディタ。_

---

## License

[Apache License 2.0](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for how to help, and [SECURITY.md](SECURITY.md) for security disclosures.
