package dev.virtualmic;

/** Independent process: worker failure ends the call before restoring its uplink. */
public final class TxMuteGuard {
    private static final class Cleanup {
        final CellularAudio call;
        final CallLease lease;
        boolean ownsMute, done;
        volatile boolean failed;
        Cleanup(CellularAudio call, CallLease lease) { this.call = call; this.lease = lease; }
        synchronized boolean finish() {
            if (done || !ownsMute) return true;
            try {
                call.finish(lease.normalStop() && !failed);
                done = true;
                System.out.println("CALL_TX_RESTORED");
                return true;
            } catch (Throwable e) {
                System.out.println("CALL_HANGUP_FAILED_OR_RESTORE_FAILED " + e);
                // Keep the original voice blocked until the call has ended.
                return false;
            }
        }
    }
    public static void main(String[] args) {
        int result = 0;
        Cleanup cleanup = null;
        try {
            CellularAudio call = new CellularAudio(ShellRuntime.create());
            CallLease lease = new CallLease(System.nanoTime());
            cleanup = new Cleanup(call, lease);
            Cleanup owned = cleanup;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { owned.failed = true; owned.finish(); }));
            call.beginCall(); call.checkRoute(); call.checkNeutral();
            cleanup.ownsMute = true;
            CellularAudio.setTx(1);
            if (call.txMode() != 1) throw new IllegalStateException("CELLULAR_UNSUPPORTED");
            Thread input = new Thread(() -> {
                try {
                    int command;
                    while ((command = System.in.read()) != -1) {
                        if (command == 2) { lease.stop(System.nanoTime()); break; }
                        if (command != 1) break;
                        lease.beat(System.nanoTime());
                    }
                } catch (Exception ignored) {} finally { lease.end(); }
            }, "call-guard-lease");
            input.setDaemon(true); input.start();
            System.out.println("CALL_TX_MUTED"); System.out.flush();
            while (!lease.expired(System.nanoTime()) && call.inCall()) {
                call.checkRoute();
                if (call.txMode() != 1) throw new IllegalStateException("CELLULAR_BUSY");
                Thread.sleep(100);
            }
            if (!lease.normalStop() && call.inCall()) {
                cleanup.failed = true; result = 1;
                System.out.println("CALL_GUARD_FAILED worker lease ended");
            }
        } catch (Throwable e) {
            if (cleanup != null) cleanup.failed = true;
            System.out.println("CALL_GUARD_FAILED " + e); result = 1;
        } finally {
            if (cleanup != null) {
                while (!cleanup.finish()) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        }
        System.exit(result);
    }
}
