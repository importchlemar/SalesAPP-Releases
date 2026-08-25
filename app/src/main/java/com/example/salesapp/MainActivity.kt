package com.example.salesapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.concurrent.thread
import androidx.core.content.FileProvider
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import com.bumptech.glide.Glide
class MainActivity : AppCompatActivity() {

    // SalesAPP v13 — Firebase e-mail/hasło + profil PRZED/rola/uprawnienia + reklamacje — 2026-08-12

    private val salesApiBaseUrl =
        "https://script.google.com/macros/s/AKfycbxiN7-7rI2kRilvh3IiOQ-wj750qTDAoJWZ840w2223U89WHL8q9x7sbiktaKX3IDTNLA/exec"

    private val apiUrl = "$salesApiBaseUrl?action=data"

    // Rynek, potencjalni klienci i FCM korzystają teraz z tego samego
    // backendu i tego samego arkusza co SalesAPP.
    private val rynekApiUrl = salesApiBaseUrl

    private lateinit var main: LinearLayout
    private lateinit var data: JSONObject
    private val firebaseSalesRepository = FirebaseSalesRepository()

    private var selectedMonth = ""
    private var selectedOwner = "Wszyscy"
    private var selectedGroup = "Handlowcy"
    private var currentScreen = "summary"
    private var konkursTab = "results"
    private var konkursSearch = ""
    private var selectedContestId = ""
    private var selectedContestRepresentativeCode = ""
    private var rynekData: JSONObject? = null
    private var rynekLoading = false
    private var potentialClientsData: JSONObject? = null
    private var potentialClientsLoading = false

    // REKLAMACJE — korzystają z tego samego projektu Firebase co SalesAPP.
    private var complaintsFirestore: FirebaseFirestore? = null
    private var complaintsFirebaseReady = false
    private var complaintsFirebaseLoading = false
    private var complaintsFirebaseError = ""
    private var firebaseAuthReady = false
    private var firebaseAuthLoading = false
    private var firebaseAuthError = ""
    private var complaintSearchResults: List<Map<String, Any?>> = emptyList()
    private var complaintCustomerResults: List<Map<String, Any?>> = emptyList()
    private var complaintProductResults: List<Map<String, Any?>> = emptyList()
    private var complaintDocumentResults: List<Map<String, Any?>> = emptyList()
    private var complaintSelectedCustomer: Map<String, Any?>? = null
    private var complaintSelectedProduct: Map<String, Any?>? = null
    private var complaintMyResults: List<Map<String, Any?>> = emptyList()
    private var complaintSelectedDocument: Map<String, Any?>? = null
    private var complaintSearchText = ""
    private var complaintMineSearchText = ""
    private var complaintMode = "new"
    private var complaintRealtimeListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var pendingOpenComplaints = false
    private var pendingComplaintId = ""
    private var pendingOpenContest = false

    private var potentialClientsSearch = ""
    private var potentialClientsStatusFilter = "WSZYSTKIE"
    private var potentialClientsRepFilter = "WSZYSCY"

    private val ROLE_ADMIN = "ADMIN"
    private val ROLE_HANDLOWIEC = "HANDLOWIEC"
    private val ROLE_BIURO = "BIURO"

    private var userRole = ""
    // Dla HANDLOWIEC loggedUserName pozostaje kodem PRZED (np. PRZED1),
    // aby zachować zgodność z dotychczasowymi filtrami i reklamacjami.
    private var loggedUserName = ""
    private var currentUserDisplayName = ""
    private var currentUserEmail = ""
    private var currentUserUid = ""
    private var representativeCode = ""
    private var firebasePermissions: Map<String, Boolean> = emptyMap()

    // Konfiguracja globalna pobierana LIVE z Firebase:
    // salesapp_users / salesapp_settings / pozostałe kolekcje SalesAPP.
    private var representativesConfig = JSONArray()
    private var settingsConfig = JSONArray()
    private var usersConfig = JSONArray()

    // Tożsamość jest utrzymywana przez Firebase Authentication.
    // sessionLogin zachowujemy wyłącznie jako zgodne wstecz pole identyfikacyjne dla backendu/FCM.
    private var sessionLogin = ""
    private var sessionClientScope = ""

    private fun hasPermission(key: String, defaultValue: Boolean = false): Boolean {
        if (isAdmin()) return true
        return firebasePermissions[key] ?: defaultValue
    }

    private val dataCachePrefs = "salesapp_data_cache"
    private val dataCacheKey = "dashboard_json"
    private val dataCacheTimeKey = "dashboard_time"

    private fun isAdmin(): Boolean = userRole == ROLE_ADMIN
    private fun isBiuro(): Boolean = userRole == ROLE_BIURO

    private val blue = Color.rgb(7, 31, 143)
    private val red = Color.rgb(227, 6, 19)
    private val dark = Color.rgb(7, 19, 58)
    private val muted = Color.rgb(102, 112, 133)
    private val bg = Color.rgb(246, 248, 252)
    private val green = Color.rgb(0, 138, 61)
    private val softBlue = Color.rgb(235, 241, 255)

    private val complaintReasons = listOf(
        "Błąd w dostawie",
        "Uszkodzenie w dostawie",
        "Brak towaru",
        "Wada fabryczna",
        "Niezgodność towaru z zamówieniem",
        "Uszkodzenie mechaniczne",
        "Niekompletne opakowanie / braki",
        "Zły wymiar / parametry",
        "Inny"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = blue
        window.navigationBarColor = blue

        requestNotificationPermission()
        applyComplaintNotificationIntent(intent)
        GitHubUpdater.handleUpdateIntent(this, intent)
        loadConfigAndStart()
        GitHubUpdater.checkForUpdate(this, silent = true)
    }

    override fun onResume() {
        super.onResume()
        GitHubUpdater.resumePendingInstall(this)
    }

    override fun onDestroy() {
        firebaseSalesRepository.stop()
        complaintRealtimeListener?.remove()
        complaintRealtimeListener = null
        super.onDestroy()
    }

    private fun loadConfigAndStart() {
        // Produkcyjny SalesAPP korzysta z Firebase Authentication oraz salesapp_users.
        // Konto i uprawnienia pochodzą z Firebase Authentication oraz salesapp_users.
        beginFirebaseAuthentication()
    }

