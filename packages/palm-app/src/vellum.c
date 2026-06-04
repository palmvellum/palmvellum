/*
 * PalmVellum Capture v1.1 — on-device entry surface for AI / thought / todo.
 *
 * Changes vs v1.0:
 *   - Main form shows a pending-sync banner ("log : N to sync") whenever
 *     records with status=draft exist. Updates after every save/edit/sync.
 *   - Detail form's body field is now EDITABLE. New `save` button rewrites
 *     the underlying record via DmResizeRecord+DmWrite, preserving the
 *     existing answer bytes, and flips status back to draft so the next
 *     HotSync re-pushes the change.
 *   - Defensive rewrites of DetailPopulate to fix the soft-reset on log tap:
 *     strict C89 layout (all locals at function top), NULL guards on every
 *     handle/lock, FldRecalculateField call after FldSetTextHandle, and a
 *     GraffitiStateIndicator on both forms.
 *
 * Storage and record format unchanged from v1.0 — VellumDB rows produced
 * by either revision are byte-compatible, so existing CLI round-trips
 * continue to work.
 */

#include <PalmOS.h>

/* --------------------------------------------------------------- IDs */

#define DB_NAME            "VellumDB"
#define DB_TYPE            'Data'
#define DB_CREATOR         'PvV1'

#define FORM_MAIN          1000
#define FORM_DETAIL        1100

#define MAIN_BTN_AI        1001
#define MAIN_BTN_THOUGHT   1002
#define MAIN_BTN_TODO      1003
#define MAIN_FLD_BODY      1010
#define MAIN_BTN_SAVE      1020
#define MAIN_LBL_STATUS    1021
#define MAIN_LBL_BANNER    1022
#define MAIN_LST_RECORDS   1030

#define DETAIL_LBL_TYPE    1101
#define DETAIL_LBL_STATUS  1102
#define DETAIL_FLD_BODY    1110
#define DETAIL_FLD_ANSWER  1111
#define DETAIL_BTN_BACK    1120
#define DETAIL_BTN_DELETE  1121
#define DETAIL_BTN_DONE    1122
#define DETAIL_BTN_SAVE    1123

#define REC_HDR_LEN        12

#define TYPE_AI            1
#define TYPE_THOUGHT       2
#define TYPE_TODO          3

#define STATUS_DRAFT       0
#define STATUS_SYNCED      1
#define STATUS_ANSWERED    2
#define STATUS_DONE        3

#define LIST_MAX_ROWS      32
#define LIST_LBL_LEN       28
#define NO_REC             0xFFFF

/* --------------------------------------------------------------- globals */

static DmOpenRef gDB         = NULL;
static UInt8     gSelType    = TYPE_AI;
static UInt16    gOpenIdx    = NO_REC;
static Char      gListBuf[LIST_MAX_ROWS * LIST_LBL_LEN];
static Char     *gListPtrs[LIST_MAX_ROWS];
static UInt16    gListRecIdx[LIST_MAX_ROWS];
static UInt16    gListCount  = 0;

/* --------------------------------------------------------------- db helpers */

static UInt16 OpenOrCreateDB(void) {
    LocalID dbID;

    dbID = DmFindDatabase(0, DB_NAME);
    if (dbID == 0) {
        if (DmCreateDatabase(0, DB_NAME, DB_CREATOR, DB_TYPE, false) != 0)
            return 1;
        dbID = DmFindDatabase(0, DB_NAME);
    }
    gDB = DmOpenDatabase(0, dbID, dmModeReadWrite);
    return gDB ? 0 : 2;
}

/* Count records with status == STATUS_DRAFT across all types — drives
 * the "N to sync" banner. */
