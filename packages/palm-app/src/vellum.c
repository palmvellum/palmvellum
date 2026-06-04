/*
 * PalmVellum Capture v1 — on-device entry surface for AI / thought / todo.
 *
 * Compile target: Palm OS 3.5 ROM (`-palmos3.5`). Designed to also load on
 * 3.1 ROMs (Palm IIIe) by sticking to the legacy API subset.
 *
 * Compiler: m68k-palmos-gcc 2.95.3 — C89 only. ALL locals at block top.
 *
 * Local store: a single PDB named "VellumDB", creator 'PvV1', type 'Data'.
 * Each record is a self-describing byte string (versioned header). The
 * future HotSync conduit will scan this PDB on each sync, mirror new
 * status=0 rows up to Supabase, and write answers back in-place.
 *
 *   Record layout v1 (big-endian):
 *     u8  version    = 0x01
 *     u8  type       = 1 (ai) | 2 (thought) | 3 (todo)
 *     u8  status     = 0 (draft / not yet synced)
 *                      1 (synced to cloud)
 *                      2 (answered — AI only)
 *                      3 (done    — todo only)
 *     u8  reserved   = 0
 *     u32 ctime      = TimGetSeconds() at create time
 *     u16 bodyLen
 *     u16 ansLen
 *     [bodyLen bytes]  body  (UTF-8 / Palm Latin-1)
 *     [ansLen  bytes]  answer
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
#define MAIN_LST_RECORDS   1030

#define DETAIL_LBL_TYPE    1101
#define DETAIL_LBL_STATUS  1102
#define DETAIL_FLD_BODY    1110
#define DETAIL_FLD_ANSWER  1111
#define DETAIL_BTN_BACK    1120
#define DETAIL_BTN_DELETE  1121
#define DETAIL_BTN_DONE    1122

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

/* --------------------------------------------------------------- db */

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

/* --------------------------------------------------------------- list helpers */

static void FormatRow(UInt16 recIdx, Char *dst) {
    MemHandle h;
    UInt8 *p;
    UInt8 type;
    UInt8 status;
    UInt16 bodyLen;
    UInt16 i;
    UInt16 lim;
    Char tch = '?';
    Char sch = ' ';

    h = DmQueryRecord(gDB, recIdx);
    if (!h) {
        dst[0] = 0;
        return;
    }
    p = (UInt8 *)MemHandleLock(h);
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
        Char c = (Char)p[REC_HDR_LEN + i];
        if (c == 0x0A || c == 0x0D || c == 0x09) c = ' ';
        dst[3 + i] = c;
    }
    dst[3 + lim] = 0;

    MemHandleUnlock(h);
}

static void RebuildList(void) {
    FormType *frm;
    ListType *lst;
    UInt16 n;
    UInt16 i;
    UInt16 idx;
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
        UInt16 recIdx = n - 1 - i;
        MemHandle h = DmQueryRecord(gDB, recIdx);
        UInt8 *p;
        UInt8 typ;
        if (!h) continue;
        p = (UInt8 *)MemHandleLock(h);
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
}

/* --------------------------------------------------------------- save */

static void StatusMsg(UInt16 lblID, const Char *msg) {
    FormType *frm = FrmGetActiveForm();
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

    /* clear field */
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
    const Char *typeStr;
    const Char *statusStr;
    FieldType *bodyFld;
    FieldType *ansFld;
    MemHandle txtH;
    Char *txt;

    if (gOpenIdx == NO_REC) return;
    frm = FrmGetActiveForm();
    if (!frm) return;

    h = DmQueryRecord(gDB, gOpenIdx);
    if (!h) return;
    p = (UInt8 *)MemHandleLock(h);
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

    bodyFld = (FieldType *)FrmGetObjectPtr(frm,
                  FrmGetObjectIndex(frm, DETAIL_FLD_BODY));
    if (bodyFld) {
        txtH = MemHandleNew(bodyLen + 1);
        if (txtH) {
            txt = (Char *)MemHandleLock(txtH);
            if (bodyLen) MemMove(txt, p + REC_HDR_LEN, bodyLen);
            txt[bodyLen] = 0;
            MemHandleUnlock(txtH);
            FldSetTextHandle(bodyFld, txtH);
            FldDrawField(bodyFld);
        }
    }

    ansFld = (FieldType *)FrmGetObjectPtr(frm,
                 FrmGetObjectIndex(frm, DETAIL_FLD_ANSWER));
    if (ansFld) {
        if (ansLen > 0) {
            MemHandle aH = MemHandleNew(ansLen + 1);
            if (aH) {
                Char *a = (Char *)MemHandleLock(aH);
                MemMove(a, p + REC_HDR_LEN + bodyLen, ansLen);
                a[ansLen] = 0;
                MemHandleUnlock(aH);
                FldSetTextHandle(ansFld, aH);
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
    UInt8 s = STATUS_DONE;

    if (gOpenIdx == NO_REC) return;
    h = DmGetRecord(gDB, gOpenIdx);
    if (!h) return;
    p = (UInt8 *)MemHandleLock(h);
    DmWrite(p, 2, &s, 1);
    MemHandleUnlock(h);
    DmReleaseRecord(gDB, gOpenIdx, true);
}

/* --------------------------------------------------------------- form handlers */

static Boolean MainFormHandleEvent(EventType *e) {
    Boolean handled = false;
    FormType *frm;

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
                UInt16 sel = e->data.lstSelect.selection;
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
    Boolean handled = false;
    FormType *frm;

    switch (e->eType) {
        case frmOpenEvent:
            frm = FrmGetActiveForm();
            FrmDrawForm(frm);
            DetailPopulate();
            handled = true;
            break;

        case ctlSelectEvent:
            switch (e->data.ctlSelect.controlID) {
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

    do {
        EvtGetEvent(&ev, evtWaitForever);
        if (SysHandleEvent(&ev)) continue;
        if (MenuHandleEvent(NULL, &ev, &err)) continue;

        if (ev.eType == frmLoadEvent) {
            FormType *frm = FrmInitForm(ev.data.frmLoad.formID);
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
