@file:Suppress("ktlint:standard:filename")

package io.github.kdroidfilter.seforimapp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.application.aotTraining
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.SingleInstanceManager
import dev.nucleusframework.energymanager.EnergyManager
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.jewel.rememberJewelTitleBarStyle
import dev.nucleusframework.window.jewel.rememberJewelWindowStyle
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.kdroidfilter.seforimapp.core.buildCopyWithSourcePayload
import io.github.kdroidfilter.seforimapp.core.coroutines.runSuspendCatching
import io.github.kdroidfilter.seforimapp.core.deeplink.ContentDeepLinkHandler
import io.github.kdroidfilter.seforimapp.core.e2e.E2e
import io.github.kdroidfilter.seforimapp.core.e2e.E2eScenario
import io.github.kdroidfilter.seforimapp.core.e2e.E2eTortureScenario
import io.github.kdroidfilter.seforimapp.core.e2e.E2eWorkspaceScenario
import io.github.kdroidfilter.seforimapp.core.e2e.E2eZoomScenario
import io.github.kdroidfilter.seforimapp.core.presentation.components.AppDockMenu
import io.github.kdroidfilter.seforimapp.core.presentation.components.AppJumpList
import io.github.kdroidfilter.seforimapp.core.presentation.components.AppLinuxQuicklist
import io.github.kdroidfilter.seforimapp.core.presentation.components.AppNativeMenuBar
import io.github.kdroidfilter.seforimapp.core.presentation.theme.ThemeUtils
import io.github.kdroidfilter.seforimapp.core.presentation.utils.rememberWindowViewModelStoreOwner
import io.github.kdroidfilter.seforimapp.core.presentation.window.DesktopTabs
import io.github.kdroidfilter.seforimapp.core.presentation.window.MainAppWindow
import io.github.kdroidfilter.seforimapp.core.settings.AppSettingsStore
import io.github.kdroidfilter.seforimapp.features.database.health.LibraryBannerState
import io.github.kdroidfilter.seforimapp.features.database.health.LibraryCheckWindow
import io.github.kdroidfilter.seforimapp.features.database.update.DatabaseUpdateWindow
import io.github.kdroidfilter.seforimapp.features.onboarding.OnBoardingWindow
import io.github.kdroidfilter.seforimapp.features.portable.DriveInUseWindow
import io.github.kdroidfilter.seforimapp.features.settings.SettingsWindow
import io.github.kdroidfilter.seforimapp.features.settings.SettingsWindowEvents
import io.github.kdroidfilter.seforimapp.features.settings.SettingsWindowViewModel
import io.github.kdroidfilter.seforimapp.features.update.UpdateDialog
import io.github.kdroidfilter.seforimapp.framework.database.LibraryHealth
import io.github.kdroidfilter.seforimapp.framework.database.LibraryProblem
import io.github.kdroidfilter.seforimapp.framework.database.PendingDbCleanup
import io.github.kdroidfilter.seforimapp.framework.database.StartupRoute
import io.github.kdroidfilter.seforimapp.framework.database.checkRequiredParts
import io.github.kdroidfilter.seforimapp.framework.database.decodeProblems
import io.github.kdroidfilter.seforimapp.framework.database.isRepeatedAfterReinstall
import io.github.kdroidfilter.seforimapp.framework.database.libraryFilesFor
import io.github.kdroidfilter.seforimapp.framework.database.reinstallMarker
import io.github.kdroidfilter.seforimapp.framework.database.routeStartup
import io.github.kdroidfilter.seforimapp.framework.di.AppGraph
import io.github.kdroidfilter.seforimapp.framework.di.LocalAppGraph
import io.github.kdroidfilter.seforimapp.framework.platform.PlatformInfo
import io.github.kdroidfilter.seforimapp.framework.portable.DriveLock
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import io.github.kdroidfilter.seforimapp.framework.portable.keepPortableReportsAnonymous
import io.github.kdroidfilter.seforimapp.framework.portable.lockIdentifierFor
import io.github.kdroidfilter.seforimapp.logger.errorln
import io.github.kdroidfilter.seforimapp.logger.infoln
import io.github.kdroidfilter.seforimapp.logger.isDevEnv
import io.github.kdroidfilter.seforimapp.logger.warnln
import io.github.kdroidfilter.seforimlibrary.cli.runCli
import io.github.kdroidfilter.seforimlibrary.core.text.HebrewTextUtils
import io.github.vinceglb.filekit.FileKit
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import seforimapp.seforimapp.generated.resources.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalFoundationApi::class)
private val AOT_TRAINING_DURATION = 45.seconds

