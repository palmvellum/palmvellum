# PalmVellum desktop sync — usage

Sync your Sony Clié's native apps (Memo Pad, To Do, Date Book, Address, Mail)
with your PalmVellum cloud, using the **Memory Stick** as the transport. The
Palm itself is never modified — you keep using its **built-in MS Backup** app.

- Project: <https://tatliving.dev/palmvellum>
- Source: <https://github.com/palmvellum/palmvellum>

## ⚠️ Use at your own risk

This is free, open-source software provided **with no warranty of any kind**.
You run it **entirely at your own risk**. Restoring data to a Palm can fail or
behave unexpectedly. **Always keep a separate copy of your Memory Stick (and a
HotSync/desktop backup) before restoring.** The authors are not responsible for
any data loss or damage.

## What's supported

- ✅ **Sony Clié + Memory Stick + the built-in MS Backup app** — this is the
  tested, supported configuration.
- ❓ **Palm devices that use an SD card — NOT yet tested.** It may or may not
  work; treat it as experimental.
- On restore, the Clié may show a **brief soft reset** — this is expected and
  harmless; your records load normally.

## First time

1. Drag **PalmVellum.app** to your Applications folder.
2. It's an unsigned build, so the first launch is blocked by Gatekeeper:
   **right-click the app → Open → Open**. (Only needed once.)
3. Click **Email me a code**, enter the 6-digit code from your email, and
   **Log in** with your PalmVellum account. You stay logged in until you log out.

## Each sync

1. **On the Clié:** open the built-in **MS Backup** and **back up to the
   Memory Stick**.
2. **On your Mac:** put the Memory Stick into a card reader. PalmVellum detects
   it and syncs automatically (or click **Sync now**). It then **ejects the
   card** for you.
   - "Wait for AI answers" makes a memo in the **AI** category come back with
     its answer in the same sync (a little slower).
3. **Back on the Clié:** insert the card and use **MS Backup → restore from
   card**.

That's it — your memos, to-dos, appointments, contacts and mail digests are now
on both the Palm and the cloud.

## Notes / limits

- Syncs Memo Pad, To Do, Date Book, Address and Mail (digests, into the Inbox).
  Expense and Note Pad are not present on the Clié and aren't synced.
- Conflicting edits use **last-write-wins** for now, so sync soon after you back
  up, and don't edit the same record on both sides between syncs.
- Repeating Date Book appointments currently sync as a single occurrence.

---

## 使用說明（繁中）

用 Memory Stick 將 Sony Clié 內置 app（Memo Pad / To Do / Date Book / Address /
Mail）同 PalmVellum 雲端同步。Palm 本身唔會改動 — 照用機內 **MS Backup**。

**⚠️ 風險自負：** 免費開源軟件，**不提供任何保養**，一切**自行承擔風險**。
Restore 落 Palm 有機會失敗。**Restore 前請自行另備一份 Memory Stick 副本。**
作者對任何資料遺失或損壞概不負責。

**支援：** ✅ Sony Clié + Memory Stick + 內置 MS Backup（已測試）。
❓ 用 **SD 卡** 嘅 Palm：**未測試**，當實驗性質。
Restore 時 Clié 可能短暫 soft reset，正常無害。

**流程：**
1. Clié 用內置 MS Backup **backup 落 Memory Stick**
2. 卡插 Mac 讀卡器 → app 自動同步 → 自動退卡
3. 卡插返 Clié → MS Backup **restore from card**

首次啟動：右鍵 app → 開啟（unsigned，Gatekeeper 擋一次）；用 email 收 6 位
code 登入。
