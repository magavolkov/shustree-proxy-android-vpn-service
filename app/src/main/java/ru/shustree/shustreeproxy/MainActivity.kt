package ru.shustree.shustreeproxy


import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import ru.shustree.shustreeproxy.data.ProxyDetails
import ru.shustree.shustreeproxy.data.ShustreeVpnService
import ru.shustree.shustreeproxy.ui.theme.ShustreeTheme
import ru.shustree.shustreeproxy.ui.theme.StatusConnected
import ru.shustree.shustreeproxy.ui.theme.StatusDisconnected
import java.util.Locale
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.asPaddingValues // <-- ADD THIS
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.copy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.io.path.moveTo
import kotlin.text.append
import kotlin.text.firstOrNull
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin




class MainActivity : ComponentActivity(), ShustreeVpnService.VpnStatusListener {
    private var vpnService: ShustreeVpnService? = null
    private var isBound = false
    private val isVpnConnected = mutableStateOf(false)
    private lateinit var navController: NavHostController
    private val isConnecting = mutableStateOf(false)





    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ShustreeVpnService.LocalBinder
            vpnService = binder.getService()
            isBound = true
            Log.i("MainActivity", "Service connected and bound.")



            binder.registerListener(this@MainActivity)

        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            // When the service disconnects, unregister the listener to prevent memory leaks.
            isBound = false
            vpnService = null
            isVpnConnected.value = false
            isConnecting.value = false
            Log.w("MainActivity", "Service disconnected.")
        }
    }

    // Implement the required method from the VpnStatusListener interface
    override fun onVpnStatusChanged(isConnected: Boolean, isConnecting: Boolean) {
        val wasConnected = this.isVpnConnected.value
        this.isVpnConnected.value = isConnected
        this.isConnecting.value = isConnecting
        Log.d("MainActivity", "[CALLBACK] Status changed: isConnected=$isConnected, isConnecting=$isConnecting")
    }




    override fun onStart() {
        super.onStart()
        Intent(this, ShustreeVpnService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {

            vpnService?.unregisterClientListener()

            unbindService(connection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume: Fetching fresh API data.")

        vpnInfoViewModel.refreshApiData()

        if (isBound) {
            isVpnConnected.value = vpnService?.isVpnRunning() ?: false
        }
    }

    // This function now sends commands via the Binder.
    private fun prepareAndStartVpn(
        tcpProxies: List<ProxyDetails>,
        udpProxies: List<ProxyDetails>, // Добавили второй список
        balance: Long,
        whatsappPrefixes: List<String>,
        ruApps: List<String>
    ) {
        if (!isBound || vpnService == null) {
            Log.e("MainActivity", "Start command failed: Service not bound.")
            return
        }
        val vpnPrepareIntent = VpnService.prepare(applicationContext)
        if (vpnPrepareIntent != null) {
            vpnPermissionLauncher.launch(vpnPrepareIntent)
        } else {
            Log.i("MainActivity", "VPN permission already granted. Commanding service to start.")
            // Send command via binder
            vpnService?.commandStartVpn(tcpProxies, udpProxies, balance, whatsappPrefixes, ruApps)
        }
    }




    // This is a new helper function for clarity
    private fun stopVpnService() {
        if (!isBound || vpnService == null) {
            Log.e("MainActivity", "Stop command failed: Service not bound.")
            return
        }
        Log.i("MainActivity", "Commanding service to stop.")
        // Send command via binder
        vpnService?.commandStopVpn()
    }




    // This function is still needed by the vpnPermissionLauncher

    private val vpnInfoViewModel: VpnInfoViewModel by viewModels()

    // --- THIS LAUNCHER IS THE GATEKEEPER ---
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i("MainActivity", "VPN permission GRANTED. Starting service now.")
            // Извлекаем актуальное состояние из ViewModel
            val state = vpnInfoViewModel.vpnInfoState.value
            val tcpProxies = state.tcpProxies ?: emptyList()
            val udpProxies = state.udpProxies ?: emptyList() // Новое поле из твоего стейта
            val balance = state.balance ?: 0L
            val prefixes = state.whatsappPrefixes
            val ruApps = state.ruApps ?: emptyList()

            if (tcpProxies.isNotEmpty() && udpProxies.isNotEmpty() && isBound) {
                vpnService?.commandStartVpn(tcpProxies, udpProxies, balance, prefixes, ruApps)
            } else {
                Log.e("MainActivity", "Permission granted, but some proxies missing.")
                Toast.makeText(this, getString(R.string.toast_proxy_server_error), Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w("MainActivity", "VPN permission DENIED or canceled.")
            //isLoading.value = false
            isVpnConnected.value = false
        }
    }



    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val sharedPrefs = getSharedPreferences("shustree_prefs", Context.MODE_PRIVATE)

        setContent {

            val isFirstLaunch = remember { sharedPrefs.getBoolean("is_first_launch", true) }
            val startDestination = if (isFirstLaunch) "about" else "main"

            val vpnInfoState by vpnInfoViewModel.vpnInfoState.collectAsState()
            val isApiDataReady by vpnInfoViewModel.isApiDataReady.collectAsState()
            val activationState by vpnInfoViewModel.activationState.collectAsState()
            LaunchedEffect(activationState.activationSuccess) {
                if (activationState.activationSuccess) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_activation_success), Toast.LENGTH_SHORT).show()
                    navController.navigate("main") {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    }
                    // Tell the ViewModel we have handled the success signal
                    vpnInfoViewModel.consumedActivationSuccess()
                }
            }



            ShustreeTheme {

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isVertical = maxHeight > maxWidth

                    val useHorizontalLayout = maxWidth > 600.dp || !isVertical

                    Image(
                        painter = painterResource(id = R.drawable.shustree_background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        // RULE: Vertical -> Top-Right | Horizontal -> Top-Left
                        alignment = if (isVertical) {
                            BiasAlignment(horizontalBias = 1f, verticalBias = -1f) // Top-Right
                        } else {
                            BiasAlignment(horizontalBias = -1f, verticalBias = -1f) // Top-Left
                        }
                    )


                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    navController = rememberNavController()

                    // This state helps determine which icon (Menu or Back) to show in the TopAppBar
                    val currentRoute by remember {
                        derivedStateOf {
                            navController.currentBackStackEntry?.destination?.route
                        }
                    }
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            AppDrawerContent(
                                currentRoute = currentRoute,
                                onNavigate = { route ->
                                    navController.navigate(route)
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    ) {
                        // The Scaffold provides the structure for the TopAppBar and the main content area.
                        Scaffold(
                            topBar = {
                                MainTopAppBar(
                                    onMenuClick = {
                                        scope.launch { drawerState.open() }
                                    }
                                )
                            },
                            containerColor = Color.Transparent

                        ) { innerPadding ->


                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding), // Page content is correctly padded.
                                color = Color.Transparent

                            ) {

                                NavHost(
                                    navController = navController,
                                    startDestination = startDestination
                                ) {
                                    composable("main") {
                                        VpnControlScreen(
                                            isHorizontal = useHorizontalLayout,
                                            isConnected = isVpnConnected.value,
                                            isLoading = isConnecting.value,
                                            onToggle = { ctx ->
                                                vibrate(ctx)
                                                if (isVpnConnected.value) {
                                                    stopVpnService()
                                                } else {
                                                    // First, check for API errors (highest priority).
                                                    if (vpnInfoState.error != null) {
                                                        Log.w("MainActivity", "VPN start blocked. API error: ${vpnInfoState.error}")
                                                        Toast.makeText(this@MainActivity, getString(R.string.toast_network_error), Toast.LENGTH_LONG).show()

                                                    } else {
                                                        // If there's no error, proceed with balance and proxy checks.
                                                        val currentBalance = vpnInfoState.balance
                                                        val currentTcpProxies = vpnInfoState.tcpProxies ?: emptyList()
                                                        val currentUdpProxies = vpnInfoState.udpProxies ?: emptyList()

                                                        val whatsappPrefixes = vpnInfoState.whatsappPrefixes ?: emptyList() // Added fallback
                                                        val ruApps = vpnInfoState.ruApps ?: emptyList() // Added fallback to resolve type mismatch


                                                        // --- START OF THE REFINED LOGIC ---

                                                        // Condition 1: Everything is OK to start.
                                                        if (currentBalance != null && currentBalance > 0L && currentTcpProxies.isNotEmpty()) {
                                                            Log.i("MainActivity", "Starting VPN with ${whatsappPrefixes.size} WhatsApp ranges for heartbeat.")
                                                            prepareAndStartVpn(currentTcpProxies, currentUdpProxies, currentBalance, ArrayList(whatsappPrefixes), ruApps)
                                                        }
                                                        // Condition 2: Specifically check for zero balance.
                                                        else if (currentBalance == 0L) {
                                                            Log.w("MainActivity", "VPN start blocked. Balance is exactly zero.")
                                                            Toast.makeText(this@MainActivity, getString(R.string.toast_insufficient_balance), Toast.LENGTH_LONG).show()
                                                        }
                                                        // Condition 3: Catch-all for other issues (null balance, no proxies).
                                                        // This state is now treated as "not ready", and we show the generic network error toast
                                                        // because we can't be sure why the data is incomplete.
                                                        else {
                                                            Log.w("MainActivity", "VPN start blocked. Data not ready. Balance: $currentBalance, Proxies: ${currentTcpProxies.size}")
                                                            Toast.makeText(this@MainActivity, getString(R.string.toast_network_error), Toast.LENGTH_LONG).show()
                                                        }
                                                        // --- END OF THE REFINED LOGIC ---
                                                    }
                                                }
                                            },
                                            isApiParsed = !vpnInfoState.isLoading && isApiDataReady,
                                            balance = vpnInfoState.humanizedBalance ?: "____",
                                            userId = vpnInfoState.shuAppId ?: "____",
                                            userIp = vpnInfoState.currentIp ?: "_._._._",
                                            onAddFundsClick = {
                                                navController.navigate("subscriptions")
                                            }
                                        )
                                    }

                                    composable("subscriptions") {
                                        // Check the user's country to decide which screen to show.
                                        //if (vpnInfoState.userCountry == "RU") {
                                            // For Russian users, show the dedicated screen with the activation input field.
                                            ManageSubscriptionRuScreen(
                                                humanizedBalance = vpnInfoState.humanizedBalance,
                                                shuAppId = vpnInfoState.shuAppId,
                                                onActivateId = { paidId ->
                                                    vpnInfoViewModel.handleActivatePaidId(paidId)
                                                },
                                                activationError = activationState.error,
                                                onClearError = {
                                                    vpnInfoViewModel.clearActivationError()
                                                }
                                            )

                                    }
                                    composable("about") { // New route for the About screen
                                        AboutScreen(
                                            onFinished = { target ->
                                                if (target == "legalNotice") {
                                                    // Navigate to the legal screen (route defined as "legalNotice")
                                                    navController.navigate("legalNotice")
                                                } else {
                                                    // This block handles the "OK" or "Get Started" button
                                                    if (sharedPrefs.getBoolean("is_first_launch", true)) {
                                                        // Mark first launch as complete
                                                        sharedPrefs.edit().putBoolean("is_first_launch", false).apply()

                                                        // Navigate to main and clear "about" from history
                                                        navController.navigate("main") {
                                                            popUpTo("about") { inclusive = true }
                                                        }
                                                    } else {
                                                        // If not first launch, just go back (close the screen)
                                                        navController.popBackStack()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    composable("support") { // Or any route name you prefer
                                        TechnicalSupportScreen()
                                    }
                                    composable("privacy") {
                                        GenericScreen(stringResource(R.string.screen_title_privacy))
                                    }
                                    composable("blog") {
                                        GenericScreen(stringResource(R.string.screen_title_blog))
                                    }

                                    composable("legalNotice") { // Add this
                                        LegalNoticeScreen()
                                    }
                                }
                            }

                            // 2. THE "FIXED" CLOSE BUTTON OVERLAY IS DECLARED SECOND.
                            // It is elevated to a higher layer to capture clicks.
                            val currentRoute = navController
                                .currentBackStackEntryAsState()
                                .value?.destination?.route

                            if (currentRoute in listOf("subscriptions", "support", "privacy", "blog", "about", "legalNotice")) {
                                Box(
                                    // THIS MODIFIER FIXES THE CLICK PROBLEM
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(1f), // Elevate this Box to a higher layer.
                                    contentAlignment = Alignment.TopEnd // Align content to the top-right
                                ) {
                                    IconButton(
                                        onClick = {
                                            // Navigate back to the main screen and clear the stack.
                                            navController.navigate("main") {
                                                popUpTo(navController.graph.startDestinationId) {
                                                    inclusive = true
                                                }
                                            }
                                        },
                                        // Add padding here to fine-tune the position relative to the
                                        // top-right corner, ensuring it aligns with the hamburger menu.
                                        modifier = Modifier.padding(
                                            top = 40.dp,
                                            end = if (!isVertical) 46.dp else 4.dp
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close",
                                            modifier = Modifier.size(34.dp),
                                            tint = Color(177, 187, 255, 187)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }





    // Data class to represent a menu item
    data class MenuItem(val route: String, val title: String, val icon: ImageVector)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppDrawerContent(currentRoute: String?, onNavigate: (String) -> Unit) {
        val menuItems = listOf(
            MenuItem(
                "subscriptions",
                stringResource(R.string.screen_title_subscriptions), // Use string resource
                Icons.Default.CreditCard
            ),
            MenuItem( // New "About" item
                "about",
                stringResource(R.string.screen_title_about),
                Icons.AutoMirrored.Filled.HelpOutline // A suitable icon for "About"
            ),
            MenuItem(
                "legalNotice",
                stringResource(R.string.screen_title_legal),
                Icons.Default.Gavel // Or any legal-themed icon
            ),
            MenuItem(
                "support",
                stringResource(R.string.screen_title_tech_support),       // Use string resource
                Icons.Default.Info
            ),
            MenuItem(
                "privacy",
                stringResource(R.string.screen_title_privacy),      // Use string resource
                Icons.Default.Description
            ),
            MenuItem(
                "blog",
                stringResource(R.string.screen_title_blog),      // Use string resource
                Icons.Default.Newspaper
            )
        )

        val iconColor = Color(187, 199, 221)
        val uriHandler = LocalUriHandler.current
        val context = LocalContext.current

        ModalDrawerSheet(
            modifier = Modifier
                .offset(x = (-1).dp) // <-- ADD THIS LINE
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val borderColor = Color(137, 255, 255)

                    // Draw a line on the right edge of the composable's area
                    drawLine(
                        color = borderColor,
                        start = Offset(size.width, 0f), // Top-right corner
                        end = Offset(size.width, size.height), // Bottom-right corner
                        strokeWidth = strokeWidth
                    )
                },
            drawerContainerColor = Color(26, 46, 71, 230),
            drawerShape = RectangleShape // 3. Remove rounded corners
        ) {
            Image(
                painter = painterResource(id = R.drawable.shustree_key_logo),
                contentDescription = "Shustree Logo", // For accessibility
                modifier = Modifier
                    .padding(horizontal = 26.dp, vertical = 26.dp) // Keep the same padding for positioning
                    .height(26.dp) // Set the explicit height
            )
            //Divider()
            Spacer(Modifier.height(17.dp))
            menuItems.forEach { item ->
                NavigationDrawerItem(
                    icon = { Icon(item.icon, contentDescription = null, tint = iconColor) },
                    label = { Text(item.title) },
                    selected = currentRoute == item.route,
                    onClick = {
                        val systemLocale = Locale.getDefault().toLanguageTag()
                        if (item.route == "privacy") {
                            // Determine the locale and choose the correct URL
                            val url = if (systemLocale.startsWith("ru")) {
                                "https://shustree.ru/ru/legal"
                            } else {
                                "https://shustree.ru/en/legal"
                            }

                            // Open the URL in the browser
                            try {
                                uriHandler.openUri(url)
                            } catch (e: Exception) {
                                Log.e("AppDrawerContent", "Could not open URL: $url", e)
                                Toast.makeText(context, getString(R.string.toast_browser_error), Toast.LENGTH_SHORT).show()
                            }
                        } else if (item.route == "blog") {
                            // Determine the locale and choose the correct URL
                            val urlBlog = if (systemLocale.startsWith("ru")) {
                                "https://shustree.ru/ru/blog"
                            } else {
                                "https://shustree.ru/en/blog"
                            }

                            // Open the URL in the browser
                            try {
                                uriHandler.openUri(urlBlog)
                            } catch (e: Exception) {
                                Log.e("AppDrawerContent", "Could not open URL: $urlBlog", e)
                                Toast.makeText(context, getString(R.string.toast_browser_error), Toast.LENGTH_SHORT).show()
                            }

                        } else {
                            // For all other items, perform the standard navigation
                            onNavigate(item.route)
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    // --- ADD THIS 'colors' PARAMETER ---
                    colors = NavigationDrawerItemDefaults.colors(
                        // 2. Set the text color for both selected and unselected states
                        selectedTextColor = Color(187, 225, 255),
                        unselectedTextColor = Color(187, 199, 255),
                        // 1. Remove the grey background by making it transparent
                        selectedContainerColor = Color.Transparent,
                        unselectedContainerColor = Color.Transparent
                    )
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainTopAppBar(
        onMenuClick: () -> Unit,
    ) {
        // This TopAppBar's ONLY job is the hamburger menu.
        TopAppBar(
            title = { },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Menu",
                        modifier = Modifier.size(34.dp),
                        tint = Color(177, 187, 255, 199)
                    )
                }
            }

        )
    }


    @Composable
    fun GenericScreen(title: String) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(17.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium, // Already styled
                color = Color.White
            )

            Spacer(modifier = Modifier.height(26.dp)) // Add some space below the title

            // This is the body content text
            Text(
                // Use the new string resource
                text = stringResource(id = R.string.screen_content_coming_soon),

                // OPTION 1: Use a predefined style from your theme
                style = MaterialTheme.typography.bodyLarge,

                // OPTION 2 (More control): Create a custom TextStyle on the fly
                // You can combine this with Option 1 for a base style
                // style = MaterialTheme.typography.bodyLarge.copy(
                //     fontStyle = FontStyle.Italic,
                //     lineHeight = 26.sp
                // ),

                // Set the color for the body text
                color = Color(187, 199, 255, 187), // A slightly dimmer white for contrast

                // Control text alignment
                textAlign = TextAlign.Center,

                modifier = Modifier.padding(horizontal = 17.dp) // Add padding for readability
            )
        }
    }





    /**
     * Triggers a short haptic feedback vibration.
     * This is the function that was missing.
     * @param context The application or activity context.
     */
    private fun vibrate(context: Context) {
        try {
            val durationMs = 61L // A short, crisp vibration
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

            // Ensure the device has a vibrator before trying to use it
            if (!vibrator.hasVibrator()) {
                return
            }

            // --- THIS IS THE CRITICAL VERSION CHECK ---
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For Android 8.0 (Oreo) and above, which introduced VibrationEffect
                val vibrationEffect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(vibrationEffect)
            } else {
                // For older versions (API 25 and 25)
                // The vibrate(long) method is deprecated but is the only option here.
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Log the error but don't crash the app if vibration fails
            Log.e("Vibration", "Failed to trigger vibration.", e)
        }
    }


    @Composable
    fun VpnInfoTable(
        modifier: Modifier = Modifier,
        startPadding: Dp = 0.dp, // New: Padding for the left column
        innerPadding: Dp = 0.dp,  // New: Padding between columns
        balance: String,
        userId: String,
        userIp: String,
        isConnected: Boolean,
        onAddFundsClick: () -> Unit,
    ) {

        val normalTextColor = Color(187, 199, 255)
        val secondaryTextColor = Color(127, 178, 255)

        val borderColor = if (isConnected) {
            Color(87, 255, 255)   // Cyan-ish for Connected
        } else {
            Color(255, 100, 178)  // Pink-ish for Disconnected
        }

        // The entire table is a Column. The start padding is applied here.
        Column(
            modifier = modifier
                .padding(horizontal = 7.dp, vertical = 26.dp)
                .padding(start = startPadding)
                // Draw the 1px vertical border on the left
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    drawLine(
                        color = borderColor,
                        start = Offset(x = 0f, y = 0f),
                        end = Offset(x = 0f, y = size.height),
                        strokeWidth = strokeWidth
                    )
                }
                // Add a small gap between the new border and the text
                .padding(start = 12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // --- Balance Row ---
            Row {
                Text(
                    text = stringResource(id = R.string.table_balance),
                    color = secondaryTextColor,
                    // The width is now the dynamic inner padding
                    modifier = Modifier
                        .width(innerPadding)

                )
                Text(
                    text = balance,
                    color = normalTextColor,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.offset(y = 1.dp)
                )
            }
            Spacer(Modifier.height(4.dp))

            // --- Add Funds Row ---

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Spacer uses the same dynamic width
                Spacer(modifier = Modifier.width(innerPadding + 2.dp))

                val triangleColor = Color(87, 255, 255)
                Canvas(modifier = Modifier
                    .size(12.dp)
                    // CenterVertically puts it in the middle.
                    // Shifting it down (+2dp) usually lands it exactly on the text baseline.
                    .offset(y = 0.dp)
                    .clickable { onAddFundsClick() }
                ) {
                    val path = Path().apply {
                        moveTo(0f, 0f)                          // Top Left
                        lineTo(size.width, size.height / 2)    // Right Tip (Middle)
                        lineTo(0f, size.height)                 // Bottom Left
                        close()
                    }
                    drawPath(path = path, color = triangleColor)
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = stringResource(id = R.string.table_add_funds),
                    color = Color(87, 255, 255),
                    modifier = Modifier.clickable {
                        onAddFundsClick()
                    }
                )
            }



            Spacer(Modifier.height(17.dp)) // A larger gap between sections

            // --- ID Row ---
            Row {
                Text(
                    text = "ID:",
                    color = secondaryTextColor,
                    modifier = Modifier
                        .width(innerPadding)

                )
                Text(
                    text = userId,
                    color = normalTextColor,
                    modifier = Modifier.offset(y = 1.dp)
                )
            }
            Spacer(Modifier.height(8.dp))

            // --- IP Row ---
            Row {
                Text(
                    text = "IP:",
                    color = secondaryTextColor,
                    modifier = Modifier
                        .width(innerPadding)

                )
                Text(
                    text = userIp,
                    color = normalTextColor,
                    modifier = Modifier.offset(y = 1.dp)
                )
            }
        }
    }


    @Composable
    fun VpnInfoGasSkeleton(
        modifier: Modifier = Modifier,
        startPadding: Dp = 0.dp
    ) {
        // Бесконечная анимация фазы движения газа (0..2PI)
        val infiniteTransition = rememberInfiniteTransition(label = "GasAnimation")

        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )

        // Анимация пульсации прозрачности/яркости
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        // Циановые оттенки под ваш интерфейс (с глубокими градиентами)
        val cyanCore = Color(87, 255, 255, 180)       //#57FFFF (Яркий циановый)
        val cyanSoft = Color(0, 180, 216, 110)        //#00B4D8 (Глубокий голубой)
        val cyanGlow = Color(127, 178, 255, 80)       //#7FB2FF (Мягкий фиолетово-голубой)
        val transparent = Color.Transparent

        Box(
            modifier = modifier
                .padding(horizontal = 7.dp, vertical = 26.dp)
                .padding(start = startPadding)
                .height(110.dp) // Высота соответствует примерно 4 строкам таблицы
                .fillMaxWidth()
                .drawWithCache {
                    onDrawWithContent {
                        val w = size.width
                        val h = size.height

                        // Вычисляем динамическое смещение "газовых облаков" по синусоидам
                        val offsetX1 = w * 0.3f + (w * 0.2f * sin(phase))
                        val offsetY1 = h * 0.4f + (h * 0.2f * cos(phase))

                        val offsetX2 = w * 0.7f + (w * 0.25f * cos(phase * 0.8f))
                        val offsetY2 = h * 0.6f + (h * 0.3f * sin(phase * 0.8f))

                        // 1. Тонкая вертикальная направляющая линия слева (имитирует левый бордюр таблицы)
                        drawLine(
                            color = cyanCore.copy(alpha = pulseAlpha * 0.6f),
                            start = Offset(0f, 0f),
                            end = Offset(0f, h),
                            strokeWidth = 1.dp.toPx()
                        )

                        // 2. Первое сгущение «газа» (Основное яркое ядро)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(cyanCore.copy(alpha = pulseAlpha), cyanSoft, transparent),
                                center = Offset(offsetX1, offsetY1),
                                radius = w * 0.55f
                            ),
                            radius = w * 0.55f,
                            center = Offset(offsetX1, offsetY1)
                        )

                        // 3. Второе сгущение «газа» (Мягкий шлейф)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(cyanGlow.copy(alpha = pulseAlpha * 0.7f), cyanSoft.copy(alpha = 0.3f), transparent),
                                center = Offset(offsetX2, offsetY2),
                                radius = w * 0.65f
                            ),
                            radius = w * 0.65f,
                            center = Offset(offsetX2, offsetY2)
                        )

                        // 4. Бегущая поверх диагональная газовая волна
                        val sweepOffset = (phase / (2 * Math.PI).toFloat()) * (w + h)
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    transparent,
                                    cyanCore.copy(alpha = pulseAlpha * 0.3f),
                                    transparent
                                ),
                                start = Offset(sweepOffset - 80f, sweepOffset - 80f),
                                end = Offset(sweepOffset + 80f, sweepOffset + 80f)
                            )
                        )
                    }
                }
        )
    }




    // --- Composable UI ---
    @Composable
    fun VpnControlScreen(
        isHorizontal: Boolean,
        isConnected: Boolean,
        onToggle: (Context) -> Unit,
        isLoading: Boolean,
        // Add the new state values to the function signature
        isApiParsed: Boolean,
        balance: String?,
        userId: String?,
        userIp: String?,
        onAddFundsClick: () -> Unit,
    ) { // FIX 1: CHANGE SIGNATURE HERE
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val displayHeightDp = configuration.screenHeightDp.dp
        val screenWidthDp = configuration.screenWidthDp.dp // Get screen width

        // Determine which resources to use based on the isConnected state
        val enablerImageRes = if (isConnected) R.drawable.enabler_on else R.drawable.enabler_off
        val logoImageRes = if (isConnected) R.drawable.shustree700 else R.drawable.shustree700_vertical
        val logoTextRes = if (isConnected) R.drawable.shustree else R.drawable.shustree_in_dark


        val statusTextId: Int
        val statusColor: Color

        if (isConnected) {
            statusTextId = R.string.status_enabled
            statusColor = StatusConnected // Use the light blue color
        } else {
            statusTextId = R.string.status_disabled
            statusColor = StatusDisconnected // Use the pink color
        }


        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {


            ConstraintLayout(modifier = Modifier.fillMaxSize()) {
                // Create references for each UI element to define constraints
                val (logo, status, enabler, logoText, infoTable) = createRefs()

                val enablerWidth = 71.dp

                val horizontalImageBottomMargin = (displayHeightDp / 2) - 17.dp
                val horizontalTableBottomMargin = (displayHeightDp / 2) - 77.dp

                // 1. Logo Image
                Image(
                    painter = painterResource(id = logoImageRes),
                    contentDescription = stringResource(R.string.logo_description),
                    modifier = Modifier
                        .constrainAs(logo) {
                            if (isHorizontal) {
                                // LEFT: Positioned on the left side, vertically centered
                                start.linkTo(parent.start, margin = (screenWidthDp / 16).coerceAtLeast(48.dp)) // 32dp base + 16dp shift
                                bottom.linkTo(parent.bottom, margin = horizontalImageBottomMargin)

                            } else {
                                // VERTICAL: Original positioning
                                top.linkTo(parent.top, margin = (87 / 3 * 2).dp)
                                start.linkTo(parent.start)
                                end.linkTo(parent.end)
                            }
                            width = Dimension.value(71.dp)
                        }
                )

                // Logo Text
                Image(
                    painter = painterResource(id = logoTextRes),
                    contentDescription = null,
                    modifier = Modifier.constrainAs(logoText) {
                        top.linkTo(logo.top)
                        bottom.linkTo(logo.bottom)
                        start.linkTo(logo.end, margin = (87 / 3).dp)
                        height = Dimension.value((87 / 5).dp)
                    }
                )

                // 2. Enabler Button / Loader
                val enablerModifier = Modifier
                    .constrainAs(enabler) {
                        if (isHorizontal) {
                            // CENTER: Positioned in the middle of the screen
                            //centerHorizontallyTo(parent)
                            linkTo(
                                start = parent.start,
                                end = parent.end,
                                bias = 0.46f
                            )
                            bottom.linkTo(parent.bottom, margin = horizontalImageBottomMargin)
                        } else {
                            // VERTICAL: Original positioning
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            bottom.linkTo(parent.bottom, margin = (displayHeightDp / 2))
                        }
                        width = Dimension.value(enablerWidth)
                    }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = enablerModifier.size(enablerWidth),
                        color = if (isConnected) StatusDisconnected else StatusConnected,
                        strokeWidth = 5.dp
                    )

                } else {
                    Image(
                        painter = painterResource(id = enablerImageRes),
                        contentDescription = stringResource(R.string.enabler_description),
                        modifier = enablerModifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onToggle(context) }
                            )
                    )
                }



                // --- ЛОГИКА ТАБЛИЦЫ И СКЕЛЕТОНА ---

                // Анимации прозрачности для плавного Crossfade
                val tableAlpha by animateFloatAsState(
                    targetValue = if (isApiParsed) 1f else 0f,
                    animationSpec = tween(durationMillis = 762),
                    label = "tableAlphaAnimation"
                )

                val skeletonAlpha by animateFloatAsState(
                    targetValue = if (isApiParsed) 0f else 1f,
                    animationSpec = tween(durationMillis = 762),
                    label = "skeletonAlphaAnimation"
                )

                // Общие constraints для обоих элементов, чтобы не дублировать позиционирование
                val tableConstraints = Modifier.constrainAs(infoTable) {
                    if (isHorizontal) {
                        // RIGHT: Positioned to the right of the enabler
                        start.linkTo(enabler.end, margin = 17.dp)
                        end.linkTo(parent.end, margin = 32.dp)
                        bottom.linkTo(parent.bottom, margin = horizontalTableBottomMargin)
                        width = Dimension.fillToConstraints
                    } else {
                        // VERTICAL: Original positioning
                        top.linkTo(enabler.bottom)
                        bottom.linkTo(status.top)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        width = Dimension.fillToConstraints
                    }
                }

                // 3. Скелетон (виден сразу, пока данные загружаются)
                //if (skeletonAlpha > 0f) {
                //    VpnInfoGasSkeleton(
                //        modifier = tableConstraints.alpha(skeletonAlpha),
                //        startPadding = if (isHorizontal) 26.dp else 27.dp
                //    )
                //}

                // 4. Таблица с реальными данными (плавно проявляется поверх скелетона)
                if (tableAlpha > 0f) {
                    VpnInfoTable(
                        modifier = tableConstraints.alpha(tableAlpha),
                        isConnected = isConnected,
                        balance = balance ?: "",
                        userId = userId ?: "",
                        userIp = userIp ?: "",
                        // Conditional padding for the table internals
                        startPadding = if (isHorizontal) 26.dp else 27.dp,
                        innerPadding = if (isHorizontal) 77.dp else (screenWidthDp / 2 - enablerWidth / 2) - 51.dp,
                        onAddFundsClick = onAddFundsClick
                    )
                }

                // 5. Status Text (Bottom Center)
                Text(
                    text = stringResource(id = statusTextId),
                    color = statusColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.constrainAs(status) {
                        linkTo(
                            start = parent.start,
                            end = parent.end,
                            bias = if (isHorizontal) 0.46f else 0.5f
                        )
                        bottom.linkTo(parent.bottom, margin = (120 / 3).dp)
                    }
                )
            }
        }
    }
}



