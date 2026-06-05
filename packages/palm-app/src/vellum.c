/*
 * PalmVellum Capture v1.2 — on-device entry surface for AI / thought / todo.
 *
 * Changes vs v1.1:
 *   - Replaced the LIST widget with five NOFRAME LEFTANCHOR buttons
 *     (IDs 1200..1204). Tapping a row now fires ctlSelectEvent, which
 *     is the standard PalmOS path for form-switching from a tap and
 *     avoids the known dispatch quirk where FrmGotoForm inside
 *     lstSelectEvent could crash the list widget's own redraw pass.
 *   - Detail body field uses a fixed-size 1024-byte MemHandle so
 *     PalmOS doesn't have to grow it under the user during edits.
 *   - Detail answer field uses a static buffer via FldSetTextPtr —
 *     read-only display with no handle ownership ambiguity.
 *
 * Record byte format and VellumDB layout are unchanged; old/new
 * VellumDB.pdb backups remain byte-compatible.
 *
 * Compile target: -palmos3.5 (works on 4.x ROMs via backward compat).
 * Compiler: m68k-palmos-gcc 2.95.3 — C89, locals at block top.
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

#define MAIN_BTN_ROW_BASE  1200
#define MAIN_BTN_ROW_MAX   5

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

#define LIST_LBL_LEN       28
#define DETAIL_BUF_SIZE    1024
#define NO_REC             0xFFFF

/* --------------------------------------------------------------- globals */

static DmOpenRef gDB         = NULL;
static UInt8     gSelType    = TYPE_AI;
static UInt16    gOpenIdx    = NO_REC;
static Char      gListBuf[MAIN_BTN_ROW_MAX * LIST_LBL_LEN];
static Char     *gListPtrs[MAIN_BTN_ROW_MAX];
static UInt16    gListRecIdx[MAIN_BTN_ROW_MAX];
static UInt16    gListCount  = 0;

/* Static buffers for the detail form's body/answer fields. Using
 * FldSetTextPtr against these avoids the handle ownership ambiguity
 * that PalmOS' field code can get tangled in when an editable field
 * is given a too-small handle (the most likely v1.1 crash). */
static Char      gDetailAnsBuf[DETAIL_BUF_SIZE];

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

/* Count records with status == STATUS_DRAFT (any type) — drives the
 * "N to sync" banner on the main form. */
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

/* --------------------------------------------------------------- row helpers */

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

/* Walk records newest-first, fill gListBuf/gListPtrs/gListRecIdx with
 * up to MAIN_BTN_ROW_MAX matching entries, then assign each as the
 * label of the corresponding row button and reveal it. Hide unused
 * slots. */
static void RebuildList(void) {
    FormType *frm;
    UInt16 n;
    UInt16 i;
    UInt16 idx;
    UInt16 recIdx;
    UInt16 objIdx;
    MemHandle h;
    UInt8 *p;
    UInt8 typ;
    Char *ptr;
    ControlType *ctl;

    frm = FrmGetActiveForm();
    if (!frm) return;

    n = DmNumRecords(gDB);
    idx = 0;
    ptr = gListBuf;

    for (i = 0; i < n && idx < MAIN_BTN_ROW_MAX; i++) {
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

    /* Apply to buttons */
    for (i = 0; i < MAIN_BTN_ROW_MAX; i++) {
        objIdx = FrmGetObjectIndex(frm, MAIN_BTN_ROW_BASE + i);
        if (objIdx == (UInt16)frmInvalidObjectId) continue;
        ctl = (ControlType *)FrmGetObjectPtr(frm, objIdx);
        if (!ctl) continue;
        if (i < idx) {
            CtlSetLabel(ctl, gListPtrs[i]);
            FrmShowObject(frm, objIdx);
        } else {
            FrmHideObject(frm, objIdx);
        }
    }

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

static void DetailPopulate(void) {
    FormType *frm;
    MemHandle h;
    UInt8 *p;
    UInt8 type;
    UInt8 status;
    UInt16 bodyLen;
    UInt16 ansLen;
    UInt16 lim;
    const Char *typeStr;
    const Char *statusStr;
    FieldType *bodyFld;
    FieldType *ansFld;
    MemHandle bodyH;
    Char *txt;

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

    /* Editable body — always allocate a full-size handle so PalmOS
     * has room to let the user grow the text without resizing under
     * us. The handle is owned by the field after FldSetTextHandle
     * and will be freed by PalmOS when the form closes. */
    bodyFld = (FieldType *)FrmGetObjectPtr(frm,
                  FrmGetObjectIndex(frm, DETAIL_FLD_BODY));
    if (bodyFld) {
        bodyH = MemHandleNew(DETAIL_BUF_SIZE);
        if (bodyH) {
            txt = (Char *)MemHandleLock(bodyH);
            if (txt) {
                MemSet(txt, DETAIL_BUF_SIZE, 0);
                lim = bodyLen < DETAIL_BUF_SIZE - 1 ? bodyLen
                                                    : DETAIL_BUF_SIZE - 1;
                if (lim) MemMove(txt, p + REC_HDR_LEN, lim);
                MemHandleUnlock(bodyH);
                FldSetTextHandle(bodyFld, bodyH);
                FldRecalculateField(bodyFld, true);
                FldDrawField(bodyFld);
            } else {
                MemHandleFree(bodyH);
            }
        }
    }

    /* Read-only answer — copy bytes into our static buffer and point
     * the field at it. No handle ownership question; safe to reset
     * to "" when there's no answer. */
    ansFld = (FieldType *)FrmGetObjectPtr(frm,
                 FrmGetObjectIndex(frm, DETAIL_FLD_ANSWER));
    if (ansFld) {
        MemSet(gDetailAnsBuf, DETAIL_BUF_SIZE, 0);
        if (ansLen > 0) {
            lim = ansLen < DETAIL_BUF_SIZE - 1 ? ansLen
                                               : DETAIL_BUF_SIZE - 1;
            MemMove(gDetailAnsBuf, p + REC_HDR_LEN + bodyLen, lim);
        }
        /* FldSetTextLength isn't in the 3.5 SDK header; the field reads
         * length by walking until NUL, and our buffer is zero-padded. */
        FldSetTextPtr(ansFld, gDetailAnsBuf);
        FldRecalculateField(ansFld, true);
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
    hdr[2]  = STATUS_DRAFT;
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
    UInt16 cid;
    UInt16 row;

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
            cid = e->data.ctlSelect.controlID;
            switch (cid) {
                case MAIN_BTN_AI:
                    gSelType = TYPE_AI;
                    RebuildList();
                    handled = true;
                    break;
                case MAIN_BTN_THOUGHT:
                    gSelType = TYPE_THOUGHT;
                    RebuildList();
                    handled = true;
                    break;
                case MAIN_BTN_TODO:
                    gSelType = TYPE_TODO;
                    RebuildList();
                    handled = true;
                    break;
                case MAIN_BTN_SAVE:
                    SaveCurrent();
                    handled = true;
                    break;
                default:
                    if (cid >= MAIN_BTN_ROW_BASE &&
                        cid < MAIN_BTN_ROW_BASE + MAIN_BTN_ROW_MAX) {
                        row = cid - MAIN_BTN_ROW_BASE;
                        if (row < gListCount &&
                            gListRecIdx[row] < DmNumRecords(gDB)) {
                            gOpenIdx = gListRecIdx[row];
                            FrmGotoForm(FORM_DETAIL);
                        }
                        handled = true;
                    }
                    break;
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
