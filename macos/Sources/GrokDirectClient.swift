import Foundation

/// Fetches weekly usage via OAuth + REST billing only.
final class GrokDirectClient {
    var pollInterval: TimeInterval = 300

    enum ClientError: LocalizedError {
        case notSignedIn
        case freePlan
        case http(Int, String)
        case parseFailed
        case network(Error)

        var errorDescription: String? {
            switch self {
            case .notSignedIn:
                return "Not signed in — use Sign In to Grok…"
            case .freePlan:
                return "This app requires a SuperGrok or SuperGrok Heavy plan to access usage limits."
            case .http(let code, let body):
                return "HTTP \(code): \(body.prefix(120))"
            case .parseFailed:
                return "Could not parse usage (session may have expired — Sign In again)"
            case .network(let e):
                return e.localizedDescription
            }
        }
    }

    func fetch(force: Bool = false) async throws -> UsageResponse {
        _ = force // refresh is always live against the billing API
        guard OidcSession.shared.hasTokens else {
            throw ClientError.notSignedIn
        }
        do {
            return try await BillingClient.fetch()
        } catch let e as ClientError {
            throw e
        } catch {
            throw ClientError.network(error)
        }
    }
}
