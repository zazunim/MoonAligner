import Foundation

/// GPS 위경도 및 고도 좌표 모델
public struct GeoPoint: Equatable, Hashable, Codable {
    public var latitudeDeg: Double
    public var longitudeDeg: Double
    public var altitudeMeters: Double

    public init(latitudeDeg: Double, longitudeDeg: Double, altitudeMeters: Double = 0.0) {
        self.latitudeDeg = latitudeDeg
        self.longitudeDeg = longitudeDeg
        self.altitudeMeters = altitudeMeters
    }
}