/**
 * The screen that displays technical support information.
 */
@Composable
fun TechnicalSupportScreen() {
    val defaultTextColor = Color(187, 199,255)
    val highlightedTextColor = Color(137, 225, 255)

    // Simplified to Column + verticalScroll
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // === HEADER ===
        Text(
            text = stringResource(id = R.string.screen_title_tech_support),
            style = MaterialTheme.typography.headlineMedium.copy(color = defaultTextColor),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 18.dp)
        )

        // === BODY CONTENT ===
        // Using a sub-column or just direct children to maintain horizontal alignment
        Column(horizontalAlignment = Alignment.Start) {
            // Intro text
            SupportText(stringResource(id = R.string.support_intro), color = defaultTextColor)
            Spacer(modifier = Modifier.height(34.dp))

            // Reason 1
            SupportText(stringResource(id = R.string.support_reason1_title), color = highlightedTextColor)
            Spacer(modifier = Modifier.height(18.dp))
            SupportText(stringResource(id = R.string.support_reason1_solution), color = defaultTextColor)
            Spacer(modifier = Modifier.height(34.dp))

            // Reason 2
            SupportText(stringResource(id = R.string.support_reason2_title), color = highlightedTextColor)
            Spacer(modifier = Modifier.height(18.dp))
            SupportText(stringResource(id = R.string.support_reason2_solution), color = defaultTextColor)
            Spacer(modifier = Modifier.height(34.dp))

            // Contact Info
            SupportText(stringResource(id = R.string.support_contact_intro), color = defaultTextColor)
            Spacer(modifier = Modifier.height(18.dp))
            ClickableEmailText(
                email = stringResource(id = R.string.support_contact_email),
                color = highlightedTextColor
            )
            Spacer(modifier = Modifier.height(34.dp))

            // Outro Info
            SupportText(stringResource(id = R.string.support_outro_info), color = defaultTextColor)
            Spacer(modifier = Modifier.height(61.dp))

            // Signature
            SupportText(stringResource(id = R.string.support_signature_greeting), color = defaultTextColor)
            SupportText(stringResource(id = R.string.support_signature_name), color = defaultTextColor)

            // Final bottom spacer to ensure content isn't flush against the screen edge when scrolled
            Spacer(modifier = Modifier.height(34.dp))
        }
    }
}