static UInt16 CountPending(void) {
    UInt16 n;
    UInt16 i;
    UInt16 cnt;
    MemHandle h;
    UInt8 *p;

    cnt = 0;
    n = DmNumRecords(gDB);
    for (i = 0; i < n; i++) {
        h = DmQueryRecord(gDB, i);
        if (!h) continue;
        p = (UInt8 *)MemHandleLock(h);
        if (!p) continue;
        if (p[2] == STATUS_DRAFT) cnt++;
        MemHandleUnlock(h);
    }
    return cnt;
}

/* --------------------------------------------------------------- list helpers */

static void FormatRow(UInt16 recIdx, Char *dst) {
    MemHandle h;
    UInt8 *p;
    UInt8 type;
    UInt8 status;
    UInt16 bodyLen;
    UInt16 i;
    UInt16 lim;
    Char tch;
    Char sch;
    Char c;

    tch = '?';
    sch = ' ';

    h = DmQueryRecord(gDB, recIdx);
    if (!h) {
        dst[0] = 0;
        return;
    }
    p = (UInt8 *)MemHandleLock(h);
    if (!p) {
        dst[0] = 0;
        return;
    }
    type    = p[1];
    status  = p[2];
    bodyLen = ((UInt16)p[8] << 8) | (UInt16)p[9];

    switch (type) {
        case TYPE_AI:      tch = 'a'; break;
        case TYPE_THOUGHT: tch = 't'; break;
        case TYPE_TODO:    tch = 'd'; break;
    }
    switch (status) {
        case STATUS_DRAFT:    sch = '.'; break;
        case STATUS_SYNCED:   sch = '>'; break;
        case STATUS_ANSWERED: sch = '*'; break;
        case STATUS_DONE:     sch = 'x'; break;
    }

    dst[0] = sch;
    dst[1] = tch;
    dst[2] = ' ';

    lim = LIST_LBL_LEN - 4;
    if (bodyLen < lim) lim = bodyLen;

    for (i = 0; i < lim; i++) {
        c = (Char)p[REC_HDR_LEN + i];
        if (c == 0x0A || c == 0x0D || c == 0x09) c = ' ';
        dst[3 + i] = c;
    }
    dst[3 + lim] = 0;

    MemHandleUnlock(h);
}

static void UpdatePendingLabel(void) {
    FormType *frm;
    Char buf[24];
    UInt16 cnt;

    frm = FrmGetActiveForm();
    if (!frm) return;
    cnt = CountPending();
    if (cnt == 0) {
        StrCopy(buf, "log");
    } else {
        StrPrintF(buf, "log : %d to sync", cnt);
    }
    FrmCopyLabel(frm, MAIN_LBL_BANNER, buf);
}

static void RebuildList(void) {
    FormType *frm;
    ListType *lst;
    UInt16 n;
    UInt16 i;
    UInt16 idx;
    UInt16 recIdx;
    MemHandle h;
    UInt8 *p;
    UInt8 typ;
    Char *ptr;

    frm = FrmGetActiveForm();
    if (!frm) return;
    lst = (ListType *)FrmGetObjectPtr(frm,
              FrmGetObjectIndex(frm, MAIN_LST_RECORDS));
    if (!lst) return;

    n = DmNumRecords(gDB);
    idx = 0;
    ptr = gListBuf;

    /* Walk newest → oldest, take only those matching gSelType */
    for (i = 0; i < n && idx < LIST_MAX_ROWS; i++) {
        recIdx = n - 1 - i;
        h = DmQueryRecord(gDB, recIdx);
        if (!h) continue;
        p = (UInt8 *)MemHandleLock(h);
        if (!p) continue;
        typ = p[1];
        MemHandleUnlock(h);
        if (typ != gSelType) continue;

        FormatRow(recIdx, ptr);
        gListPtrs[idx]   = ptr;
        gListRecIdx[idx] = recIdx;
        ptr += StrLen(ptr) + 1;
        idx++;
    }
    gListCount = idx;
    LstSetListChoices(lst, idx == 0 ? NULL : gListPtrs, idx);
    LstSetSelection(lst, noListSelection);
    LstDrawList(lst);

    UpdatePendingLabel();
}

