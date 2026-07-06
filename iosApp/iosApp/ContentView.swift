import SharedUI
import SwiftUI

/// Hosts the shared Compose UI (VoiceAgentScreen) inside SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    let controller: VoiceAgentController

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(controller: controller)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    let controller: VoiceAgentController

    var body: some View {
        ComposeView(controller: controller)
            .ignoresSafeArea(.keyboard) // Compose handles its own insets
    }
}