@Composable
fun LegalNoticeScreen() {
    val uriHandler = LocalUriHandler.current

    // Exact colors from TechnicalSupportScreen
    val defaultTextColor = Color(187, 199, 255)
    val highlightedTextColor = Color(137, 225, 255)

    // Build the text with clickable links
    val annotatedString = buildAnnotatedString {
        append(stringResource(R.string.legal_notice_p1))

        pushStringAnnotation(tag = "URL", annotation = "https://www.consultant.ru/document/cons_doc_LAW_10699/a4d58c1af8677d94b4fc8987c71b131f10476a76/")
        withStyle(style = SpanStyle(color = highlightedTextColor, textDecoration = TextDecoration.Underline)) {
            append(stringResource(R.string.legal_link_article))
        }
        pop()

        append(stringResource(R.string.legal_notice_p2))

        pushStringAnnotation(tag = "URL", annotation = "https://www.cnews.ru/news/top/2022-11-10_v_rossii_vynesli_obvinitelnyj")
        withStyle(style = SpanStyle(color = highlightedTextColor, textDecoration = TextDecoration.Underline)) {
            append(stringResource(R.string.legal_link_details))
        }
        pop()

        // We split the third part to handle the signature separately for spacing consistency
        append(stringResource(R.string.legal_notice_p3))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // === HEADER (Matches TechnicalSupportScreen) ===
        Text(
            text = stringResource(id = R.string.screen_title_legal),
            style = MaterialTheme.typography.headlineMedium.copy(color = defaultTextColor),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 27.dp)
        )

        // === BODY CONTENT ===
        Column(horizontalAlignment = Alignment.Start) {

            // Main legal text with links
            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = defaultTextColor,
                    lineHeight = 24.sp // Consistent with your About screen style
                ),
                onClick = { offset ->
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            uriHandler.openUri(annotation.item)
                        }
                }
            )

            // Signature Spacer (Matches the 61.dp in TechnicalSupportScreen)
            Spacer(modifier = Modifier.height(57.dp))

        }
    }
}




