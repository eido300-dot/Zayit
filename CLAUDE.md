# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Building and Running
```bash
# Build entire project
./gradlew build

# Run desktop application
./gradlew :SeforimApp:run

# Hot reload development (run in separate terminals)
./gradlew :SeforimApp:hotRunJvm
./gradlew :SeforimApp:reload

# Create native packages (DMG/MSI/DEB)
./gradlew :SeforimApp:createDistributable

# Build specific modules
./gradlew :SeforimApp:build
./gradlew :core:build
./gradlew :dao:build
```

### Testing
```bash
# Run all tests
./gradlew test

# Run desktop JVM tests only
./gradlew :SeforimApp:jvmTest

# Run SeforimLibrary tests
./gradlew :SeforimLibrary:test
```

### Linting & Code Quality
```bash
# Run ktlint check (style/formatting)
./gradlew ktlintCheck

# Auto-fix ktlint issues
./gradlew ktlintFormat

# Run detekt (static analysis)
./gradlew detekt

# Run both linters
./gradlew ktlintCheck detekt
```

Configuration files:
- **ktlint**: Configured in `build.gradle.kts` (uses ktlint 1.5.0)
- **detekt**: Custom rules in `detekt.yml`

### SeforimLibrary Data Generation (JVM only)
```bash
# Generate database and search indexes (full pipeline)
./gradlew :SeforimLibrary:generator:generateLines
./gradlew :SeforimLibrary:generator:generateLinks
./gradlew :SeforimLibrary:generator:buildLuceneIndex
./gradlew :SeforimLibrary:generator:packageArtifacts
```

## Project Architecture

### High-Level Structure
This is a **Kotlin Multiplatform desktop application** built with:
- **UI**: Compose Multiplatform + Jewel themes for native desktop look
- **DI**: Metro dependency injection with `AppGraph` accessible via `LocalAppGraph`
- **Navigation**: Custom tab system (`TabsContent`), no Compose Navigation
- **Data**: SeforimLibrary composite build (core domain + dao persistence + generator tools)
- **Platform**: JVM desktop with macOS/Windows/Linux native packaging

### SeforimLibrary Architecture (Composite Build)
The `SeforimLibrary` composite build provides the core functionality for Jewish religious text management:

#### Core Module (`SeforimLibrary/core`)
- **Purpose**: Domain models and business logic
- **Key Models**: `Book`, `Category`, `Line`, `Link`, `TocEntry`, `SearchResult`
- **Dependencies**: Only kotlinx-serialization-json
- **Platforms**: Android, JVM

#### DAO Module (`SeforimLibrary/dao`) 
- **Purpose**: Data persistence with SQLDelight
- **Key Component**: `SeforimRepository` with comprehensive CRUD operations
- **Database**: Complex relational schema with categories, books, lines, TOC, links
- **Dependencies**: Core + SQLDelight + coroutines + logging
- **Platforms**: Android, JVM

#### Generator Module (`SeforimLibrary/generator`)
- **Purpose**: Data pipeline to convert Otzaria sources to SQLite + Lucene indexes
- **Key Tasks**: Download external data, build database, create search indexes, package artifacts
- **Dependencies**: Core + DAO + Lucene + JSoup + compression libraries
- **Platform**: JVM only (heavy processing)

### Tab System
`TAB_SYSTEM_README.md` is outdated (it describes an LRU, `TabStateManager` and `TabAwareViewModel`, none of which exist). Key points:
- Each tab owns its own `SimpleTabViewModelOwner` (ViewModel lifecycle), which stays alive while the tab exists
- `TabsContent` keeps the `MAX_LIVE_TABS` (5) most recently selected tabs alive: composed, with their ViewModels. Only the selected tab is measured and drawn. Older tabs are released (composition and ViewModels together) and restored from `TabPersistedStateStore` when selected again, like after a cold start. Never keep a tab's ViewModel alive while tearing down its composition: its pager re-collects from empty and the list visibly jumps
- `TabPersistedStateStore` holds lightweight per-tab state (`get`/`update`/`remove` by `tabId`); `SessionManager` saves it to disk for cold-boot restoration
- `TabsViewModel` manages tab lifecycle (create/select/close/replace)
- A tab keeps its `tabId` (and so its ViewModels) across `replaceCurrentTabDestination`; use `replaceCurrentTabWithNewTabId` when the old ViewModels must not be reused (e.g. going Home)

### Dependency Injection (Metro)
- App-wide graph: `AppGraph` created with `createGraph<AppGraph>()`
- Access in Compose: `LocalAppGraph.current`
- ViewModels: Retrieved from graph, not created directly
- Example: `LocalAppGraph.current.bookContentViewModel(savedStateHandle)`

### Navigation & Features
- Features live under: `SeforimApp/src/jvmMain/kotlin/io/github/kdroidfilter/seforimapp/features`
- Main destinations: `Home`, `Search`, `BookContent` (all via `TabsDestination` sealed interface)
- Navigation: Use `TabsViewModel.openTab()` or `replaceCurrentTabDestination()`

