/* Minimal Palm OS 3.5 hello world — toolchain sanity check.
 * Compiled cross-target via the palmvellum/palm-toolchain Docker image
 * (m68k-palmos-gcc 2.95.3, C89 — all locals at block start). */
#include <PalmOS.h>

UInt32 PilotMain(UInt16 cmd, void *cmdPBP, UInt16 launchFlags) {
    EventType event;

    if (cmd == sysAppLaunchCmdNormalLaunch) {
        WinDrawChars("Hello Vellum", 12, 30, 50);
        do {
            EvtGetEvent(&event, evtWaitForever);
            SysHandleEvent(&event);
        } while (event.eType != appStopEvent);
    }
    return 0;
}
