package com.example.mobilenetlitert

import android.os.Build

enum class BackendPolicy {
  CPU_EMULATOR,
  NPU_REQUIRED,
}

object DevicePolicy {
  fun defaultBackendPolicy(): BackendPolicy {
    // Note: Forced to CPU_EMULATOR because the sm8750 AOT model in this repo 
    // is incompatible with the connected SM8750 device.
    return BackendPolicy.CPU_EMULATOR
  }

  fun isProbablyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    val device = Build.DEVICE.lowercase()
    val product = Build.PRODUCT.lowercase()
    val hardware = Build.HARDWARE.lowercase()

    return fingerprint.startsWith("generic") ||
      fingerprint.contains("emulator") ||
      model.contains("sdk") ||
      model.contains("emulator") ||
      model.contains("android sdk built for") ||
      manufacturer.contains("genymotion") ||
      hardware.contains("goldfish") ||
      hardware.contains("ranchu") ||
      (brand.startsWith("generic") && device.startsWith("generic")) ||
      product.contains("sdk") ||
      product.contains("emulator")
  }
}
