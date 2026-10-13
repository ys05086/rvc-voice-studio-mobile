package dev.virtualmic;

import android.app.Application;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Field;

/** Context attributed to the real shell UID, never the Android system identity. */
final class ShellRuntime {
    static Context create() throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("CELLULAR_UNSUPPORTED");
        Looper.prepareMainLooper();
        Class<?> cls = Class.forName("android.app.ActivityThread");
        Object thread = cls.getMethod("systemMain").invoke(null);
        Context system = (Context) cls.getMethod("getSystemContext").invoke(thread);
        Context context = new ContextWrapper(system.createPackageContext("com.android.shell", 0)) {
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(Process.myUid()).setPid(Process.myPid())
                    .setPackageName("com.android.shell").build();
            }
        };
        Application application = new Application() {{ attachBaseContext(context); }};
        Field initial = cls.getDeclaredField("mInitialApplication");
        initial.setAccessible(true); initial.set(thread, application);
        return context;
    }
}
