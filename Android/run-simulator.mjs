/**
 * MoonAligner 핵심 광학 및 천체 계산 엔진 시뮬레이터 (Node.js 버전)
 * Android 앱의 Kotlin 코드(OpticsCalculator, EphemerisEngine)와 100% 동일한 수식으로 작동합니다.
 */

const MOON_RADIUS_KM = 1737.4;
const DEG_TO_RAD = Math.PI / 180.0;
const RAD_TO_DEG = 180.0 / Math.PI;

function calculateDistance(subjectHeightMeters, scaleRatioToMoon = 1.0, moonAngularDiameterDeg = 0.5181) {
    const targetAngularRad = (moonAngularDiameterDeg * scaleRatioToMoon) * DEG_TO_RAD;
    return subjectHeightMeters / (2.0 * Math.tan(targetAngularRad / 2.0));
}

function calculateMoonImageOnSensor(focalLengthMm, moonAngularDiameterDeg = 0.5181) {
    const angleRad = moonAngularDiameterDeg * DEG_TO_RAD;
    return 2.0 * focalLengthMm * Math.tan(angleRad / 2.0);
}

function calculateFocalLengthForCoverage(sensorHeightMm, coverageFraction, moonAngularDiameterDeg = 0.5181) {
    const targetImageMm = sensorHeightMm * coverageFraction;
    const angleRad = moonAngularDiameterDeg * DEG_TO_RAD;
    return targetImageMm / (2.0 * Math.tan(angleRad / 2.0));
}

// 천체 계산 (현재 시각 서울 기준)
function calculateMoonPosition(date, latDeg = 37.5665, lonDeg = 126.9780) {
    const jd = 2440587.5 + (date.getTime() / 86400000.0);
    const t = (jd - 2451545.0) / 36525.0;

    const lPrime = (218.3164477 + 481267.88128 * t) % 360.0;
    const d = (297.8501921 + 445267.1114034 * t) % 360.0;
    const m = (357.5291092 + 35999.0502909 * t) % 360.0;
    const mPrime = (134.9633964 + 477198.8675055 * t) % 360.0;
    const f = (93.2720950 + 483202.0175233 * t) % 360.0;

    const dRad = d * DEG_TO_RAD;
    const mRad = m * DEG_TO_RAD;
    const mPrimeRad = mPrime * DEG_TO_RAD;
    const fRad = f * DEG_TO_RAD;

    const lambda = lPrime +
        6.288774 * Math.sin(mPrimeRad) +
        1.274027 * Math.sin(2.0 * dRad - mPrimeRad) +
        0.658314 * Math.sin(2.0 * dRad);

    const beta = 5.128178 * Math.sin(fRad) +
        0.280602 * Math.sin(mPrimeRad + fRad);

    const distanceKm = 385000.56 -
        20905.355 * Math.cos(mPrimeRad) -
        3699.111 * Math.cos(2.0 * dRad - mPrimeRad) -
        2955.968 * Math.cos(2.0 * dRad);

    const eps = (23.439291 - 0.0130042 * t) * DEG_TO_RAD;
    const lambdaRad = lambda * DEG_TO_RAD;
    const betaRad = beta * DEG_TO_RAD;

    const sinDec = Math.sin(betaRad) * Math.cos(eps) + Math.cos(betaRad) * Math.sin(eps) * Math.sin(lambdaRad);
    const dec = Math.asin(sinDec);
    const yRa = Math.sin(lambdaRad) * Math.cos(eps) - Math.tan(betaRad) * Math.sin(eps);
    const xRa = Math.cos(lambdaRad);
    const ra = Math.atan2(yRa, xRa);

    const latRad = latDeg * DEG_TO_RAD;
    let gmst = (280.46061837 + 360.98564736629 * (jd - 2451545.0)) % 360.0;
    if (gmst < 0) gmst += 360.0;
    const lstRad = ((gmst + lonDeg) % 360.0) * DEG_TO_RAD;
    const ha = lstRad - ra;

    const sinAlt = Math.sin(latRad) * Math.sin(dec) + Math.cos(latRad) * Math.cos(dec) * Math.cos(ha);
    const altRad = Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt)));
    const cosAz = (Math.sin(dec) - Math.sin(latRad) * Math.sin(altRad)) / (Math.cos(latRad) * Math.cos(altRad));
    const sinAz = -Math.cos(dec) * Math.sin(ha) / Math.cos(altRad);
    let azDeg = Math.atan2(sinAz, cosAz) * RAD_TO_DEG;
    if (azDeg < 0) azDeg += 360.0;

    const angularDiameter = 2.0 * Math.asin(MOON_RADIUS_KM / distanceKm) * RAD_TO_DEG;
    const phaseAngle = (d % 360.0 + 360.0) % 360.0;
    const illumination = (1.0 - Math.cos(phaseAngle * DEG_TO_RAD)) / 2.0;

    return {
        azimuthDeg: azDeg,
        altitudeDeg: altRad * RAD_TO_DEG,
        distanceKm,
        angularDiameterDeg: angularDiameter,
        illuminationFraction: illumination
    };
}

