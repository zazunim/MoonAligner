import SwiftUI
import CoreLocation
#if canImport(UIKit)
import UIKit
#endif

struct ContentView: View {
    @StateObject private var sharedState = PlannerSharedState()
    
    init() {
        #if canImport(UIKit)
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor.surfaceDarkUI
        
        let itemAppearance = UITabBarItemAppearance()
        itemAppearance.normal.iconColor = UIColor.textSecondaryUI
        itemAppearance.normal.titleTextAttributes = [.foregroundColor: UIColor.textSecondaryUI]
        
        itemAppearance.selected.iconColor = UIColor.sunGoldUI
        itemAppearance.selected.titleTextAttributes = [.foregroundColor: UIColor.sunGoldUI]
        
        appearance.stackedLayoutAppearance = itemAppearance
        appearance.inlineLayoutAppearance = itemAppearance
        appearance.compactInlineLayoutAppearance = itemAppearance
        
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
        #endif
    }
    
    var body: some View {
        TabView(selection: $sharedState.selectedTab) {
            ShotPlannerView(sharedState: sharedState)
                .tabItem {
                    Label("정렬 플래너", systemImage: "map.fill")
                }
                .tag(0)
            
            CalculatorView(sharedState: sharedState)
                .tabItem {
                    Label("화각 계산기", systemImage: "scope")
                }
                .tag(1)
            
            ArTrackerView(sharedState: sharedState)
                .tabItem {
                    Label("AR 뷰파인더", systemImage: "camera.viewfinder")
                }
                .tag(2)
        }
        .accentColor(ColorTheme.sunGold)
        .background(ColorTheme.nightSky)
    }
}

#Preview {
    ContentView()
}
