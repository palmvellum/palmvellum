import Foundation

/// The load-bearing merge decision, factored out so it can be unit-tested
/// without any network. Given the local row's state and the remote row's
/// `updated_at`, decide what the pull step should do.
public enum SyncMergeAction: Equatable {
    case applyRemote    // take the server version (new row, or remote moved while local was clean)
    case keepLocal      // leave local untouched (no remote change, or only local changed → push later)
    case conflict       // both sides changed since last sync
}

public enum SyncDecision {
    public static func decide(
        localExists: Bool,
        localIsDirty: Bool,
        localRemoteUpdatedAt: String?,
        remoteUpdatedAt: String
    ) -> SyncMergeAction {
        if !localExists { return .applyRemote }
        if !localIsDirty {
            return localRemoteUpdatedAt != remoteUpdatedAt ? .applyRemote : .keepLocal
        }
        // local has un-pushed changes
        if localRemoteUpdatedAt == remoteUpdatedAt { return .keepLocal } // only local changed
        return .conflict
    }
}
