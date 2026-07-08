import AVFAudio
import SharedUI
import SwiftUI

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    /// One controller per app; it survives reconnects (tap the orb again).
    private let controller: VoiceAgentController = {
        let config = BuddyVoiceConfig.load()
        return MainViewControllerKt.createVoiceAgentController(
            proxyBaseUrl: config.proxyBaseUrl,
            proxyKey: config.proxyKey
        )
    }()

    var body: some Scene {
        WindowGroup {
            ContentView(controller: controller)
                .onAppear {
                    AVAudioApplication.requestRecordPermission { _ in /* state read lazily */ }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // No background audio mode in the Phase 2 sample: iOS suspends the
            // process and severs the socket anyway, so disconnect cleanly instead
            // of leaving the user a dead session (mirrors MainActivity.onStop).
            if phase == .background {
                controller.disconnect()
            }
        }
    }
}
