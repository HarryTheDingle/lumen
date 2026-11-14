import Foundation

/**
 * A short diary of playing (the loading screen's steps, the player opening, what failed), kept in Lumen's own
 * storage: the phone's own log can't be read without a Mac's administrator, and this one can be copied off it.
 * Only the newest ~200 KB are kept.
 */
enum Log {
    private static let queue = DispatchQueue(label: "lumen-log")
    private static let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("lumen-log.txt")
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()

    static func note(_ line: String) {
        let text = "\(stamp.string(from: Date())) \(line)\n"
        queue.async {
            guard let data = text.data(using: .utf8) else { return }
            if let handle = try? FileHandle(forWritingTo: file) {
                handle.seekToEndOfFile()
                handle.write(data)
                let size = handle.offsetInFile
                try? handle.close()
                // (past 400 KB: the newest half kept)
                if size > 400_000, let all = try? Data(contentsOf: file) { try? all.suffix(200_000).write(to: file) }
            } else {
                try? data.write(to: file)
            }
        }
    }
}