/**
 * The initial route. Never throws: the check runs in a launched effect, where an exception would
 * repeat on every launch and take the app down with it, and an unexpected failure is no reason to
 * offer a multi-GB reinstall. The app opens without the library check instead, and the failure is
 * reported once.
 */
private fun computeStartupRoute(appGraph: AppGraph): StartupRoute =
    runSuspendCatching { readStartupRoute(appGraph) }
        .onFailure { errorln(it) { "[startup] the library check failed; opening the app without it" } }
        .getOrElse { StartupRoute.Main(libraryChecked = false) }

/**
 * Determines the initial route: settings, file sizes, the 100-byte database header and one
 * read-only query. Fast on a local disk, but slow on a drive, so it runs off the UI thread. The
 * indexes and the dictionary are checked once the main window is up
 * ([io.github.kdroidfilter.seforimapp.features.database.health.LibraryHealthService.checkOptionalLibraryParts]).
 */
private fun readStartupRoute(appGraph: AppGraph): StartupRoute {
    val appSettings = appGraph.appSettings
    val databasePathProvider = appGraph.databasePathProvider
    if (!appSettings.isOnboardingFinished()) return StartupRoute.Onboarding
    val database = runCatching { Path.of(databasePathProvider.expected()) }.getOrNull()
    val health =
        database?.let { checkRequiredParts(libraryFilesFor(it)) }
            ?: LibraryHealth(listOf(LibraryProblem.DatabaseMissing))
    val modified = database?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() }
    val requested = appSettings.getReinstallRequest()?.let(::decodeProblems)
    if (requested != null) appSettings.setReinstallRequest(null)
    val route =
        routeStartup(
            onboardingFinished = true,
            health = health,
            versionCompatible =
                !health.needsReinstall &&
                    runCatching { appGraph.databaseVersionManager.isDatabaseVersionCompatible() }.getOrDefault(false),
            databasePathOverridden = databasePathProvider.isOverridden(),
            repeatedAfterReinstall = isRepeatedAfterReinstall(appSettings.getLastReinstallMarker(), health.problems, modified),
            reinstallRequested = requested,
        )
    // Written only when it changes: in portable mode every write is a durable save on the drive. It is
    // forgotten only after the checks once the main window is up (checkLibraryAfterStart), since the
    // indexes and the dictionary are not known before.
    if (route is StartupRoute.Update && route.problems.isNotEmpty()) {
        val marker = reinstallMarker(route.problems, modified)
        if (marker != appSettings.getLastReinstallMarker()) appSettings.setLastReinstallMarker(marker)
    }
    // An install may run now, and its session may never reach the main window (the installer can be
    // closed on its last screen). A reinstall of the same version has the same fingerprint, so the
    // last read-back is forgotten here, or the next launch would take it for this library's.
    if (route is StartupRoute.Update && appSettings.getVerifiedLibrary() != null) appSettings.setVerifiedLibrary(null)
    if (!health.isHealthy) warnln { "[startup] library problems: ${health.problems}, route: $route" }
    return route
}

