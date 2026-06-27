import Foundation
import GRDB

/// Type-erased handle for a live database observation, so the UI layer can
/// hold/cancel observations without importing GRDB. Cancels on deinit.
public final class ObservationToken {
    private let cancellable: AnyDatabaseCancellable
    init(_ cancellable: AnyDatabaseCancellable) { self.cancellable = cancellable }
    public func cancel() { cancellable.cancel() }
    deinit { cancellable.cancel() }
}
