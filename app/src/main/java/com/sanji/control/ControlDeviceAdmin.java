package com.sanji.control;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Device admin receiver for Sanji Control. Used ONLY for the remote
 * "lock" command (DevicePolicyManager.lockNow()). No wipe, no password
 * reset, no other policies — res/xml/device_admin.xml declares
 * {@code <force-lock />} and nothing else.
 *
 * Note: being a device admin does NOT make the MediaProjection screen
 * permission permanent. Android still asks "Start now" every time a new
 * screen-capture session starts (privacy rule). The screen permission is
 * therefore requested each time the app is turned ON — this is stated in
 * the README and is not something device admin can change.
 */
public class ControlDeviceAdmin extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
    }
}