### Local Libraries (UI/Utils)
- `htmlparser`: HTML parsing utilities
- `icons`: Application icons and resources
- `jewel`: Jetbrains Jewel desktop UI components
- `logger`: Logging utilities
- `navigation`: Navigation helpers and utilities
- `pagination`: Pagination support for large datasets
- `texteffects`: Text rendering effects and styling
- `network`: Network utilities and Ktor client setup

## Key Technologies & Dependencies

### Frontend Stack
- **Compose Multiplatform**: UI framework (v1.9.2)
- **Jewel**: JetBrains desktop UI theme (v0.31.0-252.27409)
- **Lifecycle**: AndroidX Lifecycle ViewModel + Runtime (v2.9.5)
- **Navigation**: AndroidX Navigation Compose (v2.9.1)
- **Paging**: AndroidX Paging 3 for large datasets (v3.3.6)

### Backend & Data
- **SQLDelight**: Type-safe SQL code generation (v2.1.0)
- **Ktor**: HTTP client for network requests (v3.3.1)
- **Lucene**: Full-text search indexing (v10.3.1)
- **JSoup**: HTML parsing for content processing (v1.21.2)

### Development & Tooling
- **Kotlin**: v2.2.21 with multiplatform support
- **Metro**: Dependency injection framework (v0.7.2)
- **Hot Reload**: Fast development iteration (v1.0.0-rc02)
- **BuildConfig**: Build-time configuration (v5.7.0)
- **ktlint**: Code style checking and formatting
- **detekt**: Static code analysis

### Build & Runtime Requirements
- **Java**: JetBrains Runtime 21 (JBR 21) required
- **Gradle Toolchains**: Auto-downloads JBR 21
- **IDE Setup**: Set Gradle JDK to bundled JBR 21 in IntelliJ IDEA
- **Platform Packages**: DMG (macOS), MSI (Windows), DEB (Linux)

## Important Conventions

### Code Organization
- **Shared Logic**: Place in `commonMain` source sets
- **Platform Code**: Place in `jvmMain` (avoid leaking platform types across source sets)
- **Composables**: Follow `SomethingView` and `SomethingViewModel` pattern
- **DI**: Use Metro graph via `LocalAppGraph` instead of global singletons

### Testing
- **Test Location**: `src/<target>Test` (e.g., `commonTest`, `jvmTest`)
- **Example**: `SeforimApp/src/jvmTest/kotlin/io/github/kdroidfilter/seforimapp/SampleTest.kt`

### State Management
- **Tab State**: Read and write `TabPersistedStateStore` by `tabId` for restoration
- **Global State**: Avoid; prefer dependency injection and proper component lifecycle
- **RAM**: bounded; at most `MAX_LIVE_TABS` (5) tabs are alive at once (`TabsContent`), however many are open

### Security & Configuration
- **Secrets**: Never commit to repository; use `local.properties` for machine-specific settings
- **Logging**: Add `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` to JVM args for verbose logging

## Development Tips

### Tab System Usage
```kotlin
// Open new tab
val tabsVm = LocalAppGraph.current.tabsViewModel
scope.launch {
    tabsVm.openTab(TabsDestination.BookContent(bookId = 123L, tabId = UUID.randomUUID().toString()))
}

// Navigate the current tab (keeps its tabId and ViewModels)
tabsVm.replaceCurrentTabDestination(TabsDestination.Search(searchQuery = query, tabId = currentTabId))

// Go Home with a fresh tabId, so the old tab's ViewModels are not reused
tabsVm.replaceCurrentTabWithNewTabId(TabsDestination.Home(currentTabId))
```

### ViewModel Creation
```kotlin
// In TabsContent: ViewModels are scoped to the tab's owner via Metro assisted injection
tabOwner.setDefaultArgs(savedState { putString(StateKeys.TAB_ID, tabId) })
val viewModel: BookContentViewModel = assistedMetroViewModel(viewModelStoreOwner = tabOwner)
```

### State Persistence
```kotlin
// Per-tab state lives in TabPersistedStateStore (injected), keyed by the tab's id
private val tabId: String = savedStateHandle.get<String>(StateKeys.TAB_ID) ?: ""

val restoredQuery = persistedStore.get(tabId)?.search?.query.orEmpty()

persistedStore.update(tabId) { current ->
    current.copy(search = (current.search ?: SearchPersistedState()).copy(query = newQuery))
}
```

### Performance
- **Hot Reload**: Use `hotRunJvm` + `reload` for fast iteration
- **Memory**: bounded by `MAX_LIVE_TABS` in `TabsContent` (see Tab System)
- **Large Datasets**: Leverage Paging 3 for efficient data loading

## File Locations Reference

- **Main Entry**: `SeforimApp/src/jvmMain/kotlin/main.kt`
- **Features**: `SeforimApp/src/jvmMain/kotlin/io/github/kdroidfilter/seforimapp/features/`
- **Resources**: `SeforimApp/src/commonMain/composeResources/`
- **Desktop Assets**: `SeforimApp/src/jvmMain/assets/`
- **Tab System**: `SeforimApp/src/jvmMain/kotlin/io/github/kdroidfilter/seforimapp/core/presentation/tabs/TabsContent.kt` (`TAB_SYSTEM_README.md` is outdated)
- **Version Catalog**: `gradle/libs.versions.toml`