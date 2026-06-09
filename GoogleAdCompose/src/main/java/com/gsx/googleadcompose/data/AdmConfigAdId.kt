package com.gsx.googleadcompose.data

object AdmConfigAdId {
    var listBannerAdUnitID: List<String> = emptyList()
    var listInterstitialAdUnitID: List<String> = emptyList()
    var listRewardAdUnitID: List<String> = emptyList()
    var listRewardInterstitialAdUnitID: List<String> = emptyList()
    var listNativeAdUnitID: List<String> = emptyList()
    var listOpenAdUnitID: List<String> = emptyList()

    fun getBannerAdUnitID(id : Int) : String{
        return listBannerAdUnitID[id]
    }

    fun getInterstitialAdUnitID(id : Int) : String{
        return listInterstitialAdUnitID[id]
    }

    fun getRewardAdUnitID(id : Int) : String{
        return listRewardAdUnitID[id]
    }
    fun getRewardInterstitialAdUnitID(id : Int) : String{
        return listRewardInterstitialAdUnitID[id]
    }

    fun getNativeAdUnitID(id : Int) : String{
        return listNativeAdUnitID[id]
    }

    fun getOpenAdUnitID(id : Int) : String{
        return listOpenAdUnitID[id]
    }
}