/* --------------------------------------------------------------- save (main) */

static void StatusMsg(UInt16 lblID, const Char *msg) {
    FormType *frm;
    frm = FrmGetActiveForm();
    if (frm) FrmCopyLabel(frm, lblID, msg);
}

static void SaveCurrent(void) {
    FormType *frm;
    FieldType *fld;
    UInt16 bodyLen;
    MemHandle bodyH;
    Char *body;
    UInt16 recIdx;
    MemHandle recH;
    UInt8 *recP;
    UInt16 totalSz;
    UInt32 now;
    UInt8 hdr[REC_HDR_LEN];

    frm = FrmGetActiveForm();
    if (!frm) return;
    fld = (FieldType *)FrmGetObjectPtr(frm,
              FrmGetObjectIndex(frm, MAIN_FLD_BODY));
    if (!fld) return;

    bodyLen = FldGetTextLength(fld);
    if (bodyLen == 0) {
        StatusMsg(MAIN_LBL_STATUS, "type something");
        return;
    }
    bodyH = FldGetTextHandle(fld);
    body  = bodyH ? (Char *)MemHandleLock(bodyH) : (Char *)"";

    totalSz = REC_HDR_LEN + bodyLen;
    recIdx = dmMaxRecordIndex;
    recH = DmNewRecord(gDB, &recIdx, totalSz);
    if (!recH) {
        if (bodyH) MemHandleUnlock(bodyH);
        StatusMsg(MAIN_LBL_STATUS, "save failed");
        return;
    }
    recP = (UInt8 *)MemHandleLock(recH);
    if (!recP) {
        if (bodyH) MemHandleUnlock(bodyH);
        DmReleaseRecord(gDB, recIdx, false);
        StatusMsg(MAIN_LBL_STATUS, "lock failed");
        return;
    }

    now = TimGetSeconds();
    hdr[0]  = 0x01;
    hdr[1]  = gSelType;
    hdr[2]  = STATUS_DRAFT;
    hdr[3]  = 0;
    hdr[4]  = (UInt8)(now >> 24);
    hdr[5]  = (UInt8)(now >> 16);
    hdr[6]  = (UInt8)(now >> 8);
    hdr[7]  = (UInt8)(now);
    hdr[8]  = (UInt8)(bodyLen >> 8);
    hdr[9]  = (UInt8)(bodyLen);
    hdr[10] = 0;
    hdr[11] = 0;

    DmWrite(recP, 0, hdr, REC_HDR_LEN);
    DmWrite(recP, REC_HDR_LEN, body, bodyLen);

    MemHandleUnlock(recH);
    DmReleaseRecord(gDB, recIdx, true);
    if (bodyH) MemHandleUnlock(bodyH);

    FldDelete(fld, 0, FldGetTextLength(fld));
    FldDrawField(fld);

    StatusMsg(MAIN_LBL_STATUS, "saved");
    RebuildList();
}

/* --------------------------------------------------------------- detail */

/* Decode the header in-place from the given Char* into the locals,
 * keeping the chunk pointer (used to access body/answer bytes). */