@Composable
fun AboutScreen(onFinished: ((String?) -> Unit)? = null) { // Change: Added (String?)
    // The color for the list of services
    val linkColor = Color(87, 255, 255)

    // A simple list of service names
    val services = listOf(
        "WhatsApp",
        "YouTube",
        "YouTube Music",
        "Gemini",
        "ChatGPT",
        "Google Cloud"
    )

    // Join the list into a single string with newlines
    val servicesText = services.joinToString("\n")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(id = R.string.about_intro),
            color = Color(187, 199, 255),
            fontSize = 18.sp,
            textAlign = TextAlign.Start
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(id = R.string.about_body),
            color = Color(187, 199, 255),
            fontSize = 16.sp,
            textAlign = TextAlign.Start,
            lineHeight = 24.sp
        )
        Spacer(modifier = Modifier.height(17.dp))

        // --- CHANGE: Replaced ClickableText with a simple Text composable ---
        Text(
            text = servicesText,
            color = linkColor, // Keep the special color
            style = LocalTextStyle.current.copy(
                textAlign = TextAlign.Start,
                fontSize = 16.sp,
                lineHeight = 26.sp
            )
            // No underline, no onClick handler
        )


        Spacer(modifier = Modifier.height(17.dp)) // Increased spacer height
        Text(
            text = stringResource(id = R.string.about_outro),
            color = Color(187, 199, 255),
            fontSize = 16.sp,
            textAlign = TextAlign.Start,
            lineHeight = 24.sp
        )



        Spacer(modifier = Modifier.height(17.dp))
        val annotatedLegalString = buildAnnotatedString {
            append(stringResource(R.string.about_legal_link_intro))
            pushStringAnnotation(tag = "navigation", annotation = "legal")
            withStyle(
                style = SpanStyle(
                    color = Color(137, 225, 255),
                    textDecoration = TextDecoration.Underline
                )
            ) {
                append(stringResource(R.string.about_legal_link_text))
            }
            pop()
            append(".")
        }

        ClickableText(
            text = annotatedLegalString,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = Color(187, 199, 255),
                fontSize = 16.sp,
                lineHeight = 24.sp
            ),
            onClick = { offset ->
                annotatedLegalString.getStringAnnotations(tag = "navigation", start = offset, end = offset)
                    .firstOrNull()?.let {
                        // Trigger navigation via the callback instead of using a controller here
                        onFinished?.invoke("legalNotice")
                    }
            }
        )

        Spacer(modifier = Modifier.height(17.dp))


        Text(
            text = stringResource(id = R.string.about_devices),
            color = Color(187, 199, 255),
            fontSize = 16.sp,
            textAlign = TextAlign.Start,
            lineHeight = 24.sp
        )


        /*
        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = stringResource(id = R.string.about_sincerely),
            color = Color(187, 199, 255),
            fontSize = 16.sp,
            textAlign = TextAlign.Start
        )
        */

        if (onFinished != null) {
            Spacer(modifier = Modifier.height(26.dp))
            Button(
                onClick = { onFinished(null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(87, 255, 255) // Your linkColor
                )
            ) {
                Text(
                    text = stringResource(id = R.string.ok_proceed).uppercase(),
                    // We use labelLarge to inherit the system-wide 'Google' font settings
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Color(17, 8, 46),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold, // Play Store uses very heavy weight for primary CTAs
                        letterSpacing = 1.26.sp // This creates that clean "Google" look
                    )
                )
            }
            Spacer(modifier = Modifier.height(26.dp))
        }
    }
}






