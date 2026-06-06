# PalmVellum

> *Slow tools for fast lives.*  
> *老器具，新雲端。* / *老器具，新云端。*

[繁體中文](#palmvellum-繁體中文) · [简体中文](#palmvellum-简体中文) · [English](#english)

---

## English

**PalmVellum is an open-source platform that gives the native apps on a 1996–2003 Palm Pilot AI-assist and cloud sync — while leaving the Palm itself completely alone.**

You write on the Palm because it's quiet. Two AAA batteries. 160×160 monochrome screen. No always-on radio. No camera. No infinite scroll. You hold it, you note things down, you put it away. The platform watches the cloud copy and helps when called.

### What "low-fi" means here

Not low quality. Not retro for retro's sake. **Low-fi = low fidelity, deliberately.** Fewer pixels, fewer notifications, fewer pretexts to pick up the device. The platform brings AI and cloud in where they're genuinely useful — natural-language input parsing, transcription of handwritten sketches, daily research digests, agentic event / task creation — and stops there. We don't profile you. We don't show ads. We don't notify because someone you don't know looked at your profile. There is no profile.

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
- **BYOK** (Bring Your Own Key) for AI providers. Your OpenAI / Anthropic spend is yours. A platform-credits option (via Airwallex) is opt-in.
- **No social, no analytics, no email marketing.** You write the records, you own them.
- **Hardware support strictly limited to AAA-battery Palms (1996–2003)** — 19 specific models. Rechargeable Palms, Treos, Tungstens, and any device with an integrated radio are out of scope.
- **The Palm stays Palm.** We will never push a custom firmware. The platform speaks the existing HotSync protocol to the existing PalmOS apps. If a conduit doesn't work on your model we fix the conduit — the model itself never changes.

### What's where in this repo

```
packages/
├── pwa/             SvelteKit + adapter-static web app
│                    (Organizers dashboard at tatliving.dev/palmvellum/app)
├── palm-app/        Cross-compiled PalmOS .prc artifacts (historical)
├── sync-cli/        Go CLI: vellum-sync — manual VellumDB / MemoDB /
│                    ToDoDB ↔ Supabase round-trip
├── mac-daemon/      Go scaffold for the future Network HotSync daemon
└── android/         Capacitor scaffold for the Palm Organizers Android
                     companion app (in prep)
supabase/
├── migrations/      SQL schema (records, events, mail_sources, devices,
│                    Vault BYOK, sketch storage bucket, agent triggers)
└── functions/       Deno Edge Functions (process-ai-queue, ai-agent,
                     process-event-draft, process-sketch, fetch-mail-source,
                     summarize-upload)
docs/                Project docs: ROADMAP, threat-model, crypto-spec,
                     hardware-compatibility
```

### Companion Android app

A native Android companion called **Palm Organizers** is in preparation under `packages/android/` — wrapping the SvelteKit Organizers shell via Capacitor so the same UI runs on phones for users who want the AI assist without sitting at a desk. Status: scaffold only.

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

### 「low-fi」對我哋嚟講是甚麼

唔係指品質低。唔係懷舊 for 懷舊。**Low-fi = 低保真，有意嘅。** 像素少啲、通知少啲、令你拎機嘅藉口少啲。平台只係喺 AI 同雲端真係有用嘅地方介入 — 自然語言輸入解析、手稿辨識、每日新聞摘要、自動執行任務／日程 — 然後止步。我哋唔追蹤你嘅閱讀習慣，唔賣廣告，唔會因為「有人睇過你嘅 profile」推 push notification 畀你。根本冇 profile。

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
- **BYOK**（自帶 API key）AI 供應商。OpenAI / Anthropic 嘅消費由你自己 control。平台 credits（Airwallex 付款）係 opt-in。
- **冇 social、冇 analytics、冇 email marketing**。Records 由你寫，亦由你擁有。
- **硬件支援嚴格限於 AAA 電池 Palm (1996-2003)** — 19 部指定型號。可充電 Palm、Treo、Tungsten、有 radio 嘅設備都唔喺範圍內。
- **Palm 機保持 Palm 機。** 我哋不會推 custom firmware。平台講嘅係現有 HotSync 協議，對住現有 PalmOS app。如果某個 model conduit 唔通，我哋會修 conduit — 唔會改 Palm 嘅 app。

### Android 伴隨 app

`packages/android/` 入面正在準備一隻叫 **Palm Organizers** 嘅 Android 原生 app — 用 Capacitor 包住 SvelteKit Organizers shell，等同一個介面可以喺手機上跑。狀態：scaffold 階段。

---

## PalmVellum 简体中文

**PalmVellum 是一个开源平台，为 1996-2003 年那批 Palm Pilot 的原生 app 加入 AI 辅助和云端同步 — Palm 机本身完全不需要改动。**

你选 Palm 是因为它安静。两节 AAA 电池。160×160 黑白屏。没有常驻通讯。没有摄像头。没有无限滚动。拿上手写东西，写完放下。平台在后台保存云端副本，要用 AI 时才取出来。

### 「low-fi」对我们来说意味着什么

不是说品质低。不是为复古而复古。**Low-fi = 低保真，有意为之。** 像素少一些、通知少一些、让你拿起设备的理由少一些。平台只在 AI 和云端真正有用的地方介入 — 自然语言输入解析、手稿识别、每日新闻摘要、自动执行任务／日程 — 然后止步。我们不追踪你的阅读习惯，不卖广告，不会因为「有人看过你的 profile」推送通知。根本没有 profile。

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
- **BYOK**（自带 API key）。OpenAI / Anthropic 的消费由你自己控制。平台 credits（Airwallex 付款）是 opt-in。
- **没有 social、没有 analytics、没有 email marketing**。Records 由你写，也由你拥有。
- **硬件支持严格限于 AAA 电池 Palm (1996-2003)** — 19 个指定型号。可充电 Palm、Treo、Tungsten、有 radio 的设备都不在范围内。
- **Palm 机保持 Palm 机。** 我们不会推 custom firmware。平台讲的是现有 HotSync 协议，对着现有 PalmOS app。如果某个 model conduit 不通，我们会修 conduit — 不会改 Palm 上的 app。

### Android 配套 app

`packages/android/` 中正在准备一款叫 **Palm Organizers** 的 Android 原生 app — 用 Capacitor 包装 SvelteKit Organizers shell，让同一个界面可以在手机上运行。状态：scaffold 阶段。

---

## License

[Apache License 2.0](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) for how to help, and [SECURITY.md](SECURITY.md) for security disclosures.