static void DetailPopulate(void) {
    FormType *frm;
    MemHandle h;
    UInt8 *p;
    UInt8 type;
    UInt8 status;
    UInt16 bodyLen;
    UInt16 ansLen;
    const Char *typeStr;
    const Char *statusStr;
    FieldType *bodyFld;
    FieldType *ansFld;
    MemHandle txtH;
    MemHandle ansH;
    Char *txt;
    Char *ansTxt;

    if (gOpenIdx == NO_REC) return;
    frm = FrmGetActiveForm();
    if (!frm) return;

    h = DmQueryRecord(gDB, gOpenIdx);
    if (!h) return;
    p = (UInt8 *)MemHandleLock(h);
    if (!p) return;

    type    = p[1];
    status  = p[2];
    bodyLen = ((UInt16)p[8] << 8) | (UInt16)p[9];
    ansLen  = ((UInt16)p[10] << 8) | (UInt16)p[11];

    typeStr   = (type == TYPE_AI)      ? "AI"
              : (type == TYPE_THOUGHT) ? "thought"
              : (type == TYPE_TODO)    ? "todo"
              :                          "?";
    statusStr = (status == STATUS_DRAFT)    ? "draft"
              : (status == STATUS_SYNCED)   ? "synced"
              : (status == STATUS_ANSWERED) ? "answered"
              : (status == STATUS_DONE)     ? "done"
              :                                "?";

    FrmCopyLabel(frm, DETAIL_LBL_TYPE,   typeStr);
    FrmCopyLabel(frm, DETAIL_LBL_STATUS, statusStr);

    /* Body field — allocate own handle so the field owns + frees it
     * when the form closes. */
    bodyFld = (FieldType *)FrmGetObjectPtr(frm,
                  FrmGetObjectIndex(frm, DETAIL_FLD_BODY));
    if (bodyFld) {
        txtH = MemHandleNew(bodyLen + 1);
        if (txtH) {
            txt = (Char *)MemHandleLock(txtH);
            if (txt) {
                if (bodyLen) MemMove(txt, p + REC_HDR_LEN, bodyLen);
                txt[bodyLen] = 0;
                MemHandleUnlock(txtH);
                FldSetTextHandle(bodyFld, txtH);
                FldRecalculateField(bodyFld, true);
                FldDrawField(bodyFld);
            } else {
                MemHandleFree(txtH);
            }
        }
    }

    /* Answer field — read-only display, also gets its own handle. */
    ansFld = (FieldType *)FrmGetObjectPtr(frm,
                 FrmGetObjectIndex(frm, DETAIL_FLD_ANSWER));
    if (ansFld) {
        if (ansLen > 0) {
            ansH = MemHandleNew(ansLen + 1);
            if (ansH) {
                ansTxt = (Char *)MemHandleLock(ansH);
                if (ansTxt) {
                    MemMove(ansTxt, p + REC_HDR_LEN + bodyLen, ansLen);
                    ansTxt[ansLen] = 0;
                    MemHandleUnlock(ansH);
                    FldSetTextHandle(ansFld, ansH);
                    FldRecalculateField(ansFld, true);
                } else {
                    MemHandleFree(ansH);
                }
            }
        } else {
            FldSetTextHandle(ansFld, NULL);
        }
        FldDrawField(ansFld);
    }

    MemHandleUnlock(h);
}

static void DetailDelete(void) {
    if (gOpenIdx == NO_REC) return;
    DmRemoveRecord(gDB, gOpenIdx);
    gOpenIdx = NO_REC;
}

static void DetailMarkDone(void) {
    MemHandle h;
    UInt8 *p;
    UInt8 s;

    if (gOpenIdx == NO_REC) return;
    s = STATUS_DONE;
    h = DmGetRecord(gDB, gOpenIdx);
    if (!h) return;
    p = (UInt8 *)MemHandleLock(h);
    if (!p) {
        DmReleaseRecord(gDB, gOpenIdx, false);
        return;
    }
    DmWrite(p, 2, &s, 1);
    MemHandleUnlock(h);
    DmReleaseRecord(gDB, gOpenIdx, true);
}

/* Save edits made in the detail body field. Rebuilds the record:
 *   - preserve type + ctime + answer bytes
 *   - replace body bytes with the field's current text
 *   - flip status to draft (will re-sync on next push)
 *
 * Implementation: capture the old answer bytes into a temp handle,
 * call DmResizeRecord to set the new total size, rewrite the header
 * + body + answer at the right offsets. */
