import Testing
import Foundation
@testable import PalmKit

@Test func icsParsesTimedAndAllDay() {
    let sample = """
    BEGIN:VCALENDAR
    BEGIN:VEVENT
    UID:abc123
    SUMMARY:Team Sync
    DTSTART:20260701T140000Z
    DTEND:20260701T150000Z
    LOCATION:Room 5
    DESCRIPTION:Weekly\\, standup
    END:VEVENT
    BEGIN:VEVENT
    UID:allday1
    SUMMARY:Holiday
    DTSTART;VALUE=DATE:20260704
    END:VEVENT
    END:VCALENDAR
    """
    let events = Ics.parse(sample)
    #expect(events.count == 2)
    #expect(events[0].summary == "Team Sync")
    #expect(events[0].allDay == false)
    #expect(events[0].location == "Room 5")
    #expect(events[0].description == "Weekly, standup")   // unescaped comma
    #expect(events[0].uid == "abc123")
    #expect(events[1].summary == "Holiday")
    #expect(events[1].allDay == true)
    // All-day DATE is timezone-independent → pinned to UTC midnight with the
    // exact canonical string the PWA / Android clients also emit, so the same
    // subscribed event de-dupes to one identical row across every client.
    #expect(events[1].startIso == "2026-07-04T00:00:00.000Z")
    // The timed event's DTSTART was UTC 14:00 → round-trips through Clock.
    #expect(Clock.parse(events[0].startIso) != nil)
}

@Test func icsLineUnfolding() {
    let sample = """
    BEGIN:VEVENT
    SUMMARY:A very long title that
      continues on the next line
    DTSTART:20260701T090000Z
    END:VEVENT
    """
    let events = Ics.parse(sample)
    #expect(events.count == 1)
    #expect(events[0].summary == "A very long title that continues on the next line")
}