// -------------------------------------------------------------
// 실행 시뮬레이션 출력
// -------------------------------------------------------------
console.log("================================================================================");
console.log("🌙 MoonAligner 광학 및 천체 거리 계산 시뮬레이션");
console.log("================================================================================");

console.log("\n[1] 초망원 렌즈 배경 압축 거리 계산 (달 크기와 피사체 크기 1:1 일치 시)");
const subjects = [
    { name: "사람 전신 실루엣", height: 1.8 },
    { name: "전통 정자/누각", height: 6.0 },
    { name: "석탑/조각상", height: 10.0 },
    { name: "N서울타워 송신탑 부", height: 25.0 },
    { name: "고층 아파트/빌딩", height: 50.0 },
];

for (const sub of subjects) {
    const dist = calculateDistance(sub.height, 1.0);
    const distStr = dist >= 1000 ? `${(dist / 1000).toFixed(2)} km (${Math.round(dist)} m)` : `${dist.toFixed(1)} m`;
    console.log(` - ${sub.name.padEnd(16)} (높이: ${sub.height}m) ➔ 필요 촬영 거리: ${distStr}`);
}

console.log("\n[2] 렌즈 초점거리별 센서 상 달 크기 (풀프레임 세로 24mm 기준)");
const focalLengths = [200, 400, 600, 800, 1200];
for (const fl of focalLengths) {
    const moonMm = calculateMoonImageOnSensor(fl);
    const coverage = (moonMm / 24.0) * 100.0;
    console.log(` - 초점거리 ${String(fl).padStart(4)}mm ➔ 센서상 달 지름: ${moonMm.toFixed(2)}mm (세로 화면의 ${coverage.toFixed(1)}% 점유)`);
}

const reqFl50 = calculateFocalLengthForCoverage(24.0, 0.5);
console.log(` - [Tip] 달이 화면 세로의 50%를 거대하게 채우려면: 약 ${Math.round(reqFl50)}mm 렌즈 필요 (예: 600mm + 2x 텔레컨버터)`);

console.log("\n[3] 실시간 천체 위치 (서울 기준: 위도 37.5665°, 경도 126.9780°)");
const now = new Date();
const moonNow = calculateMoonPosition(now);
console.log(` - 관측 시각: ${now.toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })}`);
console.log(` - 달 방위각(Azimuth) : ${moonNow.azimuthDeg.toFixed(1)}°`);
console.log(` - 달 고도각(Altitude): ${moonNow.altitudeDeg.toFixed(1)}° (${moonNow.altitudeDeg > 0 ? "지평선 위 🟢" : "지평선 아래 🔴"})`);
console.log(` - 달-지구 거리       : ${Math.round(moonNow.distanceKm).toLocaleString()} km`);
console.log(` - 달의 시직경        : ${moonNow.angularDiameterDeg.toFixed(3)}°`);
console.log(` - 달 조도율(위상)    : ${(moonNow.illuminationFraction * 100).toFixed(1)}%`);
console.log("================================================================================");
