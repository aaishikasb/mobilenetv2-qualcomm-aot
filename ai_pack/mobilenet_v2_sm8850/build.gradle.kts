plugins { id("com.android.ai-pack") }

aiPack {
  packName = "mobilenet_v2_sm8850"
  dynamicDelivery { deliveryType = "on-demand" }
}
