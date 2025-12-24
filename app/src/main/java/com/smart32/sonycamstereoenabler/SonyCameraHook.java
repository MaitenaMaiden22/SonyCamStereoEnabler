package com.smart32.sonycamstereoenabler;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SonyCameraHook implements IXposedHookLoadPackage {

    private static final String TAG = "SonyCameraHook";
    // Set to true to enable Xposed logs
    private static final boolean DEBUG = false;

    private static final String MEDIA_RECORDER_CLASS = "com.sonymobile.android.media.MediaRecorder";
    private static final String AUDIO_RECORD_CLASS = "android.media.AudioRecord";

    private static final String VIDEOPRO_PKG_BASE = "jp.co.sony.mc.videopro";
    private static final String CAMERA_PKG_BASE = "jp.co.sony.mc.camera";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        boolean isVideoPro;

        // Use startsWith to support modded package names
        if (lpparam.packageName.startsWith(VIDEOPRO_PKG_BASE)) {
            isVideoPro = true;
        } else if (lpparam.packageName.startsWith(CAMERA_PKG_BASE)) {
            isVideoPro = false;
        } else {
            return;
        }

        if (DEBUG) XposedBridge.log(TAG + ": Loaded target package: " + lpparam.packageName);

        // Define internal class names based on the app type
        final String settingPackage = isVideoPro ? (VIDEOPRO_PKG_BASE + ".setting") : (CAMERA_PKG_BASE + ".setting");
        final String proSettingClass = settingPackage + ".CameraProSetting";
        final String commonSettingsClass = settingPackage + ".CommonSettings";
        final String cameraSettingsClass = settingPackage + ".CameraSettings";
        final String recorderPackage = isVideoPro ? (VIDEOPRO_PKG_BASE + ".recorder") : (CAMERA_PKG_BASE + ".recorder");
        final String audioLevelMonitorClass = recorderPackage + ".AudioLevelMonitor";

        // VideoPro widget path is slightly different
        final String audioLevelWidgetClass = isVideoPro
                ? "jp.co.sony.mc.videopro.view.widget.AudioLevelWidget"
                : recorderPackage.replace("recorder", "view.widget") + ".AudioLevelWidget";

        // 1. Hook setAudioSource
        XposedHelpers.findAndHookMethod(MEDIA_RECORDER_CLASS, lpparam.classLoader, "setAudioSource", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (DEBUG) XposedBridge.log(TAG + ": setAudioSource original: " + param.args[0]);

                String micName = getCurrentMicSetting(lpparam.classLoader, isVideoPro, commonSettingsClass, cameraSettingsClass, proSettingClass);

                if ("LR".equals(micName)) {
                    param.args[0] = 1; // Stereo
                    if (DEBUG) XposedBridge.log(TAG + ": Force Stereo (1) for LR");
                } else {
                    param.args[0] = 5; // Mono/rear
                    if (DEBUG) XposedBridge.log(TAG + ": Force Mono (5) for " + micName);
                }
            }
        });

        // 2. Hook AudioRecord constructor (level indicator)
        XposedHelpers.findAndHookConstructor(AUDIO_RECORD_CLASS, lpparam.classLoader,
                int.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // Check call stack to ensure we only hook inside camera context
                        if (!isCalledFromCamera(Thread.currentThread().getStackTrace(), isVideoPro, audioLevelMonitorClass, audioLevelWidgetClass, recorderPackage)) {
                            return;
                        }

                        if (DEBUG) XposedBridge.log(TAG + ": AudioRecord hook. Original: " + param.args[0]);

                        String micName = getCurrentMicSetting(lpparam.classLoader, isVideoPro, commonSettingsClass, cameraSettingsClass, proSettingClass);
                        if ("LR".equals(micName)) {
                            param.args[0] = 1;
                            if (DEBUG) XposedBridge.log(TAG + ": Preview forced to Stereo (1) for LR");
                        } else {
                            param.args[0] = 5;
                            if (DEBUG) XposedBridge.log(TAG + ": Preview forced to Mono (5) for " + micName);
                        }
                    }
                });
    }

    // Helper method to get the current MIC setting value from the app via reflection.
    private String getCurrentMicSetting(ClassLoader classLoader, boolean isVideoPro, String commonSettingsClass, String cameraSettingsClass, String proSettingClass) {
        try {
            String micSettingsClass = isVideoPro ? commonSettingsClass : cameraSettingsClass;
            Class<?> micSettingsClazz = XposedHelpers.findClass(micSettingsClass, classLoader);
            Object micKey = XposedHelpers.getStaticObjectField(micSettingsClazz, "MIC");
            Class<?> proSettingClazz = XposedHelpers.findClass(proSettingClass, classLoader);
            Object settingInstance = XposedHelpers.callStaticMethod(proSettingClazz, "getInstance");
            Object micValue = XposedHelpers.callMethod(settingInstance, "get", micKey);
            return (micValue != null) ? micValue.toString() : "UNKNOWN";
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log(TAG + ": Error getting mic setting: " + t.getMessage());
            return "ERROR";
        }
    }

    // Helper method to check if the execution flow comes from Camera/VideoPro specific classes.
    private boolean isCalledFromCamera(StackTraceElement[] stack, boolean isVideoPro, String audioLevelMonitorClass, String audioLevelWidgetClass, String recorderPackage) {
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (!isVideoPro) {
                // Logic for Camera App
                if (className.contains(audioLevelMonitorClass)) return true;
            } else {
                // Logic for VideoPro
                if (className.contains(audioLevelWidgetClass) || className.contains(recorderPackage)) return true;
            }
        }
        return false;
    }
}