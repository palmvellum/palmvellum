import Testing
@testable import PalmKit

@Test func decisionNewRemoteRow() {
    #expect(SyncDecision.decide(localExists: false, localIsDirty: false,
                                localRemoteUpdatedAt: nil, remoteUpdatedAt: "t1") == .applyRemote)
}

@Test func decisionCleanLocalRemoteMoved() {
    // Local clean, remote changed since we last saw it → take remote.
    #expect(SyncDecision.decide(localExists: true, localIsDirty: false,
                                localRemoteUpdatedAt: "t1", remoteUpdatedAt: "t2") == .applyRemote)
}

@Test func decisionCleanLocalNoRemoteChange() {
    #expect(SyncDecision.decide(localExists: true, localIsDirty: false,
                                localRemoteUpdatedAt: "t1", remoteUpdatedAt: "t1") == .keepLocal)
}

@Test func decisionDirtyLocalOnlyLocalChanged() {
    // Local dirty, remote unchanged since last sync → keep local (push later).
    #expect(SyncDecision.decide(localExists: true, localIsDirty: true,
                                localRemoteUpdatedAt: "t1", remoteUpdatedAt: "t1") == .keepLocal)
}

@Test func decisionDirtyLocalBothChanged() {
    // Local dirty AND remote moved → conflict.
    #expect(SyncDecision.decide(localExists: true, localIsDirty: true,
                                localRemoteUpdatedAt: "t1", remoteUpdatedAt: "t2") == .conflict)
}

@Test func decisionDirtyLocalNeverSynced() {
    // Local dirty, never synced (remoteUpdatedAt nil) but remote exists → conflict.
    #expect(SyncDecision.decide(localExists: true, localIsDirty: true,
                                localRemoteUpdatedAt: nil, remoteUpdatedAt: "t1") == .conflict)
}