@Composable
fun ManageSubscriptionRuScreen(
    humanizedBalance: String?,
    shuAppId: String?,
    onActivateId: (String) -> Unit,
    activationError: String?,
    onClearError: () -> Unit,
) {
    var paidId by remember { mutableStateOf(TextFieldValue("")) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // This Column now wraps the entire screen for better vertical arrangement
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 17.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Screen Title
        Text(
            // Use .copy() to apply a custom color to a theme style correctly
            style = MaterialTheme.typography.headlineMedium.copy(color = Color(220, 220, 255)),
            text = stringResource(id = R.string.subscriptions_title),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(26.dp))

        // Activation Section
        Text(
            text = stringResource(id = R.string.subscriptions_activate_title),
            style = MaterialTheme.typography.titleMedium.copy(color = Color(187, 199, 255)),
        )

        Spacer(modifier = Modifier.height(17.dp))

        // Error Message Display
        if (!activationError.isNullOrBlank()) {
            Text(
                text = activationError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 17.dp)
            )
        }

        // Input field for Paid ID
        OutlinedTextField(
            value = paidId,
            onValueChange = {
                paidId = it
                onClearError()
            },
            label = { Text(stringResource(id = R.string.subscriptions_activate_field_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color(87, 255, 255),
                unfocusedTextColor = Color(87, 199, 225),
                cursorColor = Color(87, 255, 255),
                focusedLabelColor = Color(187, 199, 255, 200),
                unfocusedLabelColor = Color(187, 199, 255, 168),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color(137, 177, 255),
                unfocusedIndicatorColor = Color(187, 199, 255, 120),
            )
        )

        Spacer(modifier = Modifier.height(17.dp))

        // "Activate" button
        Button(
            onClick = {
                val idToActivate = paidId.text.trim()
                if (idToActivate.isNotEmpty()) {
                    onActivateId(idToActivate)
                } else {
                    Toast.makeText(context, context.getString(R.string.subscriptions_error_empty_id), Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(87, 255, 255, 187),
                contentColor = Color(26, 17, 87)
            )
        ) {
            Text(
                text = stringResource(id = R.string.subscriptions_activate_button).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(color = Color(26, 17, 87))
            )
        }

        Spacer(modifier = Modifier.height(34.dp))

        // "or" Divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Divider(modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp), color = Color(187, 199, 255, 100), thickness = 1.dp)
            Text(
                text = stringResource(id = R.string.subscriptions_divider_or),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(187, 199, 255, 168)
            )
            Divider(modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp), color = Color(187, 199, 255, 100), thickness = 1.dp)
        }

        Spacer(modifier = Modifier.height(34.dp))

        // Manage/Add Funds Section
        Text(
            text = stringResource(id = R.string.subscriptions_manage_title),
            style = MaterialTheme.typography.titleMedium.copy(color = Color(187, 199, 255)),
        )

        Spacer(modifier = Modifier.height(17.dp))

        // "AT SHUSTREE.RU" button
        Button(
            onClick = {
                // 1. Get the current locale from the device context
                val currentLocale = context.resources.configuration.locales[0]
                val languageTag = currentLocale.toLanguageTag() // e.g., "ru-RU" or "en-US"

                if (!shuAppId.isNullOrBlank()) {
                    val url = "https://shustree.ru/manage_subscription?shustree_uid=$shuAppId&lang=$languageTag"
                    try {
                        uriHandler.openUri(url)
                    } catch (e: Exception) {
                        Log.e("ManageSubscription", "Could not open URL: $url", e)
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.subscriptions_id_not_available), Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color(26, 17, 87),
                containerColor = Color(87, 255, 255, 225),
            ),
            border = BorderStroke(1.dp, Color(87, 255, 255, 100))
        ) {
            Text(
                text = stringResource(id = R.string.subscriptions_manage_button).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(color = Color(26, 17, 87))
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 34.dp)
        ) {
            Text(
                text = if (humanizedBalance != null) stringResource(R.string.subscriptions_current_balance, humanizedBalance) else stringResource(R.string.subscriptions_current_balance_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(187, 199, 255),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (shuAppId != null) stringResource(R.string.subscriptions_current_id, shuAppId) else stringResource(R.string.subscriptions_current_id_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(187, 199, 255),
            )
        }
    }
}




@Composable
fun ForeignSubscriptionScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(17.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. The Screen Title
        Text(
            text = stringResource(id = R.string.screen_title_subscriptions),
            style = MaterialTheme.typography.headlineMedium,
            color = Color(187, 199, 255, 187),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Determine which string resource to use based on the country code
        val contentStringId = R.string.screen_manage_subscriptions_abroad_content

        // 2. The Specific Body Content
        Text(
            // Use the exact string resource you created
            //text = stringResource(id = R.string.screen_manage_subscriptions_abroad_content),
            text = stringResource(id = contentStringId),

            // Apply font styles and color
            style = MaterialTheme.typography.bodyLarge,
            color = Color(187, 199, 255, 187), // Using the same body color as before
            textAlign = TextAlign.Left,
            modifier = Modifier.padding(horizontal = 17.dp)
        )
    }
}



// In MainActivity.kt, after the handleActivatePaidId function


/**
 * A helper composable for standard support text blocks to avoid repetition.
 */
@Composable
private fun SupportText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth() // Align text to the start of the column
    )
}

/**
 * A helper composable that makes the email address clickable.
 */
@Composable
private fun ClickableEmailText(email: String, color: Color) {
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = AnnotatedString(email),
        style = MaterialTheme.typography.bodyLarge.copy(
            color = color,
            textAlign = TextAlign.Start,
            textDecoration = TextDecoration.Underline // Make it look like a link
        ),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            try {
                uriHandler.openUri("mailto:$email")
            } catch (e: Exception) {
                Log.e("TechnicalSupportScreen", "Failed to open email client for $email", e)
            }
        }
    )
}









