import Testing
import Foundation
@testable import PalmKit

@Test func ulidFormat() {
    let id = Ulid.new()
    #expect(id.count == 26)
    let allowed = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    #expect(id.allSatisfy { allowed.contains($0) })
}

@Test func ulidTimeSortable() {
    let early = Ulid.new(date: Date(timeIntervalSince1970: 1_000))
    let late = Ulid.new(date: Date(timeIntervalSince1970: 2_000_000))
    #expect(String(early.prefix(10)) < String(late.prefix(10)))
}

@Test func javaHashKnownVectors() {
    // Matches java.lang.String.hashCode (and Kotlin / the PWA's javaHashCode).
    #expect(DeterministicId.javaHash("") == 0)
    #expect(DeterministicId.javaHash("a") == 97)
    #expect(DeterministicId.javaHash("test") == 3_556_498)
    #expect(DeterministicId.javaHash("hello") == 99_162_322)
}

@Test func deterministicIdsStable() {
    let url = "https://calendar.google.com/secret/basic.ics"
    #expect(DeterministicId.calsub(url: url) == DeterministicId.calsub(url: url))
    #expect(DeterministicId.calsub(url: url).hasPrefix("calsub"))
    #expect(DeterministicId.ics(url: url, key: "uid-1") == DeterministicId.ics(url: url, key: "uid-1"))
    #expect(DeterministicId.ics(url: url, key: "uid-1") != DeterministicId.ics(url: url, key: "uid-2"))
}

@Test func clockRoundTrip() {
    let iso = Clock.nowIso(Date(timeIntervalSince1970: 1_700_000_000))
    #expect(Clock.parse(iso) != nil)
    #expect(Clock.parse("2026-06-25T12:00:00+00:00") != nil)
    #expect(Clock.parse("2026-06-25T12:00:00Z") != nil)
}