    private fun showConfigErrorScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
        }

        layout.addView(TextView(this).apply {
            text = "Nie udało się wczytać konfiguracji SalesAPP"
            textSize = 20f
            setTextColor(red)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        layout.addView(TextView(this).apply {
            text = "Sprawdź internet i wdrożenie Apps Script. Następnie spróbuj ponownie."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        })

        layout.addView(Button(this).apply {
            text = "Spróbuj ponownie"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener { loadConfigAndStart() }
        })

        setContentView(layout)
    }

    private fun applyConfigObject(config: JSONObject?) {
        if (config == null) return

        config.optJSONArray("representatives")?.let {
            representativesConfig = it
        }

        config.optJSONArray("settingsList")?.let {
            settingsConfig = it
        }

        config.optJSONArray("users")?.let {
            usersConfig = it
        }
    }

    private fun syncConfigFromData(root: JSONObject) {
        applyConfigObject(root.optJSONObject("config"))
    }

    private fun settingEnabled(key: String, defaultValue: Boolean = true): Boolean {
        for (i in 0 until settingsConfig.length()) {
            val row = settingsConfig.optJSONObject(i) ?: continue
            if (row.optString("key").equals(key, ignoreCase = true)) {
                return row.optString("value", if (defaultValue) "TAK" else "NIE")
                    .trim()
                    .uppercase() != "NIE"
            }
        }
        return defaultValue
    }

    private fun screenAllowed(screen: String): Boolean {
        if (isAdmin()) return true
        val permissionKey = when (screen) {
            "summary" -> "tabs.summary"
            "owners" -> "tabs.owners"
            "konkurs" -> "tabs.contest"
            "rynek" -> "tabs.market"
            "clients" -> "tabs.clients"
            "reklamacje" -> "tabs.complaints"
            "settings" -> "tabs.settings"
            else -> return true
        }
        // Brak klucza zachowuje zgodność z istniejącymi kontami.
        return firebasePermissions[permissionKey] ?: true
    }

    private fun setSettingLocal(key: String, enabled: Boolean) {
        for (i in 0 until settingsConfig.length()) {
            val row = settingsConfig.optJSONObject(i) ?: continue
            if (row.optString("key").equals(key, ignoreCase = true)) {
                row.put("value", if (enabled) "TAK" else "NIE")
                return
            }
        }
    }

    private fun activeUsersByRole(role: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()

        for (i in 0 until usersConfig.length()) {
            val row = usersConfig.optJSONObject(i) ?: continue
            if (!row.optBoolean("active", true)) continue
            if (!row.optString("role").equals(role, ignoreCase = true)) continue
            out.add(row)
        }

        return out.sortedBy { it.optString("name") }
    }

    private fun firstActiveUserByRole(role: String): JSONObject? {
        return activeUsersByRole(role).firstOrNull()
    }

    private fun representativeNameForUser(user: JSONObject): String {
        val code = user.optString("representativeCode").trim()
        if (code.isNotBlank()) return displayName(code)
        return user.optString("name").trim()
    }


    private fun representativeDisplayName(value: String): String? {
        val wanted = normalizePersonName(value)

        for (i in 0 until representativesConfig.length()) {
            val row = representativesConfig.optJSONObject(i) ?: continue
            val code = row.optString("code").trim()
            val name = row.optString("name").trim()

            val aliases = listOf(
                code,
                code.replace("PRZED", "PRZEDSTAWICIEL"),
                name
            )

            if (aliases.any { normalizePersonName(it) == wanted }) {
                return name.ifBlank { null }
            }
        }

        return null
    }

    private fun activeSalesmenNormalized(): Set<String> {
        return activeUsersByRole(ROLE_HANDLOWIEC)
            .map { user -> normalizePersonName(representativeNameForUser(user)) }
            .toSet()
    }

    private fun complaintIdentity(): String = currentUserEmail.ifBlank {
        representativeCode.ifBlank { loggedUserName }
    }

    private fun beginFirebaseAuthentication() {
        // Nie pozostawiamy listenerów z poprzedniej sesji.
        firebaseSalesRepository.stop()

        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            showLoginScreen()
            return
        }
        showAuthLoading("Sprawdzanie konta...")
        loadFirebaseProfile(user.uid, user.email.orEmpty())
    }

    private fun showAuthLoading(message: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
            addView(ProgressBar(this@MainActivity))
            addView(TextView(this@MainActivity).apply {
                text = message
                textSize = 15f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            })
        }
        setContentView(layout)
    }

    private fun loadFirebaseProfile(uid: String, authEmail: String) {
        FirebaseFirestore.getInstance()
            .collection("salesapp_users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    FirebaseAuth.getInstance().signOut()
                    showFirebaseProfileError(
                        "Konto e-mail istnieje w Firebase Authentication, ale nie ma profilu SalesAPP.\n\n" +
                            "E-mail: $authEmail\nUID: $uid\n\n" +
                            "Utwórz dokument: salesapp_users/$uid"
                    )
                    return@addOnSuccessListener
                }

                if (doc.getBoolean("active") != true) {
                    FirebaseAuth.getInstance().signOut()
                    showFirebaseProfileError("To konto SalesAPP jest nieaktywne.")
                    return@addOnSuccessListener
                }

                val role = doc.getString("role").orEmpty().trim().uppercase()
                val rep = doc.getString("representativeCode").orEmpty().trim().uppercase()
                val name = doc.getString("name").orEmpty().trim().ifBlank { authEmail }
                val email = doc.getString("email").orEmpty().trim().ifBlank { authEmail }
                val scope = doc.getString("clientScope").orEmpty().trim().uppercase().ifBlank {
                    if (role == ROLE_ADMIN || role == ROLE_BIURO) "WSZYSCY" else "SWOI"
                }

                if (role !in setOf(ROLE_ADMIN, ROLE_BIURO, ROLE_HANDLOWIEC)) {
                    FirebaseAuth.getInstance().signOut()
                    showFirebaseProfileError("Nieprawidłowa rola konta: $role")
                    return@addOnSuccessListener
                }

                if (role == ROLE_HANDLOWIEC && rep.isBlank()) {
                    FirebaseAuth.getInstance().signOut()
                    showFirebaseProfileError("Konto HANDLOWIEC nie ma przypisanego representativeCode (np. PRZED1).")
                    return@addOnSuccessListener
                }

                @Suppress("UNCHECKED_CAST")
                val rawPermissions = doc.get("permissions") as? Map<String, Any?> ?: emptyMap()
                val permissions = rawPermissions.mapValues { (_, value) -> value == true }

                completeFirebaseLogin(
                    uid = uid,
                    email = email,
                    displayNameValue = name,
                    role = role,
                    repCode = rep,
                    clientScopeValue = scope,
                    permissions = permissions
                )
            }
            .addOnFailureListener { e ->
                FirebaseAuth.getInstance().signOut()
                showFirebaseProfileError("Nie udało się pobrać profilu SalesAPP: ${e.message}")
            }
    }

    private fun completeFirebaseLogin(
        uid: String,
        email: String,
        displayNameValue: String,
        role: String,
        repCode: String,
        clientScopeValue: String,
        permissions: Map<String, Boolean>
    ) {
        currentUserUid = uid
        currentUserEmail = email
        currentUserDisplayName = displayNameValue
        userRole = role
        representativeCode = repCode
        firebasePermissions = permissions
        sessionLogin = email
        sessionClientScope = if (clientScopeValue == "WSZYSCY" || clientScopeValue == "ALL") "WSZYSCY" else "SWOI"

        // Dotychczasowe filtry SalesAPP pracują na kodzie PRZED.
        loggedUserName = if (role == ROLE_HANDLOWIEC) repCode else displayNameValue.uppercase()

        // Usuń ślady starej sesji PIN, jeżeli aplikacja była wcześniej używana.
        getSharedPreferences("salesapp_login", MODE_PRIVATE).edit().clear().apply()

        firebaseAuthReady = true
        firebaseAuthLoading = false
        firebaseAuthError = ""

        subscribeUserTopics()
        // Produkcyjny token FCM zapisujemy do salesapp_devices; stary Apps Script nie wysyła już powiadomień.
        registerComplaintDeviceToken()
        startAfterLogin()
    }

    private fun showFirebaseProfileError(message: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
        }

        layout.addView(TextView(this).apply {
            text = "Nie można uruchomić SalesAPP"
            textSize = 22f
            setTextColor(red)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        layout.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(dark)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        })
        layout.addView(Button(this).apply {
            text = "Wróć do logowania"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener { showLoginScreen() }
        })
        setContentView(layout)
    }

    private fun sendFcmTokenToServer() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                thread {
                    try {
                        val payload = JSONObject().apply {
                            put("action", "saveFcmToken")
                            put("user", loggedUserName)
                            put("role", userRole)
                            put("email", currentUserEmail)
                            put("uid", currentUserUid)
                            put("representativeCode", representativeCode)
                            put("token", token)
                            put("device", Build.MODEL)
                            put("version", Build.VERSION.RELEASE)
                        }
                        val response = postJson(rynekApiUrl, payload)
                        android.util.Log.d("FCM_SAVE", response)
                    } catch (e: Exception) {
                        android.util.Log.e("FCM_SAVE", e.message ?: "Błąd zapisu tokenu")
                    }
                }
            }
    }

    private fun registerComplaintDeviceToken() {
        val authUser = FirebaseAuth.getInstance().currentUser ?: return
        if (authUser.uid != currentUserUid || currentUserUid.isBlank()) return

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                val db = FirebaseFirestore.getInstance()
                val deviceId = try {
                    android.provider.Settings.Secure.getString(
                        contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ).orEmpty().ifBlank { Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_") }
                } catch (_: Exception) {
                    Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")
                }

                val payload = hashMapOf<String, Any?>(
                    "uid" to currentUserUid,
                    "email" to currentUserEmail,
                    "login" to complaintIdentity(),
                    "name" to loggedUserName,
                    "displayName" to currentUserDisplayName,
                    "representativeCode" to representativeCode,
                    "role" to userRole,
                    "token" to token,
                    "device" to Build.MODEL,
                    "android" to Build.VERSION.RELEASE,
                    "environment" to "PROD",
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                // Ten sam token FCM nie może należeć do dwóch użytkowników.
                // Np. gdy na tym samym telefonie wylogowano jedną osobę i zalogowano drugą.
                db.collection("salesapp_devices")
                    .whereEqualTo("token", token)
                    .get()
                    .addOnSuccessListener { oldDevices ->
                        oldDevices.documents
                            .filter { it.id != currentUserUid }
                            .forEach { oldDoc ->
                                oldDoc.reference.delete()
                            }
                    }

                // Zgodność z istniejącym modułem Windows.
                db.collection("salesapp_devices").document(currentUserUid)
                    .set(payload, com.google.firebase.firestore.SetOptions.merge())
                    .addOnFailureListener {
                        android.util.Log.w("FCM_DEVICE", "salesapp_devices: ${it.message}")
                    }

                // Docelowy zapis wielu urządzeń pod konkretnym użytkownikiem.
                db.collection("salesapp_users").document(currentUserUid)
                    .collection("devices").document(deviceId)
                    .set(payload, com.google.firebase.firestore.SetOptions.merge())
                    .addOnFailureListener {
                        android.util.Log.w("FCM_DEVICE", "salesapp_users/devices: ${it.message}")
                    }
            }
    }

    private fun applyComplaintNotificationIntent(source: Intent?) {
        val screen = source?.getStringExtra("screen").orEmpty()
        val type = source?.getStringExtra("type").orEmpty()
        if (screen == "reklamacje" || type == "COMPLAINT_UPDATE" || type == "COMPLAINT_DELETED") {
            pendingOpenComplaints = true
            pendingOpenContest = false
            pendingComplaintId = if (type == "COMPLAINT_DELETED") "" else source?.getStringExtra("complaintId").orEmpty()
        } else if (screen == "konkurs" || screen == "competition" || type.startsWith("CONTEST")) {
            pendingOpenContest = true
            pendingOpenComplaints = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyComplaintNotificationIntent(intent)
        if (GitHubUpdater.handleUpdateIntent(this, intent)) return
        if (userRole.isNotBlank() && ::main.isInitialized && pendingOpenComplaints) {
            currentScreen = "reklamacje"
            complaintMode = "mine"
            loadMyComplaints()
        } else if (userRole.isNotBlank() && ::main.isInitialized && pendingOpenContest) {
            currentScreen = "konkurs"
            pendingOpenContest = false
            render()
        }
    }

    private fun complaintStatusLabel(code: String): String = when (code.uppercase()) {
        "NOWA" -> "Nowa"
        "PRZYJETA" -> "Przyjęta"
        "OCZEKUJE_NA_TOWAR" -> "Oczekuje na towar"
        "TOWAR_NA_MAGAZYNIE" -> "Towar na magazynie"
        "W_REALIZACJI" -> "W realizacji"
        "OCZEKUJE_NA_DOSTAWCE" -> "Oczekuje na dostawcę"
        "ZAKONCZONA" -> "Zakończona"
        else -> code.ifBlank { "Nowa" }
    }

    private fun complaintDecisionLabel(code: String): String = when (code.uppercase()) {
        "PENDING" -> "Oczekuje na decyzję"
        "ACCEPTED" -> "Uznana"
        "REJECTED" -> "Odrzucona"
        "REPLACEMENT" -> "Wymiana towaru"
        "REFUND" -> "Zwrot środków"
        "CORRECTION" -> "Korekta"
        "OTHER" -> "Inna"
        else -> code.ifBlank { "Oczekuje na decyzję" }
    }

    private fun logout() {
        // Najpierw odpinamy listenery Firestore, dopiero potem wylogowujemy Auth.
        // Dzięki temu po signOut nie ma PERMISSION_DENIED z aktywnych listenerów.
        firebaseSalesRepository.stop()

        complaintRealtimeListener?.remove()
        complaintRealtimeListener = null

        try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) {}

        userRole = ""
        loggedUserName = ""
        currentUserDisplayName = ""
        currentUserEmail = ""
        currentUserUid = ""
        representativeCode = ""
        firebasePermissions = emptyMap()
        sessionLogin = ""
        sessionClientScope = ""
        firebaseAuthReady = false
        firebaseAuthLoading = false
        firebaseAuthError = ""
        complaintsFirebaseReady = false
        complaintsFirestore = null
        currentScreen = "summary"
        showLoginScreen()
    }

    private fun startAfterLogin() {
        // Firebase Authentication jest już aktywne i profil SalesAPP został zweryfikowany.
        if (!firebaseLiveSessionReady()) {
            android.util.Log.w(
                "SALESAPP_FIREBASE_LIVE",
                "startAfterLogin pominięty: sesja Auth/profil nie są jeszcze gotowe."
            )
            beginFirebaseAuthentication()
            return
        }

        if (pendingOpenComplaints) {
            currentScreen = "reklamacje"
            complaintMode = "mine"
        } else if (pendingOpenContest) {
            currentScreen = "konkurs"
            pendingOpenContest = false
        }

        if (isBiuro() && !pendingOpenComplaints && currentScreen != "konkurs") {
            currentScreen = "rynek"
            buildLayout()
            return
        }

        val cached = loadCachedDashboard()

        if (cached != null) {
            data = cached
            syncConfigFromData(data)
            data.optJSONObject("potentialClients")?.let {
                potentialClientsData = it
            }

            buildLayout()

            // Świeże dane pobieramy bez blokowania ekranu.
            refreshDataInBackground()
        } else {
            loadData()
        }
    }

    private fun showLoginScreen() {
        firebaseSalesRepository.stop()

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) auth.signOut()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
        }

        layout.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_chlemar_salesapp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            )
        })

        layout.addView(TextView(this).apply {
            text = "SalesAPP"
            textSize = 26f
            setTextColor(blue)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        })

        layout.addView(TextView(this).apply {
            text = "Zaloguj się kontem firmowym"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })

        layout.addView(TextView(this).apply {
            text = "Firebase • SalesAPP ${BuildConfig.VERSION_NAME}"
            textSize = 11f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        })

        val emailInput = EditText(this).apply {
            hint = "E-mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            setAutofillHints(android.view.View.AUTOFILL_HINT_EMAIL_ADDRESS)
        }
        layout.addView(emailInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ))

        val passwordInput = EditText(this).apply {
            hint = "Hasło"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setAutofillHints(android.view.View.AUTOFILL_HINT_PASSWORD)
        }
        layout.addView(passwordInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ).apply { topMargin = dp(8) })

        val errorText = TextView(this).apply {
            textSize = 13f
            setTextColor(red)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        layout.addView(errorText)

        val loginButton = Button(this).apply {
            text = "ZALOGUJ"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            typeface = Typeface.DEFAULT_BOLD
        }
        layout.addView(loginButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply { topMargin = dp(8) })

        fun doLogin() {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (email.isBlank() || password.isBlank()) {
                errorText.text = "Wpisz e-mail i hasło."
                return
            }

            loginButton.isEnabled = false
            errorText.text = "Logowanie..."
            FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val user = result.user
                    if (user == null) {
                        loginButton.isEnabled = true
                        errorText.text = "Firebase nie zwrócił użytkownika."
                    } else {
                        showAuthLoading("Pobieranie profilu SalesAPP...")
                        loadFirebaseProfile(user.uid, user.email.orEmpty())
                    }
                }
                .addOnFailureListener { e ->
                    loginButton.isEnabled = true
                    errorText.text = friendlyFirebaseAuthError(e.message)
                }
        }

        loginButton.setOnClickListener { doLogin() }
        passwordInput.setOnEditorActionListener { _, _, _ ->
            doLogin()
            true
        }

        layout.addView(Button(this).apply {
            text = "NIE PAMIĘTAM HASŁA"
            setTextColor(blue)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val email = emailInput.text.toString().trim()
                if (email.isBlank()) {
                    errorText.text = "Najpierw wpisz adres e-mail."
                    return@setOnClickListener
                }
                FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        errorText.setTextColor(green)
                        errorText.text = "Wysłano wiadomość do resetu hasła."
                    }
                    .addOnFailureListener { e ->
                        errorText.setTextColor(red)
                        errorText.text = friendlyFirebaseAuthError(e.message)
                    }
            }
        })

        setContentView(layout)
    }

    private fun friendlyFirebaseAuthError(raw: String?): String {
        val text = raw.orEmpty().lowercase()
        return when {
            "network" in text -> "Brak połączenia z Firebase."
            "password" in text || "credential" in text || "user" in text -> "Nieprawidłowy e-mail lub hasło."
            "blocked" in text || "too many" in text -> "Za dużo prób logowania. Spróbuj później."
            else -> "Logowanie nie powiodło się. ${raw.orEmpty()}".trim()
        }
    }

    private fun loadCachedDashboard(): JSONObject? {
        return try {
            val prefs = getSharedPreferences(dataCachePrefs, MODE_PRIVATE)
            val raw = prefs.getString(dataCacheKey, null) ?: return null
            if (raw.isBlank()) return null

            val obj = JSONObject(raw)
            if (!obj.optBoolean("ok", false)) return null
            if (!obj.has("months") || !obj.has("monthlyData")) return null

            obj
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDashboardCache(raw: String) {
        try {
            getSharedPreferences(dataCachePrefs, MODE_PRIVATE)
                .edit()
                .putString(dataCacheKey, raw)
                .putLong(dataCacheTimeKey, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            android.util.Log.w("SALESAPP_CACHE", "Nie udało się zapisać cache: ${e.message}")
        }
    }

    private fun refreshDataInBackground() {
        startFirebaseLiveData()
    }

    private fun loadData() {
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
        }

        loading.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_chlemar_salesapp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(115)
            )
        })

        loading.addView(TextView(this).apply {
            text = "Ładowanie danych sprzedażowych..."
            textSize = 16f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(loading)

        startFirebaseLiveData()
    }

    private fun firebaseLiveSessionReady(): Boolean {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        return firebaseAuthReady &&
            currentUserUid.isNotBlank() &&
            authUid.isNotBlank() &&
            authUid == currentUserUid &&
            userRole.isNotBlank()
    }

    private fun startFirebaseLiveData() {
        // Firestore Rules wymagają activeUser(), więc listener może wystartować
        // dopiero po Firebase Auth i poprawnym odczycie profilu salesapp_users/<uid>.
        if (!firebaseLiveSessionReady()) {
            android.util.Log.d(
                "SALESAPP_FIREBASE_LIVE",
                "Listener nie został uruchomiony — oczekiwanie na Auth + profil SalesAPP."
            )
            return
        }

        firebaseSalesRepository.start(
            onChanged = { fresh ->
                // Callback może przyjść chwilę po wylogowaniu. W takim przypadku
                // ignorujemy go zamiast próbować przebudowywać ekran.
                if (!firebaseLiveSessionReady()) return@start

                runOnUiThread {
                    if (!firebaseLiveSessionReady()) return@runOnUiThread
                    data = fresh
                    syncConfigFromData(fresh)
                    potentialClientsData = fresh.optJSONObject("potentialClients")
                    saveDashboardCache(fresh.toString())
                    if (::main.isInitialized) render() else buildLayout()
                }
            },
            onError = { message ->
                android.util.Log.w("SALESAPP_FIREBASE_LIVE", message)

                // Nie pokazujemy PERMISSION_DENIED po wylogowaniu ani przed
                // zakończeniem logowania. To był fałszywy komunikat na ekranie logowania.
                if (!firebaseLiveSessionReady()) return@start

                runOnUiThread {
                    if (!firebaseLiveSessionReady()) return@runOnUiThread

                    val friendly = when {
                        message.contains("PERMISSION_DENIED", ignoreCase = true) ||
                            message.contains("permission", ignoreCase = true) ->
                            "Brak uprawnień do danych Firebase LIVE. Sprawdź reguły Firestore i profil użytkownika."
                        else -> message
                    }
                    Toast.makeText(
                        this,
                        "Firebase LIVE: $friendly",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun buildLayout() {
        val scroll = ScrollView(this)

        main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(16))
            setBackgroundColor(bg)
        }

        scroll.addView(main)
        setContentView(scroll)

        render()
    }

    private fun render() {
        main.removeAllViews()

        appHeader()
        navigationTabs()

        if (isBiuro()) {
            if (currentScreen != "reklamacje") currentScreen = "rynek"
            if (currentScreen == "reklamacje") renderComplaintsScreen() else renderRynekScreen()
            refreshButton()
            footer()
            return
        }

        val months = data.getJSONArray("months")

        if (selectedMonth.isEmpty() && months.length() > 0) {
            selectedMonth = months.getString(months.length() - 1)
        }

        val current = data
            .getJSONObject("monthlyData")
            .getJSONObject(selectedMonth)

        val rawOwners = current.getJSONArray("ownersArray")
        val owners = mergeOfficeOwners(rawOwners)

        when (currentScreen) {
            "summary" -> renderSummaryScreen(months, owners, current)
            "owners" -> renderOwnersScreen(months, owners, current)
            "months" -> renderMonthsScreen()
            "konkurs" -> renderKonkursScreen()
            "rynek" -> renderRynekScreen()
            "clients" -> renderPotentialClientsScreen()
            "reklamacje" -> renderComplaintsScreen()
            "settings" -> renderAdminSettingsScreen()
            else -> renderSummaryScreen(months, owners, current)
        }

        refreshButton()
        footer()
    }

    private fun renderSummaryScreen(months: JSONArray, owners: JSONArray, current: JSONObject) {
        val filteredOwners = filterOwnersByGroup(owners)

        if (selectedOwner != "Wszyscy" && !containsOwner(filteredOwners, selectedOwner)) {
            selectedOwner = "Wszyscy"
        }

        selectorCard(months, filteredOwners)

        val viewData = if (selectedOwner == "Wszyscy") {
            totalFromOwners(filteredOwners, selectedGroup)
        } else {
            findOwner(filteredOwners, selectedOwner)
        }

        section("📊 Podsumowanie - ${groupLabel(selectedGroup)}")
        kpiGrid(viewData)

        section("🎯 Cele")
        compactGoals(viewData)

        if (selectedOwner == "Wszyscy") {
            top3SalesmenCard(filteredOwners)
            trendsMonthCard(filteredOwners, previousOwnersForSelectedMonth(), yearAgoOwnersForSelectedMonth())

            section("🏆 Rankingi - ${groupLabel(selectedGroup)}")
            rankingCard("TOP 10 Sprzedaż", filteredOwners, "totalSales", true)
            rankingCard("TOP 10 Udział importu", filteredOwners, "importShare", false)
            rankingCard("TOP 10 Marża import BI", filteredOwners, "importMarginPct", false)
            rankingCard("TOP 10 Marża kraj BI", filteredOwners, "domesticMarginPct", false)
        } else {
            section("🏆 Wynik przedstawiciela")
            ownerDetailsTable(viewData)
            singleTrendCard(viewData, previousOwnerForSelectedMonth(viewData.optString("name", selectedOwner)), yearAgoOwnerForSelectedMonth(viewData.optString("name", selectedOwner)))
        }
    }

    private fun renderOwnersScreen(months: JSONArray, owners: JSONArray, current: JSONObject) {
        val filteredOwners = filterOwnersByGroup(owners)

        if (selectedOwner != "Wszyscy" && !containsOwner(filteredOwners, selectedOwner)) {
            selectedOwner = "Wszyscy"
        }

        selectorCard(months, filteredOwners)

        val viewData = if (selectedOwner == "Wszyscy") {
            totalFromOwners(filteredOwners, selectedGroup)
        } else {
            findOwner(filteredOwners, selectedOwner)
        }

        if (selectedOwner == "Wszyscy") {
            section("👥 Opiekunowie - ${groupLabel(selectedGroup)}")
            rankingCard("Ranking sprzedaży", filteredOwners, "totalSales", true)
            rankingCard("Udział importu", filteredOwners, "importShare", false)
            rankingCard("Marża import BI", filteredOwners, "importMarginPct", false)
            rankingCard("Marża kraj BI", filteredOwners, "domesticMarginPct", false)
            trendsMonthCard(filteredOwners, previousOwnersForSelectedMonth(), yearAgoOwnersForSelectedMonth())
        } else {
            section("👥 ${displayName(selectedOwner)}")
            ownerDetailsTable(viewData)
            singleTrendCard(viewData, previousOwnerForSelectedMonth(viewData.optString("name", selectedOwner)), yearAgoOwnerForSelectedMonth(viewData.optString("name", selectedOwner)))
        }
    }

    private fun renderMonthsScreen() {
        section("📅 Miesiące")
        val monthlyData = data.getJSONObject("monthlyData")
        val months = data.getJSONArray("months")

        card(padding = 14) {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = "Sprzedaż miesiąc po miesiącu"
                textSize = 16f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            for (i in 0 until months.length()) {
                val month = months.getString(i)
                val total = monthlyData.getJSONObject(month).getJSONObject("total")

                addView(tableRowView(monthLabel(month), money(total.optDouble("totalSales"))))
                addView(tableRowView("  Import", money(total.optDouble("importSales"))))
                addView(tableRowView("  Kraj", money(total.optDouble("domesticSales"))))
                addView(tableRowView("  Udział importu", percent(total.optDouble("importShare"))))
                space(4)
            }
        }
    }

    private fun renderKonkursScreen() {
        val contests = mutableListOf<JSONObject>()
        val activeContests = data.optJSONArray("activeContests") ?: JSONArray()
        for (index in 0 until activeContests.length()) {
            activeContests.optJSONObject(index)?.let { contest ->
                if (contestVisibleForCurrentUser(contest)) contests.add(contest)
            }
        }
        if (contests.isEmpty() && activeContests.length() == 0) {
            val legacy = data.optJSONObject("activeContest")
            if (legacy != null && legacy.optString("_id").isNotBlank() && contestVisibleForCurrentUser(legacy)) {
                contests.add(legacy)
            }
        }

        section("🏆 Konkurs")
        if (contests.isEmpty()) {
            card(padding = 14) {
                addView(TextView(context).apply {
                    text = if (activeContests.length() > 0) {
                        "Nie masz obecnie przypisanych aktywnych konkursów."
                    } else {
                        "Obecnie nie ma aktywnych konkursów."
                    }
                    textSize = 14f
                    setTextColor(muted)
                })
            }
            return
        }

        if (contests.none { it.optString("_id") == selectedContestId }) {
            selectedContestId = contests.first().optString("_id")
            selectedContestRepresentativeCode = ""
        }
        if (contests.size > 1) renderContestSelector(contests)
        val contest = contests.firstOrNull { it.optString("_id") == selectedContestId } ?: contests.first()
        val results = contestResults(contest)
        val viewedResult = contestViewedResult(contest, results)
        val mode = contestCalculationMode(contest, viewedResult)

        renderContestMainHeader(contest, mode)

        if ((isAdmin() || isBiuro()) && results.length() > 0) {
            renderContestRepresentativeSelector(results)
        }

        if (viewedResult != null) {
            renderContestProgressSummary(contest, viewedResult, mode)
        } else {
            card(padding = 12) {
                addView(TextView(context).apply {
                    text = "Brak wyniku dla wybranego przedstawiciela. Wynik pojawi się po synchronizacji serwera."
                    textSize = 13f
                    setTextColor(muted)
                })
            }
        }

        konkursTopTabs()
        if (konkursTab == "products") {
            renderKonkursProductsScreen(contest)
            if (mode == "VALUE") {
                card(padding = 12) {
                    addView(TextView(context).apply {
                        text = "ℹ W tym konkursie lista produktów określa tylko pulę sprzedaży. " +
                            "Ilości przy produktach nie są warunkiem premii — liczy się wartość sprzedaży netto."
                        textSize = 12f
                        setTextColor(muted)
                    })
                }
            }
            return
        }

        when (mode) {
            "QUANTITY" -> if (viewedResult != null) renderQuantityContestDetails(contest, viewedResult)
            "VALUE" -> renderValueContestDetails(contest, viewedResult)
            else -> {
                renderRankingRewards(contest)
                konkursCard(contestRanking(contest), contest)
            }
        }

        if ((isAdmin() || isBiuro()) && mode != "RANKING") {
            renderCompactContestParticipants(contest, results, mode)
        }
    }

    private fun currentContestCode(): String {
        return if (isBiuro()) "BIURO" else representativeCode.trim().uppercase(Locale.ROOT)
    }

    private fun contestVisibleForCurrentUser(contest: JSONObject): Boolean {
        if (isAdmin()) return true
        val scope = contest.optString("participantScope", "ALL").uppercase(Locale.ROOT)
        if (isBiuro()) return scope == "OFFICE" || contest.optBoolean("includeOffice")
        val code = currentContestCode()
        if (code.isBlank() || scope == "OFFICE") return false
        if (scope == "SELECTED") {
            val selected = contest.optJSONArray("participantCodes") ?: JSONArray()
            return (0 until selected.length()).any {
                selected.optString(it).replace("PRZEDSTAWICIEL", "PRZED", ignoreCase = true)
                    .equals(code, ignoreCase = true)
            }
        }
        if (scope == "GROUP") {
            val allowed = contest.optJSONArray("participantGroups") ?: JSONArray()
            val groups = mutableSetOf<String>()
            val profile = currentUserConfig() ?: return false
            for (key in listOf("group", "team", "department", "participantGroup")) {
                profile.optString(key).split(',', ';').map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotBlank() }.forEach { groups.add(it) }
            }
            val profileGroups = profile.optJSONArray("groups") ?: JSONArray()
            for (index in 0 until profileGroups.length()) {
                profileGroups.optString(index).trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }?.let(groups::add)
            }
            return (0 until allowed.length()).any {
                allowed.optString(it).trim().lowercase(Locale.ROOT) in groups
            }
        }
        return true
    }

    private fun renderContestSelector(contests: List<JSONObject>) {
        card(padding = 8) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Aktywne konkursy i zadania: ${contests.size}"
                textSize = 12f
                setTextColor(muted)
                setPadding(dp(4), 0, 0, dp(6))
            })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    contests.forEach { contest ->
                        val contestId = contest.optString("_id")
                        val active = contestId == selectedContestId
                        addView(Button(context).apply {
                            val icon = if (contest.optString("type") == "TASK") "🎯" else "🏆"
                            text = "$icon " + contest.optString("displayTitle", contest.optString("name", "Konkurs"))
                            textSize = 12f
                            isAllCaps = false
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(if (active) Color.WHITE else blue)
                            setBackgroundColor(if (active) blue else softBlue)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)
                            ).apply { setMargins(dp(3), 0, dp(3), 0) }
                            setOnClickListener {
                                selectedContestId = contestId
                                selectedContestRepresentativeCode = ""
                                konkursTab = "results"
                                konkursSearch = ""
                                render()
                            }
                        })
                    }
                })
            })
        }
    }

    private fun contestResults(contest: JSONObject): JSONArray {
        val source = data.optJSONArray("allContestResults") ?: data.optJSONArray("contestResultsRaw") ?: JSONArray()
        val selected = JSONArray()
        val contestId = contest.optString("_id")
        for (index in 0 until source.length()) {
            val result = source.optJSONObject(index) ?: continue
            if (result.optString("contestId") == contestId) selected.put(result)
        }
        return selected
    }

    private fun ownContestResult(contest: JSONObject): JSONObject? {
        val code = currentContestCode()
        if (code.isBlank()) return null
        val results = contestResults(contest)
        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: continue
            if (result.optString("representativeCode").equals(code, ignoreCase = true)) return result
        }
        return null
    }

    private fun contestRanking(contest: JSONObject): JSONArray {
        val results = contestResults(contest)
        val ranking = JSONArray()
        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: continue
            ranking.put(JSONObject()
                .put("Kontrahent Grupa", result.optString("representativeCode", result.optString("name")))
                .put("Sprzedaż Wartość", result.optDouble("value", result.optDouble("sales", result.optDouble("totalSales"))))
                .put("Ranking Wartość", result.optDouble("rankingValue", result.optDouble("value")))
                .put("Pozycja", result.optInt("rank", index + 1))
                .put("Zakwalifikowany", result.optBoolean("qualified", true))
                .put("Wynik", result))
        }
        return ranking
    }

    private fun contestMetricLabel(metric: String, contest: JSONObject? = null): String {
        if (contest != null && contest.optString("rankingMetric").equals(metric, ignoreCase = true)) {
            contest.optString("rankingMetricLabel").takeIf { it.isNotBlank() }?.let { return it }
        }
        return when (metric.uppercase(Locale.ROOT)) {
            "SALES_VALUE" -> "Wartość sprzedaży netto"
            "QUANTITY" -> "Liczba sprzedanych sztuk"
            "MARGIN_VALUE" -> "Uzyskana marża"
            "MARGIN_PERCENT" -> "Marża / narzut handlowy"
            "NEW_CUSTOMERS" -> "Nowi klienci"
            "ACTIVE_CUSTOMERS" -> "Aktywni klienci"
            "DOCUMENT_COUNT" -> "Liczba dokumentów"
            "AVERAGE_DOCUMENT_VALUE" -> "Średnia wartość dokumentu"
            "GOAL_PERCENT" -> "Realizacja celu indywidualnego"
            "IMPORT_SALES" -> "Sprzedaż IMPORT"
            "DOMESTIC_SALES" -> "Sprzedaż KRAJ"
            "DIAMANTO_SALES" -> "Sprzedaż DIAMANTO"
            "IMPORT_MARGIN" -> "Marża IMPORT"
            "DOMESTIC_MARGIN" -> "Marża KRAJ"
            "IMPORT_SHARE" -> "Udział IMPORT"
            "DIAMANTO_SHARE" -> "Udział DIAMANTO"
            "SALES_GROWTH_VALUE" -> "Przyrost sprzedaży"
            "SALES_GROWTH_PERCENT" -> "Przyrost sprzedaży procentowo"
            "SOLD_PRODUCTS" -> "Różne sprzedane produkty"
            else -> metric
        }
    }

    private fun formatContestMetric(metric: String, value: Double, unitHint: String = ""): String {
        val code = metric.uppercase(Locale.ROOT)
        if (code in setOf("SALES_VALUE", "MARGIN_VALUE", "AVERAGE_DOCUMENT_VALUE", "IMPORT_SALES", "DOMESTIC_SALES",
                "DIAMANTO_SALES", "IMPORT_MARGIN", "DOMESTIC_MARGIN", "SALES_GROWTH_VALUE")) return money(value)
        if (code in setOf("MARGIN_PERCENT", "GOAL_PERCENT", "IMPORT_SHARE", "DIAMANTO_SHARE", "SALES_GROWTH_PERCENT")) {
            return "%.2f %%".format(Locale("pl", "PL"), value)
        }
        val decimals = if (value % 1.0 == 0.0) "%.0f" else "%.2f"
        val rendered = decimals.format(Locale("pl", "PL"), value)
        val unit = unitHint.ifBlank {
            when (code) {
                "QUANTITY" -> "szt."
                "NEW_CUSTOMERS", "ACTIVE_CUSTOMERS" -> "klientów"
                "DOCUMENT_COUNT" -> "dok."
                "SOLD_PRODUCTS" -> "prod."
                else -> ""
            }
        }
        return if (unit.isBlank()) rendered else "$rendered $unit"
    }


    private fun contestCalculationMode(contest: JSONObject, result: JSONObject?): String {
        val progress = result?.optJSONObject("conditionProgress")
        when (progress?.optString("mode")?.uppercase(Locale.ROOT)) {
            "ALL_PRODUCTS_MIN_QTY" -> return "QUANTITY"
            "SALES_VALUE" -> return "VALUE"
            "RANKING" -> return "RANKING"
        }
        if (contest.optString("type").equals("RANKING", true)) return "RANKING"
        val conditions = contest.optJSONArray("conditions") ?: JSONArray()
        for (i in 0 until conditions.length()) {
            if (conditions.optJSONObject(i)?.optString("metric")?.equals("SALES_VALUE", true) == true) return "VALUE"
        }
        return if (contest.optString("type").equals("TASK", true)) "QUANTITY" else "RANKING"
    }

    private fun contestViewedResult(contest: JSONObject, results: JSONArray): JSONObject? {
        if (!isAdmin() && !isBiuro()) return ownContestResult(contest)
        if (results.length() == 0) return null
        if (selectedContestRepresentativeCode.isBlank() ||
            (0 until results.length()).none {
                results.optJSONObject(it)?.optString("representativeCode")
                    ?.equals(selectedContestRepresentativeCode, true) == true
            }) {
            selectedContestRepresentativeCode = results.optJSONObject(0)?.optString("representativeCode").orEmpty()
        }
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            if (r.optString("representativeCode").equals(selectedContestRepresentativeCode, true)) return r
        }
        return results.optJSONObject(0)
    }

    private fun contestMaxReward(contest: JSONObject, mode: String): Double {
        return when (mode) {
            "VALUE" -> {
                val tiers = contest.optJSONArray("valueTiers") ?: JSONArray()
                var maxReward = 0.0
                for (i in 0 until tiers.length()) {
                    val tier = tiers.optJSONObject(i) ?: continue
                    maxReward = maxOf(maxReward, tier.optDouble("reward", 0.0))
                }
                if (maxReward > 0.0) maxReward else contest.optDouble("rewardAmount", 0.0)
            }
            "RANKING" -> {
                val rewards = contest.optJSONObject("rankingRewards") ?: JSONObject()
                var maxReward = 0.0
                val keys = rewards.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    maxReward = maxOf(maxReward, rewards.optDouble(key, 0.0))
                }
                maxReward
            }
            else -> contest.optDouble("rewardAmount", 0.0)
        }
    }

    private fun contestRewardProgressView(
        contest: JSONObject,
        result: JSONObject,
        mode: String
    ): TextView {
        val earned = result.optDouble("rewardAmount", 0.0).coerceAtLeast(0.0)
        val maximum = contestMaxReward(contest, mode).coerceAtLeast(0.0)
        return TextView(this).apply {
            text = "Premia: ${money(earned)} / ${money(maximum)}"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (earned > 0.0) green else red)
            setPadding(0, dp(7), 0, dp(2))
        }
    }

    private fun renderContestMainHeader(contest: JSONObject, mode: String) {
        card(padding = 13) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                val icon = when (mode) { "VALUE" -> "💰"; "RANKING" -> "🏆"; else -> "🎯" }
                text = "$icon " + contest.optString("displayTitle", contest.optString("name", "Konkurs"))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(blue)
            })
            val start = contest.optString("startDate")
            val end = contest.optString("endDate")
            if (start.isNotBlank() || end.isNotBlank()) addView(TextView(context).apply {
                text = "${start.ifBlank { "—" }} – ${end.ifBlank { "—" }}"
                textSize = 12f; setTextColor(muted); setPadding(0, dp(4), 0, 0)
            })
            val maxReward = contestMaxReward(contest, mode)
            if (maxReward > 0.0) addView(TextView(context).apply {
                text = "Maksymalna premia: ${money(maxReward)}"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(muted); setPadding(0, dp(5), 0, 0)
            })
            if (contest.optInt("productsCount", 0) > 0) addView(TextView(context).apply {
                text = "Produkty w puli: ${contest.optInt("productsCount")}"
                textSize = 12f; setTextColor(muted); setPadding(0, dp(3), 0, 0)
            })
            val description = contest.optString("description")
            if (description.isNotBlank()) addView(TextView(context).apply {
                text = description
                textSize = 12f; setTextColor(dark); setPadding(0, dp(7), 0, 0)
            })
        }
    }

    private fun renderContestRepresentativeSelector(results: JSONArray) {
        card(padding = 8) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Podgląd przedstawiciela"
                textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(muted); setPadding(dp(4), 0, 0, dp(6))
            })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val list = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                        .sortedBy { displayName(it.optString("representativeCode")) }
                    list.forEach { r ->
                        val code = r.optString("representativeCode")
                        val active = code.equals(selectedContestRepresentativeCode, true)
                        addView(Button(context).apply {
                            text = displayName(code)
                            textSize = 11f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
                            setTextColor(if (active) Color.WHITE else blue)
                            setBackgroundColor(if (active) blue else softBlue)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                                setMargins(dp(2), 0, dp(2), 0)
                            }
                            setOnClickListener {
                                selectedContestRepresentativeCode = code
                                render()
                            }
                        })
                    }
                })
            })
        }
    }

    private fun renderContestProgressSummary(contest: JSONObject, result: JSONObject, mode: String) {
        val person = if (isAdmin() || isBiuro()) displayName(result.optString("representativeCode")) else "Twój postęp"
        card(padding = 14) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = person
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
            })

            if (contestMaxReward(contest, mode) > 0.0) {
                addView(contestRewardProgressView(contest, result, mode))
            }

            when (mode) {
                "QUANTITY" -> {
                    val cp = result.optJSONObject("conditionProgress") ?: JSONObject()
                    val total = cp.optInt("productsTotal", contest.optInt("productsCount", 0))
                    val done = cp.optInt("productsDone", completedTaskProducts(contest.optJSONArray("products") ?: JSONArray(), result.optJSONObject("progress") ?: JSONObject()))
                    val pct = if (total > 0) done * 100.0 / total else 0.0
                    addView(TextView(context).apply {
                        text = "$done / $total produktów"
                        textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (result.optBoolean("completed")) green else dark)
                        setPadding(0, dp(5), 0, dp(5))
                    })
                    addView(contestProgressBar(pct))
                    addView(TextView(context).apply {
                        text = if (result.optBoolean("completed")) "✓ Premia zdobyta" else "Do premii trzeba wykonać wszystkie pozycje."
                        textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (result.optBoolean("completed")) green else muted)
                        setPadding(0, dp(7), 0, 0)
                    })
                }
                "VALUE" -> {
                    val cp = result.optJSONObject("conditionProgress") ?: JSONObject()
                    val current = cp.optDouble("current", result.optDouble("value"))
                    val target = cp.optDouble("target", contestSalesTarget(contest))
                    val pct = if (target > 0) current * 100.0 / target else 0.0
                    val missing = (target - current).coerceAtLeast(0.0)
                    addView(TextView(context).apply {
                        text = "${money(current)} / ${money(target)} netto"
                        textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (current >= target && target > 0) green else dark)
                        setPadding(0, dp(5), 0, dp(5))
                    })
                    addView(contestProgressBar(pct))
                    addView(TextView(context).apply {
                        val earned = result.optDouble("rewardAmount", 0.0)
                        text = when {
                            earned > 0.0 && missing > 0.0 -> "Aktualna premia: ${money(earned)} • do kolejnego progu: ${money(missing)}"
                            earned > 0.0 -> "✓ Aktualna premia: ${money(earned)}"
                            missing <= 0.0 && target > 0 -> "✓ Próg osiągnięty"
                            else -> "Brakuje: ${money(missing)}"
                        }
                        textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (earned > 0.0 || (missing <= 0.0 && target > 0)) green else red)
                        setPadding(0, dp(7), 0, 0)
                    })
                }
                else -> {
                    val rank = result.optInt("rank", 0)
                    val value = result.optDouble("rankingValue", result.optDouble("value"))
                    addView(TextView(context).apply {
                        text = if (rank > 0) "$rank. miejsce" else "Brak miejsca"
                        textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue); setPadding(0, dp(5), 0, 0)
                    })
                    addView(TextView(context).apply {
                        text = money(value) + " netto"
                        textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(dark); setPadding(0, dp(3), 0, 0)
                    })
                    val gap = rankingGapToPrevious(contest, result)
                    if (gap > 0.0 && rank > 1) addView(TextView(context).apply {
                        text = "Do ${rank - 1}. miejsca: ${money(gap)}"
                        textSize = 13f; setTextColor(red); setPadding(0, dp(5), 0, 0)
                    })
                }
            }
        }
    }

    private fun contestProgressBar(percentValue: Double): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = percentValue.toInt().coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(9))
        }
    }

    private fun contestSalesTarget(contest: JSONObject): Double {
        val conditions = contest.optJSONArray("conditions") ?: JSONArray()
        for (i in 0 until conditions.length()) {
            val c = conditions.optJSONObject(i) ?: continue
            if (c.optString("metric").equals("SALES_VALUE", true)) return c.optDouble("target")
        }
        return 0.0
    }

    private fun rankingGapToPrevious(contest: JSONObject, result: JSONObject): Double {
        val rank = result.optInt("rank", 0)
        if (rank <= 1) return 0.0
        val results = contestResults(contest)
        var previousValue = 0.0
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            if (r.optInt("rank", 0) == rank - 1) {
                previousValue = r.optDouble("rankingValue", r.optDouble("value"))
                break
            }
        }
        val current = result.optDouble("rankingValue", result.optDouble("value"))
        return (previousValue - current).coerceAtLeast(0.0)
    }

    private fun renderQuantityContestDetails(contest: JSONObject, result: JSONObject) {
        val cp = result.optJSONObject("conditionProgress") ?: JSONObject()
        val targets = cp.optJSONObject("targets") ?: JSONObject()
        val products = contest.optJSONArray("products") ?: JSONArray()
        val names = mutableMapOf<String, String>()
        for (i in 0 until products.length()) {
            val p = products.optJSONObject(i) ?: continue
            names[p.optString("code")] = p.optString("displayName", p.optString("code"))
        }

        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Co jeszcze trzeba zrobić"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue); setPadding(0, 0, 0, dp(6))
            })
            val keys = mutableListOf<String>()
            val it = targets.keys()
            while (it.hasNext()) keys.add(it.next())
            val ordered = if (products.length() > 0) {
                (0 until products.length()).mapNotNull { products.optJSONObject(it)?.optString("code") }.filter { it.isNotBlank() }
            } else keys.sorted()
            ordered.take(60).forEach { code ->
                val row = targets.optJSONObject(code)
                val target = row?.optDouble("target") ?: productsForCode(products, code)?.optDouble("target", 0.0) ?: 0.0
                val current = row?.optDouble("current") ?: result.optJSONObject("progress")?.optDouble(code, 0.0) ?: 0.0
                val done = current >= target && target > 0
                val missing = (target - current).coerceAtLeast(0.0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(7))
                    addView(tableRowView(
                        (if (done) "✓ " else "• ") + code,
                        "${taskQuantity(current)} / ${taskQuantity(target)}"
                    ))
                    val name = names[code].orEmpty()
                    if (name.isNotBlank() && !name.equals(code, true)) addView(TextView(context).apply {
                        text = name; textSize = 11f; setTextColor(muted); setPadding(dp(10), 0, 0, 0)
                    })
                    if (!done && missing > 0) addView(TextView(context).apply {
                        text = "Brakuje: ${taskQuantity(missing)}"
                        textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(red); setPadding(dp(10), dp(2), 0, 0)
                    })
                })
            }
            if (ordered.size > 60) addView(TextView(context).apply {
                text = "Pełna lista ma ${ordered.size} pozycji. Użyj zakładki Produkty."
                textSize = 12f; setTextColor(muted); setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun productsForCode(products: JSONArray, code: String): JSONObject? {
        for (i in 0 until products.length()) {
            val p = products.optJSONObject(i) ?: continue
            if (p.optString("code").equals(code, true)) return p
        }
        return null
    }

    private fun renderValueContestDetails(contest: JSONObject, result: JSONObject?) {
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Jak liczony jest konkurs"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
            })
            addView(TextView(context).apply {
                text = "Sumowana jest wartość sprzedaży netto wyłącznie produktów z puli konkursowej. " +
                    "Im wyższy próg, tym wyższa premia. Ilości pojedynczych produktów nie są warunkiem."
                textSize = 13f; setTextColor(dark); setPadding(0, dp(6), 0, 0)
            })
            val tiers = contest.optJSONArray("valueTiers") ?: JSONArray()
            if (tiers.length() > 0) {
                addView(TextView(context).apply {
                    text = "Progi i premie"
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue); setPadding(0, dp(10), 0, dp(4))
                })
                for (i in 0 until tiers.length()) {
                    val tier = tiers.optJSONObject(i) ?: continue
                    val target = tier.optDouble("target")
                    val reward = tier.optDouble("reward")
                    val current = result?.optDouble("value", 0.0) ?: 0.0
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(2), 0, dp(2))
                        addView(TextView(context).apply {
                            text = (if (current >= target) "✓ " else "• ") + money(target)
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(if (current >= target) green else red)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(TextView(context).apply {
                            text = money(reward)
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(if (current >= target) green else muted)
                        })
                    })
                }
            }
            if (contest.optInt("productsCount", 0) > 0) addView(TextView(context).apply {
                text = "Pula: ${contest.optInt("productsCount")} produktów — otwórz zakładkę Produkty, aby wyszukać kod/EAN."
                textSize = 12f; setTextColor(muted); setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun renderCompactContestParticipants(contest: JSONObject, results: JSONArray, mode: String) {
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Wyniki przedstawicieli"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue); setPadding(0, 0, 0, dp(5))
            })
            val rows = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .sortedBy { displayName(it.optString("representativeCode")) }
            rows.forEach { r ->
                val code = r.optString("representativeCode")
                val right = if (mode == "QUANTITY") {
                    val cp = r.optJSONObject("conditionProgress") ?: JSONObject()
                    "${cp.optInt("productsDone")}/${cp.optInt("productsTotal")}"
                } else {
                    val cp = r.optJSONObject("conditionProgress") ?: JSONObject()
                    "${money(cp.optDouble("current", r.optDouble("value")))} / ${money(cp.optDouble("target", contestSalesTarget(contest)))}"
                }
                addView(tableRowView(displayName(code), (if (r.optBoolean("completed")) "✓ " else "") + right))
            }
        }
    }

    private fun renderOwnContestResult(contest: JSONObject, result: JSONObject) {
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            val task = contest.optString("type") == "TASK"
            addView(TextView(context).apply {
                text = if (task) "Twoje wykonanie zadania" else "Twój wynik"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(blue)
            })
            if (task) {
                val products = contest.optJSONArray("products") ?: JSONArray()
                val progress = result.optJSONObject("progress") ?: JSONObject()
                if (products.length() > 0) {
                    addView(tableRowView(
                        "Zaliczone produkty",
                        "${completedTaskProducts(products, progress)} / ${products.length()}"
                    ))
                }
                addView(tableRowView(
                    "Status zadania",
                    if (result.optBoolean("completed")) "✓ Zaliczone" else "W trakcie"
                ))
                if (result.optDouble("rewardAmount") > 0.0) {
                    addView(tableRowView("Przyznana premia", money(result.optDouble("rewardAmount"))))
                } else if (contest.optDouble("rewardAmount") > 0.0) {
                    addView(tableRowView("Premia do zdobycia", money(contest.optDouble("rewardAmount"))))
                }
            } else {
                val metric = contest.optString("rankingMetric", "SALES_VALUE")
                addView(tableRowView("Pozycja", "${result.optInt("rank", 0)}. miejsce"))
                addView(tableRowView(contestMetricLabel(metric, contest),
                    formatContestMetric(metric, result.optDouble("rankingValue", result.optDouble("value")))))
                addView(tableRowView("Sprzedaż", money(result.optDouble("value"))))
                addView(tableRowView("Uzyskana marża", money(result.optDouble("margin"))))
                if (result.optDouble("individualTarget") > 0.0) {
                    addView(tableRowView("Twój cel", money(result.optDouble("individualTarget"))))
                    addView(tableRowView("Realizacja celu",
                        formatContestMetric("GOAL_PERCENT", result.optDouble("goalPercent"))))
                }
                if (result.optInt("newCustomers") > 0) {
                    addView(tableRowView("Nowi klienci",
                        formatContestMetric("NEW_CUSTOMERS", result.optInt("newCustomers").toDouble())))
                }
                if (result.optDouble("rewardAmount") > 0.0) {
                    addView(tableRowView("Przyznana premia", money(result.optDouble("rewardAmount"))))
                }
            }
        }
    }

    private fun renderContestConditions(contest: JSONObject, ownResult: JSONObject?) {
        val conditions = contest.optJSONArray("conditions") ?: JSONArray()
        if (conditions.length() == 0) return
        val progress = ownResult?.optJSONArray("conditionProgress") ?: JSONArray()
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Warunki konkursu"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(blue)
            })
            for (index in 0 until conditions.length()) {
                val condition = conditions.optJSONObject(index) ?: continue
                val result = progress.optJSONObject(index)
                val metric = condition.optString("metric", "SALES_VALUE")
                val unit = condition.optString("unit")
                val operator = when (condition.optString("operator", "GTE")) {
                    "GT" -> ">"
                    "LTE" -> "≤"
                    "LT" -> "<"
                    "EQ" -> "="
                    else -> "≥"
                }
                val completed = result?.optBoolean("completed") == true
                val marker = if (completed) "✓ " else "• "
                addView(tableRowView(marker + condition.optString("label", contestMetricLabel(metric)),
                    "$operator ${formatContestMetric(metric, condition.optDouble("target"), unit)}"))
                if (result != null) {
                    addView(TextView(context).apply {
                        text = "Aktualnie: ${formatContestMetric(metric, result.optDouble("current"), unit)}"
                        textSize = 12f
                        setTextColor(if (completed) green else muted)
                        setPadding(dp(10), 0, 0, dp(5))
                    })
                }
            }
            addView(TextView(context).apply {
                text = if (contest.optString("completionRule", "ALL") == "ALL") {
                    "Zaliczenie: wszystkie wymagane warunki"
                } else {
                    "Zaliczenie: wystarczy dowolny warunek"
                }
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(7), 0, 0)
            })
        }
    }

    private fun renderRankingRewards(contest: JSONObject) {
        val rewards = contest.optJSONObject("rankingRewards") ?: return
        if (listOf("1", "2", "3").none { rewards.optDouble(it, 0.0) > 0.0 }) return
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Premie za miejsca"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(blue)
            })
            listOf("🥇" to "1", "🥈" to "2", "🥉" to "3").forEach { (medal, place) ->
                val amount = rewards.optDouble(place, 0.0)
                if (amount > 0.0) addView(tableRowView("$medal $place. miejsce", money(amount)))
            }
        }
    }

    private fun renderContestTask(contest: JSONObject) {
        val results = contestResults(contest)
        val own = ownContestResult(contest)
        val progress = own?.optJSONObject("progress") ?: JSONObject()
        val completed = own?.optBoolean("completed") == true
        val products = contest.optJSONArray("products") ?: JSONArray()
        card(padding = 14) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Premia dla każdej osoby: " + money(contest.optDouble("rewardAmount"))
                textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(green)
            })
            if (products.length() == 0 && contest.optInt("productsCount") > 0) {
                addView(TextView(context).apply {
                    text = if (contest.optBoolean("productsLoading")) {
                        "Pobieranie listy produktów z Arkusza Google..."
                    } else {
                        "Udostępnij Arkusz Google, aby zobaczyć wykonanie produktów."
                    }
                    textSize = 12f
                    setTextColor(muted)
                    setPadding(0, dp(8), 0, 0)
                })
            }
            if (own != null) {
                for (index in 0 until minOf(products.length(), 30)) {
                    val product = products.optJSONObject(index) ?: continue
                    addView(taskProductProgressView(product, progress))
                }
            } else if (!isAdmin() && !isBiuro()) {
                for (index in 0 until minOf(products.length(), 30)) {
                    val product = products.optJSONObject(index) ?: continue
                    addView(taskProductProgressView(product, progress))
                }
            }
            if (own != null && products.length() > 30) addView(TextView(context).apply {
                text = "Pozostałe produkty znajdziesz w zakładce Produkty."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(7), 0, 0)
            })
            addView(TextView(context).apply {
                text = if (contest.optString("completionRule", "ALL") == "ALL") {
                    "Zaliczenie: wszystkie wymagane warunki i produkty"
                } else {
                    "Zaliczenie: dowolny wymagany warunek lub produkt"
                }
                textSize = 12f; setTextColor(muted); setPadding(0, dp(8), 0, 0)
            })
            if (currentContestCode().isNotBlank()) {
                addView(TextView(context).apply {
                    text = if (completed) "✓ Zadanie zaliczone — premia przyznana" else "Zadanie w trakcie realizacji"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (completed) green else muted)
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }
        if ((isAdmin() || isBiuro()) && results.length() > 0) {
            card(padding = 12) {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "Wyniki przedstawicieli"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(blue)
                })
                for (index in 0 until results.length()) {
                    val result = results.optJSONObject(index) ?: continue
                    val code = result.optString("representativeCode")
                    val name = displayName(code)
                    val status = if (result.optBoolean("completed")) "✓ Zaliczone" else "W trakcie"
                    val participantProgress = result.optJSONObject("progress") ?: JSONObject()
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(6), dp(8), dp(6), dp(10))
                        if (code.equals(currentContestCode(), ignoreCase = true)) {
                            setBackgroundColor(softBlue)
                        }
                        addView(tableRowView(name, status))
                        if (products.length() > 0) {
                            addView(TextView(context).apply {
                                text = "Wykonane produkty: ${completedTaskProducts(products, participantProgress)} / ${products.length()}"
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(if (result.optBoolean("completed")) green else muted)
                                setPadding(0, 0, 0, dp(4))
                            })
                            for (productIndex in 0 until minOf(products.length(), 30)) {
                                val product = products.optJSONObject(productIndex) ?: continue
                                addView(taskProductProgressView(product, participantProgress))
                            }
                            if (products.length() > 30) {
                                addView(TextView(context).apply {
                                    text = "Pozostałe produkty: ${products.length() - 30}"
                                    textSize = 11f
                                    setTextColor(muted)
                                    setPadding(0, dp(4), 0, 0)
                                })
                            }
                        }
                        if (result.optDouble("rewardAmount") > 0.0) {
                            addView(tableRowView("Przyznana premia", money(result.optDouble("rewardAmount"))))
                        }
                    })
                }
            }
        }
    }

    private fun completedTaskProducts(products: JSONArray, progress: JSONObject): Int {
        var completed = 0
        for (index in 0 until products.length()) {
            val product = products.optJSONObject(index) ?: continue
            val target = product.optDouble("target", 0.0)
            if (target > 0.0 && progress.optDouble(product.optString("code"), 0.0) >= target) completed++
        }
        return completed
    }

    private fun taskQuantity(value: Double): String {
        if (value % 1.0 == 0.0) return "%.0f".format(Locale("pl", "PL"), value)
        return "%.2f".format(Locale("pl", "PL"), value).trimEnd('0').trimEnd(',')
    }

    private fun taskProductProgressView(product: JSONObject, progress: JSONObject): LinearLayout {
        val code = product.optString("code")
        val name = product.optString("displayName", code)
        val target = product.optDouble("target", 0.0)
        val current = progress.optDouble(code, 0.0)
        val unit = product.optString("unit", "szt.")
        val done = target > 0.0 && current >= target
        val marker = if (done) "✓" else "•"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(5))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = "$marker $code"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (done) green else dark)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "${taskQuantity(current)} / ${taskQuantity(target)} $unit"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (done) green else red)
                    gravity = Gravity.END
                })
            })
            if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) {
                addView(TextView(context).apply {
                    text = name
                    textSize = 11f
                    setTextColor(muted)
                    setPadding(dp(15), dp(2), 0, 0)
                })
            }
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = if (target > 0.0) ((current / target) * 100).toInt().coerceIn(0, 100) else 0
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(7))
            })
        }
    }

    private fun konkursTopTabs() {
        card(padding = 8) {
            orientation = LinearLayout.HORIZONTAL
            addView(konkursTabButton("🏆 Wyniki", "results"))
            addView(konkursTabButton("📦 Produkty", "products"))
        }
    }

    private fun konkursTabButton(label: String, tab: String): Button {
        val active = konkursTab == tab
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(if (active) Color.WHITE else blue)
            setBackgroundColor(if (active) blue else softBlue)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { konkursTab = tab; render() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        }
    }

    private fun renderKonkursProductsScreen(contest: JSONObject) {
        val source = contest.optJSONArray("products") ?: JSONArray()
        val products = JSONArray()
        for (index in 0 until source.length()) {
            val product = source.optJSONObject(index) ?: continue
            products.put(JSONObject()
                .put("kod", product.optString("code"))
                .put("nazwa", product.optString("displayName", product.optString("code")))
                .put("ean", product.optString("ean"))
                .put("target", product.optDouble("target"))
                .put("unit", product.optString("unit", "szt."))
                .put("zdjecie", product.optString("imageUrl", product.optString("photo", product.optString("zdjecie")))))
        }

        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "📦 Lista produktów konkursowych"
                textSize = 16f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })
            addView(TextView(context).apply {
                text = "Szukaj po kodzie, nazwie lub EAN."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, 0, 0, dp(8))
            })

            val searchInput = EditText(context).apply {
                hint = "🔍 Szukaj produktu..."
                textSize = 14f
                setSingleLine(true)
                setText(konkursSearch)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addView(searchInput)
            searchInput.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    konkursSearch = s?.toString() ?: ""
                    render()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        renderKonkursProductRows(products, konkursSearch)
    }

    private fun renderKonkursProductRows(products: JSONArray, query: String) {
        val q = query.trim().lowercase()
        val rows = mutableListOf<JSONObject>()

        for (i in 0 until products.length()) {
            val p = products.getJSONObject(i)
            val kod = konkursProductValue(p, "kod", "Kod")
            val nazwa = konkursProductValue(p, "nazwa", "Nazwa")
            val ean = konkursProductValue(p, "ean", "EAN")
            if (q.isBlank() || "$kod $nazwa $ean".lowercase().contains(q)) rows.add(p)
        }

        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Wyświetlono: ${rows.size} / ${products.length()} produktów"
                textSize = 12f
                setTextColor(muted)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            if (rows.isEmpty()) {
                addView(TextView(context).apply {
                    text = "Brak produktów dla wpisanej frazy."
                    textSize = 14f
                    setTextColor(muted)
                    setPadding(0, dp(8), 0, dp(8))
                })
                return@card
            }

            rows.take(250).forEach { addView(konkursProductRow(it)) }

            if (rows.size > 250) {
                addView(TextView(context).apply {
                    text = "Pokazano pierwsze 250 wyników. Doprecyzuj wyszukiwanie."
                    textSize = 12f
                    setTextColor(red)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }
    }

    private fun konkursProductRow(p: JSONObject): LinearLayout {
        val kod = konkursProductValue(p, "kod", "Kod")
        val nazwa = konkursProductValue(p, "nazwa", "Nazwa")
        val ean = konkursProductValue(p, "ean", "EAN")
        val photo = konkursProductValue(p, "zdjecie", "Zdjęcie")
            .ifBlank { konkursProductValue(p, "zdjęcie", "Zdjęcie") }
            .ifBlank { konkursProductValue(p, "Zdjecie", "Zdjecie") }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(12))

            if (photo.isNotBlank()) {
                val image = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(150)
                    ).apply {
                        bottomMargin = dp(8)
                    }

                    scaleType = ImageView.ScaleType.CENTER_CROP
                    adjustViewBounds = true
                    setBackgroundColor(Color.rgb(245, 247, 252))
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }

                Glide.with(context)
                    .load(photo)
                    .placeholder(R.mipmap.ic_launcher)
                    .error(R.mipmap.ic_launcher)
                    .into(image)

                addView(image)
            }

            addView(TextView(context).apply {
                text = kod.ifBlank { "Brak kodu" }
                textSize = 15f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
            })

            addView(TextView(context).apply {
                text = nazwa.ifBlank { "Brak nazwy" }
                textSize = 13f
                setTextColor(dark)
                setPadding(0, dp(2), 0, dp(2))
            })

            addView(TextView(context).apply {
                text = "EAN: ${ean.ifBlank { "-" }}"
                textSize = 12f
                setTextColor(muted)
                typeface = Typeface.DEFAULT_BOLD
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)

                addView(productMiniButton("📋 Kod") {
                    copyToClipboard("Kod produktu", kod)
                })

                addView(productMiniButton("📋 EAN") {
                    copyToClipboard("EAN", ean)
                })
            })
        }
    }

    private fun productMiniButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        }
    }

    private fun konkursProductValue(p: JSONObject, lowerKey: String, originalKey: String): String {
        return p.optString(lowerKey, p.optString(originalKey, "")).trim()
    }


    private fun renderRynekScreen() {
        section("📣 RynekAPP")

        if (rynekData == null && !rynekLoading) {
            loadRynekData()
        }

        if (rynekLoading) {
            rynekProCard(padding = 18) {
                gravity = Gravity.CENTER
                addView(ProgressBar(context).apply {
                    isIndeterminate = true
                })
                addView(TextView(context).apply {
                    text = "Ładowanie zgłoszeń rynkowych..."
                    textSize = 15f
                    setTextColor(muted)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, 0)
                })
            }
            return
        }

        val d = rynekData
        if (d == null || !d.optBoolean("ok", false)) {
            rynekProCard(padding = 18) {
                addView(TextView(context).apply {
                    text = "Nie udało się pobrać danych RynekAPP"
                    textSize = 18f
                    setTextColor(red)
                    typeface = Typeface.DEFAULT_BOLD
                })

                addView(TextView(context).apply {
                    text = d?.optString("message", "Sprawdź połączenie i API.") ?: "Sprawdź połączenie i API."
                    textSize = 13f
                    setTextColor(muted)
                    setPadding(0, dp(6), 0, dp(12))
                })

                addView(Button(context).apply {
                    text = "Pobierz ponownie"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(blue)
                    setOnClickListener { loadRynekData() }
                })
            }
            return
        }

        val dashboard = d.getJSONObject("dashboard")
        val cards = dashboard.getJSONObject("cards")

        rynekHeroCard(cards, dashboard.optString("generatedAt", "-"))
        rynekAddCard(d)
        rynekRankingCard(dashboard.optJSONArray("rankingAdded") ?: JSONArray())
        rynekHistoryCard(dashboard.optJSONArray("history") ?: JSONArray())
    }

    private fun rynekHeroCard(cards: JSONObject, generatedAt: String) {
        rynekProCard(padding = 16) {
            addView(TextView(context).apply {
                text = "Panel zgłoszeń rynkowych"
                textSize = 18f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
            })

            addView(TextView(context).apply {
                text = "Aktualizacja: $generatedAt"
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(2), 0, dp(12))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(rynekKpiTile("Zgłoszenia", cards.optInt("thisMonth", 0).toString(), blue, 1f))
                addView(rynekKpiTile("Priorytet", cards.optInt("priorityReports", 0).toString(), red, 1f))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(rynekKpiTile("Potencjał", money(cards.optDouble("marketPotential", 0.0)), green, 1f))
                addView(rynekKpiTile("Aktywni", cards.optInt("activeSalesmen", 0).toString(), blue, 1f))
            })

            spaceInside(this, 8)
            addView(rynekMiniStatRow("Nowe produkty", cards.optInt("newProducts", 0).toString()))
            addView(rynekMiniStatRow("Braki w ofercie", cards.optInt("missingProducts", 0).toString()))
            addView(rynekMiniStatRow("Problemy cenowe", cards.optInt("priceProblems", 0).toString()))
        }
    }

    private fun rynekAddCard(root: JSONObject) {
        val config = root.optJSONObject("config") ?: JSONObject()
        val salesmen = if (isBiuro()) {
            listOf("BIURO")
        } else {
            jsonArrayToList(config.optJSONArray("salesmen") ?: JSONArray()).ifEmpty {
                listOf("BIURO")
            }
        }
        val types = jsonArrayToList(config.optJSONArray("types") ?: JSONArray()).ifEmpty {
            listOf("Nowy produkt", "Problem z ceną", "Utracona sprzedaż", "Konkurencja", "Brak w ofercie", "Sugestia klienta", "Inne")
        }

        rynekProCard(padding = 16) {
            addView(TextView(context).apply {
                text = "➕ Dodaj zgłoszenie"
                textSize = 18f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })

            addView(TextView(context).apply {
                text = "Szybkie zgłoszenie z rynku — produkt jest wymagany."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, 0, 0, dp(10))
            })

            addView(rynekSmallLabel("Zgłaszający"))
            val salesmanSpinner = Spinner(context)
            salesmanSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, salesmen)
            val officeIndex = salesmen.indexOf("BIURO")
            if (officeIndex >= 0) salesmanSpinner.setSelection(officeIndex)
            addView(salesmanSpinner)

            addView(rynekSmallLabel("Typ zgłoszenia"))
            val typeSpinner = Spinner(context)
            typeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, types)
            addView(typeSpinner)

            addView(rynekSmallLabel("Potencjał"))
            val potentialSpinner = Spinner(context)
            potentialSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("1", "2", "3", "4", "5"))
            potentialSpinner.setSelection(2)
            addView(potentialSpinner)

            val product = rynekEditText("Produkt", true)
            val client = rynekEditText("Klient", false)
            val price = rynekEditText("Cena / potencjał zł", false)
            val description = rynekEditText("Opis", false, 3)
            val link = rynekEditText("Link", false)

            addView(product)
            addView(client)
            addView(price)
            addView(description)
            addView(link)

            addView(Button(context).apply {
                text = "Zapisz zgłoszenie"
                textSize = 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(red)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(10))

                setOnClickListener {
                    val produkt = product.text.toString().trim()
                    if (produkt.isEmpty()) {
                        Toast.makeText(context, "Wpisz produkt.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val payload = JSONObject().apply {
                        put("handlowiec", salesmanSpinner.selectedItem.toString())
                        put("typ", typeSpinner.selectedItem.toString())
                        put("produkt", produkt)
                        put("klient", client.text.toString().trim())
                        put("potencjal", potentialSpinner.selectedItem.toString())
                        put("opis", description.text.toString().trim())
                        put("link", link.text.toString().trim())
                        put("cena", price.text.toString().trim())
                    }

                    addRynekReport(payload)
                }
            })
        }
    }

    private fun rynekRankingCard(arr: JSONArray) {
        rynekProCard(padding = 16) {
            addView(TextView(context).apply {
                text = "🏆 Ranking dodanych"
                textSize = 18f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
            })

            addView(TextView(context).apply {
                text = "Bieżący miesiąc"
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(2), 0, dp(8))
            })

            if (arr.length() == 0) {
                addView(TextView(context).apply {
                    text = "Brak zgłoszeń w tym miesiącu."
                    textSize = 14f
                    setTextColor(muted)
                    setPadding(0, dp(8), 0, dp(8))
                })
                return@rynekProCard
            }

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                addView(rynekRankingRow(i + 1, displayName(o.optString("name")), o.optInt("count", 0).toString()))
            }
        }
    }

    private fun rynekHistoryCard(arr: JSONArray) {
        rynekProCard(padding = 16) {
            addView(TextView(context).apply {
                text = "🕘 Ostatnie zgłoszenia"
                textSize = 18f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            if (arr.length() == 0) {
                addView(TextView(context).apply {
                    text = "Brak zgłoszeń."
                    textSize = 14f
                    setTextColor(muted)
                })
                return@rynekProCard
            }

            val limit = minOf(arr.length(), 12)
            for (i in 0 until limit) {
                val r = arr.getJSONObject(i)
                addView(rynekReportCard(r))
            }
        }
    }

    private fun rynekProCard(padding: Int = 14, content: LinearLayout.() -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
            }
            content()
        }

        main.addView(box)
        space(10)
    }

    private fun rynekKpiTile(label: String, value: String, color: Int, weight: Float): TextView {
        return TextView(this).apply {
            text = "$value\n$label"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(82), weight).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        }
    }

    private fun rynekMiniStatRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))

            addView(TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(muted)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })
        }
    }

    private fun rynekSmallLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 12f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(2))
        }
    }

    private fun rynekRankingRow(place: Int, name: String, value: String): LinearLayout {
        val medal = when (place) {
            1 -> "🥇"
            2 -> "🥈"
            3 -> "🥉"
            else -> "$place."
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, dp(9))

            addView(TextView(context).apply {
                text = "$medal $name"
                textSize = 14f
                setTextColor(dark)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = value
                textSize = 15f
                setTextColor(red)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })
        }
    }

    private fun rynekReportCard(r: JSONObject): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.rgb(250, 251, 255))
                setStroke(dp(1), Color.rgb(229, 233, 242))
            }

            val id = r.optString("id", "")
            val status = r.optString("status", "NOWE").ifBlank { "NOWE" }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(TextView(context).apply {
                    text = r.optString("produkt", "Bez nazwy").ifBlank { "Bez nazwy produktu" }
                    textSize = 15f
                    setTextColor(dark)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(TextView(context).apply {
                    text = status
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(rynekStatusColor(status))
                    }
                })
            })

            addView(TextView(context).apply {
                text = r.optString("typ", "")
                textSize = 13f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(3), 0, 0)
            })

            addView(TextView(context).apply {
                text = "Potencjał: P${r.optString("potencjal", "-")}"
                textSize = 12f
                setTextColor(red)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(2), 0, 0)
            })

            val klient = r.optString("klient", "")
            if (klient.isNotBlank()) {
                addView(TextView(context).apply {
                    text = "Klient: $klient"
                    textSize = 12f
                    setTextColor(muted)
                    setPadding(0, dp(2), 0, 0)
                })
            }

            val opis = r.optString("opis", "")
            if (opis.isNotBlank()) {
                addView(TextView(context).apply {
                    text = opis
                    textSize = 12f
                    setTextColor(Color.rgb(60, 66, 82))
                    maxLines = 2
                    setPadding(0, dp(4), 0, 0)
                })
            }

            addView(TextView(context).apply {
                text = "${displayName(r.optString("handlowiec"))} • ${r.optString("data", "")}"
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(5), 0, dp(8))
            })

            if (isAdmin()) {
                val statuses = listOf(
                    "NOWE",
                    "W ANALIZIE",
                    "DO WYCENY",
                    "DO WDROŻENIA",
                    "WDROŻONE",
                    "ARCHIWUM",
                    "ODRZUCONE"
                )

                addView(TextView(context).apply {
                    text = "Status"
                    textSize = 12f
                    setTextColor(muted)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, dp(4))
                })

                addView(Spinner(context).apply {
                    adapter = ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_dropdown_item,
                        statuses
                    )

                    val currentIndex = statuses.indexOf(status)
                    if (currentIndex >= 0) setSelection(currentIndex)

                    var initialized = false
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, rowId: Long) {
                            if (!initialized) {
                                initialized = true
                                return
                            }

                            val newStatus = statuses[position]
                            if (newStatus != status) {
                                updateRynekStatus(id, newStatus)
                            }
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                })

                addView(Button(context).apply {
                    text = "Usuń zgłoszenie"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(red)
                    typeface = Typeface.DEFAULT_BOLD
                    setOnClickListener {
                        confirmDeleteRynekReport(id, r.optString("produkt", "zgłoszenie"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(42)
                    ).apply {
                        setMargins(0, dp(6), 0, 0)
                    }
                })
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
    }

    private fun rynekStatusColor(status: String): Int {
        return when (status.uppercase()) {
            "NOWE" -> red
            "W ANALIZIE" -> blue
            "DO WYCENY" -> Color.rgb(106, 27, 154)
            "DO WDROŻENIA" -> Color.rgb(245, 124, 0)
            "WDROŻONE" -> green
            "ARCHIWUM" -> Color.rgb(90, 90, 90)
            "ODRZUCONE" -> Color.rgb(120, 120, 120)
            else -> muted
        }
    }

    private fun rynekStatusButton(label: String, color: Int, weight: Float, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), weight).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }
    }

    private fun confirmDeleteRynekReport(id: String, productName: String) {
        if (id.isBlank()) {
            Toast.makeText(this, "Brak ID zgłoszenia.", Toast.LENGTH_SHORT).show()
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Usunąć zgłoszenie?")
            .setMessage(productName)
            .setPositiveButton("Usuń") { _, _ ->
                deleteRynekReport(id)
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun spaceInside(parent: LinearLayout, dpValue: Int) {
        parent.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(dpValue))
        })
    }



    private fun jsonArrayToList(arr: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            out.add(arr.optString(i))
        }
        return out.filter { it.isNotBlank() }
    }


    private fun withAction(url: String, action: String): String {
        return if (url.contains("?")) {
            "$url&action=$action"
        } else {
            "$url?action=$action"
        }
    }

    private fun assertJsonResponse(txt: String, apiName: String) {
        if (txt.trim().startsWith("<")) {
            throw Exception("$apiName zwróciło HTML zamiast JSON. Sprawdź link /exec i wdrożenie Apps Script.")
        }
    }

    private fun loadRynekData() {
        rynekLoading = true
        render()

        thread {
            try {
                val url = withAction(rynekApiUrl, "api")
                android.util.Log.d("RYNEK_API_URL", url)

                val txt = getText(url, 12000, 30000)
                android.util.Log.d("RYNEK_API_RESPONSE", txt.take(300))
                assertJsonResponse(txt, "RynekAPP API")

                val obj = JSONObject(txt)

                runOnUiThread {
                    rynekData = obj
                    rynekLoading = false
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    rynekData = JSONObject().apply {
                        put("ok", false)
                        put("message", e.message ?: "Błąd")
                    }
                    rynekLoading = false
                    render()
                }
            }
        }
    }

    private fun addRynekReport(payload: JSONObject) {
        Toast.makeText(this, "Zapisywanie zgłoszenia...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val request = JSONObject().apply {
                    put("action", "addReport")
                    put("payload", payload)
                }

                val response = postJson(rynekApiUrl, request)
                val obj = JSONObject(response)

                runOnUiThread {
                    if (obj.optBoolean("ok", false)) {
                        Toast.makeText(this, "Zgłoszenie zapisane ✅", Toast.LENGTH_LONG).show()
                        rynekData = JSONObject().apply {
                            put("ok", true)
                            put("dashboard", obj.getJSONObject("dashboard"))
                            put("config", rynekData?.optJSONObject("config") ?: JSONObject())
                        }
                        render()
                    } else {
                        Toast.makeText(this, obj.optString("message", "Błąd zapisu"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Błąd zapisu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun deleteRynekReport(id: String) {
        if (id.isBlank()) {
            Toast.makeText(this, "Brak ID zgłoszenia.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Usuwanie zgłoszenia...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val request = JSONObject().apply {
                    put("action", "deleteReport")
                    put("id", id)
                }

                val response = postJson(rynekApiUrl, request)
                val obj = JSONObject(response)

                runOnUiThread {
                    if (obj.optBoolean("ok", false)) {
                        Toast.makeText(this, obj.optString("message", "Usunięto"), Toast.LENGTH_LONG).show()
                        rynekData = JSONObject().apply {
                            put("ok", true)
                            put("dashboard", obj.getJSONObject("dashboard"))
                            put("config", rynekData?.optJSONObject("config") ?: JSONObject())
                        }
                        render()
                    } else {
                        Toast.makeText(this, obj.optString("message", "Błąd usuwania"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Błąd usuwania: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateRynekStatus(id: String, status: String) {
        if (id.isBlank()) {
            Toast.makeText(this, "Brak ID zgłoszenia.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Zmiana statusu...", Toast.LENGTH_SHORT).show()

        thread {
            try {
                val request = JSONObject().apply {
                    put("action", "updateReportStatus")
                    put("id", id)
                    put("status", status)
                    put("changedBy", "SalesAPP")
                }

                val response = postJson(rynekApiUrl, request)
                val obj = JSONObject(response)

                runOnUiThread {
                    if (obj.optBoolean("ok", false)) {
                        Toast.makeText(this, obj.optString("message", "Status zmieniony"), Toast.LENGTH_LONG).show()
                        rynekData = JSONObject().apply {
                            put("ok", true)
                            put("dashboard", obj.getJSONObject("dashboard"))
                            put("config", rynekData?.optJSONObject("config") ?: JSONObject())
                        }
                        render()
                    } else {
                        Toast.makeText(this, obj.optString("message", "Błąd zmiany statusu"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Błąd statusu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getText(
        url: String,
        connectTimeoutMs: Int = 12000,
        readTimeoutMs: Int = 20000
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw Exception("HTTP $code: ${txt.take(200)}")
            }

            txt
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: JSONObject): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val txt = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (txt.trim().startsWith("<")) {
            throw Exception("API zwróciło HTML zamiast JSON. Sprawdź adres: $url")
        }
        return txt
    }

    private fun rynekEditText(hint: String, required: Boolean, minLines: Int = 1): EditText {
        return EditText(this).apply {
            this.hint = if (required) "$hint *" else hint
            textSize = 14f
            this.minLines = minLines
            setSingleLine(minLines == 1)
            setPadding(0, dp(8), 0, dp(8))
        }
    }




    private fun currentUserConfig(): JSONObject? {
        if (currentUserUid.isNotBlank()) {
            for (i in 0 until usersConfig.length()) {
                val user = usersConfig.optJSONObject(i) ?: continue
                if (user.optString("_id") == currentUserUid ||
                    user.optString("uid") == currentUserUid) {
                    return user
                }
            }
        }

        if (currentUserEmail.isNotBlank()) {
            for (i in 0 until usersConfig.length()) {
                val user = usersConfig.optJSONObject(i) ?: continue
                if (user.optString("email").equals(currentUserEmail, ignoreCase = true)) {
                    return user
                }
            }
        }

        val loggedNorm = normalizePersonName(loggedUserName)

        for (i in 0 until usersConfig.length()) {
            val user = usersConfig.optJSONObject(i) ?: continue

            val login = normalizePersonName(user.optString("login"))
            val representativeCode = normalizePersonName(user.optString("representativeCode"))
            val name = normalizePersonName(user.optString("name"))

            if (loggedNorm == login ||
                loggedNorm == representativeCode ||
                loggedNorm == name ||
                loggedNorm == normalizePersonName(displayName(user.optString("representativeCode")))) {
                return user
            }
        }

        return null
    }

    private fun clientScope(): String {
        if (isAdmin()) return "WSZYSCY"

        if (sessionClientScope == "WSZYSCY" || sessionClientScope == "SWOI") {
            return sessionClientScope
        }

        val fromConfig = currentUserConfig()
            ?.optString("clientScope", "SWOI")
            ?.trim()
            ?.uppercase()
            ?: "SWOI"

        return if (fromConfig == "WSZYSCY") "WSZYSCY" else "SWOI"
    }

    private fun canSeePotentialClient(assigned: String): Boolean {
        if (clientScope() == "WSZYSCY") return true

        val assignedNorm = normalizePersonName(displayName(assigned))
        val userNorm = normalizePersonName(displayName(loggedUserName))

        return assignedNorm == userNorm
    }

    private fun canEditPotentialClient(assigned: String): Boolean {
        // Na razie zakres edycji odpowiada zakresowi widoczności.
        return canSeePotentialClient(assigned)
    }

    private fun potentialClientMatchesSearch(c: JSONObject): Boolean {
        val tokens = normalizeSearchText(potentialClientsSearch)
            .split(" ")
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true

        val haystack = normalizeSearchText(listOf(
            c.optString("klient", ""),
            c.optString("kod", ""),
            c.optString("miejscowosc", ""),
            c.optString("nip", ""),
            c.optString("telefon", ""),
            c.optString("email", ""),
            c.optString("adres", ""),
            c.optString("opis", ""),
            c.optString("notatka", ""),
            c.optString("przedstawiciel", ""),
            displayName(c.optString("przedstawiciel", ""))
        ).joinToString(" "))

        // Każde wpisane słowo musi wystąpić, ale kolejność nie ma znaczenia.
        return tokens.all { haystack.contains(it) }
    }

    private fun potentialClientMatchesStatus(c: JSONObject): Boolean {
        if (potentialClientsStatusFilter == "WSZYSTKIE") return true
        return c.optString("status", "NOWY")
            .trim()
            .uppercase() == potentialClientsStatusFilter
    }

    private fun potentialClientMatchesRep(c: JSONObject): Boolean {
        if (potentialClientsRepFilter == "WSZYSCY") return true

        val assigned = normalizePersonName(displayName(c.optString("przedstawiciel", "")))
        val wanted = normalizePersonName(potentialClientsRepFilter)

        return assigned == wanted
    }

    private fun renderPotentialClientsScreen() {
        section("🎯 Klienci potencjalni")
        main.addView(Button(this).apply {
            text = "Odśwież klientów"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                potentialClientsData = null
                loadPotentialClientsData()
            }
        })
        space(8)

        rynekProCard(padding = 14) {
            addView(TextView(context).apply {
                text = "Filtry"
                textSize = 15f
                setTextColor(dark)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            addView(EditText(context).apply {
                hint = "Szukaj: klient, miejscowość, NIP, telefon..."
                setText(potentialClientsSearch)
                setSingleLine(true)
                setOnEditorActionListener { _, _, _ ->
                    potentialClientsSearch = text.toString().trim()
                    render()
                    true
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        potentialClientsSearch = text.toString().trim()
                    }
                }
            })

            addView(TextView(context).apply {
                text = "Status"
                textSize = 12f
                setTextColor(muted)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(4))
            })

            val statusOptions = listOf(
                "WSZYSTKIE",
                "NOWY",
                "SKONTAKTOWANY",
                "W TRAKCIE",
                "OFERTA WYSŁANA",
                "NEGOCJACJE",
                "KLIENT POZYSKANY",
                "BRAK ZAINTERESOWANIA",
                "ODRZUCONY",
                "ARCHIWUM"
            )

            addView(Spinner(context).apply {
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    statusOptions
                )

                val idx = statusOptions.indexOf(potentialClientsStatusFilter)
                if (idx >= 0) setSelection(idx)

                var initialized = false
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (!initialized) {
                            initialized = true
                            return
                        }

                        potentialClientsStatusFilter = statusOptions[position]
                        render()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            })

            addView(TextView(context).apply {
                text = "Przedstawiciel"
                textSize = 12f
                setTextColor(muted)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, dp(4))
            })

            val repOptions = mutableListOf("WSZYSCY")

            for (i in 0 until representativesConfig.length()) {
                val r = representativesConfig.optJSONObject(i) ?: continue
                if (!r.optBoolean("active", true)) continue

                val name = r.optString("name").trim()
                if (name.isNotBlank() && !repOptions.contains(name)) {
                    repOptions.add(name)
                }
            }

            addView(Spinner(context).apply {
                adapter = ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_dropdown_item,
                    repOptions
                )

                val idx = repOptions.indexOf(potentialClientsRepFilter)
                if (idx >= 0) setSelection(idx)

                var initialized = false
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (!initialized) {
                            initialized = true
                            return
                        }

                        potentialClientsRepFilter = repOptions[position]
                        render()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            })

            addView(Button(context).apply {
                text = "Wyczyść filtry"
                setOnClickListener {
                    potentialClientsSearch = ""
                    potentialClientsStatusFilter = "WSZYSTKIE"
                    potentialClientsRepFilter = "WSZYSCY"
                    render()
                }
            })
        }

        space(8)
        if (potentialClientsData == null && !potentialClientsLoading) {
            loadPotentialClientsData()
        }

        if (potentialClientsLoading) {
            rynekProCard(padding = 18) {
                gravity = Gravity.CENTER
                addView(ProgressBar(context).apply { isIndeterminate = true })
                addView(TextView(context).apply {
                    text = "Ładowanie listy klientów..."
                    textSize = 15f
                    setTextColor(muted)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, 0)
                })
            }
            return
        }

        val root = potentialClientsData
        if (root == null || !root.optBoolean("ok", false)) {
            rynekProCard(padding = 18) {
                addView(TextView(context).apply {
                    text = "Nie udało się pobrać klientów potencjalnych"
                    textSize = 18f
                    setTextColor(red)
                    typeface = Typeface.DEFAULT_BOLD
                })

                addView(TextView(context).apply {
                    text = root?.optString(
                        "message",
                        root.optString("error", "Sprawdź API.")
                    ) ?: "Sprawdź API."
                    textSize = 13f
                    setTextColor(muted)
                    setPadding(0, dp(6), 0, dp(12))
                })

                addView(Button(context).apply {
                    text = "Pobierz ponownie"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(blue)
                    setOnClickListener { loadPotentialClientsData() }
                })
            }
            return
        }

        val arr = root.optJSONArray("clients") ?: JSONArray()
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val assigned = c.optString("przedstawiciel", "")

            if (
                canSeePotentialClient(assigned) &&
                potentialClientMatchesSearch(c) &&
                potentialClientMatchesStatus(c) &&
                potentialClientMatchesRep(c)
            ) {
                filtered.put(c)
            }
        }

        rynekProCard(padding = 16) {
            addView(TextView(context).apply {
                text = if (clientScope() == "WSZYSCY") {
                    "Wszyscy klienci potencjalni"
                } else {
                    "Twoi klienci potencjalni"
                }
                textSize = 18f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = "Liczba pozycji: ${filtered.length()}"
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(2), 0, dp(8))
            })
        }

        if (filtered.length() == 0) {
            rynekProCard(padding = 18) {
                addView(TextView(context).apply {
                    text = "Brak przypisanych klientów."
                    textSize = 15f
                    setTextColor(muted)
                })
            }
            return
        }

        for (i in 0 until filtered.length()) {
            potentialClientCard(filtered.getJSONObject(i))
        }
    }

    private fun potentialClientCard(c: JSONObject) {
        rynekProCard(padding = 14) {
            val id = c.optString("id", "")
            val status = c.optString("status", "NOWY").ifBlank { "NOWY" }
            val assigned = c.optString("przedstawiciel", "")
            val klientName = c.optString("klient", "Bez nazwy").ifBlank { "Bez nazwy klienta" }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(TextView(context).apply {
                    text = klientName
                    textSize = 16f
                    setTextColor(dark)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(4), dp(6), dp(4))
                    setOnClickListener {
                        copyToClipboard("Klient", klientName)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(TextView(context).apply {
                    text = status
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(potentialClientStatusColor(status))
                    }
                })
            })

            val miejscowosc = c.optString("miejscowosc", "")
            val nip = c.optString("nip", "")
            val telefon = c.optString("telefon", "")
            val opis = c.optString("opis", "")
            val note = c.optString("notatka", "")

            if (miejscowosc.isNotBlank()) {
                addView(clientClickableText("📍 Miejscowość: $miejscowosc") {
                    openMaps(miejscowosc)
                })
            }

            if (nip.isNotBlank()) {
                addView(clientClickableText("🧾 NIP: $nip") {
                    copyToClipboard("NIP", nip)
                })
            }

            if (telefon.isNotBlank()) {
                addView(clientClickableText("📞 Telefon: $telefon") {
                    openDialer(telefon)
                })
            }

            addView(TextView(context).apply {
                text = "OPIS / INFORMACJA WYJŚCIOWA"
                textSize = 12f
                setTextColor(muted)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, dp(3))
            })

            addView(clientClickableText(if (opis.isNotBlank()) opis else "—") {
                if (opis.isNotBlank()) copyToClipboard("Opis", opis)
            })

            addView(TextView(context).apply {
                text = "Przedstawiciel: ${displayName(assigned)} • ${c.optString("dataDodania", "")}"
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(5), 0, dp(8))
            })

            addView(TextView(context).apply {
                text = "NOTATKI HANDLOWCA"
                textSize = 12f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(5), 0, dp(3))
            })

            if (note.isNotBlank()) {
                val notes = note
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                notes.forEachIndexed { noteIndex, noteText ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(6), dp(8), dp(6))

                        addView(clientClickableText(noteText) {
                            copyToClipboard("Notatka handlowca", noteText)
                        })

                        if (canEditPotentialClient(assigned)) {
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.END

                                addView(Button(context).apply {
                                    text = "Edytuj"
                                    textSize = 11f
                                    setOnClickListener {
                                        showEditPotentialClientNoteDialog(id, noteIndex, noteText)
                                    }
                                })

                                addView(Button(context).apply {
                                    text = "Usuń"
                                    textSize = 11f
                                    setOnClickListener {
                                        confirmDeletePotentialClientNote(id, noteIndex)
                                    }
                                })
                            })
                        }
                    })
                }
            } else {
                addView(TextView(context).apply {
                    text = "Brak notatek handlowca"
                    textSize = 13f
                    setTextColor(muted)
                })
            }

            if (canEditPotentialClient(assigned)) {
                addView(TextView(context).apply {
                    text = "Zmień status klienta"
                    textSize = 12f
                    setTextColor(muted)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, dp(4))
                })

                val statuses = listOf(
                    "NOWY",
                    "SKONTAKTOWANY",
                    "W TRAKCIE",
                    "OFERTA WYSŁANA",
                    "NEGOCJACJE",
                    "KLIENT POZYSKANY",
                    "BRAK ZAINTERESOWANIA",
                    "ODRZUCONY",
                    "ARCHIWUM"
                )

                addView(Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, statuses)
                    val idx = statuses.indexOf(status)
                    if (idx >= 0) setSelection(idx)

                    var initialized = false
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, rowId: Long) {
                            if (!initialized) {
                                initialized = true
                                return
                            }
                            val newStatus = statuses[position]
                            if (newStatus != status) {
                                askClientStatusNoteAndUpdate(id, newStatus)
                            }
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                })

                addView(Button(context).apply {
                    text = "📝 Dodaj notatkę"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(blue)
                    typeface = Typeface.DEFAULT_BOLD
                    setOnClickListener {
                        askClientNoteAndUpdate(id, status)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(44)
                    ).apply {
                        setMargins(0, dp(8), 0, 0)
                    }
                })
            }
        }
    }

    private fun clientSmallText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        }
    }

    private fun clientClickableText(value: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(blue)
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { onClick() }
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "Skopiowano: $label", Toast.LENGTH_SHORT).show()
    }

    private fun openDialer(phone: String) {
        val cleanPhone = phone.replace(" ", "").replace("-", "")
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$cleanPhone")
        }
        startActivity(intent)
    }

    private fun openMaps(place: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}"))
        startActivity(intent)
    }

    private fun showEditPotentialClientNoteDialog(
        id: String,
        noteIndex: Int,
        currentText: String
    ) {
        val cleanText = currentText
            .replace(Regex("^\\[[^]]+\\]\\s*[^:]*:\\s*"), "")
            .replace(" [EDYTOWANO]", "")
            .trim()

        val input = EditText(this).apply {
            setText(cleanText)
            minLines = 4
            setSingleLine(false)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Edytuj notatkę")
            .setView(input)
            .setPositiveButton("Zapisz") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isBlank()) {
                    Toast.makeText(this, "Notatka jest pusta.", Toast.LENGTH_SHORT).show()
                } else {
                    editPotentialClientNote(id, noteIndex, newText)
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun confirmDeletePotentialClientNote(id: String, noteIndex: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Usuń notatkę")
            .setMessage("Czy na pewno usunąć tę notatkę?")
            .setPositiveButton("Usuń") { _, _ ->
                deletePotentialClientNote(id, noteIndex)
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun editPotentialClientNote(id: String, noteIndex: Int, note: String) {
        updateFirebasePotentialClient(id, "Notatka zmieniona") { client ->
            val notes = client.optString("notatka").lines().filter { it.isNotBlank() }.toMutableList()
            if (noteIndex in notes.indices) notes[noteIndex] = note
            client.put("notatka", notes.joinToString("\n"))
        }
    }

    private fun deletePotentialClientNote(id: String, noteIndex: Int) {
        updateFirebasePotentialClient(id, "Notatka usunięta") { client ->
            val notes = client.optString("notatka").lines().filter { it.isNotBlank() }.toMutableList()
            if (noteIndex in notes.indices) notes.removeAt(noteIndex)
            client.put("notatka", notes.joinToString("\n"))
        }
    }

    private fun askClientNoteAndUpdate(id: String, currentStatus: String) {
        val input = EditText(this).apply {
            hint = "Wpisz notatkę"
            minLines = 4
            setSingleLine(false)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Dodaj notatkę")
            .setView(input)
            .setPositiveButton("Zapisz") { _, _ ->
                val note = input.text.toString().trim()

                if (note.isBlank()) {
                    Toast.makeText(this, "Notatka jest pusta.", Toast.LENGTH_SHORT).show()
                } else {
                    updatePotentialClientStatus(id, currentStatus, note)
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun askClientStatusNoteAndUpdate(id: String, status: String) {
        val input = EditText(this).apply {
            hint = "Notatka handlowca (opcjonalnie)"
            minLines = 3
            setSingleLine(false)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Zmiana statusu")
            .setMessage("Nowy status: $status")
            .setView(input)
            .setPositiveButton("Zapisz") { _, _ ->
                updatePotentialClientStatus(id, status, input.text.toString().trim())
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun potentialClientStatusColor(status: String): Int {
        return when (status.uppercase()) {
            "NOWY" -> red
            "SKONTAKTOWANY" -> blue
            "W TRAKCIE" -> Color.rgb(106, 27, 154)
            "OFERTA WYSŁANA" -> Color.rgb(245, 124, 0)
            "NEGOCJACJE" -> Color.rgb(230, 81, 0)
            "KLIENT POZYSKANY" -> green
            "BRAK ZAINTERESOWANIA" -> Color.rgb(120, 120, 120)
            "ODRZUCONY" -> Color.rgb(90, 90, 90)
            "ARCHIWUM" -> Color.rgb(70, 70, 70)
            else -> blue
        }
    }

    private fun loadPotentialClientsData() {
        potentialClientsLoading = false
        potentialClientsData = data.optJSONObject("potentialClients") ?: JSONObject().put("ok", true).put("clients", JSONArray())
        if (::main.isInitialized) render()
    }

    private fun updatePotentialClientStatus(id: String, status: String, note: String) {
        updateFirebasePotentialClient(id, "Status klienta zapisany LIVE") { client ->
            client.put("status", status)
            if (note.isNotBlank()) {
                val existing = client.optString("notatka")
                client.put("notatka", listOf(existing, "$loggedUserName: $note").filter { it.isNotBlank() }.joinToString("\n"))
            }
        }
    }

    private fun updateFirebasePotentialClient(id: String, message: String, change: (JSONObject) -> Unit) {
        val ref = FirebaseFirestore.getInstance().collection("salesapp_potential_clients").document(id)
        ref.get().addOnSuccessListener { snapshot ->
            val client = snapshot.getString("dataJson")?.let { JSONObject(it) }
                ?: JSONObject(snapshot.data ?: emptyMap<String, Any>())
            change(client)
            ref.set(mapOf("dataJson" to client.toString(), "updatedAt" to FieldValue.serverTimestamp()),
                com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
                .addOnFailureListener { Toast.makeText(this, "Błąd zapisu klienta: ${it.message}", Toast.LENGTH_LONG).show() }
        }.addOnFailureListener { Toast.makeText(this, "Błąd klienta Firebase: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun normalizePersonName(value: String): String {
        return value.trim().uppercase()
            .replace("Ą", "A")
            .replace("Ć", "C")
            .replace("Ę", "E")
            .replace("Ł", "L")
            .replace("Ń", "N")
            .replace("Ó", "O")
            .replace("Ś", "S")
            .replace("Ż", "Z")
            .replace("Ź", "Z")
    }

    private fun normalizeSearchText(value: String): String {
        return normalizePersonName(value)
            .replace(Regex("[^A-Z0-9@.+ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun salesmanTopic(name: String): String {
        return name.trim().lowercase()
            .replace("ą", "a")
            .replace("ć", "c")
            .replace("ę", "e")
            .replace("ł", "l")
            .replace("ń", "n")
            .replace("ó", "o")
            .replace("ś", "s")
            .replace("ż", "z")
            .replace("ź", "z")
            .replace(Regex("\\s+"), "_")
    }
    private fun subscribeUserTopics() {
        // 1.36 / PROD:
        // Stare wersje zapisywały WSZYSTKIE telefony do wspólnych topiców testowych.
        // Jeżeli backend wysłał reklamację na topic, Android wyświetlał ją wszystkim.
        //
        // Produkcyjne SalesAPP korzysta już z salesapp_devices i tokenów FCM
        // przypisanych do UID / representativeCode, więc globalne topiki wyłączamy.
        val messaging = FirebaseMessaging.getInstance()

        val legacyTopics = mutableSetOf(
            "salesapp",
            "salesapp_test",
            "salesapp_stats_test",
            "salesapp_contests_test",
        )

        if (representativeCode.isNotBlank()) {
            legacyTopics += "salesapp_test_" + salesmanTopic(representativeCode)
        }

        legacyTopics.forEach { topic ->
            messaging.unsubscribeFromTopic(topic)
                .addOnSuccessListener {
                    android.util.Log.d("FCM_TOPIC", "Usunięto stary topic: $topic")
                }
                .addOnFailureListener {
                    android.util.Log.w("FCM_TOPIC", "Nie udało się usunąć topicu $topic", it)
                }
        }
    }


    /**
     * Weryfikuje aktywną sesję Firebase Authentication e-mail/hasło.
     * Nie tworzymy już anonimowych użytkowników.
     */
    private fun ensureFirebaseAuth(onReady: (() -> Unit)? = null) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            firebaseAuthReady = false
            firebaseAuthLoading = false
            firebaseAuthError = "Sesja Firebase wygasła. Zaloguj się ponownie."
            if (::main.isInitialized && currentScreen == "reklamacje") render()
            return
        }

        firebaseAuthLoading = true
        firebaseAuthError = ""
        user.getIdToken(false)
            .addOnSuccessListener { token ->
                if (token.token.isNullOrBlank()) {
                    firebaseAuthReady = false
                    firebaseAuthError = "FirebaseAuth: brak tokenu ID."
                } else {
                    firebaseAuthReady = true
                    firebaseAuthError = ""
                    onReady?.invoke()
                }
                firebaseAuthLoading = false
                if (::main.isInitialized && currentScreen == "reklamacje") render()
            }
            .addOnFailureListener { e ->
                firebaseAuthReady = false
                firebaseAuthLoading = false
                firebaseAuthError = "Firebase Authentication: ${e.message}"
                if (::main.isInitialized && currentScreen == "reklamacje") render()
            }
    }


    // ========================= REKLAMACJE =========================

    private fun ensureComplaintsFirebase(onReady: (() -> Unit)? = null) {
        if (complaintsFirebaseReady && complaintsFirestore != null && FirebaseAuth.getInstance().currentUser != null) {
            onReady?.invoke()
            return
        }
        if (complaintsFirebaseLoading) return

        complaintsFirebaseLoading = true
        complaintsFirebaseError = ""

        ensureFirebaseAuth {
            try {
                complaintsFirestore = FirebaseFirestore.getInstance()
                complaintsFirebaseReady = true
                complaintsFirebaseLoading = false
                complaintsFirebaseError = ""
                onReady?.invoke()
                if (::main.isInitialized && currentScreen == "reklamacje") render()
            } catch (e: Exception) {
                complaintsFirebaseReady = false
                complaintsFirebaseLoading = false
                complaintsFirebaseError = "Firestore SalesAPP: ${e.message}"
                if (::main.isInitialized && currentScreen == "reklamacje") render()
            }
        }

        android.os.Handler(mainLooper).postDelayed({
            if (!firebaseAuthReady && firebaseAuthError.isNotBlank()) {
                complaintsFirebaseReady = false
                complaintsFirebaseLoading = false
                complaintsFirebaseError = firebaseAuthError +
                    "\nW Firebase włącz: Authentication → Sign-in method → Email/Password."
                if (::main.isInitialized && currentScreen == "reklamacje") render()
            }
        }, 1200)
    }

    private fun renderComplaintsScreen() {
        section("⚠️ Reklamacje")

        if (!hasPermission("complaints.read")) {
            card(padding = 14) {
                addView(TextView(context).apply {
                    text = "Brak uprawnienia do modułu Reklamacje."
                    textSize = 14f
                    setTextColor(red)
                })
            }
            return
        }

        if (!complaintsFirebaseReady) {
            card(padding = 14) {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = if (complaintsFirebaseLoading) "Łączenie z Firebase reklamacji..." else
                        if (complaintsFirebaseError.isNotBlank()) complaintsFirebaseError else "Uruchamianie modułu reklamacji..."
                    textSize = 14f
                    setTextColor(if (complaintsFirebaseError.isNotBlank()) red else muted)
                })
                if (!complaintsFirebaseLoading) {
                    addView(Button(context).apply {
                        text = "Połącz ponownie"
                        setOnClickListener { ensureComplaintsFirebase() }
                    })
                }
            }
            ensureComplaintsFirebase()
            return
        }

        card(padding = 10) {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "➕ NOWA"
                isEnabled = hasPermission("complaints.create")
                setTextColor(Color.WHITE)
                setBackgroundColor(if (complaintMode == "new") red else blue)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0,0,dp(4),0) }
                setOnClickListener {
                    complaintMode = "new"
                    complaintSelectedDocument = null
                    complaintSelectedCustomer = null
                    complaintSelectedProduct = null
                    complaintCustomerResults = emptyList()
                    complaintProductResults = emptyList()
                    complaintDocumentResults = emptyList()
                    complaintRealtimeListener?.remove()
                    complaintRealtimeListener = null
                    render()
                }
            })
            addView(Button(context).apply {
                text = "📋 MOJE"
                setTextColor(Color.WHITE)
                setBackgroundColor(if (complaintMode == "mine") red else blue)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4),0,0,0) }
                setOnClickListener { complaintMode = "mine"; loadMyComplaints() }
            })
        }

        if (complaintMode == "mine") {
            renderMyComplaints()
            return
        }

        // KROK 3: wybrany dokument -> od razu formularz dla wybranego produktu.
        val selectedDocument = complaintSelectedDocument
        val selectedProduct = complaintSelectedProduct
        if (selectedDocument != null && selectedProduct != null) {
            val items = selectedDocument["items"] as? List<*> ?: emptyList<Any>()
            val wantedId = complaintProductOptimaId(selectedProduct)?.toString()
            val wantedCode = selectedProduct["code"]?.toString().orEmpty()
            val item = items.mapNotNull { it as? Map<*, *> }.firstOrNull {
                val pid = it["product_id"]?.toString()
                val code = it["code"]?.toString().orEmpty()
                (wantedId != null && pid == wantedId) || code.equals(wantedCode, ignoreCase = true)
            }
            if (item != null) {
                showComplaintForm(selectedDocument, item)
                complaintSelectedDocument = null
                return
            }
        }

        val selectedCustomer = complaintSelectedCustomer
        val product = complaintSelectedProduct

        if (selectedCustomer == null) {
            card(padding = 14) {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "1. Znajdź kontrahenta"
                    textSize = 17f; setTextColor(blue); typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(context).apply {
                    text = "Szukaj po nazwie, kodzie lub NIP."
                    textSize = 13f; setTextColor(muted); setPadding(0, dp(4), 0, dp(8))
                })
                val input = EditText(context).apply {
                    hint = "np. MAT-BUD, K123, 944..."
                    setText(complaintSearchText)
                }
                addView(input)
                addView(Button(context).apply {
                    text = "🔎 SZUKAJ KONTRAHENTA"
                    setTextColor(Color.WHITE); setBackgroundColor(blue)
                    setOnClickListener {
                        complaintSearchText = input.text.toString().trim()
                        searchComplaintCustomers(complaintSearchText)
                    }
                })
            }
            complaintCustomerResults.forEach { row ->
                card(padding = 12) {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = row["name"]?.toString().orEmpty()
                        textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
                    })
                    addView(TextView(context).apply {
                        text = "Kod: ${row["code"] ?: ""} • NIP: ${row["nip"] ?: ""}"
                        textSize = 12f; setTextColor(muted)
                    })
                    setOnClickListener {
                        complaintSelectedCustomer = row
                        complaintProductResults = emptyList()
                        complaintSearchText = ""
                        render()
                    }
                }
            }
            return
        }

        if (product == null) {
            card(padding = 12) {
                orientation = LinearLayout.VERTICAL
                addView(Button(context).apply {
                    text = "← Zmień kontrahenta"
                    setOnClickListener {
                        complaintSelectedCustomer = null
                        complaintProductResults = emptyList()
                        complaintDocumentResults = emptyList()
                        render()
                    }
                })
                addView(TextView(context).apply {
                    text = selectedCustomer["name"]?.toString().orEmpty()
                    textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
                })
                addView(TextView(context).apply {
                    text = "2. Znajdź produkt kupowany przez tego kontrahenta"
                    textSize = 13f; setTextColor(muted); setPadding(0, dp(8), 0, dp(4))
                })
                val input = EditText(context).apply { hint = "kod lub fragment nazwy produktu" }
                addView(input)
                addView(Button(context).apply {
                    text = "🔎 SZUKAJ PRODUKTU"
                    setTextColor(Color.WHITE); setBackgroundColor(blue)
                    setOnClickListener { searchComplaintProducts(input.text.toString().trim()) }
                })
            }
            complaintProductResults.forEach { row ->
                card(padding = 12) {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = row["code"]?.toString().orEmpty()
                        textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
                    })
                    addView(TextView(context).apply {
                        text = row["name"]?.toString().orEmpty(); textSize = 13f; setTextColor(dark)
                    })
                    setOnClickListener {
                        complaintSelectedProduct = row
                        complaintDocumentResults = emptyList()
                        loadComplaintDocumentsForProduct()
                    }
                }
            }
            return
        }

        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply {
                text = "← Zmień produkt"
                setOnClickListener {
                    complaintSelectedProduct = null
                    complaintDocumentResults = emptyList()
                    render()
                }
            })
            addView(TextView(context).apply {
                text = "${selectedCustomer["name"] ?: ""}\n${product["code"] ?: ""} — ${product["name"] ?: ""}"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
            })
            addView(TextView(context).apply {
                text = "3. Wybierz dokument, na którym kupiono ten produkt"
                textSize = 13f; setTextColor(muted); setPadding(0, dp(8), 0, 0)
            })
        }

        if (complaintDocumentResults.isEmpty()) {
            card { addView(TextView(context).apply { text = "Brak dokumentów sprzedaży z tym produktem."; setTextColor(muted) }) }
        }
        complaintDocumentResults.forEach { row ->
            val items = row["items"] as? List<*> ?: emptyList<Any>()
            val wantedId = complaintProductOptimaId(product)?.toString()
            val wantedCode = product["code"]?.toString().orEmpty()
            val item = items.mapNotNull { it as? Map<*, *> }.firstOrNull {
                (wantedId != null && it["product_id"]?.toString() == wantedId) ||
                    it["code"]?.toString().orEmpty().equals(wantedCode, ignoreCase = true)
            }
            card(padding = 12) {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = row["number"]?.toString().orEmpty()
                    textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
                })
                addView(TextView(context).apply {
                    text = "${row["date"] ?: ""} • kupiono: ${item?.get("quantity") ?: ""} ${item?.get("unit") ?: ""}"
                    textSize = 12f; setTextColor(muted)
                })
                setOnClickListener {
                    complaintSelectedDocument = row
                    if (item != null) showComplaintForm(row, item)
                }
            }
        }

    }

    private fun firestoreRows(snap: com.google.firebase.firestore.QuerySnapshot): List<Map<String, Any?>> =
        snap.documents.mapNotNull { d -> d.data?.toMutableMap()?.apply { put("_id", d.id) } }

    private fun searchComplaintCustomers(text: String) {
        val raw = text.trim()
        if (raw.length < 2) {
            Toast.makeText(this, "Wpisz co najmniej 2 znaki", Toast.LENGTH_SHORT).show()
            return
        }

        val db = complaintsFirestore ?: return
        val q = raw.uppercase(Locale.getDefault())
        val digits = raw.filter { it.isDigit() }
        complaintCustomerResults = emptyList()

        val results = linkedMapOf<String, Map<String, Any?>>()
        val errors = mutableListOf<String>()
        var pending = 2 + if (digits.length >= 3) 1 else 0

        fun customerKey(row: Map<String, Any?>): String {
            return row["_id"]?.toString()
                ?: row["optima_id"]?.toString()
                ?: row["code"]?.toString()
                ?: row["name"]?.toString()
                ?: row.hashCode().toString()
        }

        fun finishIndexSearch() {
            if (results.isNotEmpty()) {
                complaintCustomerResults = results.values.take(40)
                render()
                return
            }

            // Awaryjne wyszukiwanie bez indeksu. Czytamy tylko rekordy
            // pasujące do pól klienta w sales_documents, a nie 500 ostatnich dokumentów.
            searchComplaintCustomersFallback(raw, q, digits, errors)
        }

        fun done() {
            pending--
            if (pending <= 0) finishIndexSearch()
        }

        fun queryPrefix(field: String, value: String) {
            db.collection("sales_search_customers")
                .orderBy(field)
                .startAt(value)
                .endAt(value + "\uf8ff")
                .limit(25)
                .get()
                .addOnSuccessListener { snap ->
                    firestoreRows(snap).forEach { row ->
                        results[customerKey(row)] = row
                    }
                    done()
                }
                .addOnFailureListener { e ->
                    errors.add("$field: ${e.message}")
                    done()
                }
        }

        queryPrefix("name_search", q)
        queryPrefix("code_search", q)
        if (digits.length >= 3) queryPrefix("nip_search", digits)
    }

    private fun searchComplaintCustomersFallback(
        raw: String,
        q: String,
        digits: String,
        indexErrors: List<String>
    ) {
        val db = complaintsFirestore ?: return
        val results = linkedMapOf<String, Map<String, Any?>>()
        val errors = mutableListOf<String>()

        /*
         * sales_documents w starszej synchronizacji ma klienta głównie jako:
         * customer.id / customer.code / customer.name / customer.nip
         *
         * Nowe pola *_search są tylko optymalizacją. Nie wolno od nich
         * uzależniać wyszukiwania kontrahenta.
         */
        val nameVariants = linkedSetOf(
            raw.trim(),
            raw.trim().uppercase(Locale.getDefault()),
            raw.trim().lowercase(Locale.getDefault())
                .replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault())
                    else it.toString()
                }
        ).filter { it.length >= 2 }

        var pending = nameVariants.size + 1 + if (digits.length >= 3) 2 else 0

        fun customerKey(row: Map<String, Any?>): String {
            val customer = row["customer"] as? Map<*, *>
            return customer?.get("id")?.toString()
                ?: row["customer_id"]?.toString()
                ?: customer?.get("code")?.toString()
                ?: customer?.get("name")?.toString()
                ?: row["_id"]?.toString()
                ?: row.hashCode().toString()
        }

        fun addCustomerFromSalesDoc(row: Map<String, Any?>) {
            val customer = row["customer"] as? Map<*, *> ?: return
            val cid = customer["id"] ?: row["customer_id"] ?: return
            val key = "optima_${cid}"

            results[key] = hashMapOf(
                "_id" to key,
                "optima_id" to cid,
                "code" to (customer["code"] ?: ""),
                "name" to (customer["name"] ?: ""),
                "nip" to (customer["nip"] ?: "")
            )
        }

        fun done() {
            pending--
            if (pending > 0) return

            complaintCustomerResults = results.values
                .sortedBy { it["name"]?.toString().orEmpty() }
                .take(50)

            if (complaintCustomerResults.isEmpty()) {
                val details = (indexErrors + errors)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(2)
                    .joinToString("\n")

                val msg = if (details.isNotBlank()) {
                    "Nie znaleziono kontrahenta: $raw\n\nFirestore:\n$details"
                } else {
                    "Nie znaleziono kontrahenta: $raw"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            render()
        }

        fun prefixNested(field: String, value: String) {
            db.collection("sales_documents")
                .orderBy(field)
                .startAt(value)
                .endAt(value + "\uf8ff")
                .limit(40)
                .get()
                .addOnSuccessListener { snap ->
                    firestoreRows(snap).forEach { addCustomerFromSalesDoc(it) }
                    done()
                }
                .addOnFailureListener { e ->
                    errors.add("$field: ${e.message}")
                    done()
                }
        }

        // Nazwa klienta — bezpośrednio po danych, które już są w dokumentach.
        nameVariants.forEach { prefix ->
            prefixNested("customer.name", prefix)
        }

        // Kod klienta.
        prefixNested("customer.code", raw.trim())

        // NIP: próbujemy wersję wpisaną i samymi cyframi.
        if (digits.length >= 3) {
            prefixNested("customer.nip", raw.trim())
            if (digits != raw.trim()) {
                prefixNested("customer.nip", digits)
            } else {
                // utrzymujemy poprawny licznik pending
                done()
            }
        }
    }

    private fun complaintNumericId(row: Map<String, Any?>, vararg fields: String): Any? {
        fields.forEach { field ->
            val value = row[field]
            if (value is Number) return value.toLong()
            val txt = value?.toString()?.trim().orEmpty()
            if (txt.isNotBlank()) {
                txt.toLongOrNull()?.let { return it }
            }
        }

        val docId = row["_id"]?.toString().orEmpty()
        val numeric = docId
            .removePrefix("optima_")
            .removePrefix("customer_")
            .removePrefix("product_")
            .toLongOrNull()

        return numeric
    }

    private fun complaintCustomerOptimaId(customer: Map<String, Any?>): Any? =
        complaintNumericId(
            customer,
            "optima_id",
            "customer_id",
            "id"
        )

    private fun complaintProductOptimaId(product: Map<String, Any?>): Any? =
        complaintNumericId(
            product,
            "product_id",
            "optima_id",
            "id"
        )

    private fun searchComplaintProducts(text: String) {
        val customer = complaintSelectedCustomer ?: return
        val customerDocId = customer["_id"]?.toString().orEmpty()
        if (customerDocId.isBlank()) return

        val raw = text.trim()
        if (raw.length < 2) {
            Toast.makeText(this, "Wpisz co najmniej 2 znaki", Toast.LENGTH_SHORT).show()
            return
        }

        val db = complaintsFirestore ?: return
        val q = raw.uppercase(Locale.getDefault())
        complaintProductResults = emptyList()

        val results = linkedMapOf<String, Map<String, Any?>>()
        val errors = mutableListOf<String>()
        var pending = 2

        fun done() {
            pending--
            if (pending > 0) return

            if (results.isNotEmpty()) {
                complaintProductResults = results.values.take(60)
                render()
            } else {
                searchComplaintProductsFallback(customer, q, errors)
            }
        }

        fun queryPrefix(field: String) {
            db.collection("sales_search_customers")
                .document(customerDocId)
                .collection("products")
                .orderBy(field)
                .startAt(q)
                .endAt(q + "\uf8ff")
                .limit(40)
                .get()
                .addOnSuccessListener { snap ->
                    firestoreRows(snap).forEach { row ->
                        results[row["_id"].toString()] = row
                    }
                    done()
                }
                .addOnFailureListener { e ->
                    errors.add("$field: ${e.message}")
                    done()
                }
        }

        queryPrefix("code_search")
        queryPrefix("name_search")
    }

    private fun searchComplaintProductsFallback(
        customer: Map<String, Any?>,
        q: String,
        indexErrors: List<String>
    ) {
        val db = complaintsFirestore ?: return
        val cid: Any = complaintCustomerOptimaId(customer) ?: run {
            Toast.makeText(
                this,
                "Brak ID kontrahenta — odśwież dane sprzedaży.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        fun consume(snap: com.google.firebase.firestore.QuerySnapshot) {
            val products = linkedMapOf<String, Map<String, Any?>>()

            snap.documents.forEach { doc ->
                val items = doc.get("items") as? List<*> ?: return@forEach
                items.mapNotNull { it as? Map<*, *> }.forEach { item ->
                    val codeValue = item["code"]?.toString().orEmpty()
                    val nameValue = item["name"]?.toString().orEmpty()
                    val eanValue = item["ean"]?.toString().orEmpty()

                    val matches =
                        codeValue.uppercase(Locale.getDefault()).contains(q) ||
                        nameValue.uppercase(Locale.getDefault()).contains(q) ||
                        eanValue.uppercase(Locale.getDefault()).contains(q)

                    if (matches) {
                        val pid = item["product_id"] ?: return@forEach
                        val key = "optima_${pid}"
                        products[key] = hashMapOf(
                            "_id" to key,
                            "product_id" to pid,
                            "code" to codeValue,
                            "name" to nameValue,
                            "ean" to eanValue
                        )
                    }
                }
            }

            complaintProductResults = products.values.take(60)
            if (complaintProductResults.isEmpty()) {
                val detail = indexErrors.filter { it.isNotBlank() }.take(1).joinToString()
                Toast.makeText(
                    this,
                    if (detail.isBlank()) "Brak produktu u tego kontrahenta"
                    else "Brak produktu. Indeks: $detail",
                    Toast.LENGTH_LONG
                ).show()
            }
            render()
        }

        db.collection("sales_documents")
            .whereEqualTo("customer_id", cid)
            .limit(1000)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) {
                    consume(snap)
                } else {
                    // Zgodność ze starszym schematem.
                    db.collection("sales_documents")
                        .whereEqualTo("customer.id", cid)
                        .limit(1000)
                        .get()
                        .addOnSuccessListener { consume(it) }
                        .addOnFailureListener {
                            Toast.makeText(
                                this,
                                "Błąd produktów: ${it.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .addOnFailureListener {
                // Zgodność ze starszym schematem.
                db.collection("sales_documents")
                    .whereEqualTo("customer.id", cid)
                    .limit(1000)
                    .get()
                    .addOnSuccessListener { consume(it) }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            this,
                            "Błąd produktów: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
    }

    private fun loadComplaintDocumentsForProduct() {
        val customer = complaintSelectedCustomer ?: return
        val product = complaintSelectedProduct ?: return

        val cidAny = complaintCustomerOptimaId(customer)
        val pidAny = complaintProductOptimaId(product)

        val customerCode = customer["code"]?.toString().orEmpty().trim()
        val customerNip = customer["nip"]?.toString().orEmpty()
            .filter { it.isDigit() }
        val productCode = product["code"]?.toString().orEmpty()
            .trim()
            .uppercase(Locale.getDefault())

        if (pidAny == null && productCode.isBlank()) {
            Toast.makeText(
                this,
                "Brak ID i kodu produktu.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val db = complaintsFirestore ?: return
        complaintDocumentResults = emptyList()

        fun customerMatches(row: Map<String, Any?>): Boolean {
            val nested = row["customer"] as? Map<*, *>
            val rowId = (
                row["customer_id"]
                    ?: nested?.get("id")
            )?.toString().orEmpty()
            val rowCode = nested?.get("code")?.toString().orEmpty()
            val rowNip = nested?.get("nip")?.toString().orEmpty()
                .filter { it.isDigit() }

            return when {
                cidAny != null && rowId == cidAny.toString() -> true
                customerCode.isNotBlank() &&
                    rowCode.equals(customerCode, ignoreCase = true) -> true
                customerNip.isNotBlank() &&
                    rowNip == customerNip -> true
                else -> false
            }
        }

        fun productMatches(row: Map<String, Any?>): Boolean {
            val items = row["items"] as? List<*> ?: emptyList<Any>()
            return items.mapNotNull { it as? Map<*, *> }.any { item ->
                val itemPid = item["product_id"]?.toString()
                val itemCode = item["code"]?.toString().orEmpty()
                    .trim()
                    .uppercase(Locale.getDefault())

                (pidAny != null && itemPid == pidAny.toString()) ||
                    (productCode.isNotBlank() && itemCode == productCode)
            }
        }

        fun accept(rows: List<Map<String, Any?>>): Boolean {
            val found = rows
                .filter { customerMatches(it) && productMatches(it) }
                .distinctBy {
                    it["optima_id"]?.toString()
                        ?: it["_id"]?.toString().orEmpty()
                }
                .sortedByDescending {
                    it["date"]?.toString().orEmpty()
                }
                .take(500)

            if (found.isNotEmpty()) {
                complaintDocumentResults = found
                render()
                return true
            }
            return false
        }

        fun finalCustomerFallback() {
            if (cidAny == null) {
                Toast.makeText(
                    this,
                    "Nie znaleziono FA/FS/PA dla tego produktu i klienta.",
                    Toast.LENGTH_LONG
                ).show()
                render()
                return
            }

            db.collection("sales_documents")
                .whereEqualTo("customer_id", cidAny)
                .limit(1000)
                .get()
                .addOnSuccessListener { snap ->
                    if (!accept(firestoreRows(snap))) {
                        db.collection("sales_documents")
                            .whereEqualTo("customer.id", cidAny)
                            .limit(1000)
                            .get()
                            .addOnSuccessListener { oldSnap ->
                                if (!accept(firestoreRows(oldSnap))) {
                                    Toast.makeText(
                                        this,
                                        "Produkt jest znaleziony, ale nie ma powiązanego FA/FS/PA w zsynchronizowanych danych.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    render()
                                }
                            }
                            .addOnFailureListener {
                                render()
                            }
                    }
                }
                .addOnFailureListener {
                    render()
                }
        }

        fun byProductCode() {
            if (productCode.isBlank()) {
                finalCustomerFallback()
                return
            }

            db.collection("sales_documents")
                .whereArrayContains("product_codes", productCode)
                .limit(1000)
                .get()
                .addOnSuccessListener { snap ->
                    if (!accept(firestoreRows(snap))) {
                        finalCustomerFallback()
                    }
                }
                .addOnFailureListener {
                    finalCustomerFallback()
                }
        }

        fun byProductId() {
            if (pidAny == null) {
                byProductCode()
                return
            }

            db.collection("sales_documents")
                .whereArrayContains("product_ids", pidAny)
                .limit(1000)
                .get()
                .addOnSuccessListener { snap ->
                    if (!accept(firestoreRows(snap))) {
                        byProductCode()
                    }
                }
                .addOnFailureListener {
                    byProductCode()
                }
        }

        // 1. Najszybszy dokładny klucz klient|produkt.
        if (cidAny != null && pidAny != null) {
            val exactKey = "${cidAny}|${pidAny}"
            db.collection("sales_documents")
                .whereArrayContains("customer_product_keys", exactKey)
                .limit(500)
                .get()
                .addOnSuccessListener { snap ->
                    if (!accept(firestoreRows(snap))) {
                        byProductId()
                    }
                }
                .addOnFailureListener {
                    byProductId()
                }
        } else {
            byProductId()
        }
    }

    private fun renderComplaintDocumentItems(doc: Map<String, Any?>) {
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply {
                text = "← Wróć do wyników"
                setOnClickListener { complaintSelectedDocument = null; render() }
            })
            val customer = doc["customer"] as? Map<*, *>
            addView(TextView(context).apply {
                text = doc["number"]?.toString().orEmpty()
                textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(blue)
            })
            addView(TextView(context).apply { text = customer?.get("name")?.toString().orEmpty(); setTextColor(dark) })
            addView(TextView(context).apply { text = "Wybierz reklamowany produkt"; setTextColor(muted); setPadding(0,dp(8),0,0) })
        }

        val items = doc["items"] as? List<*> ?: emptyList<Any>()
        items.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            card(padding = 12) {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = item["code"]?.toString().orEmpty(); textSize=15f; typeface=Typeface.DEFAULT_BOLD; setTextColor(blue) })
                addView(TextView(context).apply { text = item["name"]?.toString().orEmpty(); textSize=13f; setTextColor(dark) })
                addView(TextView(context).apply {
                    text = "Sprzedano: ${item["quantity"] ?: 0} ${item["unit"] ?: ""}"
                    textSize=12f; setTextColor(muted)
                })
                setOnClickListener { showComplaintForm(doc, item) }
            }
        }
    }

    private fun showComplaintForm(doc: Map<String, Any?>, item: Map<*, *>) {
        val sold = (item["quantity"] as? Number)?.toDouble() ?: 0.0

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }

        container.addView(TextView(this).apply {
            text = "${item["code"] ?: ""} — ${item["name"] ?: ""}\nSprzedano: $sold ${item["unit"] ?: ""}"
            setTextColor(dark)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })

        val qty = EditText(this).apply {
            hint = "Ilość reklamowana"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val reasonLabel = TextView(this).apply {
            text = "Powód reklamacji"
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(4))
        }

        val reason = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, complaintReasons)
        }

        val desc = EditText(this).apply {
            hint = "Opis zgłoszenia"
            minLines = 3
            gravity = Gravity.TOP
        }

        val keyInfo = EditText(this).apply {
            hint = "Kluczowe informacje dla biura — np. oczekiwanie klienta, pilność, ustalenia (wymagane)"
            minLines = 3
            gravity = Gravity.TOP
        }

        val brought = CheckBox(this).apply {
            text = "Towar dostarczony na magazyn"
            setTextColor(dark)
            setPadding(0, dp(8), 0, dp(6))
        }

        container.addView(qty)
        container.addView(reasonLabel)
        container.addView(reason)
        container.addView(desc)
        container.addView(keyInfo)
        container.addView(brought)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nowa reklamacja")
            .setView(container)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Wyślij", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val qv = qty.text.toString().replace(',', '.').toDoubleOrNull()
                if (qv == null || qv <= 0) {
                    qty.error = "Podaj ilość"
                    return@setOnClickListener
                }
                if (qv > sold) {
                    qty.error = "Maksymalnie $sold"
                    return@setOnClickListener
                }
                if (keyInfo.text.toString().trim().isBlank()) {
                    keyInfo.error = "Wpisz najważniejsze informacje / ustalenia dla biura"
                    return@setOnClickListener
                }

                saveComplaint(
                    doc = doc,
                    item = item,
                    qty = qv,
                    reason = (reason.selectedItem?.toString() ?: "").trim(),
                    description = desc.text.toString().trim(),
                    keyInfo = keyInfo.text.toString().trim(),
                    broughtGoods = brought.isChecked
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }


    private fun complaintEventActor(): HashMap<String, Any?> = hashMapOf(
        "uid" to currentUserUid,
        "name" to loggedUserName,
        "displayName" to currentUserDisplayName,
        "email" to currentUserEmail,
        "representativeCode" to representativeCode,
        "role" to userRole,
        "login" to complaintIdentity()
    )

    private fun complaintEventSnapshot(row: Map<String, Any?>): HashMap<String, Any?> {
        val document = row["document"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val customer = row["customer"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val product = row["product"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val decision = row["decision"] as? Map<*, *> ?: emptyMap<Any, Any>()

        return hashMapOf(
            "complaintNumber" to row["complaintNumber"],
            "status" to row["status"],
            "document" to hashMapOf(
                "id" to document["id"], "optimaId" to document["optimaId"],
                "number" to document["number"], "type" to document["type"], "date" to document["date"]
            ),
            "customer" to hashMapOf(
                "id" to customer["id"], "code" to customer["code"],
                "name" to customer["name"], "nip" to customer["nip"]
            ),
            "product" to hashMapOf(
                "id" to product["id"], "code" to product["code"], "name" to product["name"]
            ),
            "soldQuantity" to row["soldQuantity"],
            "complaintQuantity" to row["complaintQuantity"],
            "unit" to row["unit"],
            "reason" to row["reason"],
            "description" to row["description"],
            "representativeKeyInfo" to row["representativeKeyInfo"],
            "representativeBroughtGoods" to complaintDelivered(row),
            "responsible" to row["responsible"],
            "decision" to hashMapOf(
                "code" to decision["code"], "label" to decision["label"], "text" to decision["text"]
            )
        )
    }

    private fun complaintDelivered(row: Map<String, Any?>): Boolean {
        val warehouse = row["warehouse"] as? Map<*, *>
        return when {
            warehouse?.containsKey("received") == true -> warehouse["received"] == true
            row.containsKey("representativeBroughtGoods") -> row["representativeBroughtGoods"] == true
            else -> row["warehouseReceived"] == true
        }
    }

    private fun complaintEditableSnapshot(row: Map<String, Any?>): HashMap<String, Any?> = hashMapOf(
        "complaintQuantity" to ((row["complaintQuantity"] as? Number)?.toDouble() ?: 0.0),
        "reason" to row["reason"]?.toString().orEmpty(),
        "description" to row["description"]?.toString().orEmpty(),
        "representativeKeyInfo" to row["representativeKeyInfo"]?.toString().orEmpty(),
        "representativeBroughtGoods" to complaintDelivered(row)
    )

    private fun complaintEditableSnapshot(
        qty: Double, reason: String, description: String, keyInfo: String, broughtGoods: Boolean
    ): HashMap<String, Any?> = hashMapOf(
        "complaintQuantity" to qty,
        "reason" to reason,
        "description" to description,
        "representativeKeyInfo" to keyInfo,
        "representativeBroughtGoods" to broughtGoods
    )

    private fun complaintChangeSet(before: Map<String, Any?>, after: Map<String, Any?>): HashMap<String, Any?> {
        val labels = linkedMapOf(
            "complaintQuantity" to "Ilość reklamowana",
            "reason" to "Powód reklamacji",
            "description" to "Opis zgłoszenia",
            "representativeKeyInfo" to "Informacje dla biura",
            "representativeBroughtGoods" to "Towar dostarczony na magazyn"
        )
        val out = hashMapOf<String, Any?>()
        labels.forEach { (key, label) ->
            val oldValue = before[key]
            val newValue = after[key]
            if (oldValue != newValue) {
                out[key] = hashMapOf("label" to label, "before" to oldValue, "after" to newValue)
            }
        }
        return out
    }

    private fun complaintEventEnvelope(
        eventType: String,
        complaintId: String,
        complaintNumber: String,
        complaintSnapshot: Map<String, Any?>,
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
        changes: Map<String, Any?>? = null
    ): HashMap<String, Any?> {
        val event = hashMapOf<String, Any?>(
            "eventType" to eventType,
            "complaintId" to complaintId,
            "complaintNumber" to complaintNumber,
            "source" to "SALESAPP",
            "actor" to complaintEventActor(),
            "complaint" to complaintSnapshot,
            "occurredAt" to FieldValue.serverTimestamp(),
            "emailRequested" to true,
            "emailStatus" to "PENDING",
            "emailAttempts" to 0L,
            "schemaVersion" to 1L
        )
        if (before != null) event["before"] = before
        if (after != null) event["after"] = after
        if (changes != null) event["changes"] = changes
        return event
    }

    private fun saveComplaint(
        doc: Map<String, Any?>,
        item: Map<*, *>,
        qty: Double,
        reason: String,
        description: String,
        keyInfo: String,
        broughtGoods: Boolean
    ) {
        if (!hasPermission("complaints.create")) {
            Toast.makeText(this, "Brak uprawnienia do tworzenia reklamacji", Toast.LENGTH_LONG).show()
            return
        }
        val db = complaintsFirestore ?: return
        val customer = doc["customer"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val year = java.text.SimpleDateFormat("yyyy", Locale.getDefault()).format(java.util.Date())
        val complaintRef = db.collection("complaints").document()
        val eventRef = db.collection("complaint_events").document()
        val counterRef = db.collection("system_counters").document("complaints_$year")

        val payload = hashMapOf<String, Any?>(
            "status" to "NOWA",
            "createdAt" to FieldValue.serverTimestamp(),
            "source" to "SALESAPP",
            "reportedBy" to hashMapOf(
                "name" to loggedUserName, "displayName" to currentUserDisplayName,
                "email" to currentUserEmail, "representativeCode" to representativeCode,
                "role" to userRole, "login" to complaintIdentity(), "uid" to currentUserUid
            ),
            "document" to hashMapOf(
                "id" to doc["_id"], "optimaId" to doc["optima_id"], "number" to doc["number"],
                "type" to doc["document_type"], "date" to doc["date"]
            ),
            "customer" to hashMapOf(
                "id" to customer["id"], "code" to customer["code"], "name" to customer["name"], "nip" to customer["nip"]
            ),
            "product" to hashMapOf(
                "id" to item["product_id"], "code" to item["code"], "name" to item["name"]
            ),
            "soldQuantity" to ((item["quantity"] as? Number)?.toDouble() ?: 0.0),
            "complaintQuantity" to qty,
            "unit" to (item["unit"]?.toString() ?: ""),
            "reason" to reason,
            "description" to description,
            "representativeKeyInfo" to keyInfo,
            "representativeBroughtGoods" to broughtGoods,
            "warehouseReceived" to broughtGoods,
            "warehouse" to hashMapOf<String, Any?>(
                "received" to broughtGoods,
                "note" to "",
                "receivedAt" to if (broughtGoods) FieldValue.serverTimestamp() else null
            ),
            "representativeBroughtAt" to if (broughtGoods) FieldValue.serverTimestamp() else null,
            "decision" to hashMapOf("code" to "PENDING", "label" to "Oczekuje na decyzję", "text" to ""),
            "responsible" to "",
            "notificationVersion" to 0
        )

        db.runTransaction { tx ->
            val snap = tx.get(counterRef)
            val last = if (snap.exists()) snap.getLong("last") ?: 0L else 0L
            val seq = last + 1L
            val nr = "REK/$seq/$year"

            tx.set(counterRef, hashMapOf<String, Any?>(
                "last" to seq, "year" to year.toInt(), "updatedAt" to FieldValue.serverTimestamp()
            ), com.google.firebase.firestore.SetOptions.merge())

            payload["complaintNumber"] = nr
            payload["complaintSequence"] = seq
            payload["complaintYear"] = year.toInt()
            tx.set(complaintRef, payload)

            val eventSnapshot = hashMapOf<String, Any?>(
                "complaintNumber" to nr, "status" to "NOWA",
                "document" to payload["document"], "customer" to payload["customer"], "product" to payload["product"],
                "soldQuantity" to payload["soldQuantity"], "complaintQuantity" to qty, "unit" to payload["unit"],
                "reason" to reason, "description" to description, "representativeKeyInfo" to keyInfo,
                "representativeBroughtGoods" to broughtGoods, "responsible" to "", "decision" to payload["decision"]
            )
            tx.set(eventRef, complaintEventEnvelope(
                eventType = "CREATED",
                complaintId = complaintRef.id,
                complaintNumber = nr,
                complaintSnapshot = eventSnapshot,
                after = complaintEditableSnapshot(qty, reason, description, keyInfo, broughtGoods)
            ))
            nr
        }
            .addOnSuccessListener { nr ->
                Toast.makeText(this, "Reklamacja $nr zapisana", Toast.LENGTH_LONG).show()
                complaintSelectedDocument = null
                complaintSelectedCustomer = null
                complaintSelectedProduct = null
                complaintCustomerResults = emptyList()
                complaintProductResults = emptyList()
                complaintDocumentResults = emptyList()
                complaintSearchResults = emptyList()
                complaintSearchText = ""
                complaintMode = "mine"
                pendingComplaintId = complaintRef.id
                loadMyComplaints()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Błąd zapisu reklamacji: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun loadMyComplaints() {
        complaintMode = "mine"
        val db = complaintsFirestore
        if (db == null) {
            ensureComplaintsFirebase { loadMyComplaints() }
            render()
            return
        }

        complaintRealtimeListener?.remove()
        complaintRealtimeListener = null

        if (loggedUserName.isBlank()) {
            Toast.makeText(this, "Brak identyfikatora użytkownika", Toast.LENGTH_LONG).show()
            return
        }

        // Tylko autor reklamacji widzi jej bieżące statusy i decyzje.
        // Pole name zachowuje zgodność również ze starszymi zgłoszeniami v5.
        complaintRealtimeListener = db.collection("complaints")
            .whereEqualTo("reportedBy.name", loggedUserName)
            .limit(100)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Toast.makeText(this, "Błąd reklamacji: ${error.message}", Toast.LENGTH_LONG).show()
                    return@addSnapshotListener
                }

                val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                snap?.documents?.forEach { d ->
                    val reported = d.get("reportedBy") as? Map<*, *>
                    val existingUid = reported?.get("uid")?.toString().orEmpty()
                    if (currentUid.isNotBlank() && existingUid.isBlank()) {
                        // Jednorazowa migracja starszych reklamacji: użytkownik widzi tylko
                        // rekordy filtrowane po swoim reportedBy.name, więc dopisujemy UID
                        // potrzebny do bezpiecznego odczytu podpisanego dokumentu ze Storage.
                        d.reference.update("reportedBy.uid", currentUid)
                    }
                }

                complaintMyResults = snap?.documents
                    ?.mapNotNull { d -> d.data?.toMutableMap()?.apply { put("_id", d.id) } }
                    ?.sortedWith(compareByDescending<Map<String, Any?>> {
                        (it["createdAt"] as? com.google.firebase.Timestamp)?.seconds ?: 0L
                    }) ?: emptyList()

                if (::main.isInitialized && currentScreen == "reklamacje" && complaintMode == "mine") {
                    render()
                }
            }
    }

    private fun renderMyComplaints() {
        if (complaintRealtimeListener == null) {
            android.os.Handler(mainLooper).post { loadMyComplaints() }
        }

        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "🔎 Szukaj w moich reklamacjach"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(blue)
                setPadding(0, 0, 0, dp(5))
            })

            val input = EditText(context).apply {
                hint = "nr reklamacji, kontrahent, produkt, dokument, status..."
                setText(complaintMineSearchText)
                setSingleLine(true)
            }
            addView(input)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)

                addView(Button(context).apply {
                    text = "SZUKAJ"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(blue)
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(0, 0, dp(4), 0)
                    }
                    setOnClickListener {
                        complaintMineSearchText = input.text.toString().trim()
                        render()
                    }
                })

                addView(Button(context).apply {
                    text = "WYCZYŚĆ"
                    setTextColor(blue)
                    setBackgroundColor(softBlue)
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(4), 0, 0, 0)
                    }
                    setOnClickListener {
                        complaintMineSearchText = ""
                        render()
                    }
                })
            })
        }

        if (complaintMyResults.isEmpty()) {
            card {
                addView(TextView(context).apply {
                    text = "Brak reklamacji zgłoszonych przez: $loggedUserName"
                    setTextColor(muted)
                })
            }
            return
        }

        val query = complaintMineSearchText.trim().lowercase(Locale.getDefault())
        val visibleRows = if (query.isBlank()) complaintMyResults else complaintMyResults.filter { row ->
            val document = row["document"] as? Map<*, *>
            val product = row["product"] as? Map<*, *>
            val customer = row["customer"] as? Map<*, *>
            val decision = row["decision"] as? Map<*, *>
            listOf(
                row["complaintNumber"],
                row["status"],
                row["reason"],
                row["description"],
                row["responsible"],
                document?.get("number"),
                document?.get("type"),
                customer?.get("name"),
                customer?.get("code"),
                customer?.get("nip"),
                product?.get("code"),
                product?.get("name"),
                decision?.get("code"),
                decision?.get("text")
            ).joinToString(" ") { it?.toString().orEmpty() }
                .lowercase(Locale.getDefault())
                .contains(query)
        }

        if (visibleRows.isEmpty()) {
            card {
                addView(TextView(context).apply {
                    text = "Brak wyników dla: \"$complaintMineSearchText\""
                    setTextColor(muted)
                    textSize = 13f
                })
            }
            return
        }

        visibleRows.forEach { row ->
            val document = row["document"] as? Map<*, *>
            val product = row["product"] as? Map<*, *>
            val customer = row["customer"] as? Map<*, *>
            val decision = row["decision"] as? Map<*, *>
            val statusCode = row["status"]?.toString().orEmpty()
            val decisionCode = decision?.get("code")?.toString().orEmpty().ifBlank { "PENDING" }
            val decisionText = decision?.get("text")?.toString().orEmpty()
            val responsible = row["responsible"]?.toString().orEmpty()
            val brought = complaintDelivered(row)
            val isSelected = pendingComplaintId.isNotBlank() && row["_id"]?.toString() == pendingComplaintId

            val customerName = customer?.get("name")?.toString().orEmpty()
            val customerCode = customer?.get("code")?.toString().orEmpty()
            val customerNip = customer?.get("nip")?.toString().orEmpty()

            card(padding = 13) {
                orientation = LinearLayout.VERTICAL
                if (isSelected) setBackgroundColor(Color.rgb(248, 250, 255))

                addView(TextView(context).apply {
                    text = row["complaintNumber"]?.toString() ?: "REKLAMACJA"
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(blue)
                })

                if (customerName.isNotBlank() || customerCode.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = buildString {
                            append("Kontrahent: ")
                            append(customerName.ifBlank { customerCode })
                            if (customerCode.isNotBlank() && !customerName.equals(customerCode, true)) {
                                append("  [").append(customerCode).append("]")
                            }
                            if (customerNip.isNotBlank()) {
                                append("  •  NIP ").append(customerNip)
                            }
                        }
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(dark)
                        setPadding(0, dp(3), 0, dp(2))
                    })
                }

                addView(TextView(context).apply {
                    text = "${document?.get("number") ?: ""} • ${product?.get("code") ?: ""} — ${product?.get("name") ?: ""}"
                    setTextColor(dark)
                    textSize = 13f
                })

                addView(TextView(context).apply {
                    text = "Status: ${complaintStatusLabel(statusCode)}"
                    setTextColor(if (statusCode == "ZAKONCZONA") green else red)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 13f
                    setPadding(0, dp(7), 0, 0)
                })

                addView(TextView(context).apply {
                    text = "Decyzja: ${complaintDecisionLabel(decisionCode)}"
                    setTextColor(blue)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 13f
                })

                if (decisionText.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = decisionText
                        textSize = 12f
                        setTextColor(dark)
                        setPadding(0, dp(3), 0, 0)
                    })
                }

                if (responsible.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = "Prowadzi: $responsible"
                        textSize = 12f
                        setTextColor(muted)
                    })
                }

                addView(TextView(context).apply {
                    text = "Towar przekazany przez przedstawiciela: ${if (brought) "TAK" else "NIE"}"
                    textSize = 11f
                    setTextColor(muted)
                    setPadding(0, dp(4), 0, 0)
                })

                addView(TextView(context).apply {
                    text = row["reason"]?.toString().orEmpty()
                    textSize = 12f
                    setTextColor(muted)
                    setPadding(0, dp(4), 0, 0)
                })

                val description = row["description"]?.toString().orEmpty()
                if (description.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = description
                        textSize = 12f
                        setTextColor(dark)
                        setPadding(0, dp(3), 0, 0)
                    })
                }

                val actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                }

                val signedDoc = row["signedDocument"] as? Map<*, *>
                val signedPath = signedDoc?.get("storagePath")?.toString().orEmpty()
                if (signedPath.isNotBlank()) {
                    actions.addView(compactComplaintButton("📄 Podgląd dokumentu", blue) {
                        openComplaintSignedDocument(row)
                    })
                }

                if (canEditComplaintInApp(row)) {
                    actions.addView(compactComplaintButton("✏ Edytuj", blue) {
                        showEditComplaintDialog(row)
                    })
                }

                if (canDeleteComplaintInApp(row)) {
                    actions.addView(compactComplaintButton("🗑 Usuń", red) {
                        confirmDeleteComplaint(row)
                    })
                }

                if (actions.childCount > 0) addView(actions)
            }
        }

        pendingOpenComplaints = false
    }

    private fun compactComplaintButton(
        label: String,
        backgroundColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 10.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(backgroundColor)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
        }
    }

    private fun complaintOwnedByCurrentUser(row: Map<String, Any?>): Boolean {
        val reported = row["reportedBy"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val savedUid = reported["uid"]?.toString().orEmpty()
        val savedRep = reported["representativeCode"]?.toString().orEmpty()
        val savedName = reported["name"]?.toString().orEmpty()
        val savedLogin = reported["login"]?.toString().orEmpty()

        if (currentUserUid.isNotBlank() && savedUid == currentUserUid) return true
        if (representativeCode.isNotBlank() && savedRep.equals(representativeCode, ignoreCase = true)) return true
        if (loggedUserName.isNotBlank() && savedName.equals(loggedUserName, ignoreCase = true)) return true
        if (savedLogin.isNotBlank() && savedLogin.equals(complaintIdentity(), ignoreCase = true)) return true
        return false
    }

    private fun canEditComplaintInApp(row: Map<String, Any?>): Boolean {
        if (isAdmin() || isBiuro()) return true
        return complaintOwnedByCurrentUser(row) && hasPermission("complaints.edit", true)
    }

    private fun canDeleteComplaintInApp(row: Map<String, Any?>): Boolean {
        if (isAdmin() || isBiuro()) return true
        return complaintOwnedByCurrentUser(row) && hasPermission("complaints.delete", true)
    }

    private fun showEditComplaintDialog(row: Map<String, Any?>) {
        if (!canEditComplaintInApp(row)) {
            Toast.makeText(this, "Brak uprawnienia do edycji reklamacji", Toast.LENGTH_LONG).show()
            return
        }

        val sold = (row["soldQuantity"] as? Number)?.toDouble() ?: 0.0
        val currentQty = (row["complaintQuantity"] as? Number)?.toDouble() ?: 0.0
        val currentReason = row["reason"]?.toString().orEmpty()
        val currentDescription = row["description"]?.toString().orEmpty()
        val currentKeyInfo = row["representativeKeyInfo"]?.toString().orEmpty()
        val currentBrought = complaintDelivered(row)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }

        val qty = EditText(this).apply {
            hint = "Ilość reklamowana"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (currentQty == 0.0) "" else currentQty.toString().removeSuffix(".0"))
        }

        val reason = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                complaintReasons
            )
            val index = complaintReasons.indexOfFirst { it.equals(currentReason, ignoreCase = true) }
            if (index >= 0) setSelection(index)
        }

        val desc = EditText(this).apply {
            hint = "Opis zgłoszenia"
            minLines = 3
            gravity = Gravity.TOP
            setText(currentDescription)
        }

        val keyInfo = EditText(this).apply {
            hint = "Kluczowe informacje dla biura (wymagane)"
            minLines = 3
            gravity = Gravity.TOP
            setText(currentKeyInfo)
        }

        val brought = CheckBox(this).apply {
            text = "Towar dostarczony na magazyn"
            setTextColor(dark)
            isChecked = currentBrought
            setPadding(0, dp(8), 0, dp(6))
        }

        container.addView(TextView(this).apply {
            text = row["complaintNumber"]?.toString().orEmpty().ifBlank { "Reklamacja" }
            setTextColor(blue)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        })
        container.addView(qty)
        container.addView(TextView(this).apply {
            text = "Powód reklamacji"
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(4))
        })
        container.addView(reason)
        container.addView(desc)
        container.addView(keyInfo)
        container.addView(brought)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edytuj reklamację")
            .setView(container)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Zapisz", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val qv = qty.text.toString().replace(',', '.').toDoubleOrNull()
                if (qv == null || qv <= 0) {
                    qty.error = "Podaj ilość"
                    return@setOnClickListener
                }
                if (sold > 0.0 && qv > sold) {
                    qty.error = "Maksymalnie $sold"
                    return@setOnClickListener
                }
                if (keyInfo.text.toString().trim().isBlank()) {
                    keyInfo.error = "Wpisz najważniejsze informacje / ustalenia dla biura"
                    return@setOnClickListener
                }

                updateComplaintFromApp(
                    row = row,
                    qty = qv,
                    reason = reason.selectedItem?.toString().orEmpty().trim(),
                    description = desc.text.toString().trim(),
                    keyInfo = keyInfo.text.toString().trim(),
                    broughtGoods = brought.isChecked
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateComplaintFromApp(
        row: Map<String, Any?>,
        qty: Double,
        reason: String,
        description: String,
        keyInfo: String,
        broughtGoods: Boolean
    ) {
        if (!canEditComplaintInApp(row)) {
            Toast.makeText(this, "Brak uprawnienia do edycji reklamacji", Toast.LENGTH_LONG).show()
            return
        }
        val complaintId = row["_id"]?.toString().orEmpty()
        val db = complaintsFirestore
        if (complaintId.isBlank() || db == null) {
            Toast.makeText(this, "Nie można ustalić reklamacji do edycji", Toast.LENGTH_LONG).show()
            return
        }

        val before = complaintEditableSnapshot(row)
        val after = complaintEditableSnapshot(qty, reason, description, keyInfo, broughtGoods)
        val changes = complaintChangeSet(before, after)
        if (changes.isEmpty()) {
            Toast.makeText(this, "Nie zmieniono żadnych danych", Toast.LENGTH_SHORT).show()
            return
        }

        val updates = hashMapOf<String, Any>(
            "complaintQuantity" to qty,
            "reason" to reason,
            "description" to description,
            "representativeKeyInfo" to keyInfo,
            "representativeBroughtGoods" to broughtGoods,
            "warehouseReceived" to broughtGoods,
            "warehouse.received" to broughtGoods,
            "warehouse.receivedAt" to if (broughtGoods) FieldValue.serverTimestamp() else FieldValue.delete(),
            "representativeBroughtAt" to if (broughtGoods) FieldValue.serverTimestamp() else FieldValue.delete(),
            "lastEditedAt" to FieldValue.serverTimestamp(),
            "lastEditedBy" to hashMapOf(
                "uid" to currentUserUid, "name" to loggedUserName,
                "email" to currentUserEmail, "representativeCode" to representativeCode
            ),
            "editVersion" to FieldValue.increment(1)
        )

        val eventSnapshot = complaintEventSnapshot(row).apply {
            this["complaintQuantity"] = qty
            this["reason"] = reason
            this["description"] = description
            this["representativeKeyInfo"] = keyInfo
            this["representativeBroughtGoods"] = broughtGoods
        }
        val complaintNumber = row["complaintNumber"]?.toString().orEmpty()
        val complaintRef = db.collection("complaints").document(complaintId)
        val eventRef = db.collection("complaint_events").document()
        val event = complaintEventEnvelope(
            eventType = "UPDATED", complaintId = complaintId, complaintNumber = complaintNumber,
            complaintSnapshot = eventSnapshot, before = before, after = after, changes = changes
        )

        db.runBatch { batch ->
            batch.update(complaintRef, updates)
            batch.set(eventRef, event)
        }
            .addOnSuccessListener { Toast.makeText(this, "Reklamacja została zaktualizowana", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { e -> Toast.makeText(this, "Błąd edycji reklamacji: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun confirmDeleteComplaint(row: Map<String, Any?>) {
        if (!canDeleteComplaintInApp(row)) {
            Toast.makeText(this, "Brak uprawnienia do usunięcia reklamacji", Toast.LENGTH_LONG).show()
            return
        }

        val nr = row["complaintNumber"]?.toString().orEmpty().ifBlank { "tę reklamację" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Usunąć reklamację?")
            .setMessage("Czy na pewno usunąć $nr? Tej operacji nie można cofnąć.")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń") { _, _ -> deleteComplaintFromApp(row) }
            .show()
    }

    private fun deleteComplaintFromApp(row: Map<String, Any?>) {
        if (!canDeleteComplaintInApp(row)) return

        val complaintId = row["_id"]?.toString().orEmpty()
        val db = complaintsFirestore
        if (complaintId.isBlank() || db == null) {
            Toast.makeText(this, "Nie można ustalić reklamacji do usunięcia", Toast.LENGTH_LONG).show()
            return
        }

        fun deleteFirestoreRecordWithEvent() {
            val complaintNumber = row["complaintNumber"]?.toString().orEmpty()
            val complaintRef = db.collection("complaints").document(complaintId)
            val eventRef = db.collection("complaint_events").document()
            val event = complaintEventEnvelope(
                eventType = "DELETED",
                complaintId = complaintId,
                complaintNumber = complaintNumber,
                complaintSnapshot = complaintEventSnapshot(row),
                before = complaintEditableSnapshot(row)
            )

            db.runBatch { batch ->
                batch.set(eventRef, event)
                batch.delete(complaintRef)
            }
                .addOnSuccessListener {
                    pendingComplaintId = ""
                    Toast.makeText(this, "Reklamacja została usunięta", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e -> Toast.makeText(this, "Błąd usuwania reklamacji: ${e.message}", Toast.LENGTH_LONG).show() }
        }

        val signed = row["signedDocument"] as? Map<*, *>
        val signedPath = signed?.get("storagePath")?.toString().orEmpty()
        if (signedPath.isBlank()) {
            deleteFirestoreRecordWithEvent()
            return
        }

        FirebaseStorage.getInstance().reference.child(signedPath).delete().addOnCompleteListener {
            deleteFirestoreRecordWithEvent()
        }
    }

    private fun openComplaintSignedDocument(row: Map<String, Any?>) {
        val signed = row["signedDocument"] as? Map<*, *> ?: return
        val path = signed["storagePath"]?.toString().orEmpty()
        if (path.isBlank()) {
            Toast.makeText(this, "Brak dokumentu do wyświetlenia", Toast.LENGTH_SHORT).show()
            return
        }

        ensureFirebaseAuth {
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            val complaintId = row["_id"]?.toString().orEmpty()
            val reported = row["reportedBy"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val savedUid = reported["uid"]?.toString().orEmpty()
            val ownerName = reported["name"]?.toString().orEmpty()
            val ownerLogin = reported["login"]?.toString().orEmpty()

            val sameOwner =
                ownerName.equals(loggedUserName, ignoreCase = true) ||
                (ownerLogin.isNotBlank() &&
                    ownerLogin.equals(complaintIdentity(), ignoreCase = true))

            // Starsze reklamacje mogły mieć UID z dawnej sesji anonymous.
            // Jeżeli kod PRZED / login potwierdza właściciela, migrujemy je na UID konta e-mail.
            if (
                complaintId.isNotBlank() &&
                currentUid.isNotBlank() &&
                savedUid != currentUid &&
                sameOwner
            ) {
                val db = complaintsFirestore
                if (db != null) {
                    db.collection("complaints")
                        .document(complaintId)
                        .update("reportedBy.uid", currentUid)
                        .addOnSuccessListener {
                            downloadComplaintSignedFile(signed, path)
                        }
                        .addOnFailureListener {
                            downloadComplaintSignedFile(signed, path)
                        }
                    return@ensureFirebaseAuth
                }
            }

            downloadComplaintSignedFile(signed, path)
        }
    }

    private fun downloadComplaintSignedFile(
        signed: Map<*, *>,
        path: String
    ) {
        val fileName = signed["fileName"]?.toString().orEmpty()
            .ifBlank { "reklamacja_dokument" }
        val contentType = signed["contentType"]?.toString().orEmpty()
            .ifBlank { "application/octet-stream" }

        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val cacheDirDocs = File(cacheDir, "complaint_docs").apply { mkdirs() }
        val target = File(cacheDirDocs, safeName)

        Toast.makeText(this, "Pobieranie dokumentu...", Toast.LENGTH_SHORT).show()

        FirebaseStorage.getInstance()
            .reference
            .child(path)
            .getFile(target)
            .addOnSuccessListener {
                try {
                    val uri = FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        target
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, contentType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(
                        Intent.createChooser(
                            intent,
                            "Otwórz podpisany dokument"
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Nie można otworzyć dokumentu: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Nie udało się pobrać dokumentu.\n${e.javaClass.simpleName}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun renderAdminSettingsScreen() {
        if (!isAdmin()) {
            section("Brak dostępu")
            return
        }

        section("⚙️ Ustawienia globalne")

        card(padding = 14) {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = "Ustawienia są zapisywane w Firebase LIVE i obowiązują wszystkich użytkowników."
                textSize = 13f
                setTextColor(muted)
                setPadding(0, 0, 0, dp(10))
            })

            val categories = listOf("ZAKŁADKI", "POWIADOMIENIA")

            categories.forEach { category ->
                addView(TextView(context).apply {
                    text = if (category == "ZAKŁADKI") "Widoczność zakładek" else "Powiadomienia"
                    textSize = 16f
                    setTextColor(blue)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(10), 0, dp(4))
                })

                for (i in 0 until settingsConfig.length()) {
                    val row = settingsConfig.optJSONObject(i) ?: continue
                    if (!row.optString("category").equals(category, ignoreCase = true)) continue

                    val key = row.optString("key")
                    val label = row.optString("label", key)
                    if (key.isBlank()) continue

                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, dp(5))

                        addView(TextView(context).apply {
                            text = label
                            textSize = 14f
                            setTextColor(dark)
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f
                            )
                        })

                        addView(Switch(context).apply {
                            isChecked = settingEnabled(key)
                            setOnCheckedChangeListener { _, checked ->
                                saveGlobalSetting(key, checked)
                            }
                        })
                    })
                }
            }
        }
    }

    private fun saveGlobalSetting(key: String, enabled: Boolean) {
        if (!isAdmin() || !firebaseLiveSessionReady()) {
            Toast.makeText(
                this,
                "Ustawienia globalne wymagają aktywnej sesji ADMIN.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setSettingLocal(key, enabled)
        val ref = FirebaseFirestore.getInstance().collection("salesapp_settings").document("global")

        ref.get()
            .addOnSuccessListener { snap ->
                val current = (snap.get("settingsList") as? List<*>)
                    ?.mapNotNull { it as? Map<*, *> }
                    ?.map { row ->
                        val out = mutableMapOf<String, Any?>()
                        row.forEach { (k, v) -> if (k != null) out[k.toString()] = v }
                        out
                    }?.toMutableList() ?: mutableListOf()

                var found = false
                current.forEach { row ->
                    if (row["key"]?.toString().equals(key, ignoreCase = true)) {
                        row["value"] = if (enabled) "TAK" else "NIE"
                        found = true
                    }
                }
                if (!found) {
                    current.add(mutableMapOf(
                        "category" to "ZAKŁADKI",
                        "key" to key,
                        "label" to key,
                        "value" to if (enabled) "TAK" else "NIE"
                    ))
                }

                ref.set(
                    mapOf(
                        "settingsList" to current,
                        "updatedAtIso" to java.time.Instant.now().toString()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                ).addOnSuccessListener {
                    Toast.makeText(this, "Zapisano ustawienie globalne", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener {
                    setSettingLocal(key, !enabled)
                    Toast.makeText(this, "Błąd zapisu ustawienia: ${it.message}", Toast.LENGTH_LONG).show()
                    render()
                }
            }
            .addOnFailureListener {
                setSettingLocal(key, !enabled)
                Toast.makeText(this, "Błąd odczytu ustawień: ${it.message}", Toast.LENGTH_LONG).show()
                render()
            }
    }

    private fun navigationTabs() {
        card(padding = 8) {
            orientation = LinearLayout.VERTICAL

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                if (isBiuro()) {
                    if (settingEnabled("zakladka_rynek") && screenAllowed("rynek")) {
                        addView(tabButton("📣", "Rynek", "rynek"))
                    }
                    if (settingEnabled("zakladka_reklamacje") && screenAllowed("reklamacje")) {
                        addView(tabButton("⚠️", "Rekl.", "reklamacje"))
                    }
                } else {
                    if (settingEnabled("zakladka_podsumowanie") && screenAllowed("summary")) {
                        addView(tabButton("📊", "Podsum.", "summary"))
                    }
                    if (settingEnabled("zakladka_opiekunowie") && screenAllowed("owners")) {
                        addView(tabButton("👥", "Opiek.", "owners"))
                    }
                    if (settingEnabled("zakladka_konkurs") && screenAllowed("konkurs")) {
                        addView(tabButton("🏆", "Konk.", "konkurs"))
                    }
                    if (settingEnabled("zakladka_rynek") && screenAllowed("rynek")) {
                        addView(tabButton("📣", "Rynek", "rynek"))
                    }
                    if (settingEnabled("zakladka_klienci") && screenAllowed("clients")) {
                        addView(tabButton("🎯", "Klienci", "clients"))
                    }
                    if (settingEnabled("zakladka_reklamacje") && screenAllowed("reklamacje")) {
                        addView(tabButton("⚠️", "Rekl.", "reklamacje"))
                    }
                    if (isAdmin() && settingEnabled("zakladka_ustawienia") && screenAllowed("settings")) {
                        addView(tabButton("⚙️", "Ustaw.", "settings"))
                    }
                }
            })
        }

        ensureVisibleScreen()
    }

    private fun ensureVisibleScreen() {
        val visible = when (currentScreen) {
            "summary" -> settingEnabled("zakladka_podsumowanie") && screenAllowed("summary")
            "owners" -> settingEnabled("zakladka_opiekunowie") && screenAllowed("owners")
            "months" -> false
            "konkurs" -> settingEnabled("zakladka_konkurs") && screenAllowed("konkurs")
            "rynek" -> settingEnabled("zakladka_rynek") && screenAllowed("rynek")
            "clients" -> settingEnabled("zakladka_klienci") && screenAllowed("clients")
            "reklamacje" -> settingEnabled("zakladka_reklamacje") && screenAllowed("reklamacje")
            "settings" -> isAdmin() && settingEnabled("zakladka_ustawienia") && screenAllowed("settings")
            else -> false
        }

        if (visible) return

        currentScreen = when {
            settingEnabled("zakladka_podsumowanie") && screenAllowed("summary") && !isBiuro() -> "summary"
            settingEnabled("zakladka_opiekunowie") && screenAllowed("owners") && !isBiuro() -> "owners"
            settingEnabled("zakladka_konkurs") && screenAllowed("konkurs") && !isBiuro() -> "konkurs"
            settingEnabled("zakladka_rynek") && screenAllowed("rynek") -> "rynek"
            settingEnabled("zakladka_klienci") && screenAllowed("clients") && !isBiuro() -> "clients"
            settingEnabled("zakladka_reklamacje") && screenAllowed("reklamacje") -> "reklamacje"
            isAdmin() && settingEnabled("zakladka_ustawienia") && screenAllowed("settings") -> "settings"
            else -> currentScreen
        }
    }

    private fun tabButton(icon: String, label: String, screen: String): Button {
        val active = currentScreen == screen

        return Button(this).apply {
            text = "$icon\n$label"
            textSize = 11f
            setTextColor(if (active) Color.WHITE else blue)
            setBackgroundColor(if (active) blue else softBlue)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                currentScreen = screen
                render()
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(54),
                1f
            ).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }
    }

    private fun appHeader() {
        card(padding = 0) {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            addView(ImageView(context).apply {
                setImageResource(R.drawable.logo_chlemar_salesapp)
                adjustViewBounds = false
                scaleType = ImageView.ScaleType.FIT_XY

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(135)
                )
            })

            addView(TextView(context).apply {
                val updateText = if (::data.isInitialized) {
                    "Ostatnia aktualizacja: ${formatUpdateDate(data.optString("generatedAt", "-"))}"
                } else {
                    "Tryb: $loggedUserName"
                }
                text = updateText
                textSize = 12f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(10))
            })
        }
    }

    private fun selectorCard(months: JSONArray, owners: JSONArray) {
        card(padding = 8) {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = "Filtry"
                textSize = 12f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(4))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, 0, dp(4), 0)
                    }

                    addView(compactLabel("Miesiąc"))
                    addView(compactSpinnerView(
                        items = List(months.length()) { months.getString(it) },
                        selected = selectedMonth,
                        labelMapper = { monthLabel(it) }
                    ) {
                        selectedMonth = it
                        render()
                    })
                })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), 0, 0, 0)
                    }

                    addView(compactLabel("Grupa"))
                    addView(compactSpinnerView(
                        items = listOf("Wszyscy", "Handlowcy", "BIURO"),
                        selected = selectedGroup,
                        labelMapper = { groupLabelCompact(it) }
                    ) {
                        selectedGroup = it
                        selectedOwner = "Wszyscy"
                        render()
                    })
                })
            })

            val ownerNames = mutableListOf("Wszyscy")
            for (i in 0 until owners.length()) {
                ownerNames.add(owners.getJSONObject(i).getString("name"))
            }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)

                addView(compactLabel("Widok"))
                addView(compactSpinnerView(
                    items = ownerNames,
                    selected = selectedOwner,
                    labelMapper = { displayName(it) }
                ) {
                    selectedOwner = it
                    render()
                })
            })
        }
    }

    private fun compactLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 10f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(1))
        }
    }

    private fun compactSpinnerView(
        items: List<String>,
        selected: String,
        labelMapper: (String) -> String,
        onPick: (String) -> Unit
    ): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items.map(labelMapper)
            )

            setSelection(items.indexOf(selected).coerceAtLeast(0))
            minimumHeight = dp(38)
            setPadding(0, 0, 0, 0)

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val value = items[position]
                    if (value != selected) onPick(value)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun groupLabelCompact(group: String): String {
        return when (group.uppercase()) {
            "HANDLOWCY" -> "Handlowcy"
            "BIURO" -> "BIURO"
            else -> "Wszyscy"
        }
    }

    private fun konkursCard(arr: JSONArray, selectedContest: JSONObject? = null) {
        card(padding = 14) {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                val contest = selectedContest ?: data.optJSONObject("activeContest") ?: JSONObject()
                text = contest.optString("displayTitle", "Ranking sprzedaży konkursowej")
                textSize = 16f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            if (arr.length() == 0) {
                addView(TextView(context).apply {
                    text = "Brak wyników konkursowych. Oczekiwanie na synchronizację Optima → Firebase."
                    textSize = 14f
                    setTextColor(muted)
                    setPadding(0, dp(8), 0, dp(8))
                })
                return@card
            }

            val list = mutableListOf<JSONObject>()

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("Kontrahent Grupa", "").trim()

                if (name.isNotEmpty() && !name.lowercase().contains("suma") && !name.lowercase().contains("razem")) {
                    list.add(o)
                }
            }

            val ordered = if (list.any { it.has("Pozycja") }) {
                list.sortedBy { it.optInt("Pozycja", Int.MAX_VALUE) }
            } else {
                list.sortedByDescending { it.optDouble("Sprzedaż Wartość", 0.0) }
            }
            ordered.forEachIndexed { index, item ->
                val code = item.optString("Kontrahent Grupa")
                val metric = selectedContest?.optString("rankingMetric", "SALES_VALUE") ?: "SALES_VALUE"
                val score = item.optDouble("Ranking Wartość", item.optDouble("Sprzedaż Wartość", 0.0))
                val place = item.optInt("Pozycja", index + 1)
                val result = item.optJSONObject("Wynik")
                addView(konkursRowView(place, displayName(code), formatContestMetric(metric, score)).apply {
                    if (code.equals(currentContestCode(), ignoreCase = true)) setBackgroundColor(softBlue)
                    if (result != null) {
                        val details = mutableListOf<String>()
                        if (metric != "SALES_VALUE") details.add("sprzedaż ${money(result.optDouble("value"))}")
                        if (metric != "MARGIN_VALUE" && result.optDouble("margin") != 0.0) {
                            details.add("marża ${money(result.optDouble("margin"))}")
                        }
                        if (result.optDouble("rewardAmount") > 0.0) {
                            details.add("premia ${money(result.optDouble("rewardAmount"))}")
                        }
                        if (!item.optBoolean("Zakwalifikowany", true)) details.add("warunki niespełnione")
                        if (details.isNotEmpty()) addView(TextView(context).apply {
                            text = details.joinToString(" • ")
                            textSize = 11f
                            setTextColor(if (item.optBoolean("Zakwalifikowany", true)) muted else red)
                            setPadding(dp(24), dp(3), 0, 0)
                        })
                    }
                })
            }
        }
    }

    private fun konkursRowView(place: Int, name: String, value: String): LinearLayout {
        val medal = when (place) {
            1 -> "🥇"
            2 -> "🥈"
            3 -> "🥉"
            else -> "$place."
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(TextView(context).apply {
                    text = "$medal $name"
                    textSize = 15f
                    setTextColor(dark)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                })

                addView(TextView(context).apply {
                    text = value
                    textSize = 15f
                    setTextColor(blue)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                })
            })
        }
    }

    private fun kpiGrid(o: JSONObject) {
        kpi("Sprzedaż ogółem", money(o.optDouble("totalSales")), blue)
        kpi("Sprzedaż import", money(o.optDouble("importSales")), green)
        kpi("Sprzedaż kraj", money(o.optDouble("domesticSales")), blue)
        kpi("Udział importu", goalText(o.optDouble("importShare"), threshold("importSharePct", 40.0)), red)
        kpi("Marża import BI", goalText(o.optDouble("importMarginPct"), threshold("importMarginPct", 75.0)), blue)
        kpi("Marża kraj BI", goalText(o.optDouble("domesticMarginPct"), threshold("domesticMarginPct", 35.0)), green)
    }

    private fun compactGoals(o: JSONObject) {
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL
            addView(goalMiniRow("Udział importu", o.optDouble("importShare"), threshold("importSharePct", 40.0)))
            addView(goalMiniRow("Marża import BI", o.optDouble("importMarginPct"), threshold("importMarginPct", 75.0)))
            addView(goalMiniRow("Marża kraj BI", o.optDouble("domesticMarginPct"), threshold("domesticMarginPct", 35.0)))
        }
    }

    private fun goalMiniRow(label: String, value: Double, target: Double): LinearLayout {
        val ok = value >= target
        val progress = ((value / target) * 100).coerceIn(0.0, 100.0).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(8))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    setTextColor(dark)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                })

                addView(TextView(context).apply {
                    text = goalText(value, target)
                    textSize = 13f
                    setTextColor(if (ok) green else red)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                })
            })

            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = progress
            })
        }
    }

    private fun ownerDetailsTable(o: JSONObject) {
        card(padding = 14) {
            orientation = LinearLayout.VERTICAL
            addView(tableRowView("Przedstawiciel", displayName(o.optString("name", selectedOwner))))
            addView(tableRowView("Sprzedaż ogółem", money(o.optDouble("totalSales"))))
            addView(tableRowView("Sprzedaż import", money(o.optDouble("importSales"))))
            addView(tableRowView("Sprzedaż kraj", money(o.optDouble("domesticSales"))))
            addView(tableRowView("Udział importu / cel ${percent(threshold("importSharePct", 40.0))}", goalText(o.optDouble("importShare"), threshold("importSharePct", 40.0))))
            addView(tableRowView("Marża import BI / cel ${percent(threshold("importMarginPct", 75.0))}", goalText(o.optDouble("importMarginPct"), threshold("importMarginPct", 75.0))))
            addView(tableRowView("Marża kraj BI / cel ${percent(threshold("domesticMarginPct", 35.0))}", goalText(o.optDouble("domesticMarginPct"), threshold("domesticMarginPct", 35.0))))
        }
    }

    private fun rankingCard(title: String, arr: JSONArray, key: String, isMoney: Boolean) {
        card(padding = 14) {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }

            list.sortedByDescending { it.optDouble(key) }
                .take(10)
                .forEachIndexed { index, o ->
                    val value = if (isMoney) {
                        money(o.optDouble(key))
                    } else {
                        percent(o.optDouble(key))
                    }

                    addView(
                        rankingRowView(
                            place = index + 1,
                            name = displayName(o.getString("name")),
                            value = value
                        )
                    )
                }
        }
    }

    private fun kpi(label: String, value: String, accent: Int) {
        card(padding = 12) {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(accent)
                typeface = Typeface.DEFAULT_BOLD
            })

            addView(TextView(context).apply {
                text = value
                textSize = 18f
                setTextColor(dark)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun rankingRowView(place: Int, name: String, value: String): LinearLayout {
        val medal = when (place) {
            1 -> "🥇"
            2 -> "🥈"
            3 -> "🥉"
            else -> "$place."
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))

            addView(TextView(context).apply {
                text = "$medal $name"
                textSize = 14f
                setTextColor(dark)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })

            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(dark)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })
        }
    }

    private fun tableRowView(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, dp(9))

            addView(TextView(context).apply {
                text = left
                textSize = 13f
                setTextColor(muted)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })

            addView(TextView(context).apply {
                text = right
                textSize = 13f
                setTextColor(dark)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
            })
        }
    }

    private fun section(text: String) {
        main.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(blue)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        })
    }

    private fun label(text: String) {
        main.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(3))
        })
    }

    private fun spinner(
        items: List<String>,
        selected: String,
        labelMapper: (String) -> String,
        onPick: (String) -> Unit
    ) {
        val spinner = Spinner(this)
        val labels = items.map(labelMapper)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )

        spinner.setSelection(items.indexOf(selected).coerceAtLeast(0))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val value = items[position]
                if (value != selected) {
                    onPick(value)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        main.addView(spinner)
    }

    private fun card(padding: Int = 14, content: LinearLayout.() -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            setBackgroundColor(Color.WHITE)
            content()
        }

        main.addView(box)
        space(8)
    }

    private fun refreshButton() {
        val btn = Button(this).apply {
            text = "Odśwież dane"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                // 1.36: nie przechodzimy już na pełnoekranowe
                // "Ładowanie danych sprzedażowych...".
                // Zostawiamy aktualne dane na ekranie i odświeżamy LIVE w tle.
                Toast.makeText(
                    this@MainActivity,
                    "Odświeżam dane w tle…",
                    Toast.LENGTH_SHORT
                ).show()

                if (isBiuro() && currentScreen == "rynek") {
                    loadRynekData()
                } else {
                    refreshDataInBackground()
                }
            }
        }

        main.addView(btn)
        space(10)
    }

    private fun space(dpValue: Int) {
        main.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(dpValue))
        })
    }

    private fun footer() {
        main.addView(Button(this).apply {
            text = "Sprawdź aktualną wersję • ${BuildConfig.VERSION_NAME}"
            setTextColor(Color.WHITE)
            setBackgroundColor(blue)
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Sprawdzam najnowszą wersję SalesAPP…",
                    Toast.LENGTH_SHORT
                ).show()
                GitHubUpdater.checkForUpdate(this@MainActivity, silent = false)
            }
        })
        space(8)

        main.addView(Button(this).apply {
            text = "Wyloguj / zmień użytkownika"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(90, 90, 90))
            setOnClickListener { logout() }
        })
        space(8)

        main.addView(TextView(this).apply {
            text = "Zrobiono przez CHLE-MAR\nWykonał Przemysław Janus • 2026"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
        })
    }


    private fun groupLabel(group: String): String {
        return when (group.uppercase()) {
            "HANDLOWCY" -> "tylko handlowcy"
            "BIURO" -> "tylko BIURO"
            else -> "wszyscy"
        }
    }

    private fun containsOwner(arr: JSONArray, name: String): Boolean {
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("name") == name) return true
        }
        return false
    }

    private fun filterOwnersByGroup(arr: JSONArray): JSONArray {
        val out = JSONArray()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val rawName = o.optString("name", "")
            val display = normalizePersonName(displayName(rawName))

            val include = when (selectedGroup.uppercase()) {
                "HANDLOWCY" -> isSalesRepresentativeOwner(rawName)
                "BIURO" -> display == "BIURO" || isOfficeName(rawName)
                else -> true
            }

            if (include) {
                out.put(o)
            }
        }

        return out
    }

    private fun totalFromOwners(arr: JSONArray, name: String): JSONObject {
        val total = JSONObject().apply {
            put("name", name)
            put("totalSales", 0.0)
            put("totalMargin", 0.0)
            put("importSales", 0.0)
            put("importMargin", 0.0)
            put("domesticSales", 0.0)
            put("domesticMargin", 0.0)
        }

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            total.put("totalSales", total.optDouble("totalSales") + o.optDouble("totalSales"))
            total.put("totalMargin", total.optDouble("totalMargin") + o.optDouble("totalMargin"))
            total.put("importSales", total.optDouble("importSales") + o.optDouble("importSales"))
            total.put("importMargin", total.optDouble("importMargin") + o.optDouble("importMargin"))
            total.put("domesticSales", total.optDouble("domesticSales") + o.optDouble("domesticSales"))
            total.put("domesticMargin", total.optDouble("domesticMargin") + o.optDouble("domesticMargin"))
        }

        finalizeOwnerMetrics(total)
        return total
    }

    private fun previousMonthKey(): String {
        if (!::data.isInitialized || selectedMonth.isBlank()) return ""

        val months = data.optJSONArray("months") ?: return ""
        for (i in 0 until months.length()) {
            if (months.optString(i) == selectedMonth) {
                return if (i > 0) months.optString(i - 1) else ""
            }
        }

        return ""
    }

    private fun previousOwnersForSelectedMonth(): JSONArray {
        val prevMonth = previousMonthKey()
        if (prevMonth.isBlank()) return JSONArray()

        return try {
            val monthData = data.getJSONObject("monthlyData").getJSONObject(prevMonth)
            val rawOwners = monthData.getJSONArray("ownersArray")
            filterOwnersByGroup(mergeOfficeOwners(rawOwners))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun previousOwnerForSelectedMonth(ownerName: String): JSONObject? {
        val prev = previousOwnersForSelectedMonth()
        val wanted = normalizePersonName(displayName(ownerName))

        for (i in 0 until prev.length()) {
            val o = prev.getJSONObject(i)
            if (normalizePersonName(displayName(o.optString("name"))) == wanted) {
                return o
            }
        }

        return null
    }


    private fun yearAgoMonthKey(): String {
        if (selectedMonth.isBlank()) return ""
        return try {
            val key = java.time.YearMonth.parse(selectedMonth).minusYears(1).toString()
            val months = data.optJSONArray("months") ?: JSONArray()
            if ((0 until months.length()).any { months.optString(it) == key }) key else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun yearAgoOwnersForSelectedMonth(): JSONArray {
        val key = yearAgoMonthKey()
        if (key.isBlank()) return JSONArray()
        return try {
            val monthData = data.getJSONObject("monthlyData").getJSONObject(key)
            val rawOwners = monthData.getJSONArray("ownersArray")
            filterOwnersByGroup(mergeOfficeOwners(rawOwners))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun yearAgoOwnerForSelectedMonth(ownerName: String): JSONObject? {
        val arr = yearAgoOwnersForSelectedMonth()
        val wanted = normalizePersonName(displayName(ownerName))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (normalizePersonName(displayName(o.optString("name"))) == wanted) return o
        }
        return null
    }


    private fun coloredMiniText(value: String, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = 11f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(1), 0, dp(1))
        }
    }

    private fun top3SalesmenCard(owners: JSONArray) {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until owners.length()) {
            list.add(owners.getJSONObject(i))
        }

        val top = list.sortedByDescending { it.optDouble("totalSales") }.take(3)
        if (top.isEmpty()) return

        section("🥇 TOP 3 handlowców")

        card(padding = 14) {
            orientation = LinearLayout.VERTICAL

            top.forEachIndexed { index, o ->
                addView(
                    rankingRowView(
                        place = index + 1,
                        name = displayName(o.optString("name")),
                        value = money(o.optDouble("totalSales"))
                    )
                )

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(28), 0, 0, dp(6))

                    addView(coloredMiniText("Cele: ${goalsCountText(o)}", muted))

                    addView(coloredMiniText(
                        "Udział importu: ${percent(o.optDouble("importShare"))}",
                        if (o.optDouble("importShare") >= 0.40) green else red
                    ))

                    addView(coloredMiniText(
                        "Marża import BI: ${percent(o.optDouble("importMarginPct"))}",
                        if (o.optDouble("importMarginPct") >= 0.75) green else red
                    ))

                    addView(coloredMiniText(
                        "Marża polska BI: ${percent(o.optDouble("domesticMarginPct"))}",
                        if (o.optDouble("domesticMarginPct") >= 0.35) green else red
                    ))
                })
            }
        }
    }

    private fun trendsMonthCard(
        currentOwners: JSONArray,
        previousOwners: JSONArray,
        yearAgoOwners: JSONArray
    ) {
        val prevMonth = previousMonthKey()
        val yoyMonth = yearAgoMonthKey()

        section("📈 Trendy miesiąca")

        if ((prevMonth.isBlank() || previousOwners.length() == 0) &&
            (yoyMonth.isBlank() || yearAgoOwners.length() == 0)) {
            card(padding = 14) {
                addView(TextView(context).apply {
                    text = "Brak wcześniejszych okresów do porównania."
                    textSize = 14f
                    setTextColor(muted)
                })
            }
            return
        }

        val list = mutableListOf<Triple<JSONObject, JSONObject?, JSONObject?>>()
        for (i in 0 until currentOwners.length()) {
            val current = currentOwners.getJSONObject(i)
            list.add(
                Triple(
                    current,
                    findPreviousByName(previousOwners, current.optString("name")),
                    findPreviousByName(yearAgoOwners, current.optString("name"))
                )
            )
        }

        val sorted = list.sortedByDescending { item ->
            val prevSales = item.second?.optDouble("totalSales") ?: 0.0
            if (prevSales > 0.0) {
                (projectedMonthSales(item.first.optDouble("totalSales")) - prevSales) / prevSales
            } else -9999.0
        }

        card(padding = 14) {
            orientation = LinearLayout.VERTICAL

            addView(TextView(context).apply {
                text = "M/M = miesiąc do poprzedniego miesiąca • R/R = ten sam miesiąc rok do roku"
                textSize = 11f
                setTextColor(muted)
                setPadding(0, 0, 0, dp(6))
            })

            sorted.forEach { item ->
                addView(trendOwnerRow(item.first, item.second, item.third, prevMonth, yoyMonth))
            }
        }
    }

    private fun singleTrendCard(
        current: JSONObject,
        previous: JSONObject?,
        yearAgo: JSONObject?
    ) {
        section("📈 Trendy miesiąca")

        card(padding = 14) {
            orientation = LinearLayout.VERTICAL
            addView(trendOwnerRow(current, previous, yearAgo, previousMonthKey(), yearAgoMonthKey()))
        }
    }

    private fun trendOwnerRow(
        current: JSONObject,
        previous: JSONObject?,
        yearAgo: JSONObject?,
        previousMonth: String,
        yearAgoMonth: String
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))

            addView(TextView(context).apply {
                text = displayName(current.optString("name"))
                textSize = 15f
                setTextColor(dark)
                typeface = Typeface.DEFAULT_BOLD
            })

            val currentSales = current.optDouble("totalSales")
            val projected = projectedMonthSales(currentSales)
            val openMonth = elapsedDaysForSelectedMonth() < daysInSelectedMonth()

            addView(TextView(context).apply {
                text = if (openMonth) {
                    "Sprzedaż: ${money(currentSales)}  •  prognoza: ${money(projected)}"
                } else {
                    "Sprzedaż: ${money(currentSales)}"
                }
                textSize = 12f
                setTextColor(muted)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(3), 0, dp(6))
            })

            addView(
                trendComparisonBlock(
                    "M/M",
                    if (previousMonth.isBlank()) "brak poprzedniego miesiąca"
                    else "${monthLabel(selectedMonth)} vs ${monthLabel(previousMonth)}",
                    current,
                    previous
                )
            )

            addView(
                trendComparisonBlock(
                    "R/R",
                    if (yearAgoMonth.isBlank()) "brak tego miesiąca rok temu"
                    else "${monthLabel(selectedMonth)} vs ${monthLabel(yearAgoMonth)}",
                    current,
                    yearAgo
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(7), 0, 0) }
                }
            )
        }
    }

    private fun trendComparisonBlock(
        title: String,
        subtitle: String,
        current: JSONObject,
        reference: JSONObject?
    ): LinearLayout {
        val currentSales = projectedMonthSales(current.optDouble("totalSales"))
        val refSales = reference?.optDouble("totalSales") ?: 0.0
        val salesTrend = if (reference != null && refSales > 0.0) {
            (currentSales - refSales) / refSales
        } else null
        val importTrend = if (reference != null) {
            trendPoints(current.optDouble("importShare"), reference.optDouble("importShare"))
        } else null
        val importMarginTrend = if (reference != null) {
            trendPoints(current.optDouble("importMarginPct"), reference.optDouble("importMarginPct"))
        } else null
        val domesticMarginTrend = if (reference != null) {
            trendPoints(current.optDouble("domesticMarginPct"), reference.optDouble("domesticMarginPct"))
        } else null

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(softBlue)

            addView(TextView(context).apply {
                text = "$title  •  $subtitle"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(blue)
                setPadding(0, 0, 0, dp(4))
            })

            if (reference == null) {
                addView(TextView(context).apply {
                    text = "Brak danych porównawczych."
                    textSize = 11f
                    setTextColor(muted)
                })
                return@apply
            }

            addView(trendLine("Sprzedaż", trendTextPercent(salesTrend), salesTrend))
            addView(trendLine("Import", trendTextPoints(importTrend), importTrend))
            addView(trendLine("Marża import", trendTextPoints(importMarginTrend), importMarginTrend))
            addView(trendLine("Marża polska BI", trendTextPoints(domesticMarginTrend), domesticMarginTrend))
        }
    }

    private fun trendLine(label: String, valueText: String, value: Double?): TextView {
        return TextView(this).apply {
            text = "$label: $valueText"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(trendColor(value))
            setPadding(0, dp(1), 0, dp(1))
        }
    }

    private fun findPreviousByName(arr: JSONArray, name: String): JSONObject? {
        val wanted = normalizePersonName(displayName(name))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (normalizePersonName(displayName(o.optString("name"))) == wanted) {
                return o
            }
        }
        return null
    }


    private fun projectedMonthSales(currentSales: Double): Double {
        val daysElapsed = elapsedDaysForSelectedMonth().coerceAtLeast(1)
        val totalDays = daysInSelectedMonth().coerceAtLeast(daysElapsed)
        return (currentSales / daysElapsed.toDouble()) * totalDays.toDouble()
    }

    private fun trendProjectedSalesPct(currentSales: Double, previousFullMonthSales: Double): Double? {
        if (previousFullMonthSales <= 0.0) return null
        val projected = projectedMonthSales(currentSales)
        return (projected - previousFullMonthSales) / previousFullMonthSales
    }

    private fun daysInSelectedMonth(): Int {
        return try {
            val parts = selectedMonth.split("-")
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            java.time.YearMonth.of(year, month).lengthOfMonth()
        } catch (e: Exception) {
            30
        }
    }

    private fun elapsedDaysForSelectedMonth(): Int {
        return try {
            val parts = selectedMonth.split("-")
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val selected = java.time.YearMonth.of(year, month)
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Warsaw"))
            val current = java.time.YearMonth.from(today)

            when {
                selected == current -> today.dayOfMonth
                selected.isBefore(current) -> selected.lengthOfMonth()
                else -> 1
            }
        } catch (e: Exception) {
            1
        }
    }

    private fun trendPct(current: Double, previous: Double): Double? {
        if (previous <= 0.0) return null
        return (current - previous) / previous
    }

    private fun trendPoints(current: Double, previous: Double): Double? {
        return current - previous
    }

    private fun trendTextPercent(value: Double?): String {
        if (value == null) return "brak porównania"
        val sign = if (value >= 0.0) "+" else ""
        return sign + percent(value)
    }

    private fun trendTextPoints(value: Double?): String {
        if (value == null) return "brak porównania"
        val sign = if (value >= 0.0) "+" else ""
        return sign + pp(value)
    }

    private fun trendColor(value: Double?): Int {
        if (value == null) return muted
        return if (value >= 0.0) green else red
    }

    private fun goalsCountText(o: JSONObject): String {
        var ok = 0
        if (o.optDouble("importShare") >= threshold("importSharePct", 40.0)) ok++
        if (o.optDouble("importMarginPct") >= threshold("importMarginPct", 75.0)) ok++
        if (o.optDouble("domesticMarginPct") >= threshold("domesticMarginPct", 35.0)) ok++
        return "$ok/3"
    }

    private fun threshold(name: String, fallbackPercent: Double): Double {
        return (data.optJSONObject("thresholds")?.optDouble(name, fallbackPercent) ?: fallbackPercent) / 100.0
    }

    private fun findOwner(arr: JSONArray, name: String): JSONObject {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("name") == name) {
                return o
            }
        }

        return JSONObject()
    }

    private fun mergeOfficeOwners(arr: JSONArray): JSONArray {
        val result = JSONArray()
        var office: JSONObject? = null

        for (i in 0 until arr.length()) {
            val source = arr.getJSONObject(i)
            val rawName = source.optString("name", "")
            val normalized = normalizeName(rawName)

            // Zasada SalesAPP:
            // tylko rozpoznani przedstawiciele występują osobno.
            // Każdy inny operator Optimy/WMS należy do BIURA.
            if (!isSalesRepresentativeOwner(rawName)) {
                office = addOwnerValues(office, source, "BIURO")
            } else {
                val copy = JSONObject(source.toString())
                copy.put("name", normalized)
                result.put(copy)
            }
        }

        office?.let {
            finalizeOwnerMetrics(it)
            result.put(it)
        }

        return result
    }

    private fun addOwnerValues(
        current: JSONObject?,
        source: JSONObject,
        name: String
    ): JSONObject {
        val target = current ?: JSONObject().apply {
            put("name", name)
            put("totalSales", 0.0)
            put("totalMargin", 0.0)
            put("importSales", 0.0)
            put("importMargin", 0.0)
            put("domesticSales", 0.0)
            put("domesticMargin", 0.0)
        }

        target.put("totalSales", target.optDouble("totalSales") + source.optDouble("totalSales"))
        target.put("totalMargin", target.optDouble("totalMargin") + source.optDouble("totalMargin"))
        target.put("importSales", target.optDouble("importSales") + source.optDouble("importSales"))
        target.put("importMargin", target.optDouble("importMargin") + source.optDouble("importMargin"))
        target.put("domesticSales", target.optDouble("domesticSales") + source.optDouble("domesticSales"))
        target.put("domesticMargin", target.optDouble("domesticMargin") + source.optDouble("domesticMargin"))

        return target
    }

    private fun finalizeOwnerMetrics(o: JSONObject) {
        val totalSales = o.optDouble("totalSales")
        val totalMargin = o.optDouble("totalMargin")
        val importSales = o.optDouble("importSales")
        val importMargin = o.optDouble("importMargin")
        val domesticSales = o.optDouble("domesticSales")
        val domesticMargin = o.optDouble("domesticMargin")

        o.put("totalMarginPct", biMarginPct(totalSales, totalMargin))
        o.put("importMarginPct", biMarginPct(importSales, importMargin))
        o.put("domesticMarginPct", biMarginPct(domesticSales, domesticMargin))
        o.put("importShare", if (totalSales > 0.0) importSales / totalSales else 0.0)
    }

    private fun biMarginPct(sales: Double, margin: Double): Double {
        val cost = sales - margin
        return if (cost != 0.0) margin / cost else 0.0
    }

    private fun isSalesRepresentativeOwner(name: String): Boolean {
        val raw = name.trim()
        if (raw.isBlank()) return false

        // 1. Konfiguracja przedstawicieli pobrana z Firebase.
        if (representativeDisplayName(raw) != null) return true

        // 2. Aktywne konta z rolą HANDLOWIEC.
        val displayed = normalizePersonName(displayName(raw))
        if (activeSalesmenNormalized().contains(displayed)) return true

        // 3. Bezpieczny fallback dla kodów z Optimy.
        val code = raw
            .uppercase(Locale.ROOT)
            .replace("PRZEDSTAWICIEL", "PRZED")

        if (Regex("^PRZED\\d+$").matches(code)) return true

        // Starsze / specjalne kody przedstawicieli używane w Optimie.
        return code in setOf("WOJTEK", "MC")
    }

    private fun isOfficeName(name: String): Boolean {
        // Wszystko, co nie jest przedstawicielem, traktujemy jako BIURO.
        return !isSalesRepresentativeOwner(name)
    }

    private fun normalizeName(name: String): String {
        return when (name.trim().uppercase()) {
            "WOJTEK" -> "WOJTEK"
            "PRZEMEK" -> "PRZEMEK"
            "PRZEDSTAWICIEL1", "PRZED1" -> "PRZEDSTAWICIEL1"
            "PRZEDSTAWICIEL2", "PRZED2" -> "PRZEDSTAWICIEL2"
            "PRZEDSTAWICIEL3", "PRZED3" -> "PRZEDSTAWICIEL3"
            "PRZEDSTAWICIEL4", "PRZED4" -> "PRZEDSTAWICIEL4"
            "PRZEDSTAWICIEL5", "PRZED5" -> "PRZEDSTAWICIEL5"
            "PRZEDSTAWICIEL7", "PRZED7" -> "PRZEDSTAWICIEL7"
            "PRZEDSTAWICIEL8", "PRZED8" -> "PRZEDSTAWICIEL8"
            "(NIEPRZYPISANE)", "NIEPRZYPISANE", "BIURO" -> "BIURO"
            else -> name.trim()
        }
    }

    private fun displayName(name: String): String {
        representativeDisplayName(name)?.let { return it }

        return when (name.trim().uppercase()) {
            "WOJTEK" -> "Wojciech Szczurek"
            "PRZEMEK" -> "Przemysław Janus"

            "PRZEDSTAWICIEL1", "PRZED1" -> "Filip Panek"
            "PRZEDSTAWICIEL2", "PRZED2" -> "Elżbieta Chudyka"
            "PRZEDSTAWICIEL3", "PRZED3" -> "Dominika Sikora"
            "PRZEDSTAWICIEL4", "PRZED4" -> "Adam Leksander"
            "PRZEDSTAWICIEL5", "PRZED5" -> "Aleksander Wachnicki"
            "PRZEDSTAWICIEL7", "PRZED7" -> "Krystian Panek"
            "PRZEDSTAWICIEL8", "PRZED8" -> "Krzysztof Bartczak"

            "(NIEPRZYPISANE)", "NIEPRZYPISANE", "BIURO" -> "BIURO"
            else -> name
        }
    }

    private fun goalText(value: Double, target: Double): String {
        val missing = target - value

        return if (missing <= 0.0) {
            "${percent(value)} ✓ OK"
        } else {
            "${percent(value)} brakuje ${pp(missing)}"
        }
    }

    private fun formatUpdateDate(value: String): String {

        return try {

            val instant = java.time.Instant.parse(value)

            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy HH:mm:ss")
                .withZone(java.time.ZoneId.of("Europe/Warsaw"))

            formatter.format(instant)

        } catch (e: Exception) {
            value
        }
    }

    private fun monthLabel(m: String): String {
        val parts = m.split("-")
        if (parts.size != 2) return m

        val names = listOf(
            "",
            "styczeń",
            "luty",
            "marzec",
            "kwiecień",
            "maj",
            "czerwiec",
            "lipiec",
            "sierpień",
            "wrzesień",
            "październik",
            "listopad",
            "grudzień"
        )

        val month = parts[1].toIntOrNull() ?: return m
        return "${names[month]} ${parts[0]}"
    }

    private fun money(v: Double): String {
        val symbols = DecimalFormatSymbols(Locale("pl", "PL")).apply {
            groupingSeparator = ' '
            decimalSeparator = ','
        }

        val df = DecimalFormat("#,##0.00", symbols)
        return df.format(v) + " zł"
    }

    private fun percent(v: Double): String =
        "%.2f%%".format(v * 100).replace(".", ",")

    private fun pp(v: Double): String =
        "%.2f p.p.".format(v * 100).replace(".", ",")

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }
    enum class ViewMode {
        ALL,
        SALES_REPS,
        OFFICE
    }
    private fun printFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    android.util.Log.d(
                        "FCM_TOKEN",
                        task.result ?: ""
                    )
                } else {
                    android.util.Log.e(
                        "FCM_TOKEN",
                        "Nie udało się pobrać tokenu",
                        task.exception
                    )
                }
            }
    }


}