private fun initializeSentry() {
    val sentryEnvironment =
        System
            .getenv("SENTRY_ENVIRONMENT")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "development"

    Sentry.init { options ->
        options.dsn = "https://09cbadaf522c567b431dd4384c8f080b@o4510855773093888.ingest.de.sentry.io/4510857007726672"
        options.environment = sentryEnvironment
        options.release = NucleusApp.version
        options.isDebug = isDevEnv
        PortableEnvironment.layout?.let { layout ->
            keepPortableReportsAnonymous(options, layout.dataDir.toString(), System.getProperty("user.home"))
        }
    }
    infoln { "Sentry initialized for environment '$sentryEnvironment'." }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
fun main(args: Array<String>) {
    // Headless CLI mode: when the binary is launched as `zayit cli <args...>` (e.g. from the
    // in-app "open CLI in terminal" action), delegate to the SeforimLibrary search CLI and exit
    // BEFORE any Sentry/Nucleus/GUI initialization. This keeps the normal GUI launch path
    // completely untouched — the branch is only taken when "cli" is the first argument.
    if (args.firstOrNull() == "cli") {
        exitProcess(runCli(args.copyOfRange(1, args.size)))
    }

    val loggingEnv = System.getenv("SEFORIMAPP_LOGGING")?.lowercase()
    isDevEnv = loggingEnv == "true" || loggingEnv == "1" || loggingEnv == "yes"

    initializeSentry()

    // Roll back any half-applied seforim.db delta update from a previous
    // launch BEFORE the SQLDelight repository opens the DB. Cheap stat()
    // when nothing is in flight; never throws (failures are logged).
//    DbDeltaRecoveryBootstrap.runOnce()

    val appId = "io.github.kdroidfilter.seforimapp"
    val portableLayout = PortableEnvironment.layout
    if (portableLayout != null) {
        // A separate lock, so a portable copy started while an installed Zayit runs opens its own
        // window instead of handing over to the installed one, which shows the host's data.
        // Must be set before nucleusApplication acquires the lock.
        SingleInstanceManager.configuration =
            SingleInstanceManager.Configuration(lockIdentifier = lockIdentifierFor(appId, portableLayout.dataDir))
    }
    val driveLock = portableLayout?.let { DriveLock.acquireForProcess(it.dataDir) }
    val driveInUse = driveLock is DriveLock.Result.InUse
    // Before anything reads the settings: this copy only shows a message and leaves the file alone.
    AppSettingsStore.writesAllowed = !driveInUse

    nucleusApplication(
        args,
        defaultLocale = Locale.Builder().setLanguage("he").build(),
    ) {
        aotTraining(duration = AOT_TRAINING_DURATION)

        // Explicit: the data directory (database, session) is keyed on this id, whatever
        // Nucleus derives on its own. Portable: all app data goes to zayit-data on the drive
        // (databasesDir = zayit-data/databases).
        FileKit.init(appId, filesDir = portableLayout?.filesDir?.toFile(), cacheDir = portableLayout?.cacheDir?.toFile())

        val pendingDeepLink = remember { MutableStateFlow<String?>(null) }

        // Pick up the deep link CLI arg (cold-start) and any URI relayed by a second instance
        // through the automatic single-instance bridge. Register once: onDeepLink re-parses the
        // CLI args on every call, so invoking it on each recomposition would re-deliver the URI
        // and open duplicate tabs (Windows cold-start, where the link arrives via args; macOS is
        // unaffected because it delivers via Apple Events with empty args).
        LaunchedEffect(Unit) {
            onDeepLink { uri -> pendingDeepLink.value = uri.toString() }
        }

        // Create the application graph via Metro and expose via CompositionLocal
        val appGraph = remember { createGraph<AppGraph>() }

        // Register the AWT-level keyboard shortcuts here (instead of in main()) so they can read
        // from the DI-provided SelectionContext. The DisposableEffect re-runs only if the graph
        // identity changes (effectively never), and removes the dispatchers on app teardown.
        val selectionContext = appGraph.selectionContext
        DisposableEffect(selectionContext) {
            val km = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val copyWithoutNikud =
                KeyEventDispatcher { event ->
                    if (event.id == KeyEvent.KEY_PRESSED &&
                        event.keyCode == KeyEvent.VK_C &&
                        event.isShiftDown &&
                        (event.isMetaDown || event.isControlDown)
                    ) {
                        val selectedText = selectionContext.selectedText.value
                        if (selectedText.isNotBlank()) {
                            val stripped = HebrewTextUtils.removeAllDiacritics(selectedText)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(stripped), null)
                        }
                        true
                    } else {
                        false
                    }
                }
            // Skip when AltGraph is down to avoid clobbering character composition on
            // Linux/Windows layouts where Ctrl+Alt is interpreted as AltGr.
            val copyWithSource =
                KeyEventDispatcher { event ->
                    if (event.id == KeyEvent.KEY_PRESSED &&
                        event.keyCode == KeyEvent.VK_C &&
                        event.isAltDown &&
                        !event.isAltGraphDown &&
                        !event.isShiftDown &&
                        (event.isMetaDown || event.isControlDown)
                    ) {
                        val selectedText = selectionContext.selectedText.value
                        val active = selectionContext.activeBook.value
                        if (selectedText.isNotBlank() && active != null) {
                            val payload =
                                buildCopyWithSourcePayload(
                                    selectedText,
                                    active.book,
                                    active.rootTitle,
                                    selectionContext.visibleLines.value.lines,
                                )
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(payload), null)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            km.addKeyEventDispatcher(copyWithoutNikud)
            km.addKeyEventDispatcher(copyWithSource)
            onDispose {
                km.removeKeyEventDispatcher(copyWithoutNikud)
                km.removeKeyEventDispatcher(copyWithSource)
            }
        }

        // Get MainAppState from DI graph
        val mainAppState = appGraph.mainAppState

        // Startup routing reads settings, the database and the catalog. On a slow or network
        // drive that can take seconds, so it runs off the UI thread while LibraryCheckWindow
        // stands in (hidden unless it takes long). It is null until known. A copy that found the
        // drive in use only shows DriveInUseWindow and must write nothing.
        var startupRoute by remember { mutableStateOf<StartupRoute?>(if (driveInUse) StartupRoute.Main(libraryChecked = false) else null) }
        LaunchedEffect(Unit) {
            if (startupRoute == null) {
                startupRoute =
                    withContext(Dispatchers.IO) {
                        // Retry any database cleanup a previous run could not finish (e.g. a file
                        // locked by antivirus/Windows Search), before the repository opens the DB.
                        PendingDbCleanup.runOnce()
                        computeStartupRoute(appGraph)
                    }
            }
        }
        val startsWithOnboarding = startupRoute is StartupRoute.Onboarding
        val showOnboardingFromState by mainAppState.showOnBoarding.collectAsState()
        val showOnboarding = showOnboardingFromState ?: startsWithOnboarding
        // Keyed on the route: computed once it is known, then kept.
        var showDatabaseUpdate by remember(startupRoute) {
            mutableStateOf(startupRoute is StartupRoute.Update || startupRoute is StartupRoute.LibraryError)
        }
        var libraryProblems by remember(startupRoute) { mutableStateOf(startupRoute?.libraryProblems().orEmpty()) }
        val libraryBlockedReason = (startupRoute as? StartupRoute.LibraryError)?.reason
        // What the checks after startup found; shown above the tabs of every main window.
        val libraryBanner = remember { LibraryBannerState() }

        // Sync pre-computed state to mainAppState for any other observers of the flow
        LaunchedEffect(startupRoute) {
            if (startupRoute != null) mainAppState.setShowOnBoarding(startsWithOnboarding)
        }

        val initialTheme = remember { appGraph.appSettings.getThemeMode() }
        LaunchedEffect(initialTheme) {
            if (mainAppState.theme.value != initialTheme) {
                mainAppState.setTheme(initialTheme)
            }
        }

        // themeStyle is already initialized from AppSettings in MainAppState, no separate LaunchedEffect needed

        CompositionLocalProvider(
            LocalAppGraph provides appGraph,
            LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            val themeDefinition = ThemeUtils.buildThemeDefinition()
            val componentStyling = ThemeUtils.buildComponentStyling()

            IntUiTheme(
                theme = themeDefinition,
                styling = componentStyling,
            ) {
                // Above the windows: the tab workspaces open them, so there is no window call site
                // for JewelDecoratedWindow to install the Jewel window and title-bar styles at.
                NucleusDecoratedWindowTheme(
                    isDark = JewelTheme.isDark,
                    windowStyle = rememberJewelWindowStyle(),
                    titleBarStyle = rememberJewelTitleBarStyle(),
                ) {
                    if (driveInUse) {
                        DriveInUseWindow()
                    } else if (startupRoute == null) {
                        LibraryCheckWindow()
                    } else if (showOnboarding) {
                        OnBoardingWindow()
                    } else if (showDatabaseUpdate) {
                        DatabaseUpdateWindow(
                            onUpdateComplete = {
                                // After database update, refresh the version check and show main app
                                showDatabaseUpdate = false
                                libraryProblems = emptyList()
                            },
                            isDatabaseMissing = libraryProblems.isNotEmpty(),
                            problems = libraryProblems,
                            blockedReason = libraryBlockedReason,
                        )
                    } else {
                        val desktopManager = appGraph.desktopManager
                        val windows by desktopManager.windows.collectAsState()
                        val focusedWindowId by desktopManager.focusedWindowId.collectAsState()
                        val focusedWindow = windows.find { it.id == focusedWindowId } ?: windows.firstOrNull()

                        // One ViewModelStore shared by every main window, so window-agnostic
                        // ViewModels (settings dialog state) resolve to a single instance app-wide.
                        val windowViewModelOwner = rememberWindowViewModelStoreOwner()
                        val settingsWindowViewModel: SettingsWindowViewModel =
                            metroViewModel(viewModelStoreOwner = windowViewModelOwner)

                        val onQuit = {
                            // Persist session if enabled, apply any pending silent update, then exit.
                            // installPendingOnClose() launches the installer and exits the process
                            // itself when a silent (Win/Mac PATCH) update is ready.
                            appGraph.sessionManager.saveIfEnabled()
                            AppSettingsStore.flushIfPortable()
                            appGraph.appUpdateService.installPendingOnClose()
                            exitApplication()
                        }
                        // Chrome-like: closing the last tab of the last window quits the app
                        SideEffect { desktopManager.onQuitRequest = onQuit }

                        // App-level launcher integrations follow the focused window's tabs. key() forces
                        // their internal effects (dock/jumplist listeners) to re-register on the new
                        // window's TabsViewModel when focus moves — they capture it in closures.
                        if (focusedWindow != null) {
                            key(focusedWindow.id) {
                                if (PlatformInfo.isMacOS) {
                                    // Native macOS menu bar (no-op on other platforms)
                                    AppNativeMenuBar(
                                        mainAppState = mainAppState,
                                        tabsViewModel = focusedWindow.tabsViewModel,
                                        settingsWindowViewModel = settingsWindowViewModel,
                                        onQuit = onQuit,
                                    )

                                    // Native macOS dock menu with desktops and tabs
                                    AppDockMenu(
                                        desktopManager = desktopManager,
                                        tabsViewModel = focusedWindow.tabsViewModel,
                                    )
                                }

                                // Windows taskbar jump list with tabs and desktops
                                AppJumpList(
                                    desktopManager = desktopManager,
                                    tabsViewModel = focusedWindow.tabsViewModel,
                                    pendingDeepLink = pendingDeepLink,
                                    onClearDeepLink = { pendingDeepLink.value = null },
                                )

                                // Linux taskbar quicklist with tabs and desktops
                                AppLinuxQuicklist(
                                    desktopManager = desktopManager,
                                    tabsViewModel = focusedWindow.tabsViewModel,
                                )
                            }
                        }

                        // Resolve shareable zayit:// content deep links (cross-platform); opens in
                        // the window focused at the time the link arrives.
                        ContentDeepLinkHandler(
                            desktopManager = desktopManager,
                            repository = appGraph.repository,
                            pendingDeepLink = pendingDeepLink,
                            onClearDeepLink = { pendingDeepLink.value = null },
                        )

                        // Restore previously saved session (open desktops, windows, geometry) once.
                        var sessionRestored by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            if (!sessionRestored) {
                                appGraph.sessionManager.restoreIfEnabled()
                                sessionRestored = true
                            }
                        }

                        // Check for updates once at startup. PATCH updates are pre-downloaded
                        // here; MINOR/MAJOR surface the title-bar icon + UpdateDialog.
                        LaunchedEffect(Unit) {
                            appGraph.appUpdateService.checkOnStartup()
                        }

                        // Check the indexes and the dictionary, left out of the startup check so the
                        // windows open sooner. Then, portable: read the library back once after an
                        // install to catch a drive that lost data. Not in the installing session,
                        // whose reads hit the cache.
                        LaunchedEffect(Unit) {
                            val healthService = appGraph.libraryHealthService
                            val degraded = healthService.checkOptionalLibraryParts()
                            // The banner's offer is right from the start, not only after the read-back,
                            // which takes minutes on a drive.
                            if (!degraded.isNullOrEmpty()) libraryBanner.reinstallHelps = healthService.reinstallHelpsFor(degraded)
                            libraryBanner.degraded = degraded.orEmpty()
                            val startupChecked = (startupRoute as? StartupRoute.Main)?.libraryChecked ?: true
                            val found =
                                healthService.checkLibraryAfterStart(
                                    installedThisSession = startupRoute !is StartupRoute.Main,
                                    degraded = degraded.orEmpty(),
                                    libraryChecked = startupChecked && degraded != null,
                                )
                            libraryBanner.damaged = found.damaged
                            libraryBanner.reinstallHelps = found.reinstallHelps
                        }

                        // Debounced session autosave: any tab/window change persists ~2s later, so a
                        // crash no longer loses the whole session (previously saved only on quit).
                        LaunchedEffect(Unit) {
                            desktopManager.windows
                                .flatMapLatest { ws ->
                                    if (ws.isEmpty()) {
                                        emptyFlow()
                                    } else {
                                        combine(ws.map { w -> w.tabsViewModel.state }) { }
                                    }
                                }.drop(1)
                                .debounce(2.seconds)
                                .collect {
                                    if (!appGraph.sessionManager.isRestoringSession.value) {
                                        appGraph.sessionManager.saveIfEnabled()
                                    }
                                }
                        }

                        // Efficiency mode only when EVERY window is minimized
                        val allMinimized = windows.isNotEmpty() && windows.all { it.windowState.isMinimized }
                        LaunchedEffect(allMinimized) {
                            if (allMinimized) {
                                EnergyManager.enableEfficiencyMode()
                            } else {
                                EnergyManager.disableEfficiencyMode()
                            }
                        }

                        // App-level dialogs: single instance shared by all windows. They inherit the
                        // Rtl layout direction and theme from the providers above.
                        // The settings dialog is normally composed inside its owner window
                        // (see MainAppWindow) so it is modal to that window only; this
                        // app-scope fallback only covers an owner window that disappeared,
                        // making the dialog app-modal instead of silently dropping it.
                        val settingsWindowState by settingsWindowViewModel.state.collectAsState()
                        if (settingsWindowState.isVisible && windows.none { it.id == settingsWindowState.ownerWindowId }) {
                            SettingsWindow(
                                onClose = { settingsWindowViewModel.onEvent(SettingsWindowEvents.OnClose) },
                                initialDestination = settingsWindowState.initialDestination,
                            )
                        }
                        val updateDialogVisible by appGraph.appUpdateService.dialogVisible.collectAsState()
                        if (updateDialogVisible) {
                            UpdateDialog(
                                service = appGraph.appUpdateService,
                                onClose = { appGraph.appUpdateService.closeDialog() },
                            )
                        }

                        // Every open desktop's tabs, its windows' pane satellites and its tab-drag ghost.
                        val sessions by desktopManager.sessions.collectAsState()
                        sessions.forEach { session ->
                            key(session.desktopId) { DesktopTabs(session) }
                        }

                        // The windows themselves — one per open desktop window.
                        windows.forEach { w ->
                            key(w.id) {
                                MainAppWindow(
                                    openWindow = w,
                                    settingsWindowViewModel = settingsWindowViewModel,
                                    windowViewModelOwner = windowViewModelOwner,
                                    libraryBanner = libraryBanner,
                                    onQuit = onQuit,
                                )
                            }
                        }

                        if (E2e.enabled) {
                            // Its own scope: a theme switch may recompose this subtree from scratch,
                            // and the run must outlive that.
                            LaunchedEffect(Unit) {
                                E2e.start {
                                    E2eScenario.run(appGraph, extra = {
                                        E2eWorkspaceScenario.run(it)
                                        E2eTortureScenario.run(it)
                                        E2eZoomScenario.run(it)
                                    }) { exitApplication() }
                                }
                            }
                        }

                        // A system quit (Dock → Quit) ends the app without asking the tab windows,
                        // which leave the session to their workspace: persist it on the way out.
                        DisposableEffect(Unit) {
                            onDispose {
                                appGraph.sessionManager.saveIfEnabled()
                                AppSettingsStore.flushIfPortable()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun StartupRoute.libraryProblems(): List<LibraryProblem> =
    when (this) {
        is StartupRoute.Update -> problems
        is StartupRoute.LibraryError -> problems
        is StartupRoute.Main, StartupRoute.Onboarding -> emptyList()
    }
