# ClubMate: Project Context

Read this first on every new request.

## What it is
ClubMate is an Android chat app for clubs and communities. It is written in Kotlin with Jetpack Compose and uses Firebase as the backend. It offers:
- 1:1 chats, with optional end-to-end encryption and "incognito" (vanishing) messages
- Groups with roles, join requests, blocking, a notice timeline and an admin console
- Password-protected private channels
- Account, personalize, security, settings, report-bug and developers screens reached from the nav drawer

## Build setup
- Single module `:app`. Package and applicationId: `com.example.clubmate`
- AGP 8.5.2, Kotlin 2.0.0 (with the Compose compiler plugin and kotlinx-serialization), Gradle wrapper
- compileSdk/targetSdk 34, minSdk 24 (many screens use `@RequiresApi(O)`), Java/JVM target 1.8
- Dependency versions live in `gradle/libs.versions.toml`. Some dependencies are declared inline in `app/build.gradle.kts`: Coil 2 and Coil 3, Appwrite (unused), security-crypto and Cloudinary.
- **`app/google-services.json` is gitignored.** Get it from the owner or the Firebase console (project `clubmate-32d9e`) and place it in `app/` before building. Keystores (`*.jks`, `*.keystore`) are gitignored too.
- To build: `./gradlew assembleDebug`. To install: `./gradlew installDebug`, with an emulator or device attached. Needs an Android SDK, configured through `local.properties` `sdk.dir` or `ANDROID_HOME`.

## Backend
- **Firebase Auth**: email and password
- **Firebase Realtime Database**: the main data store (`https://clubmate-32d9e-default-rtdb.firebaseio.com`). Top-level nodes:
  - `user/{uid}`: profile, including `publicKey`, `encryptedPrivateKey`, `photoUrl`, `groups_connected`
  - `chat/{chatId}`: `messages`, `last`, `participants`, `incognito`
  - `groups/{grpId}`: `grpInfo`, `participants`, `messages`, `request`, `activities`, `events`
  - `private_channels/…` and `call/…`
- **Firestore** is a dependency but is barely used.
- **Cloudinary** (`MediaManager`) handles all image uploads (profile pictures, chat images, group pictures). It is initialized in `ClubMate.kt`.

## Code map (`app/src/main/java/com/example/clubmate/`)
| Path | Role |
|---|---|
| `ClubMate.kt` | `ClubMateApplication`: initializes Firebase and Cloudinary |
| `MainActivity.kt` | Holds the single activity. `App()` defines the whole type-safe Compose `NavHost`: Splash → Login or Main, depending on `AuthViewModel.authState` |
| `db/data.kt` | `Routes` (the `@Serializable` nav destinations, which double as data models such as `UserModel` and `GrpDetails`) and the `Status`, `UserState` and `GroupState` sealed classes |
| `viewmodel/` | `AuthViewModel` (login, register, profile picture), `ChatViewModel` (1:1 chat, search, incognito), `GroupViewmodel` (the largest: groups, roles, requests), `MainViewmodel`, `PrivateChannelViewModel`, `E2EEviewmodel`, `CallViewModel` (stub) |
| `crypto_manager/E2E.kt` | `CryptoManager`: RSA-2048 key pair plus AES-GCM, with the private key encrypted by the user's password and stored in EncryptedSharedPreferences |
| `auth/` | Login, Register and PrivateChannelAuth screens |
| `screens/` | Main (chat and group list), Chat, Group, Create/Details screens; `grp/` holds the participant-management screens |
| `privateChannel/` | Create and view private channels |
| `navbarScreens/` | The drawer destinations |
| `util/` | Reusable composables. `chat/` holds message UI and models; `group/` holds Console, Notice/Timeline, ChangeRoles, Block and message designs |
| `ui/theme/` | Theme and shared composables. The app forces the light theme. |

## Conventions and patterns
- ViewModels are `AndroidViewModel`/`ViewModel` classes that expose `LiveData` or `StateFlow` and use Firebase callbacks (`ValueEventListener`, `ChildEventListener`) directly. There is no repository layer and no DI.
- The four shared ViewModels are created in `MainActivity` and passed down explicitly. `PrivateChannelViewModel` is created per route.
- Navigation uses type-safe routes (`composable<Routes.X>` and `it.toRoute<Routes.X>()`).
- There are no real tests; only the template `Example*Test` files exist.

## Known issues and caveats
- **The Cloudinary `api_secret` is hardcoded in `ClubMate.kt`** and committed to git. It should be rotated and moved to unsigned uploads or a backend.
- `MainActivity.App()` has leftover hardcoded `grpId`/`uid` values that are never used.
- The Coil 2 and Coil 3 dependencies are duplicated, and Appwrite is unused.

## Environment notes
- Claude Code cloud containers have no `/dev/kvm` and block `dl.google.com`, so the Android SDK and emulator can't be installed there. Build and run on a local machine with Android Studio.
