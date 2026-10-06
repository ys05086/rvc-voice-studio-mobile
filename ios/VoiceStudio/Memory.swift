import Darwin
import os

/// The app's memory as iOS counts it when deciding to end an app (the footprint), for the result line.
enum Memory {
    /// (now, peak since launch) in bytes.
    static func footprint() -> (now: UInt64, peak: UInt64) {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return (0, 0) }
        return (info.phys_footprint, UInt64(max(info.ledger_phys_footprint_peak, 0)))
    }

    /// How much more the app may use before iOS ends it.
    static var available: UInt64 { UInt64(max(os_proc_available_memory(), 0)) }
}
