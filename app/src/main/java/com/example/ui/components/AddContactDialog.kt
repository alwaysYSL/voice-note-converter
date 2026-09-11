package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentCoral
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.DeepNavyDisplay
import com.example.ui.theme.SubtitleSlate
import com.example.ui.theme.SubtleBorder
import java.util.UUID

@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, chatId: Long, username: String?, phone: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var usernameOrId by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurfaceWhite,
        shape = RoundedCornerShape(26.dp),
        title = {
            Text(
                "Tambah Chat Tujuan",
                fontWeight = FontWeight.Bold,
                color = DeepNavyDisplay
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Kontak ini hanya menjadi pengingat. Anda tetap memilih chat saat Telegram terbuka.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SubtitleSlate
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Kontak / Label") },
                    placeholder = { Text("Misal: Sarah, Teman Kuliah") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = AccentCoral) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_name_input"),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCoral,
                        unfocusedBorderColor = SubtleBorder
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = usernameOrId,
                    onValueChange = { usernameOrId = it },
                    label = { Text("Username (Opsional)") },
                    placeholder = { Text("@username") },
                    leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = AccentCoral) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_username_input"),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCoral,
                        unfocusedBorderColor = SubtleBorder
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Nomor HP (Opsional)") },
                    placeholder = { Text("+628123456789") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = AccentCoral) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCoral,
                        unfocusedBorderColor = SubtleBorder
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanInput = usernameOrId.trim().removePrefix("@")
                    val localContactId = -(
                        UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
                        ).coerceAtLeast(1L)
                    onConfirm(
                        name.trim(),
                        localContactId,
                        cleanInput.ifEmpty { null },
                        phone.trim().ifEmpty { null }
                    )
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCoral,
                    contentColor = Color.White
                ),
                modifier = Modifier.testTag("confirm_add_contact_button")
            ) {
                Text("Simpan & Pilih", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50)
            ) {
                Text("Batal", color = SubtitleSlate, fontWeight = FontWeight.Medium)
            }
        }
    )
}