static void DetailSaveEdit(void) {
    FormType *frm;
    FieldType *bodyFld;
    MemHandle bodyH;
    UInt16 bodyLen;
    Char *body;
    MemHandle existing;
    UInt8 *existingP;
    UInt8 type;
    UInt32 ctime;
    UInt16 oldBodyLen;
    UInt16 ansLen;
    MemHandle ansBuf;
    UInt8 *ansBufP;
    MemHandle resized;
    UInt8 *p;
    UInt16 newSize;
    UInt8 hdr[REC_HDR_LEN];

    bodyH = NULL;
    body = NULL;
    ansBuf = NULL;

    if (gOpenIdx == NO_REC) return;
    frm = FrmGetActiveForm();
    if (!frm) return;
    bodyFld = (FieldType *)FrmGetObjectPtr(frm,
                  FrmGetObjectIndex(frm, DETAIL_FLD_BODY));
    if (!bodyFld) return;

    bodyLen = FldGetTextLength(bodyFld);
    bodyH = FldGetTextHandle(bodyFld);
    body = bodyH ? (Char *)MemHandleLock(bodyH) : NULL;

    /* Capture header + answer bytes from the existing record. */
    existing = DmQueryRecord(gDB, gOpenIdx);
    if (!existing) goto cleanup;
    existingP = (UInt8 *)MemHandleLock(existing);
    if (!existingP) goto cleanup;

    type       = existingP[1];
    ctime      = ((UInt32)existingP[4] << 24) | ((UInt32)existingP[5] << 16)
               | ((UInt32)existingP[6] << 8)  | (UInt32)existingP[7];
    oldBodyLen = ((UInt16)existingP[8] << 8)  | (UInt16)existingP[9];
    ansLen     = ((UInt16)existingP[10] << 8) | (UInt16)existingP[11];

    if (ansLen > 0) {
        ansBuf = MemHandleNew(ansLen);
        if (ansBuf) {
            ansBufP = (UInt8 *)MemHandleLock(ansBuf);
            if (ansBufP) {
                MemMove(ansBufP, existingP + REC_HDR_LEN + oldBodyLen, ansLen);
                MemHandleUnlock(ansBuf);
            } else {
                MemHandleFree(ansBuf);
                ansBuf = NULL;
                ansLen = 0;
            }
        } else {
            ansLen = 0;
        }
    }
    MemHandleUnlock(existing);

    /* Resize record and rewrite. */
    newSize = REC_HDR_LEN + bodyLen + ansLen;
    resized = DmResizeRecord(gDB, gOpenIdx, newSize);
    if (!resized) goto cleanup;
    p = (UInt8 *)MemHandleLock(resized);
    if (!p) {
        DmReleaseRecord(gDB, gOpenIdx, false);
        goto cleanup;
    }

    hdr[0]  = 0x01;
    hdr[1]  = type;
    hdr[2]  = STATUS_DRAFT;            /* edit → needs re-sync */
    hdr[3]  = 0;
    hdr[4]  = (UInt8)(ctime >> 24);
    hdr[5]  = (UInt8)(ctime >> 16);
    hdr[6]  = (UInt8)(ctime >> 8);
    hdr[7]  = (UInt8)(ctime);
    hdr[8]  = (UInt8)(bodyLen >> 8);
    hdr[9]  = (UInt8)(bodyLen);
    hdr[10] = (UInt8)(ansLen >> 8);
    hdr[11] = (UInt8)(ansLen);

    DmWrite(p, 0, hdr, REC_HDR_LEN);
    if (bodyLen && body) DmWrite(p, REC_HDR_LEN, body, bodyLen);
    if (ansLen && ansBuf) {
        ansBufP = (UInt8 *)MemHandleLock(ansBuf);
        if (ansBufP) {
            DmWrite(p, REC_HDR_LEN + bodyLen, ansBufP, ansLen);
            MemHandleUnlock(ansBuf);
        }
    }

    MemHandleUnlock(resized);
    DmReleaseRecord(gDB, gOpenIdx, true);

cleanup:
    if (bodyH) MemHandleUnlock(bodyH);
    if (ansBuf) MemHandleFree(ansBuf);
}

