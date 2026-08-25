package com.example.salesapp

import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Adapter kolekcji Firestore LIVE do ekranów produkcyjnego SalesAPP. */
class FirebaseSalesRepository(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {
    private val listeners = mutableListOf<ListenerRegistration>()
    private val productExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val productCache = mutableMapOf<String, Pair<String, List<Map<String, Any?>>>>()
    private val productDownloads = mutableSetOf<String>()
    private var months = emptyList<Map<String, Any?>>()
    private var contests = emptyList<Map<String, Any?>>()
    private var contestResults = emptyList<Map<String, Any?>>()
    private var clients = emptyList<Map<String, Any?>>()
    private var users = emptyList<Map<String, Any?>>()
    private var thresholds = emptyMap<String, Any?>()
    private var globalSettings = emptyMap<String, Any?>()
    private var ready = false

    fun start(onChanged: (JSONObject) -> Unit, onError: (String) -> Unit) {
        stop()

        // Nigdy nie zakładamy listenerów przed Firebase Authentication.
        // Reguły SalesAPP opierają się na request.auth oraz activeUser().
        if (FirebaseAuth.getInstance().currentUser == null) {
            android.util.Log.d(
                "SALESAPP_FIREBASE_LIVE",
                "Repository.start pominięty — brak zalogowanego FirebaseAuth.currentUser."
            )
            return
        }

        watch("salesapp_months", onError) { months = it; ready = true; onChanged(build()) }
        watch("salesapp_contests", onError) {
            contests = it
            refreshContestProducts(onChanged, onError)
            if (ready) onChanged(build())
        }
        watch("salesapp_contest_results", onError) { contestResults = it; if (ready) onChanged(build()) }
        watch("salesapp_potential_clients", onError) { clients = it; if (ready) onChanged(build()) }
        watch("salesapp_users", onError) { users = it; if (ready) onChanged(build()) }
        listeners += db.collection("salesapp_settings").document("thresholds")
            .addSnapshotListener { snapshot, error ->
                if (error != null) { onError(error.message ?: "Błąd progów Firestore"); return@addSnapshotListener }
                thresholds = snapshot?.data ?: emptyMap()
                if (ready) onChanged(build())
            }
        listeners += db.collection("salesapp_settings").document("global")
            .addSnapshotListener { snapshot, error ->
                if (error != null) { onError(error.message ?: "Błąd ustawień globalnych Firestore"); return@addSnapshotListener }
                globalSettings = snapshot?.data ?: emptyMap()
                if (ready) onChanged(build())
            }
    }

    private fun watch(collection: String, onError: (String) -> Unit, onChanged: (List<Map<String, Any?>>) -> Unit) {
        listeners += db.collection(collection).addSnapshotListener { snapshot, error ->
            if (error != null) { onError("$collection: ${error.message}"); return@addSnapshotListener }
            onChanged(snapshot?.documents?.map { document -> (document.data ?: emptyMap()) + ("_id" to document.id) } ?: emptyList())
        }
    }

    fun stop() {
        listeners.forEach { it.remove() }
        listeners.clear()
        ready = false

        // Dane sesyjne nie mogą przejść na następnego użytkownika.
        users = emptyList()
        globalSettings = emptyMap()
    }

    private fun build(): JSONObject {
        val root = JSONObject().put("ok", true).put("source", "FIREBASE_LIVE")
        val monthNames = JSONArray()
        val monthData = JSONObject()
        val sortedMonths = months.sortedBy { it["month"]?.toString() ?: it["_id"]?.toString().orEmpty() }
        if (sortedMonths.isEmpty()) {
            val current = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
            monthNames.put(current)
            monthData.put(current, JSONObject().put("total", JSONObject()).put("ownersArray", JSONArray()))
        } else for (row in sortedMonths) {
            val month = row["month"]?.toString() ?: row["_id"]?.toString().orEmpty()
            if (month.isBlank()) continue
            val payload = row["dataJson"]?.toString()?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: monthFromSummary(row)
            if (!payload.has("ownersArray")) payload.put("ownersArray", JSONArray())
            if (!payload.has("total")) payload.put("total", JSONObject())
            monthNames.put(month); monthData.put(month, payload)
        }
        root.put("months", monthNames).put("monthlyData", monthData)
        root.put("generatedAt", months.maxOfOrNull { it["updatedAtIso"]?.toString().orEmpty() }.orEmpty())
        root.put("thresholds", JSONObject(thresholds))

        val activeContests = contests.filter { it["active"] == true }
            .map { contestWithProducts(it) }
            .sortedBy { it["displayTitle"]?.toString() ?: it["name"]?.toString().orEmpty() }
        val activeContestArray = JSONArray()
        activeContests.forEach { activeContestArray.put(JSONObject(it)) }
        root.put("activeContests", activeContestArray)
        val allContestResults = JSONArray()
        contestResults.forEach { allContestResults.put(JSONObject(it)) }
        root.put("allContestResults", allContestResults)

        val active = activeContests.firstOrNull()
        val ranking = JSONArray()
        contestResults.filter { active == null || it["contestId"] == active["_id"] }.forEach { result ->
            ranking.put(JSONObject().put("Kontrahent Grupa", result["representativeCode"] ?: result["name"] ?: "")
                .put("Sprzedaż Wartość", result["value"] ?: result["sales"] ?: result["totalSales"] ?: 0))
        }
        root.put("konkurs", ranking)
        root.put("activeContest", JSONObject(active ?: emptyMap<String, Any?>()))
        val rawResults = JSONArray()
        contestResults.filter { active == null || it["contestId"] == active["_id"] }
            .forEach { rawResults.put(JSONObject(it)) }
        root.put("contestResultsRaw", rawResults)
        val products = JSONArray()
        (active?.get("products") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val code = item["code"]?.toString().orEmpty()
            val productName = item["displayName"]?.toString()?.trim().orEmpty()
                .ifBlank { item["name"]?.toString()?.trim().orEmpty() }
                .ifBlank { code }

            products.put(JSONObject().put("kod", code).put("nazwa", productName)
                .put("ean", item["ean"] ?: "").put("target", item["target"] ?: item["minimum"] ?: 0)
                .put("unit", item["unit"] ?: "szt.")
                .put("zdjecie", item["imageUrl"] ?: item["photo"] ?: item["zdjecie"] ?: ""))
        }
        root.put("konkursTowary", products)
        root.put(
            "contestProductsDebug",
            JSONObject()
                .put("contestId", active?.get("_id")?.toString().orEmpty())
                .put("expectedCount", active?.get("productsCount") ?: 0)
                .put("loadedCount", products.length())
                .put("loading", active?.get("productsLoading") ?: false)
                .put("sheet", active?.get("googleSheetTab") ?: UpdateConfig.CONTEST_PRODUCTS_SHEET)
        )

        val clientArray = JSONArray()
        clients.forEach { row ->
            val parsed = row["dataJson"]?.toString()?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject(row.filterKeys { !it.startsWith("_") })
            if (!parsed.has("id")) parsed.put("id", row["_id"])
            clientArray.put(parsed)
        }
        root.put("potentialClients", JSONObject().put("ok", true).put("clients", clientArray))

        val usersArray = JSONArray()
        users.forEach { row -> usersArray.put(JSONObject(row)) }

        val repsArray = JSONArray()
        users.asSequence()
            .filter { it["active"] != false }
            .filter { it["representativeCode"]?.toString()?.isNotBlank() == true }
            .groupBy { it["representativeCode"].toString().trim().uppercase(Locale.ROOT) }
            .toSortedMap()
            .forEach { (code, profiles) ->
                val representative = profiles.sortedWith(
                    compareByDescending<Map<String, Any?>> { representativePriority(it, code) }
                        .thenBy { it["name"]?.toString()?.lowercase(Locale.ROOT).orEmpty() }
                        .thenBy { it["_id"]?.toString().orEmpty() }
                ).first()

                repsArray.put(
                    JSONObject()
                        .put("code", code)
                        .put("name", representative["name"] ?: code)
                        .put("uid", representative["_id"] ?: "")
                )
            }
        val settingsArray = JSONArray()
        (globalSettings["settingsList"] as? List<*>)?.forEach { raw ->
            val row = raw as? Map<*, *> ?: return@forEach
            settingsArray.put(JSONObject(row))
        }
        root.put("config", JSONObject().put("users", usersArray).put("representatives", repsArray).put("settingsList", settingsArray))
        return root
    }

    private fun contestWithProducts(contest: Map<String, Any?>): Map<String, Any?> {
        val inline = contest["products"] as? List<*>
        if (inline != null && inline.isNotEmpty()) return contest
        val count = (contest["productsCount"] as? Number)?.toInt() ?: 0
        if (count == 0) return contest + ("products" to emptyList<Map<String, Any?>>())

        val contestId = contest["_id"]?.toString().orEmpty()
        val signature = productDownloadSignature(contest)
        val cached = productCache[contestId]
        if (cached != null && cached.first == signature) {
            return contest + ("products" to cached.second) + ("productsLoading" to false)
        }
        return contest + ("products" to emptyList<Map<String, Any?>>()) +
            ("productsLoading" to productDownloads.contains(contestId))
    }

    private fun productDownloadSignature(contest: Map<String, Any?>): String {
        val sheetUrl = contest["googleSheetUrl"]?.toString()?.trim().orEmpty()
            .ifBlank { UpdateConfig.CONTEST_PRODUCTS_SHEET_URL }
        val sheetTab = contest["googleSheetTab"]?.toString()?.trim().orEmpty()
            .ifBlank { UpdateConfig.CONTEST_PRODUCTS_SHEET }

        return listOf(
            contest["productsSyncedAtIso"],
            contest["googleSheetExportedAtIso"],
            contest["productRevision"],
            contest["googleSheetProductRevision"],
            contest["productsCount"],
            sheetUrl,
            sheetTab,
        ).joinToString("|") { it?.toString().orEmpty() }
    }

    private fun refreshContestProducts(onChanged: (JSONObject) -> Unit, onError: (String) -> Unit) {
        contests.filter { it["active"] == true }.forEach { contest ->
            val contestId = contest["_id"]?.toString().orEmpty()
            val count = (contest["productsCount"] as? Number)?.toInt() ?: 0

            // FIX 1.36:
            // Starszy konkurs może nie mieć jeszcze googleSheetUrl/googleSheetTab.
            // Wtedy korzystamy bezpośrednio z aktualnego arkusza PRODUKTY IMPORT.
            val sheetUrl = contest["googleSheetUrl"]?.toString()?.trim().orEmpty()
                .ifBlank { UpdateConfig.CONTEST_PRODUCTS_SHEET_URL }
            val tab = contest["googleSheetTab"]?.toString()?.trim().orEmpty()
                .ifBlank { UpdateConfig.CONTEST_PRODUCTS_SHEET }

            // 1.36.1: ten sam podpis jest używany przy zapisie i odczycie cache.
            val signature = productDownloadSignature(contest)

            if (
                contestId.isBlank() ||
                count == 0 ||
                productCache[contestId]?.first == signature ||
                !productDownloads.add(contestId)
            ) return@forEach

            productExecutor.execute {
                try {
                    val products = downloadProductsFromSheet(
                        sheetUrl = sheetUrl,
                        tab = tab,
                        contestId = contestId,
                    )
                    mainHandler.post {
                        productDownloads.remove(contestId)
                        productCache[contestId] = signature to products
                        android.util.Log.d(
                            "CONTEST_PRODUCTS",
                            "Konkurs $contestId: pobrano ${products.size} produktów z $tab"
                        )
                        if (ready) onChanged(build())
                    }
                } catch (error: Exception) {
                    mainHandler.post {
                        productDownloads.remove(contestId)
                        if (ready) {
                            onError(
                                "Produkty konkursu: ${error.message}. " +
                                    "Arkusz PRODUKTY IMPORT / KONKURSY_PRODUKTY musi mieć dostęp do odczytu z telefonu."
                            )
                            onChanged(build())
                        }
                    }
                }
            }
        }
    }

    private fun downloadProductsFromSheet(
        sheetUrl: String,
        tab: String,
        contestId: String,
    ): List<Map<String, Any?>> {
        val identifier = Regex("/spreadsheets/d/([A-Za-z0-9_-]{10,})")
            .find(sheetUrl)?.groupValues?.getOrNull(1)
            ?: UpdateConfig.CONTEST_PRODUCTS_SPREADSHEET_ID

        val encoded = URLEncoder.encode(tab, "UTF-8")
        val address = "https://docs.google.com/spreadsheets/d/$identifier/gviz/tq" +
            "?tqx=out:json&headers=1&sheet=$encoded"

        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 25_000

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Arkusz Google zwrócił HTTP ${connection.responseCode}"
                )
            }

            val body = connection.inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val start = body.indexOf('{')
            val finish = body.lastIndexOf('}')
            if (start < 0 || finish <= start) {
                throw IllegalStateException(
                    "Arkusz Google nie zwrócił danych. Sprawdź udostępnienie arkusza."
                )
            }

            val payload = JSONObject(body.substring(start, finish + 1))
            if (payload.optString("status") == "error") {
                throw IllegalStateException(
                    payload.optJSONArray("errors")
                        ?.optJSONObject(0)
                        ?.optString("detailed_message")
                        ?.ifBlank { "Nie udało się odczytać arkusza" }
                        ?: "Nie udało się odczytać arkusza"
                )
            }

            val table = payload.optJSONObject("table")
                ?: throw IllegalStateException("Brak tabeli danych w Arkuszu Google")
            val rows = table.optJSONArray("rows") ?: JSONArray()
            val cols = table.optJSONArray("cols") ?: JSONArray()

            fun normHeader(value: String): String =
                value.trim()
                    .uppercase(Locale.ROOT)
                    .replace("Ą", "A")
                    .replace("Ć", "C")
                    .replace("Ę", "E")
                    .replace("Ł", "L")
                    .replace("Ń", "N")
                    .replace("Ó", "O")
                    .replace("Ś", "S")
                    .replace("Ż", "Z")
                    .replace("Ź", "Z")
                    .replace(Regex("[^A-Z0-9]+"), "_")
                    .trim('_')

            val headerIndex = mutableMapOf<String, Int>()
            for (i in 0 until cols.length()) {
                val col = cols.optJSONObject(i) ?: continue
                val label = normHeader(col.optString("label"))
                val id = normHeader(col.optString("id"))
                if (label.isNotBlank()) headerIndex[label] = i
                if (id.isNotBlank() && id !in headerIndex) headerIndex[id] = i
            }

            fun idx(vararg names: String): Int {
                for (name in names) {
                    headerIndex[normHeader(name)]?.let { return it }
                }
                return -1
            }

            // Aktualny arkusz:
            // A KONKURS_ID | B KONKURS_NAZWA | C KOD | D NAZWA | E EAN
            // F KATEGORIA | G ATRYBUT | H WARTOSC_ATRYBUTU | I MINIMUM
            // J AKTYWNY | ... | N JEDNOSTKA | O TRYB_ZAKRESU
            val contestIndex = idx("KONKURS_ID", "CONTEST_ID")
            var codeIndex = idx("KOD", "CODE")
            var nameIndex = idx("NAZWA", "NAME", "DISPLAY_NAME")
            var eanIndex = idx("EAN")
            var targetIndex = idx("MINIMUM", "TARGET")
            var unitIndex = idx("JEDNOSTKA", "UNIT")
            val activeIndex = idx("AKTYWNY", "ACTIVE")

            // Zgodność ze starym, 6-kolumnowym arkuszem.
            val newSchema = codeIndex >= 0 && nameIndex >= 0 && targetIndex >= 0
            if (!newSchema) {
                codeIndex = 0
                nameIndex = 1
                eanIndex = 2
                targetIndex = 3
                unitIndex = 4
            }

            fun cell(cells: JSONArray, column: Int): JSONObject? {
                if (column < 0 || column >= cells.length()) return null
                return cells.optJSONObject(column)
            }

            fun textValue(cells: JSONArray, column: Int): String {
                val c = cell(cells, column) ?: return ""
                // Dla kodów typu 00001 ważna jest wartość sformatowana "f".
                val formatted = c.optString("f").trim()
                if (formatted.isNotBlank()) return formatted
                val raw = if (c.isNull("v")) null else c.opt("v")
                return when (raw) {
                    null -> ""
                    is Number -> {
                        val d = raw.toDouble()
                        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
                    }
                    else -> raw.toString().trim()
                }
            }

            fun numberValue(cells: JSONArray, column: Int): Double {
                val c = cell(cells, column) ?: return 0.0
                val raw = if (c.isNull("v")) null else c.opt("v")
                return when (raw) {
                    is Number -> raw.toDouble()
                    else -> textValue(cells, column)
                        .replace(" ", "")
                        .replace(',', '.')
                        .toDoubleOrNull() ?: 0.0
                }
            }

            val result = mutableListOf<Map<String, Any?>>()

            for (rowIndex in 0 until rows.length()) {
                val cells = rows.optJSONObject(rowIndex)?.optJSONArray("c") ?: continue

                if (newSchema && contestIndex >= 0) {
                    val rowContestId = textValue(cells, contestIndex)
                    if (!rowContestId.equals(contestId, ignoreCase = true)) continue
                }

                if (activeIndex >= 0) {
                    val active = textValue(cells, activeIndex).trim().uppercase(Locale.ROOT)
                    if (
                        active.isNotBlank() &&
                        active !in setOf("TAK", "TRUE", "1", "YES", "Y")
                    ) continue
                }

                val code = textValue(cells, codeIndex)
                if (code.isBlank()) continue

                val name = textValue(cells, nameIndex).ifBlank { code }
                val ean = textValue(cells, eanIndex)
                val target = numberValue(cells, targetIndex)
                val unit = textValue(cells, unitIndex).ifBlank { "szt." }

                result += mapOf(
                    "code" to code,
                    "displayName" to name,
                    "name" to name,
                    "ean" to ean,
                    "target" to target,
                    "minimum" to target,
                    "unit" to unit,
                    "imageUrl" to "",
                )
            }

            if (result.isEmpty()) {
                throw IllegalStateException(
                    "Nie znaleziono produktów dla konkursu $contestId w zakładce $tab"
                )
            }

            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun representativePriority(profile: Map<String, Any?>, code: String): Int {
        var priority = 0

        if (profile["primaryRepresentative"] == true || profile["isRepresentative"] == true) {
            priority += 1_000
        }

        if (profile["role"]?.toString()?.equals("HANDLOWIEC", ignoreCase = true) == true) {
            priority += 100
        }

        val emailLogin = profile["email"]?.toString()
            ?.substringBefore('@')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val normalizedCode = code.trim().lowercase(Locale.ROOT)
        val number = Regex("""^przed(?:stawiciel)?(\d+)$""")
            .matchEntire(normalizedCode)
            ?.groupValues
            ?.getOrNull(1)

        if (number != null) {
            val representativeEmail = Regex(
                """(?:^|[._-])(?:przedstawiciel|przed)$number(?:[._-]|$)"""
            )
            if (representativeEmail.containsMatchIn(emailLogin)) priority += 500
        } else if (emailLogin == normalizedCode) {
            priority += 500
        }

        return priority
    }

    private fun monthFromSummary(row: Map<String, Any?>): JSONObject {
        val total = JSONObject()
        for ((key, value) in row) if (key !in setOf("_id", "month", "representatives", "updatedAtIso")) total.put(key, value)
        val owners = JSONArray()
        @Suppress("UNCHECKED_CAST")
        (row["representatives"] as? Map<String, Any?>)?.forEach { (code, raw) ->
            val values = raw as? Map<*, *> ?: emptyMap<String, Any?>()
            val owner = JSONObject()
            for ((key, value) in values) if (key != null) owner.put(key.toString(), value)
            owner.put("name", code)
            owners.put(owner)
        }
        return JSONObject().put("total", total).put("ownersArray", owners)
    }
}
