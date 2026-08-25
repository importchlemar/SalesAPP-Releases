package com.example.salesapp

object UpdateConfig {
    const val GITHUB_OWNER = "importchlemar"
    const val GITHUB_REPO = "SalesAPP-Releases"
    const val APK_ASSET_NAME = "SalesAPP.apk"
    const val UPDATE_CHANNEL_ID = "salesapp_app_updates"
    const val APP_UPDATE_TOPIC = "salesapp_app_updates"

    // Aktualna pula produktów konkursowych.
    // Nie przechowujemy pełnego katalogu w Firebase.
    const val CONTEST_PRODUCTS_SPREADSHEET_ID = "1JumEbRtKkouHtXEG1Qf7us-Vwr5hL2eB4kFdnaMCHiU"
    const val CONTEST_PRODUCTS_SHEET = "KONKURSY_PRODUKTY"
    const val CONTEST_PRODUCTS_SHEET_URL =
        "https://docs.google.com/spreadsheets/d/$CONTEST_PRODUCTS_SPREADSHEET_ID/edit"
}
