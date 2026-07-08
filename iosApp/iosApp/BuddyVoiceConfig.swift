import Foundation

/// Proxy settings for the sample app, read at runtime from the gitignored
/// `BuddyVoiceConfig.plist` (copy `BuddyVoiceConfig.example.plist`).
/// Holds only the proxy URL and shared secret — never a provider API key.
enum BuddyVoiceConfig {
    struct Values {
        let proxyBaseUrl: String
        let proxyKey: String?
    }

    static func load() -> Values {
        guard
            let url = Bundle.main.url(forResource: "BuddyVoiceConfig", withExtension: "plist"),
            let dict = NSDictionary(contentsOf: url) as? [String: Any]
        else {
            // Missing config surfaces as a connect error in the UI rather than a crash.
            return Values(proxyBaseUrl: "", proxyKey: nil)
        }
        let baseUrl = dict["ProxyBaseURL"] as? String ?? ""
        let key = dict["ProxyKey"] as? String
        return Values(
            proxyBaseUrl: baseUrl,
            proxyKey: (key?.isEmpty == false) ? key : nil
        )
    }
}
