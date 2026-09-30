// AIDL for the Shizuku user service. It runs in a separate process at ADB/shell
// privilege. It exposes only fixed, named actions (no arbitrary command input),
// so it is not a general command channel - just three switches the alarm flips on.
package com.theftguard.app;

interface IUserService {
    void destroy() = 16777114; // Shizuku reserves this transaction id for teardown
    void exit() = 1;

    // Each returns true on success. No parameters: the commands are hardcoded.
    boolean enableMobileData() = 2;
    boolean enableWifi() = 3;
    boolean enableLocation() = 4;
}
