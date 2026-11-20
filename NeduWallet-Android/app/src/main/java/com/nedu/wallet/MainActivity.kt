package com.nedu.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nedu.wallet.data.SupabaseClientProvider
import com.nedu.wallet.data.WalletBalance
import com.nedu.wallet.data.WalletRepository
import kotlinx.coroutines.launch

private val NeduRed = Color(0xFFC91D27)
private val DeepRed = Color(0xFF98151F)
private val Ink = Color(0xFF111111)
private val Muted = Color(0xFF85858D)
private val Soft = Color(0xFFF2F1F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NeduApp() }
    }
}

@Composable
fun NeduApp() {
    var screen by remember { mutableStateOf("welcome") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var hideBalance by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var wallets by remember { mutableStateOf<List<WalletBalance>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val walletRepository = remember {
        if (SupabaseClientProvider.isConfigured) WalletRepository() else null
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = NeduRed, onPrimary = Color.White, background = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            when (screen) {
                "splash" -> SplashScreen { screen = "welcome" }
                "welcome" -> WelcomeScreen(
                    onCreate = { screen = "login" },
                    onLogin = { screen = "login" }
                )
                "login" -> LoginScreen(
                    email = email,
                    onEmail = { email = it },
                    password = password,
                    onPassword = { password = it },
                    errorMessage = authError,
                    loading = isLoading,
                    onLogin = {
                        val repository = walletRepository
                        if (repository == null) {
                            authError = "This build is not connected to Supabase yet. Configure the existing project's URL and publishable key in local.properties."
                        } else {
                            scope.launch {
                                isLoading = true
                                authError = null
                                try {
                                    repository.signIn(email, password)
                                    wallets = repository.loadMyWallets()
                                    screen = "home"
                                } catch (error: Exception) {
                                    authError = error.message ?: "Unable to sign in. Check your connection and credentials."
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    },
                    onCreate = {
                        authError = "Account creation is being held until the existing iOS signup and profile-provisioning flow is verified. Please use your existing Nedu Wallet account."
                    }
                )
                "passcode" -> PasscodeScreen(passcode, { passcode = it }, onUnlock = { screen = "home" })
                "home" -> WalletHome(wallets, hideBalance, { hideBalance = !hideBalance }, onNavigate = { screen = it })
                "card" -> CardScreen(onNavigate = { screen = it })
                else -> ProfileScreen(
                    onNavigate = { screen = it },
                    onSignOut = {
                        val repository = walletRepository
                        if (repository != null) {
                            scope.launch {
                                try {
                                    repository.signOut()
                                    wallets = emptyList()
                                    screen = "welcome"
                                } catch (error: Exception) {
                                    authError = error.message ?: "Unable to sign out."
                                }
                            }
                        } else {
                            screen = "welcome"
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SplashScreen(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(NeduRed).clickable { onContinue() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.background(Color(0xFFD92B39), RoundedCornerShape(30.dp)).padding(horizontal = 44.dp, vertical = 22.dp)) {
                Text("Nedu", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Text("Nedu Wallet", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Secure  ·  Global  ·  Non-Custodial", color = Color.White.copy(.72f), fontSize = 15.sp)
        }
    }
}

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Nedu", color = NeduRed, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(48.dp))
        Text("Welcome to Nedu Wallet", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Text("Passcode + 2FA, iCloud phrase backup, a card linked to all your wallets, and send, receive and swap across fiat and crypto.",
            color = Muted, fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(34.dp))
        PrimaryButton("Create Account", onCreate)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Log In – I already have an account", color = DeepRed, fontSize = 16.sp)
        }
        Spacer(Modifier.height(28.dp))
        Text("NGN  USD  EUR  GBP  CAD  USDT  USDC. One app for all your money.",
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun LoginScreen(email: String, onEmail: (String) -> Unit, password: String, onPassword: (String) -> Unit,
                errorMessage: String?, loading: Boolean, onLogin: () -> Unit, onCreate: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Log In", Modifier.fillMaxWidth(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(54.dp))
        Text("Welcome Back", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Log in to your Nedu Wallet account.", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        SoftField(value = email, onValueChange = onEmail, hint = "Email", keyboard = KeyboardType.Email)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Password", color = Color.LightGray) },
            shape = RoundedCornerShape(18.dp), singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted)
            } },
            colors = fieldColors())
        Spacer(Modifier.height(12.dp))
        Text("Password must have capital, small, number and character.", color = Muted, fontSize = 13.sp)
        if (errorMessage != null) {
            Text(errorMessage, color = NeduRed, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (loading) "Signing In..." else "Log In", onLogin)
        TextButton(onClick = { /* Password recovery will be connected with the verified Auth redirect flow. */ }) {
            Text("Forgot Password?", color = DeepRed, fontSize = 16.sp)
        }
        TextButton(onClick = onCreate) { Text("New here? Create Account", color = DeepRed, fontWeight = FontWeight.Bold) }
        Text("By continuing you agree to Nedu Wallet terms.", color = Muted, fontSize = 13.sp)
    }
}

@Composable
fun PasscodeScreen(passcode: String, onPasscode: (String) -> Unit, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Nedu", color = NeduRed, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("Enter Passcode", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(value = passcode, onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) onPasscode(it) },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("••••") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(18.dp), colors = fieldColors())
        Spacer(Modifier.height(22.dp))
        PrimaryButton("Unlock", onUnlock)
        Spacer(Modifier.height(18.dp))
        Text("First time? Enter any 4 digits to set your passcode.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun WalletHome(wallets: List<WalletBalance>, hidden: Boolean, toggleHidden: () -> Unit, onNavigate: (String) -> Unit) {
    Scaffold(bottomBar = { BottomBar("home", onNavigate) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(24.dp))
                Text("Nedu Wallet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(NeduRed, DeepRed)), RoundedCornerShape(28.dp)).padding(24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Your Wallets", color = Color.White.copy(.8f), fontSize = 15.sp)
                            IconButton(onClick = toggleHidden) { Icon(if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Color.White) }
                        }
                        Text("${wallets.size} currencies", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("Balances are loaded from your account", color = Color.White.copy(.7f), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("↑", "Send", Modifier.weight(1f)) { onNavigate("transfer") }
                    ActionTile("↓", "Receive", Modifier.weight(1f)) { onNavigate("receive") }
                    ActionTile("⇄", "Swap", Modifier.weight(1f)) { onNavigate("swap") }
                    ActionTile("◎", "Transfer", Modifier.weight(1f)) { onNavigate("transfer") }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Your Wallets - Tap to open", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Tap eye to hide", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (wallets.isEmpty()) {
                item {
                    Text("No wallets were returned for this account.", color = Muted, modifier = Modifier.padding(vertical = 20.dp))
                }
            } else {
                wallets.forEach { wallet ->
                    item {
                        WalletRow(
                            flag = currencyMark(wallet.code),
                            name = wallet.name,
                            code = wallet.code,
                            amount = "${wallet.balance} ${wallet.code}",
                            hidden = hidden
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WalletRow(flag: String, name: String, code: String, amount: String, hidden: Boolean) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flag, fontSize = 25.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(code, color = Muted, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (hidden) "••••" else amount, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Available balance", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
fun CardScreen(onNavigate: (String) -> Unit) {
    Scaffold(bottomBar = { BottomBar("card", onNavigate) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.CreditCard, null, tint = NeduRed, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(18.dp))
            Text("Nedu Card", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Card details and controls are not connected yet. We are verifying which existing card records the iOS app uses before displaying or changing card information here.",
                color = Muted,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun ProfileScreen(onNavigate: (String) -> Unit, onSignOut: () -> Unit) {
    Scaffold(bottomBar = { BottomBar("profile", onNavigate) }) {
        Column(Modifier.fillMaxSize().padding(it).padding(22.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Profile", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Text("Your Nedu Wallet account", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Account profile details will load after profile mapping is verified.", color = Muted)
            Spacer(Modifier.height(24.dp))
            listOf("Personal Information", "Security & 2FA", "Backup & Recovery Phrase", "Notifications", "Help & Support", "Terms & Privacy").forEach {
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
                    Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Sign Out", onSignOut)
        }
    }
}

@Composable
fun BottomBar(selected: String, onNavigate: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFFFAFAFC)) {
        NavigationBarItem(selected == "home", onClick = { onNavigate("home") }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
        NavigationBarItem(selected == "card", onClick = { onNavigate("card") }, icon = { Icon(Icons.Default.CreditCard, null) }, label = { Text("Card") })
        NavigationBarItem(selected == "profile", onClick = { onNavigate("profile") }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profile") })
    }
}

@Composable
fun ActionTile(symbol: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.height(86.dp).clickable { onClick() }, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(symbol, color = DeepRed, fontSize = 27.sp)
            Text(label, color = DeepRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = NeduRed)) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SoftField(value: String, onValueChange: (String) -> Unit, hint: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(hint, color = Color.LightGray) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = RoundedCornerShape(18.dp), colors = fieldColors())
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Soft, focusedContainerColor = Soft,
    unfocusedBorderColor = Soft, focusedBorderColor = NeduRed,
    unfocusedTextColor = Ink, focusedTextColor = Ink
)


private fun currencyMark(code: String): String = when (code.uppercase()) {
    "NGN" -> "₦"
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "CAD" -> "C$"
    "USDT" -> "₮"
    "USDC" -> "$"
    else -> "¤"
}
