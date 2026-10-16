// swift-tools-version: 5.7
import PackageDescription

let package = Package(
    name: "MoonAligner",
    platforms: [
        .iOS(.v16),
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "MoonAlignerCore",
            targets: ["MoonAlignerCore"]
        )
    ],
    targets: [
        .target(
            name: "MoonAlignerCore",
            path: "MoonAligner/Core"
        )
    ]
)
