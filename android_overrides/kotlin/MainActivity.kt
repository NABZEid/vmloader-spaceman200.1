package com.spaceman.vmloader

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private fun accessEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/${BotService::class.java.name}") ||
            s.contains("$packageName/.BotService")
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "vmloader/native")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "status" -> result.success(
                        mapOf(
                            "overlay" to Settings.canDrawOverlays(this),
                            "access" to accessEnabled(),
                            "running" to (OverlayService.instance != null)
                        )
                    )
                    "requestOverlay" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                        result.success(true)
                    }
                    "openAccessibility" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(true)
                    }
                    "startOverlay" -> {
                        if (Settings.canDrawOverlays(this)) {
                            startForegroundService(Intent(this, OverlayService::class.java))
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    }
                    "stopOverlay" -> {
                        stopService(Intent(this, OverlayService::class.java))
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
