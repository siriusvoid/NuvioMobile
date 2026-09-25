import BackgroundTasks
import Foundation
import UIKit

private let downloadsLiveStatusUpdatedNotification = Notification.Name("NuvioDownloadsLiveStatusUpdated")

/// Shows running downloads in the system's own progress UI, as a continued
/// processing task. The transfers run in the background URLSession and don't
/// depend on it: the task keeps the app running so progress stays live and the
/// queue moves on, and when it ends — expired, or stopped from that UI — the file
/// in flight still finishes, just without the progress UI.
final class DownloadsBackgroundTaskManager {
    static let shared = DownloadsBackgroundTaskManager()

    private var observers: [NSObjectProtocol] = []
    private var payload: DownloadsLiveStatusPayload?
    private var handledStartToken: Int64 = 0
    /// A `ContinuedDownloadTask` once submitted; typed loosely for the iOS 26 check.
    private var current: AnyObject?

    private init() {}

    func start() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: downloadsLiveStatusUpdatedNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            self?.payload = (notification.object as? String).flatMap(DownloadsLiveStatusPayload.decode)
            self?.sync()
        })
        // A download started while the app wasn't in front gets its UI once it is.
        observers.append(center.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.sync()
        })
    }

    private func sync() {
        guard #available(iOS 26.0, *) else { return }

        guard let payload, payload.status == "Downloading" else {
            // Nothing left, or everything paused or failed: no progress is coming.
            (current as? ContinuedDownloadTask)?.finish(success: payload?.status != "Failed")
            current = nil
            return
        }
        if let running = current as? ContinuedDownloadTask {
            running.apply(payload)
            return
        }
        // The task may only be asked for from the foreground, for something the user
        // just started — not for the same downloads again after its UI was dismissed.
        guard payload.startToken != handledStartToken,
              UIApplication.shared.applicationState != .background else { return }
        handledStartToken = payload.startToken

        let task = ContinuedDownloadTask(payload: payload) { [weak self] ended in
            if self?.current === ended { self?.current = nil }
        }
        if task.submit() { current = task }
    }
}

@available(iOS 26.0, *)
private final class ContinuedDownloadTask {
    private let identifier: String
    private var latest: DownloadsLiveStatusPayload
    private var task: BGContinuedProcessingTask?
    private var isFinished = false
    private let onEnded: (ContinuedDownloadTask) -> Void

    init(payload: DownloadsLiveStatusPayload, onEnded: @escaping (ContinuedDownloadTask) -> Void) {
        // A fresh suffix each time: an identifier can be registered only once per process.
        identifier = "\(Bundle.main.bundleIdentifier ?? "com.nuvio.app").downloads.\(UUID().uuidString)"
        latest = payload
        self.onEnded = onEnded
    }

    func submit() -> Bool {
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
            guard let self, let task = task as? BGContinuedProcessingTask, !self.isFinished else {
                task.setTaskCompleted(success: true)
                return
            }
            self.attach(task)
        }
        guard registered else { return false }

        let request = BGContinuedProcessingTaskRequest(
            identifier: identifier,
            title: latest.title,
            subtitle: latest.subtitle
        )
        do {
            try BGTaskScheduler.shared.submit(request)
            return true
        } catch {
            NSLog("Downloads progress UI not started: %@", String(describing: error))
            return false
        }
    }

    func apply(_ payload: DownloadsLiveStatusPayload) {
        latest = payload
        guard let task else { return }
        task.progress.totalUnitCount = max(payload.totalUnits, 1)
        task.progress.completedUnitCount = min(payload.completedUnits, payload.totalUnits)
        if task.title != payload.title || task.subtitle != payload.subtitle {
            task.updateTitle(payload.title, subtitle: payload.subtitle)
        }
    }

    func finish(success: Bool) {
        guard !isFinished else { return }
        isFinished = true
        if let task {
            task.setTaskCompleted(success: success)
        } else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        }
    }

    private func attach(_ task: BGContinuedProcessingTask) {
        self.task = task
        // Called when the system ends it or the user stops it from its UI. The two
        // can't be told apart, so downloads carry on either way.
        task.expirationHandler = { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.finish(success: false)
                self.onEnded(self)
            }
        }
        apply(latest)
    }
}

private struct DownloadsLiveStatusPayload: Decodable {
    let title: String
    let subtitle: String
    let status: String
    let completedUnits: Int64
    let totalUnits: Int64
    let startToken: Int64

    static func decode(_ encoded: String) -> DownloadsLiveStatusPayload? {
        try? JSONDecoder().decode(DownloadsLiveStatusPayload.self, from: Data(encoded.utf8))
    }
}