/* --------------------------------------------------------------- handlers */

static Boolean MainFormHandleEvent(EventType *e) {
    Boolean handled;
    FormType *frm;
    UInt16 sel;

    handled = false;

    switch (e->eType) {
        case frmOpenEvent:
            frm = FrmGetActiveForm();
            FrmDrawForm(frm);
            FrmSetControlGroupSelection(frm, 1,
                MAIN_BTN_AI + (gSelType - TYPE_AI));
            RebuildList();
            handled = true;
            break;

        case ctlSelectEvent:
            switch (e->data.ctlSelect.controlID) {
                case MAIN_BTN_AI:
                    gSelType = TYPE_AI;
                    RebuildList();
                    break;
                case MAIN_BTN_THOUGHT:
                    gSelType = TYPE_THOUGHT;
                    RebuildList();
                    break;
                case MAIN_BTN_TODO:
                    gSelType = TYPE_TODO;
                    RebuildList();
                    break;
                case MAIN_BTN_SAVE:
                    SaveCurrent();
                    break;
            }
            handled = true;
            break;

        case lstSelectEvent:
            if (e->data.lstSelect.listID == MAIN_LST_RECORDS) {
                sel = e->data.lstSelect.selection;
                if (sel < gListCount) {
                    gOpenIdx = gListRecIdx[sel];
                    FrmGotoForm(FORM_DETAIL);
                }
                handled = true;
            }
            break;

        default:
            break;
    }
    return handled;
}

static Boolean DetailFormHandleEvent(EventType *e) {
    Boolean handled;
    FormType *frm;

    handled = false;

    switch (e->eType) {
        case frmOpenEvent:
            frm = FrmGetActiveForm();
            FrmDrawForm(frm);
            DetailPopulate();
            handled = true;
            break;

        case ctlSelectEvent:
            switch (e->data.ctlSelect.controlID) {
                case DETAIL_BTN_SAVE:
                    DetailSaveEdit();
                    FrmGotoForm(FORM_MAIN);
                    handled = true;
                    break;
                case DETAIL_BTN_BACK:
                    FrmGotoForm(FORM_MAIN);
                    handled = true;
                    break;
                case DETAIL_BTN_DELETE:
                    DetailDelete();
                    FrmGotoForm(FORM_MAIN);
                    handled = true;
                    break;
                case DETAIL_BTN_DONE:
                    DetailMarkDone();
                    FrmGotoForm(FORM_MAIN);
                    handled = true;
                    break;
            }
            break;

        default:
            break;
    }
    return handled;
}

/* --------------------------------------------------------------- event loop */

static void AppEventLoop(void) {
    EventType ev;
    UInt16 err;
    FormType *frm;

    do {
        EvtGetEvent(&ev, evtWaitForever);
        if (SysHandleEvent(&ev)) continue;
        if (MenuHandleEvent(NULL, &ev, &err)) continue;

        if (ev.eType == frmLoadEvent) {
            frm = FrmInitForm(ev.data.frmLoad.formID);
            FrmSetActiveForm(frm);
            switch (ev.data.frmLoad.formID) {
                case FORM_MAIN:
                    FrmSetEventHandler(frm, MainFormHandleEvent);
                    break;
                case FORM_DETAIL:
                    FrmSetEventHandler(frm, DetailFormHandleEvent);
                    break;
            }
            continue;
        }

        FrmDispatchEvent(&ev);
    } while (ev.eType != appStopEvent);
}

/* --------------------------------------------------------------- main */

UInt32 PilotMain(UInt16 cmd, void *cmdPBP, UInt16 launchFlags) {
    UInt16 err;

    if (cmd != sysAppLaunchCmdNormalLaunch) return 0;

    err = OpenOrCreateDB();
    if (err) return err;

    FrmGotoForm(FORM_MAIN);
    AppEventLoop();

    if (gDB) DmCloseDatabase(gDB);
    return 0;
}
