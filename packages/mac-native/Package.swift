// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "PalmVellum",
    platforms: [.macOS(.v12)],
    products: [
        .executable(name: "PalmVellum", targets: ["PalmVellum"]),
        .library(name: "PalmKit", targets: ["PalmKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "6.29.0"),
        .package(url: "https://github.com/supabase/supabase-swift.git", from: "2.48.0"),
    ],
    targets: [
        // Pure-logic, testable core: models, local store, sync engine.
        .target(
            name: "PalmKit",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
                .product(name: "Supabase", package: "supabase-swift"),
            ]
        ),
        // SwiftUI macOS app shell.
        .executableTarget(
            name: "PalmVellum",
            dependencies: ["PalmKit"]
        ),
        .testTarget(
            name: "PalmKitTests",
            dependencies: ["PalmKit"]
        ),
    ]
)
