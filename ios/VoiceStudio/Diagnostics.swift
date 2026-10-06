import Darwin
import Foundation

/// A diary in Documents/live_log.txt (visible in the Files app) that survives a crash: a line per second while the
/// realtime mode runs, and on a crash signal or an uncaught exception, the stage the converter was in.
enum Diagnostics {
    static let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("live_log.txt")
    static var fd: Int32 = -1
    /// The current stage as a C string, readable from a signal handler.
    static let stage: UnsafeMutablePointer<CChar> = {
        let p = UnsafeMutablePointer<CChar>.allocate(capacity: 64)
        p.initialize(repeating: 0, count: 64)
        return p
    }()

    static func open() {
        if let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int, size > 2_000_000 {
            try? FileManager.default.removeItem(at: url)
        }
        fd = Darwin.open(url.path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
        _ = stage
        for s in [SIGABRT, SIGSEGV, SIGBUS, SIGTRAP, SIGILL, SIGFPE] { _ = signal(s, crashHandler) }
        NSSetUncaughtExceptionHandler { e in
            Diagnostics.log("EXCEPTION \(e.name.rawValue): \(e.reason ?? "")")
            Diagnostics.log(e.callStackSymbols.prefix(20).joined(separator: "\n"))
        }
    }

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()
    private static let clockLock = NSLock()

    static func log(_ text: String) {
        guard fd >= 0 else { return }
        clockLock.lock()
        let stamp = clock.string(from: Date())
        clockLock.unlock()
        let line = stamp + " " + text + "\n"
        line.withCString { _ = Darwin.write(fd, $0, strlen($0)) }
    }

    static func setStage(_ s: StaticString) {
        let n = min(s.utf8CodeUnitCount, 63)
        memcpy(stage, s.utf8Start, n)
        stage[n] = 0
    }

    /// The last lines of the diary.
    static func tail(_ lines: Int) -> String {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text.split(separator: "\n").suffix(lines).joined(separator: "\n")
    }
}

/// Async-signal-safe: plain writes of fixed strings, then the default action so iOS still records the crash.
private func crashHandler(_ sig: Int32) {
    let fd = Diagnostics.fd
    if fd >= 0 {
        let head: StaticString = "CRASH "
        _ = write(fd, head.utf8Start, head.utf8CodeUnitCount)
        let name: StaticString
        switch sig {
        case SIGABRT: name = "abort"
        case SIGSEGV: name = "bad memory access (SIGSEGV)"
        case SIGBUS: name = "bad memory access (SIGBUS)"
        case SIGTRAP: name = "Swift runtime error (SIGTRAP)"
        case SIGILL: name = "illegal instruction"
        default: name = "signal"
        }
        _ = write(fd, name.utf8Start, name.utf8CodeUnitCount)
        let during: StaticString = " during: "
        _ = write(fd, during.utf8Start, during.utf8CodeUnitCount)
        _ = write(fd, Diagnostics.stage, strlen(Diagnostics.stage))
        let end: StaticString = "\n"
        _ = write(fd, end.utf8Start, end.utf8CodeUnitCount)
    }
    _ = signal(sig, SIG_DFL)
    _ = raise(sig)
}
