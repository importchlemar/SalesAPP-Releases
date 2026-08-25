package com.example.salesapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        android.util.Log.d("FCM_TOKEN", token)

        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        // Po odświeżeniu tokenu pobieramy profil po UID, dzięki czemu FCM
        // pozostaje przypisany do tego samego konta / PRZED / roli.
        db.collection("salesapp_users").document(user.uid).get()
            .addOnSuccessListener { profile ->
                if (!profile.exists() || profile.getBoolean("active") != true) return@addOnSuccessListener

                val rep = profile.getString("representativeCode").orEmpty().trim().uppercase()
                val name = profile.getString("name").orEmpty().trim()
                val role = profile.getString("role").orEmpty().trim().uppercase()
                val email = profile.getString("email").orEmpty().ifBlank { user.email.orEmpty() }

                val payload = hashMapOf<String, Any?>(
                    "uid" to user.uid,
                    "email" to email,
                    "login" to email,
                    "name" to if (role == "HANDLOWIEC" && rep.isNotBlank()) rep else name.uppercase(),
                    "displayName" to name,
                    "representativeCode" to rep,
                    "role" to role,
                    "token" to token,
                    "device" to Build.MODEL,
                    "android" to Build.VERSION.RELEASE,
                    "environment" to "PROD",
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                // Jeden token = jeden aktualny użytkownik.
                db.collection("salesapp_devices")
                    .whereEqualTo("token", token)
                    .get()
                    .addOnSuccessListener { oldDevices ->
                        oldDevices.documents
                            .filter { it.id != user.uid }
                            .forEach { it.reference.delete() }
                    }

                db.collection("salesapp_devices").document(user.uid)
                    .set(payload, SetOptions.merge())

                val deviceId = try {
                    android.provider.Settings.Secure.getString(
                        contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ).orEmpty().ifBlank { Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_") }
                } catch (_: Exception) {
                    Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")
                }

                db.collection("salesapp_users").document(user.uid)
                    .collection("devices").document(deviceId)
                    .set(payload, SetOptions.merge())
            }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val type = message.data["type"].orEmpty()

        if (type == "APP_UPDATE") {
            GitHubUpdater.handleRemoteUpdate(this, message.data)
            return
        }

        val isComplaint = type == "COMPLAINT_UPDATE" || type == "COMPLAINT_DELETED"

        if (!isComplaint) {
            showResolvedNotification(message)
            return
        }

        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val recipientUid = message.data["recipientUid"].orEmpty().trim()
        val recipientRep = (
            message.data["recipientRepresentativeCode"]
                ?: message.data["representativeCode"]
                ?: ""
            ).trim().uppercase()

        // Najpewniejsza ścieżka: sender wysłał konkretny UID.
        if (recipientUid.isNotBlank()) {
            if (recipientUid != currentUser.uid) {
                android.util.Log.d(
                    "COMPLAINT_FCM",
                    "Pomijam reklamację: recipientUid=$recipientUid currentUid=${currentUser.uid}"
                )
                return
            }
            showResolvedNotification(message)
            return
        }

        // Druga ścieżka: sender podał kod przedstawiciela.
        if (recipientRep.isNotBlank()) {
            FirebaseFirestore.getInstance()
                .collection("salesapp_users")
                .document(currentUser.uid)
                .get()
                .addOnSuccessListener { profile ->
                    val myRep = profile.getString("representativeCode")
                        .orEmpty().trim().uppercase()
                    if (myRep == recipientRep) {
                        showResolvedNotification(message)
                    } else {
                        android.util.Log.d(
                            "COMPLAINT_FCM",
                            "Pomijam reklamację: recipientRep=$recipientRep myRep=$myRep"
                        )
                    }
                }
                .addOnFailureListener {
                    android.util.Log.w(
                        "COMPLAINT_FCM",
                        "Nie udało się sprawdzić representativeCode: ${it.message}"
                    )
                }
            return
        }

        // Starszy sender bez recipientUid/recipientRepresentativeCode.
        // Dla istniejącej reklamacji sprawdzamy przypisanie w Firestore.
        val complaintId = message.data["complaintId"].orEmpty().trim()
        if (type != "COMPLAINT_DELETED" && complaintId.isNotBlank()) {
            val db = FirebaseFirestore.getInstance()

            db.collection("complaints")
                .document(complaintId)
                .get()
                .addOnSuccessListener { doc ->
                    if (!doc.exists()) return@addOnSuccessListener

                    // Preferujemy jawne przypisanie, jeżeli moduł Windows je zapisuje.
                    val assignedTo = doc.get("assignedTo") as? Map<*, *>
                    val reportedBy = doc.get("reportedBy") as? Map<*, *>

                    val ownerUid = listOf(
                        assignedTo?.get("uid")?.toString(),
                        doc.getString("assignedUid"),
                        reportedBy?.get("uid")?.toString(),
                    ).firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()

                    val ownerRep = listOf(
                        assignedTo?.get("representativeCode")?.toString(),
                        doc.getString("assignedRepresentativeCode"),
                        reportedBy?.get("representativeCode")?.toString(),
                    ).firstOrNull { !it.isNullOrBlank() }.orEmpty().trim().uppercase()

                    val ownerEmail = listOf(
                        assignedTo?.get("email")?.toString(),
                        reportedBy?.get("email")?.toString(),
                    ).firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()

                    if (ownerUid.isNotBlank()) {
                        if (ownerUid == currentUser.uid) {
                            showResolvedNotification(message)
                        } else {
                            android.util.Log.d(
                                "COMPLAINT_FCM",
                                "Pomijam reklamację $complaintId - ownerUid=$ownerUid"
                            )
                        }
                        return@addOnSuccessListener
                    }

                    db.collection("salesapp_users")
                        .document(currentUser.uid)
                        .get()
                        .addOnSuccessListener { profile ->
                            val myRep = profile.getString("representativeCode")
                                .orEmpty().trim().uppercase()
                            val myEmail = currentUser.email.orEmpty().trim()

                            val isOwner =
                                (ownerRep.isNotBlank() && ownerRep == myRep) ||
                                (
                                    ownerRep.isBlank() &&
                                    ownerEmail.isNotBlank() &&
                                    ownerEmail.equals(myEmail, ignoreCase = true)
                                )

                            if (isOwner) {
                                showResolvedNotification(message)
                            } else {
                                android.util.Log.d(
                                    "COMPLAINT_FCM",
                                    "Pomijam reklamację $complaintId - inny przypisany użytkownik."
                                )
                            }
                        }
                }
                .addOnFailureListener {
                    android.util.Log.w(
                        "COMPLAINT_FCM",
                        "Nie udało się zweryfikować właściciela reklamacji: ${it.message}"
                    )
                }
            return
        }

        // COMPLAINT_DELETED bez odbiorcy jest celowo ignorowane.
        android.util.Log.d(
            "COMPLAINT_FCM",
            "Pomijam push reklamacji bez recipientUid/recipientRepresentativeCode."
        )
    }

    private fun showResolvedNotification(message: RemoteMessage) {
        val type = message.data["type"].orEmpty()
        val title = message.notification?.title
            ?: message.data["title"]
            ?: when (type) {
                "COMPLAINT_DELETED" -> "Usunięto reklamację"
                "COMPLAINT_UPDATE" -> "Aktualizacja reklamacji"
                else -> "CHLE-MAR SalesAPP"
            }

        val body = message.notification?.body
            ?: message.data["body"]
            ?: "Dane zostały zaktualizowane."

        showNotification(title, body, message.data)
    }

    private fun showNotification(title: String, body: String, data: Map<String, String>) {
        val isComplaint = data["type"] == "COMPLAINT_UPDATE" || data["type"] == "COMPLAINT_DELETED"
        val channelId = if (isComplaint) "complaint_updates" else "salesapp_updates"
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                if (isComplaint) "Aktualizacje reklamacji" else "Aktualizacje SalesAPP",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = if (isComplaint)
                    "Zmiany, decyzje i usunięcia reklamacji zgłoszonych przez użytkownika"
                else
                    "Powiadomienia o aktualizacji SalesAPP"
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("type", data["type"].orEmpty())
            putExtra("screen", data["screen"].orEmpty())
            putExtra("complaintId", data["complaintId"].orEmpty())
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            if (isComplaint) data["complaintId"].orEmpty().hashCode() else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
