import CryptoKit
import Foundation

/// The two shared models every voice needs (HuBERT content features, RMVPE pitch): downloaded once from the project's
/// release page and checked against their SHA-256. Voice models are never downloaded; users bring their own.
/// When the download fails, the files can still be put into common/ by hand (or made with the PC converter).
final class CommonDownloader: NSObject, URLSessionDownloadDelegate {
    struct Item {
        let name: String
        let bytes: Int64
        let sha256: String
    }

    static let base = URL(string: "https://github.com/ys05086/rvc-voice-studio-mobile/releases/download/common-v1/")!
    static let items = [
        Item(name: "hubert.onnx", bytes: 377_684_408, sha256: "97b93aa2bdbd21dd5bc42feab375d95f107f8b7c22ca0fa0a13548eba7d9df50"),
        Item(name: "rmvpe.onnx", bytes: 366_167_422, sha256: "6ae60d54b5b2f6633079aa8246637486e2fef8cfbbb9a60386f090e0ddcdf1b4"),
    ]
    static var totalBytes: Int64 { items.reduce(0) { $0 + $1.bytes } }

    private let target: URL
    private let progress: (Double) -> Void
    private let finished: (Error?) -> Void
    private var session: URLSession?
    private var queue: [Item] = []
    private var current: Item?
    private var doneBytes: Int64 = 0
    private var problem: Error?

    /// progress (0...1) and finished (nil = both files in place) are called on the main queue.
    init(into target: URL, progress: @escaping (Double) -> Void, finished: @escaping (Error?) -> Void) {
        self.target = target
        self.progress = progress
        self.finished = finished
    }

    func start() {
        let fm = FileManager.default
        try? fm.createDirectory(at: target, withIntermediateDirectories: true)
        queue = Self.items.filter { !fm.fileExists(atPath: target.appendingPathComponent($0.name).path) }
        doneBytes = Self.totalBytes - queue.reduce(0) { $0 + $1.bytes }
        session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        next()
    }

    func cancel() {
        session?.invalidateAndCancel()
    }

    private func next() {
        guard !queue.isEmpty, let session else {
            self.session?.finishTasksAndInvalidate()
            DispatchQueue.main.async { self.finished(nil) }
            return
        }
        current = queue.removeFirst()
        problem = nil
        session.downloadTask(with: Self.base.appendingPathComponent(current!.name)).resume()
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let done = Double(doneBytes + totalBytesWritten) / Double(Self.totalBytes)
        DispatchQueue.main.async { self.progress(min(done, 1)) }
    }

    /// The file has to be dealt with before this returns (iOS deletes it afterwards).
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let item = current else { return }
        if let http = downloadTask.response as? HTTPURLResponse, http.statusCode != 200 {
            problem = RvcError("내려받기 주소가 HTTP \(http.statusCode)로 답했어요")
            return
        }
        do {
            let hash = try CommonDownloader.sha256(location)
            guard hash == item.sha256 else { throw RvcError("\(item.name) 파일이 손상됐어요 (체크섬이 달라요)") }
            let destination = target.appendingPathComponent(item.name)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: location, to: destination)
            doneBytes += item.bytes
        } catch {
            problem = error
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let failure = error ?? problem {
            session.invalidateAndCancel()
            DispatchQueue.main.async { self.finished(failure) }
            return
        }
        next()
    }

    static func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = try autoreleasepool { try handle.read(upToCount: 4 << 20) }
            guard let chunk, !chunk.isEmpty else { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
